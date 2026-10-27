package com.lumen.editor.ui.masking

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.lumen.editor.model.AiKind
import com.lumen.editor.model.EditState
import com.lumen.editor.model.Mask
import com.lumen.editor.model.MaskComponent
import com.lumen.editor.model.MaskMode
import com.lumen.editor.model.newId
import com.lumen.editor.ui.LIcons
import com.lumen.editor.ui.MaskSelection
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.lineIcon

/**
 * Brush tool settings shared by [MaskingPanel] (sliders) and [MaskOverlay] (painting).
 * Values only affect strokes painted from now on; existing strokes keep their own params.
 */
object BrushSettings {
    /** Radius as fraction of image width. */
    var size by mutableFloatStateOf(0.05f)
    /** 0..1 */
    var feather by mutableFloatStateOf(0.5f)
    /** 0..1 */
    var flow by mutableFloatStateOf(1f)
    var erase by mutableStateOf(false)
}

internal object MaskIcons {
    val Overlay = lineIcon("m_overlay", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M7 8L17 18M6 12.5L12 18.5M9.5 6.5L17.5 14.5M13 5.5L18.5 11")
    val Rename = lineIcon("m_rename", "M4 20H8L19 9L15 5L4 16Z", "M13 7L17 11")
    val Intersect = lineIcon("m_intersect", "M9 5A7 7 0 1 1 9 19A7 7 0 1 1 9 5Z", "M15 5A7 7 0 1 1 15 19A7 7 0 1 1 15 5Z")
    val Erase = lineIcon("m_erase", "M8 20H20", "M4.5 14.5L13 6L19 12L12 19H9Z", "M9 10L15 16")
    val Paint = LIcons.Brush
    val Pipette = lineIcon("m_pipette", "M15 4L20 9L17 12L12 7Z", "M13.5 8.5L5 17V19H7L15.5 10.5")
}

internal fun MaskComponent.icon(): ImageVector = when (this) {
    is MaskComponent.Brush -> LIcons.Brush
    is MaskComponent.Linear -> LIcons.Linear
    is MaskComponent.Radial -> LIcons.Radial
    is MaskComponent.ColorRange -> LIcons.ColorRange
    is MaskComponent.LuminanceRange -> LIcons.LumRange
    is MaskComponent.Ai -> kind.icon()
}

internal fun MaskComponent.label(): String = when (this) {
    is MaskComponent.Brush -> "Brush"
    is MaskComponent.Linear -> "Linear"
    is MaskComponent.Radial -> "Radial"
    is MaskComponent.ColorRange -> "Color"
    is MaskComponent.LuminanceRange -> "Luminance"
    is MaskComponent.Ai -> kind.label
}

internal fun AiKind.icon(): ImageVector = when (this) {
    AiKind.SKY -> LIcons.Sky
    AiKind.BACKGROUND -> LIcons.Background
    AiKind.PEOPLE -> LIcons.People
    else -> LIcons.Subject
}

internal fun Mask.icon(): ImageVector = components.firstOrNull()?.icon() ?: LIcons.Masking

/** Selected mask, or null. */
internal fun EditState.selectedMask(sel: MaskSelection): Mask? = sel.maskId?.let { id -> masks.find { it.id == id } }

/** Selected component of the selected mask; falls back to the mask's last component. */
internal fun EditState.selectedComponent(sel: MaskSelection): MaskComponent? {
    val m = selectedMask(sel) ?: return null
    return m.components.find { it.id == sel.componentId } ?: m.components.lastOrNull()
}

internal fun EditState.mapMask(maskId: Long, f: (Mask) -> Mask): EditState =
    copy(masks = masks.map { if (it.id == maskId) f(it) else it })

internal fun EditState.mapComponent(maskId: Long, compId: Long, f: (MaskComponent) -> MaskComponent): EditState =
    mapMask(maskId) { m -> m.copy(components = m.components.map { if (it.id == compId) f(it) else it }) }

/** "Mask N" with N one above the highest existing number. */
internal fun nextMaskName(state: EditState): String {
    val n = state.masks.maxOfOrNull { it.name.removePrefix("Mask ").toIntOrNull() ?: 0 } ?: 0
    return "Mask ${maxOf(n, state.masks.size) + 1}"
}

/** What the create grid can make. */
internal sealed class CreateKind(val label: String, val icon: ImageVector) {
    class Ai(val kind: AiKind) : CreateKind(kind.label, kind.icon())
    data object Brush : CreateKind("Brush", LIcons.Brush)
    data object Linear : CreateKind("Linear Gradient", LIcons.Linear)
    data object Radial : CreateKind("Radial Gradient", LIcons.Radial)
    data object ColorRange : CreateKind("Color Range", LIcons.ColorRange)
    data object LumRange : CreateKind("Luminance Range", LIcons.LumRange)

    fun build(mode: MaskMode): MaskComponent = when (this) {
        is Ai -> MaskComponent.Ai(newId(), mode, kind = kind)
        Brush -> MaskComponent.Brush(newId(), mode)
        Linear -> MaskComponent.Linear(newId(), mode)
        Radial -> MaskComponent.Radial(newId(), mode)
        ColorRange -> MaskComponent.ColorRange(newId(), mode)
        LumRange -> MaskComponent.LuminanceRange(newId(), mode)
    }

    companion object {
        val AI = listOf(AiKind.SUBJECT, AiKind.SKY, AiKind.BACKGROUND, AiKind.PEOPLE).map { Ai(it) }
        val TOOLS = listOf(Brush, Linear, Radial, ColorRange, LumRange)
    }
}

/** Same component with a fresh id (used by duplicate). */
internal fun MaskComponent.withNewId(): MaskComponent = when (this) {
    is MaskComponent.Brush -> copy(id = newId())
    is MaskComponent.Linear -> copy(id = newId())
    is MaskComponent.Radial -> copy(id = newId())
    is MaskComponent.ColorRange -> copy(id = newId())
    is MaskComponent.LuminanceRange -> copy(id = newId())
    is MaskComponent.Ai -> copy(id = newId())
}

/** Components with their own settings view (shown first when selected). */
internal val MaskComponent.hasSettings: Boolean
    get() = this is MaskComponent.Brush || this is MaskComponent.Radial ||
        this is MaskComponent.ColorRange || this is MaskComponent.LuminanceRange

/** UI state shared between the right-side [MaskList] and [MaskingBottom]. */
object MaskingUi {
    /** Non-null while the create-mask sheet is open to add a component to the selected mask in this mode. */
    var addMode by mutableStateOf<MaskMode?>(null)
    /** Right mask panel expanded (true right after creating a mask, otherwise collapsed to one thumbnail). */
    var listExpanded by mutableStateOf(false)
}

internal object MaskActionIcons {
    val Add = lineIcon("m_add", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M12 8V16M8 12H16")
    val Subtract = lineIcon("m_sub", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M8 12H16")
    val Duplicate = lineIcon("m_dup", "M8 8H20V20H8Z", "M16 8V4H4V16H8")
    val More = lineIcon("m_more", "M12 5h0.01M12 12h0.01M12 19h0.01")
    val CollapseUp = lineIcon("m_up", "M7 14L12 9L17 14")
    val Collapse = lineIcon("m_collapse", "M10 7L15 12L10 17")
    val AddSub = lineIcon("m_addsub", "M7.5 7.5A4.5 4.5 0 1 1 7.5 16.5A4.5 4.5 0 1 1 7.5 7.5Z", "M16.5 7.5A4.5 4.5 0 1 1 16.5 16.5A4.5 4.5 0 1 1 16.5 7.5Z", "M5.5 12H9.5", "M14.5 12H18.5M16.5 10V14")
    val Help = lineIcon("m_help", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M9.5 9.5A2.5 2.5 0 1 1 13 11.8C12.3 12.2 12 12.7 12 13.5", "M12 16.8h0.01")
    val ExpandDown = lineIcon("m_down", "M7 10L12 15L17 10")
}

internal fun duplicateMask(state: EditState, mask: Mask, sel: MaskSelection, actions: EditorActions, onSelection: (MaskSelection) -> Unit) {
    val copy = mask.copy(id = newId(), name = mask.name + " copy", components = mask.components.map { it.withNewId() })
    actions.apply { s ->
        val i = s.masks.indexOfFirst { it.id == mask.id }
        s.copy(masks = s.masks.toMutableList().apply { add(if (i < 0) size else i + 1, copy) })
    }
    onSelection(sel.copy(maskId = copy.id, componentId = copy.components.lastOrNull()?.id))
}

internal fun deleteMask(mask: Mask, sel: MaskSelection, actions: EditorActions, onSelection: (MaskSelection) -> Unit) {
    actions.apply { s -> s.copy(masks = s.masks.filter { it.id != mask.id }) }
    onSelection(sel.copy(maskId = null, componentId = null))
}

/** Deletes a component; deleting the last one deletes the mask. */
internal fun deleteComponent(mask: Mask, c: MaskComponent, sel: MaskSelection, actions: EditorActions, onSelection: (MaskSelection) -> Unit) {
    if (mask.components.size <= 1) return deleteMask(mask, sel, actions, onSelection)
    actions.apply { s -> s.mapMask(mask.id) { m -> m.copy(components = m.components.filter { it.id != c.id }) } }
    if (sel.componentId == c.id || sel.componentId == null) onSelection(sel.copy(componentId = mask.components.lastOrNull { it.id != c.id }?.id))
}

internal fun duplicateComponent(mask: Mask, c: MaskComponent, sel: MaskSelection, actions: EditorActions, onSelection: (MaskSelection) -> Unit) {
    val copy = c.withNewId()
    actions.apply { s ->
        s.mapMask(mask.id) { m ->
            val i = m.components.indexOfFirst { it.id == c.id }
            m.copy(components = m.components.toMutableList().apply { add(if (i < 0) size else i + 1, copy) })
        }
    }
    onSelection(sel.copy(maskId = mask.id, componentId = copy.id))
}
