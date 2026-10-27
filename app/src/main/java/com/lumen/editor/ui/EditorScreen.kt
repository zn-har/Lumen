package com.lumen.editor.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lumen.editor.EditorViewModel
import com.lumen.editor.io.ExportOptions
import com.lumen.editor.model.CropState
import com.lumen.editor.render.EditorSurface
import com.lumen.editor.render.Engine
import com.lumen.editor.render.Histogram
import com.lumen.editor.render.ViewTransform
import com.lumen.editor.ui.crop.CropBottom
import com.lumen.editor.ui.crop.CropOverlay
import com.lumen.editor.ui.masking.MaskList
import com.lumen.editor.ui.masking.MaskOverlay
import com.lumen.editor.ui.masking.MaskToolColumn
import com.lumen.editor.ui.masking.MaskingBottom
import com.lumen.editor.ui.panels.ColorUiState
import com.lumen.editor.ui.panels.CurveOverlay
import com.lumen.editor.ui.panels.TargetedMixOverlay
import com.lumen.editor.ui.panels.WbPickerOverlay
import com.lumen.editor.ui.panels.EditCategoryRow
import com.lumen.editor.ui.panels.EditPanel
import com.lumen.editor.ui.panels.EditSubBar
import com.lumen.editor.ui.panels.PresetsMode
import com.lumen.editor.ui.theme.LColors
import com.lumen.editor.ui.theme.LType
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/** Transient view state of the loupe (not part of the edit). */
private class LoupeState {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    var compare by mutableIntStateOf(0)
    var showHistogram by mutableStateOf(false)
    var cropGesture by mutableStateOf(false)
    var frozenCrop by mutableStateOf(CropState())

    fun resetView() { zoom = 1f; pan = Offset.Zero }
}

private val PANEL_MAX = 230.dp

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::open) }
    val openPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val loupe = remember { LoupeState() }
    val sliderFeedback = remember { mutableStateOf<DragFocus?>(null) }

    LaunchedEffect(vm.message) {
        vm.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.message = null }
    }
    LaunchedEffect(vm.uri) { loupe.resetView() }
    LaunchedEffect(vm.tool) { if (vm.tool == Tool.CROP) loupe.resetView() }

    val inMode = vm.tool == Tool.CROP || vm.tool == Tool.MASKING || vm.tool == Tool.PRESETS
    BackHandler(enabled = vm.tool != null || vm.editSubMode != EditSubMode.NONE) {
        when {
            vm.editSubMode != EditSubMode.NONE -> { vm.commit(); vm.editSubMode = EditSubMode.NONE }
            inMode -> vm.finishMode(apply = false)
            else -> vm.selectTool(null)
        }
    }

    CompositionLocalProvider(LocalSliderFeedback provides sliderFeedback) {
        Column(Modifier.fillMaxSize().background(LColors.Loupe).systemBarsPadding()) {
            TopBar(vm, loupe, openPhoto)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // the bottom panel floats over the photo: the photo is fitted above the tool rows and may slide
                // under the translucent slider panel, like the reference app
                val density = LocalDensity.current
                var bottomPx by remember { mutableIntStateOf(0) }
                var glassPx by remember { mutableIntStateOf(0) }
                if (vm.uri == null) EmptyState(openPhoto)
                else Loupe(vm, loupe, reservedBottomPx = (bottomPx - glassPx).coerceAtLeast(0).toFloat(),
                    overlayBottom = with(density) { bottomPx.toDp() })
                if (vm.uri != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomPx = it.height }) {
                    BottomArea(vm, onGlassHeight = { glassPx = it })
                }
                sliderFeedback.value?.let { focus ->
                    BasicText(
                        focus.text, style = LType.Label,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                            .background(Color(0xCC2A2A2A), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
    vm.exportProgress?.let { p ->
        Box(Modifier.fillMaxSize().background(Color(0xAA000000)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("Exporting…", style = LType.Title)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { p }, modifier = Modifier.width(200.dp), color = LColors.Accent, trackColor = LColors.Track)
            }
        }
    }
}

// ------------------------------------------------------------------ top bar

@Composable
private fun TopBar(vm: EditorViewModel, loupe: LoupeState, openPhoto: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    val mode = vm.tool.takeIf { it == Tool.CROP || it == Tool.MASKING || it == Tool.PRESETS }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // full-screen modes have no back/share: they are left with ✕ / ✓ at the bottom
        if (mode == null) IconBtn(LIcons.Back, "Open another photo") { openPhoto() }
        Spacer(Modifier.weight(1f))
        if (vm.uri == null) return@Row
        UndoButton(vm)
        if (mode == Tool.MASKING) {
            val sel = vm.maskSelection
            IconBtn(if (sel.showOverlay) LIcons.Eye else LIcons.EyeOff, "Show mask overlay") {
                vm.maskSelection = sel.copy(showOverlay = !sel.showOverlay)
            }
        }
        if (mode == null) {
            Box {
                IconBtn(LIcons.Share, "Share") { share = true }
                DropdownMenu(share, onDismissRequest = { share = false }, modifier = Modifier.background(LColors.PanelRaised)) {
                    MenuItem("Save to device · full size") { share = false; vm.export(ExportOptions()) }
                    MenuItem("Export JPEG · 2048 px") { share = false; vm.export(ExportOptions(maxEdge = 2048, quality = 92)) }
                    MenuItem("Export PNG · full size") { share = false; vm.export(ExportOptions(png = true)) }
                }
            }
        }
        if (mode == null || mode == Tool.MASKING) Box {
            IconBtn(LIcons.MoreCircle, "More") { menu = true }
            DropdownMenu(menu, onDismissRequest = { menu = false }, modifier = Modifier.background(LColors.PanelRaised)) {
                MenuItem("Redo") { menu = false; vm.redo() }
                MenuItem(if (loupe.showHistogram) "Hide histogram" else "Show histogram") {
                    menu = false; loupe.showHistogram = !loupe.showHistogram
                }
                MenuItem("Reset all edits") { menu = false; vm.resetAll() }
            }
        }
    }
}

/** Undo; long-press = redo (a tiny corner triangle hints at it). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UndoButton(vm: EditorViewModel) {
    Box(
        Modifier.size(48.dp).combinedClickable(
            enabled = vm.canUndo || vm.canRedo,
            onClick = { vm.undo() },
            onLongClick = { vm.redo() },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(LIcons.Undo, "Undo (hold to redo)", tint = if (vm.canUndo) LColors.Text else LColors.TextFaint, modifier = Modifier.size(24.dp))
        Canvas(Modifier.align(Alignment.BottomEnd).padding(11.dp).size(5.dp)) {
            drawPath(Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() },
                if (vm.canRedo) LColors.Text else LColors.TextFaint)
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(text, color = LColors.Text) }, onClick = onClick)

@Composable
fun IconBtn(icon: ImageVector, desc: String, enabled: Boolean = true, tint: Color = LColors.Text, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (enabled) tint else LColors.TextFaint, modifier = Modifier.size(24.dp)) }
}

// ------------------------------------------------------------------ loupe

@Composable
private fun Loupe(vm: EditorViewModel, loupe: LoupeState, reservedBottomPx: Float, overlayBottom: androidx.compose.ui.unit.Dp) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var surface by remember { mutableStateOf<EditorSurface?>(null) }
    val density = LocalDensity.current
    val (pw, ph) = vm.previewSize
    val cropMode = vm.tool == Tool.CROP
    val viewCrop = if (loupe.cropGesture) loupe.frozenCrop else vm.state.crop
    val insetTop = if (cropMode) with(density) { 64.dp.toPx() } else 0f
    val insetBottom = reservedBottomPx + if (cropMode) with(density) { 150.dp.toPx() } else 0f
    val zoomable = !cropMode && vm.editSubMode != EditSubMode.CURVE && !ColorUiState.wbPicking &&
        !(vm.editSubMode == EditSubMode.MIX && ColorUiState.targetedMix)

    val view = remember(size, pw, ph, viewCrop, cropMode, loupe.zoom, loupe.pan, insetTop, insetBottom) {
        if (size.width == 0 || pw == 0) null
        else ViewTransform.compute(
            size.width.toFloat(), size.height.toFloat(), pw.toFloat(), ph.toFloat(), viewCrop, cropMode,
            if (cropMode) 1f else loupe.zoom, if (cropMode) 0f else loupe.pan.x, if (cropMode) 0f else loupe.pan.y,
            insetTop, insetBottom,
        )
    }
    val showMask = if (vm.tool == Tool.MASKING && vm.maskSelection.showOverlay) vm.maskSelection.maskId else null
    LaunchedEffect(surface, view, loupe.compare, showMask, cropMode) {
        val s = surface ?: return@LaunchedEffect
        val v = view ?: return@LaunchedEffect
        s.display = Engine.DisplayParams(v, compare = loupe.compare, showMaskId = showMask, cropMode = cropMode)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, surface) {
        val s = surface
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> s?.onPause()
                Lifecycle.Event.ON_RESUME -> s?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(zoomable) {
                if (!zoomable) return@pointerInput
                detectTapGestures(
                    onPress = {
                        // press and hold anywhere on the photo shows the original
                        val released = withTimeoutOrNull(350) { tryAwaitRelease() }
                        if (released == null) {
                            loupe.compare = 1
                            tryAwaitRelease()
                            loupe.compare = 0
                        }
                    },
                    onDoubleTap = { p ->
                        if (loupe.zoom > 1.01f) loupe.resetView()
                        else {
                            val f = 3f
                            val c = Offset(size.width / 2f, size.height / 2f)
                            loupe.pan = p + (c + loupe.pan - p) * f - c
                            loupe.zoom = f
                        }
                    },
                )
            }
            .pointerInput(zoomable) {
                if (!zoomable) return@pointerInput
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val z = (loupe.zoom * zoomChange).coerceIn(1f, 16f)
                    val f = z / loupe.zoom
                    val c = Offset(size.width / 2f, size.height / 2f)
                    loupe.pan = centroid + (c + loupe.pan - centroid) * f + panChange - c
                    loupe.zoom = z
                    if (z <= 1.001f) loupe.pan = Offset.Zero
                }
            },
    ) {
        AndroidView(
            factory = { c -> EditorSurface(c).also { s -> surface = s; vm.attach(s) } },
            onRelease = { s -> vm.detach(s); s.releaseGl() },
            modifier = Modifier.fillMaxSize(),
        )
        if (view != null && !vm.loading) Box(Modifier.fillMaxSize().padding(bottom = if (cropMode) 0.dp else overlayBottom)) {
            when {
                vm.tool == Tool.MASKING -> {
                    MaskOverlay(vm.state, vm.maskSelection, view, vm, Modifier.fillMaxSize())
                    // both position themselves inside the photo area (panel right / collapsed bottom-right, tools bottom-left)
                    MaskList(vm.state, vm.maskSelection, { vm.maskSelection = it }, vm, Modifier.fillMaxSize().padding(10.dp))
                    MaskToolColumn(vm.state, vm.maskSelection, vm, Modifier.fillMaxSize().padding(10.dp))
                }
                cropMode -> CropOverlay(
                    vm.state, view, pw, ph, vm,
                    onGestureActive = { active ->
                        if (active) loupe.frozenCrop = vm.state.crop
                        loupe.cropGesture = active
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                vm.editSubMode == EditSubMode.CURVE ->
                    CurveOverlay(vm.state, view, vm, vm.curveChannel, Modifier.fillMaxSize())
                vm.editSubMode == EditSubMode.MIX && ColorUiState.targetedMix ->
                    TargetedMixOverlay(vm.state, view, vm, Modifier.fillMaxSize())
                vm.tool == Tool.EDIT && ColorUiState.wbPicking ->
                    WbPickerOverlay(vm.state, view, vm, active = true, onDone = { ColorUiState.wbPicking = false }, modifier = Modifier.fillMaxSize())
            }
        }
        if (loupe.showHistogram && !cropMode) {
            vm.histogram?.let { HistogramView(it, Modifier.align(Alignment.TopStart).padding(10.dp)) }
        }
        if (vm.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = LColors.Accent)
        if (loupe.compare == 1) {
            BasicText(
                "Original", style = LType.Tool.copy(color = LColors.Text),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun HistogramView(h: Histogram, modifier: Modifier = Modifier) {
    Canvas(modifier.size(132.dp, 64.dp).background(Color(0x88000000), RoundedCornerShape(4.dp))) {
        // ignore the clipped extremes when normalising so a few black/white pixels don't flatten the rest
        var peak = 1
        for (i in 2 until 254) peak = max(peak, max(h.r[i], max(h.g[i], h.b[i])))
        fun path(bins: IntArray): Path {
            val p = Path()
            p.moveTo(0f, size.height)
            for (i in 0 until 256) {
                val x = i / 255f * size.width
                val y = size.height - (bins[i] / peak.toFloat()).coerceAtMost(1f) * size.height * 0.95f
                p.lineTo(x, y)
            }
            p.lineTo(size.width, size.height); p.close()
            return p
        }
        drawPath(path(h.r), Color(0xB0E04040), blendMode = BlendMode.Plus)
        drawPath(path(h.g), Color(0xB040C040), blendMode = BlendMode.Plus)
        drawPath(path(h.b), Color(0xB04060E0), blendMode = BlendMode.Plus)
    }
}

@Composable
private fun EmptyState(openPhoto: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(LIcons.Image, null, tint = LColors.TextDim, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        BasicText(
            "Open a photo", style = LType.Title.copy(color = Color.White),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(LColors.Accent).clickable { openPhoto() }
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

// ------------------------------------------------------------------ bottom

@Composable
private fun BottomArea(vm: EditorViewModel, onGlassHeight: (Int) -> Unit) {
    val density = LocalDensity.current
    // the part of the bottom area that may cover the photo (slider panels); everything else reserves space
    val solidRows = with(density) { 120.dp.roundToPx() }  // category row + tool strip / mode bar
    val panelMod = Modifier.fillMaxWidth().heightIn(max = PANEL_MAX)
    val finish = { apply: Boolean -> vm.finishMode(apply) }
    if (vm.tool == null || vm.tool == Tool.CROP || vm.tool == Tool.PRESETS) SideEffect { onGlassHeight(0) }
    when (vm.tool) {
        Tool.CROP -> CropBottom(
            vm.state, vm.previewSize.first, vm.previewSize.second, vm,
            onCancel = { finish(false) }, onDone = { finish(true) },
            modifier = Modifier.background(LColors.Controls),
        )
        Tool.MASKING -> MaskingBottom(
            vm.state, vm.maskSelection, { vm.maskSelection = it }, vm,
            onCancel = { finish(false) }, onDone = { finish(true) },
            modifier = Modifier.onSizeChanged { onGlassHeight((it.height - solidRows).coerceAtLeast(0)) },
        )
        Tool.PRESETS -> PresetsMode(
            vm.state, vm, onCancel = { finish(false) }, onDone = { finish(true) },
            modifier = Modifier.background(LColors.Controls),
        )
        Tool.EDIT -> Column(Modifier.fillMaxWidth()) {
            if (vm.editSubMode != EditSubMode.NONE) {
                EditSubBar(
                    vm.editSubMode, vm.state, vm, vm.curveChannel, { vm.curveChannel = it },
                    onDone = { vm.editSubMode = EditSubMode.NONE },
                    modifier = Modifier.background(LColors.Controls)
                        .onSizeChanged { onGlassHeight(if (vm.editSubMode == EditSubMode.CURVE) 0 else it.height / 2) },
                )
                return@Column
            }
            EditPanel(vm.tab, vm.state, vm, { vm.editSubMode = it }, panelMod.onSizeChanged { onGlassHeight(it.height) })
            EditCategoryRow(vm.tab, { vm.tab = it }, onAuto = { vm.auto() }, edited = vm.state.editedTabs(),
                modifier = Modifier.hideWhileDragging().background(LColors.Controls))
            HorizontalDivider(color = LColors.Divider, thickness = 0.5.dp, modifier = Modifier.hideWhileDragging())
            ToolStrip(vm, compact = true)
        }
        null -> Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            // nothing open: the tools float in a rounded pill over the black background
            Box(Modifier.clip(RoundedCornerShape(18.dp)).background(LColors.Controls).padding(horizontal = 6.dp)) {
                ToolStrip(vm, compact = false)
            }
        }
    }
}

/** Bottom tool row: labelled in the floating pill, icon-only while a panel is open. Dot = tool has edits. */
@Composable
private fun ToolStrip(vm: EditorViewModel, compact: Boolean) {
    val edited = vm.state.editedTools()
    Row(
        (if (compact) Modifier.fillMaxWidth().background(LColors.ControlsSolid) else Modifier).hideWhileDragging()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = if (compact) Arrangement.SpaceEvenly else Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in Tool.entries) {
            val icon = when (t) {
                Tool.PRESETS -> LIcons.Presets; Tool.CROP -> LIcons.Crop
                Tool.EDIT -> LIcons.Edit; Tool.MASKING -> LIcons.Masking
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StripItem(icon, if (compact) null else t.label, selected = vm.tool == t, selectedColor = LColors.Accent) {
                    vm.selectTool(if (vm.tool == t) null else t)
                }
                Box(Modifier.padding(top = 2.dp).size(3.dp).clip(RoundedCornerShape(50))
                    .background(if (t in edited && vm.tool != t) LColors.TextDim else Color.Transparent))
            }
        }
    }
}
