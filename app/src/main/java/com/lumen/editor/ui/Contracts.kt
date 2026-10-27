package com.lumen.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.lumen.editor.model.AiKind
import com.lumen.editor.model.EditState

/*
 * Shared contracts between the editor shell (EditorScreen / EditorViewModel) and feature UIs
 * (edit panels, masking, crop). Feature composables receive state + EditorActions only; they
 * never touch the ViewModel or GL directly.
 */

enum class EditTab(val label: String) { LIGHT("Light"), COLOR("Color"), EFFECTS("Effects"), DETAIL("Detail"), OPTICS("Optics") }

enum class Tool(val label: String) { PRESETS("Presets"), CROP("Crop"), EDIT("Edit"), MASKING("Masking") }

interface EditorActions {
    /** Live change while a gesture is running (re-renders, no undo entry). */
    fun update(f: (EditState) -> EditState)

    /** Gesture finished: pushes an undo snapshot if the state changed since the last commit. */
    fun commit()

    /** update + commit in one step (buttons, presets, list actions). */
    fun apply(f: (EditState) -> EditState) { update(f); commit() }

    /** Samples the unedited source at global image uv (0..1, y down). Result is perceptual rgb (gamma 2.2), main thread. */
    fun sampleColor(u: Float, v: Float, cb: (FloatArray?) -> Unit)

    /** Computes (if needed) and uploads the AI mask for [kind]. cb(null) on success, else an error message. Main thread. */
    fun ensureAi(kind: AiKind, cb: (String?) -> Unit)

    /**
     * Black/white thumbnail of a mask's current alpha (white = selected), long edge ~96 px, at the image aspect.
     * cb(null) if the mask isn't rasterized yet. Main thread. Cheap enough to call when the mask changes.
     */
    fun maskThumbnail(maskId: Long, cb: (android.graphics.Bitmap?) -> Unit) = cb(null)
}

/** Which Edit categories differ from defaults (drives the small "edited" dot under category icons). */
fun EditState.editedTabs(): Set<EditTab> {
    val d = EditState()
    val out = HashSet<EditTab>()
    if (light != d.light || curve != d.curve) out += EditTab.LIGHT
    if (color != d.color || grading != d.grading) out += EditTab.COLOR
    if (effects != d.effects) out += EditTab.EFFECTS
    if (detail != d.detail) out += EditTab.DETAIL
    if (optics != d.optics) out += EditTab.OPTICS
    return out
}

/** Which bottom-bar tools carry edits (small dot under the tool icon). */
fun EditState.editedTools(): Set<Tool> {
    val out = HashSet<Tool>()
    if (editedTabs().isNotEmpty()) out += Tool.EDIT
    if (!crop.isIdentity) out += Tool.CROP
    if (masks.isNotEmpty()) out += Tool.MASKING
    return out
}

/** Full-screen sub-views of the Edit tool. NONE = normal slider panel. */
enum class EditSubMode { NONE, CURVE, MIX, GRADING }

/** Which mask / component is being edited in Masking mode. */
data class MaskSelection(
    val maskId: Long? = null,
    val componentId: Long? = null,
    val showOverlay: Boolean = true,
)

/** Bumped whenever mask pixels change without the edit state changing (e.g. an AI mask finished computing). */
object MaskRevision {
    var value by androidx.compose.runtime.mutableIntStateOf(0)
}
