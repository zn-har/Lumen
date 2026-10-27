package com.lumen.editor

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.editor.ai.AiSegmenter
import com.lumen.editor.io.ExportOptions
import com.lumen.editor.io.Exporter
import com.lumen.editor.io.ImageInfo
import com.lumen.editor.io.ImageIo
import com.lumen.editor.model.AiKind
import com.lumen.editor.model.EditState
import com.lumen.editor.render.EditorSurface
import com.lumen.editor.render.Histogram
import com.lumen.editor.model.CurveChannel
import com.lumen.editor.ui.EditSubMode
import com.lumen.editor.ui.EditTab
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.MaskSelection
import com.lumen.editor.ui.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(app: Application) : AndroidViewModel(app), EditorActions {
    private val ctx: Context get() = getApplication()

    var state by mutableStateOf(EditState()); private set
    var uri by mutableStateOf<Uri?>(null); private set
    var info by mutableStateOf<ImageInfo?>(null); private set
    var previewSize by mutableStateOf(0 to 0); private set
    var loading by mutableStateOf(false); private set
    var histogram by mutableStateOf<Histogram?>(null)
    var message by mutableStateOf<String?>(null)
    var exportProgress by mutableStateOf<Float?>(null); private set
    val aiBusy = mutableStateOf(setOf<AiKind>())

    // UI navigation state lives here so it survives configuration changes
    var tool by mutableStateOf<Tool?>(null); private set
    var tab by mutableStateOf(EditTab.LIGHT)
    var maskSelection by mutableStateOf(MaskSelection())
    var editSubMode by mutableStateOf(EditSubMode.NONE)
    var curveChannel by mutableStateOf(CurveChannel.MASTER)
    private var modeSnapshot: EditState? = null

    /** Crop and Masking are full-screen modes: remember the state so ✕ can restore it. */
    fun selectTool(t: Tool?) {
        editSubMode = EditSubMode.NONE
        modeSnapshot = if (t == Tool.CROP || t == Tool.MASKING || t == Tool.PRESETS) state else null
        if (t == Tool.MASKING && maskSelection.maskId == null) {
            state.masks.lastOrNull()?.let { m -> maskSelection = MaskSelection(m.id, m.components.lastOrNull()?.id) }
        }
        tool = t
    }

    fun finishMode(apply: Boolean) {
        val snap = modeSnapshot
        if (!apply && snap != null) { update { snap }; commit(); fixSelection() } else commit()
        modeSnapshot = null
        tool = null
    }

    /** One-shot auto tone from the current histogram: centre the mean, stretch the ends. */
    fun auto() {
        val h = histogram ?: return
        val total = h.l.sum().toFloat().coerceAtLeast(1f)
        var mean = 0f
        for (i in 0 until 256) mean += i * h.l[i]
        mean /= total * 255f
        fun pct(p: Float): Float {
            var acc = 0f
            for (i in 0 until 256) { acc += h.l[i]; if (acc >= p * total) return i / 255f }
            return 1f
        }
        val lo = pct(0.005f); val hi = pct(0.995f)
        apply {
            it.copy(
                light = it.light.copy(
                    exposure = (it.light.exposure + kotlin.math.log2(0.46f / mean.coerceIn(0.05f, 0.95f)) * 0.8f).coerceIn(-2f, 2f),
                    contrast = 10f,
                    highlights = if (hi > 0.97f) -30f else 0f,
                    shadows = if (lo < 0.04f) 25f else 0f,
                    whites = ((0.97f - hi) * 120f).coerceIn(-40f, 40f),
                    blacks = ((0.03f - lo) * -150f).coerceIn(-40f, 40f),
                ),
                color = it.color.copy(vibrance = 12f),
            )
        }
    }

    private val undo = ArrayList<EditState>()
    private var undoIndex = -1
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    private var surface: EditorSurface? = null
    private val ai = AiSegmenter(app)
    private var loadJob: Job? = null

    private val previewEdge: Int by lazy {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.memoryClass >= 256 && !am.isLowRamDevice) 2560 else 2048
    }

    fun attach(s: EditorSurface) {
        surface = s
        s.onHistogram = { histogram = it }
        s.onContextLost = { uri?.let { reloadPreview(it) } }
        s.onImageReady = { w, h -> previewSize = w to h; loading = false; s.submit(state) }
        uri?.let { reloadPreview(it) }
    }

    fun detach(s: EditorSurface) {
        if (surface === s) surface = null
        s.onHistogram = null; s.onContextLost = null; s.onImageReady = null
    }

    // ---------------------------------------------------------------- image

    fun open(newUri: Uri) {
        uri = newUri
        state = EditState()
        undo.clear(); undo += state; undoIndex = 0; refreshUndo()
        maskSelection = MaskSelection()
        ai.clear()
        reloadPreview(newUri)
    }

    private fun reloadPreview(u: Uri) {
        loadJob?.cancel()
        loading = true
        loadJob = viewModelScope.launch {
            try {
                val (inf, bmp) = withContext(Dispatchers.IO) {
                    ImageIo.info(ctx, u) to ImageIo.decodeSampled(ctx, u, previewEdge)
                }
                info = inf
                val s = surface
                if (s == null) { bmp.recycle(); return@launch }
                s.setImage(bmp, inf.width)
                // AI masks are per-image GPU textures: re-upload cached ones after a reload
                for (kind in AiKind.entries) ai.cached(kind)?.let { m ->
                    s.onGl { it.setAiMask(kind, m.data, m.width, m.height) }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e("Lumen", "load failed", t)
                loading = false
                message = "Couldn't open image: ${t.message}"
            }
        }
    }

    // ---------------------------------------------------------------- EditorActions

    override fun update(f: (EditState) -> EditState) {
        val n = f(state)
        if (n == state) return
        if (tool == Tool.MASKING) autoOverlay(state, n)
        state = n
        surface?.submit(n)
    }

    /**
     * Reference behaviour: the red overlay helps while shaping a mask and gets out of the way while adjusting it.
     * Slider edits of the selected mask hide it; changing the mask's shape (brush, handles, new parts) shows it.
     */
    private fun autoOverlay(old: EditState, new: EditState) {
        val id = maskSelection.maskId ?: return
        val before = old.masks.find { it.id == id } ?: return
        val after = new.masks.find { it.id == id } ?: return
        if (after.components != before.components) {
            if (!maskSelection.showOverlay) maskSelection = maskSelection.copy(showOverlay = true)
        } else if (after.adj != before.adj && maskSelection.showOverlay) {
            maskSelection = maskSelection.copy(showOverlay = false)
        }
    }

    override fun commit() {
        if (undoIndex >= 0 && undo[undoIndex] == state) return
        while (undo.size > undoIndex + 1) undo.removeAt(undo.size - 1)
        undo += state
        if (undo.size > MAX_UNDO) undo.removeAt(0)
        undoIndex = undo.size - 1
        refreshUndo()
    }

    fun undo() {
        if (undoIndex <= 0) return
        undoIndex--; state = undo[undoIndex]; surface?.submit(state); refreshUndo(); fixSelection()
    }

    fun redo() {
        if (undoIndex >= undo.size - 1) return
        undoIndex++; state = undo[undoIndex]; surface?.submit(state); refreshUndo(); fixSelection()
    }

    fun resetAll() = apply { EditState() }

    private fun refreshUndo() {
        canUndo = undoIndex > 0; canRedo = undoIndex < undo.size - 1
    }

    private fun fixSelection() {
        val m = state.masks.find { it.id == maskSelection.maskId }
        maskSelection = when {
            m == null -> MaskSelection(showOverlay = maskSelection.showOverlay)
            m.components.none { it.id == maskSelection.componentId } -> maskSelection.copy(componentId = m.components.lastOrNull()?.id)
            else -> maskSelection
        }
    }

    override fun sampleColor(u: Float, v: Float, cb: (FloatArray?) -> Unit) {
        val s = surface ?: return cb(null)
        viewModelScope.launch { cb(s.onGl { it.sampleSource(u, v) }) }
    }

    override fun ensureAi(kind: AiKind, cb: (String?) -> Unit) {
        val s = surface ?: return cb("Editor not ready")
        val u = uri ?: return cb("No image")
        aiBusy.value = aiBusy.value + kind
        viewModelScope.launch {
            try {
                if (!s.onGl { it.hasAi(kind) }) {
                    val mask = ai.cached(kind) ?: run {
                        val input = withContext(Dispatchers.IO) { ImageIo.decodeSampled(ctx, u, AI_EDGE) }
                        try { ai.compute(kind, input) } finally { input.recycle() }
                    }
                    s.onGl { it.setAiMask(kind, mask.data, mask.width, mask.height) }
                    s.submit(state.copy())  // new instance forces mask re-rasterization
                    com.lumen.editor.ui.MaskRevision.value++
                }
                cb(null)
            } catch (t: Throwable) {
                Log.e("Lumen", "ai $kind failed", t)
                cb(t.message ?: t.javaClass.simpleName)
            } finally {
                aiBusy.value = aiBusy.value - kind
            }
        }
    }

    override fun maskThumbnail(maskId: Long, cb: (android.graphics.Bitmap?) -> Unit) {
        val s = surface ?: return cb(null)
        // render first so a freshly edited mask is rasterized before we read it back
        viewModelScope.launch { cb(s.onGl { e -> e.render(state, info?.width ?: e.width); e.maskThumbnail(maskId) }) }
    }

    // ---------------------------------------------------------------- export

    fun export(opts: ExportOptions) {
        val s = surface ?: return
        val u = uri ?: return
        if (exportProgress != null) return
        exportProgress = 0f
        viewModelScope.launch {
            try {
                Exporter.export(ctx, u, state, s, opts) { p -> viewModelScope.launch { exportProgress = p } }
                message = "Saved to Pictures/Lumen"
            } catch (t: Throwable) {
                Log.e("Lumen", "export failed", t)
                message = "Export failed: ${t.message}"
            } finally {
                exportProgress = null
            }
        }
    }

    fun onTrimMemory() {
        ai.clear()
    }

    companion object {
        const val MAX_UNDO = 120
        const val AI_EDGE = 512
    }
}
