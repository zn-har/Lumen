package com.lumen.editor.ui.masking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lumen.editor.model.EditState
import com.lumen.editor.model.Mask
import com.lumen.editor.model.MaskComponent
import com.lumen.editor.model.MaskMode
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LIcons
import com.lumen.editor.ui.MaskSelection
import com.lumen.editor.ui.hideWhileDragging
import com.lumen.editor.ui.theme.LColors
import com.lumen.editor.ui.theme.LType
import kotlinx.coroutines.delay

/**
 * Right mask panel over the photo. Expanded: rounded dark column with a blue "+" (new mask),
 * then one square black/white thumbnail per mask; the selected mask sits in a darker group with
 * its component chips and a combined Add / Subtract button. Collapsed: a single thumbnail button
 * at the bottom-right of the box the shell gives (fillMaxSize over the photo). Only buttons take input.
 *
 * Tap the selected thumbnail for the mask menu; tap a selected component chip for its menu.
 * "+" clears the selection, which makes [MaskingBottom] show "Create new Mask"; Add / Subtract
 * open it through [MaskingUi.addMode].
 */
@Composable
fun MaskList(
    state: EditState,
    selection: MaskSelection,
    onSelection: (MaskSelection) -> Unit,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    val selMask = state.selectedMask(selection)
    val selComp = state.selectedComponent(selection)
    var renaming by remember { mutableStateOf<Mask?>(null) }

    // thumbnails survive collapse / expand; refreshed (debounced) when a mask's shape changes
    val thumbs = remember { mutableStateMapOf<Long, ImageBitmap>() }
    for (m in state.masks) key(m.id) {
        LaunchedEffect(m.components, m.inverted, com.lumen.editor.ui.MaskRevision.value) {
            delay(220)
            actions.maskThumbnail(m.id) { b -> if (b != null) thumbs[m.id] = b.asImageBitmap() }
        }
    }

    Box(modifier.hideWhileDragging()) {
        if (!MaskingUi.listExpanded) {
            Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp)) {
                if (selMask != null || state.masks.isNotEmpty()) {
                    val m = selMask ?: state.masks.last()
                    Thumb(m, thumbs[m.id], selected = true, size = 52.dp) { MaskingUi.listExpanded = true }
                } else {
                    PlusButton { MaskingUi.listExpanded = true }
                }
            }
            return@Box
        }

        Column(
            Modifier
                .align(Alignment.TopEnd)
                .width(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xE61E1E1E))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(32.dp, 22.dp).clip(RoundedCornerShape(6.dp)).clickable { MaskingUi.listExpanded = false },
                contentAlignment = Alignment.Center,
            ) {
                Icon(MaskActionIcons.Collapse, "Collapse masks", tint = LColors.TextDim, modifier = Modifier.size(18.dp))
            }
            PlusButton {
                MaskingUi.addMode = null
                onSelection(selection.copy(maskId = null, componentId = null))
            }

            for (m in state.masks) {
                if (m.id != selMask?.id) {
                    Thumb(m, thumbs[m.id], selected = false) {
                        MaskingUi.addMode = null
                        onSelection(selection.copy(maskId = m.id, componentId = m.components.lastOrNull()?.id, showOverlay = true))
                    }
                    continue
                }
                // selected mask group
                Column(
                    Modifier
                        .width(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0C0C0C))
                        .padding(vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    var menu by remember(m.id) { mutableStateOf(false) }
                    Box {
                        Thumb(m, thumbs[m.id], selected = true) { menu = true }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = LColors.PanelRaised) {
                            MenuItem(MaskActionIcons.Add, "Add to \"${m.name}\"") { menu = false; MaskingUi.addMode = MaskMode.ADD }
                            MenuItem(MaskActionIcons.Subtract, "Subtract from \"${m.name}\"") { menu = false; MaskingUi.addMode = MaskMode.SUBTRACT }
                            MenuItem(LIcons.Invert, "Invert", checked = m.inverted) {
                                menu = false; actions.apply { s -> s.mapMask(m.id) { it.copy(inverted = !it.inverted) } }
                            }
                            MenuItem(if (m.visible) LIcons.EyeOff else LIcons.Eye, if (m.visible) "Hide" else "Show") {
                                menu = false; actions.apply { s -> s.mapMask(m.id) { it.copy(visible = !it.visible) } }
                            }
                            MenuItem(MaskIcons.Rename, "Rename") { menu = false; renaming = m }
                            MenuItem(MaskActionIcons.Duplicate, "Duplicate") { menu = false; duplicateMask(state, m, selection, actions, onSelection) }
                            MenuItem(LIcons.Trash, "Delete", tint = LColors.Danger) { menu = false; deleteMask(m, selection, actions, onSelection) }
                        }
                    }
                    for (c in m.components) ComponentChip(m, c, c.id == selComp?.id, selection, onSelection, actions)
                    AddSubButton()
                }
            }
        }
    }

    renaming?.let { m ->
        RenameDialog(m.name, onDismiss = { renaming = null }) { n ->
            renaming = null
            if (n.isNotBlank() && n.trim() != m.name) actions.apply { s -> s.mapMask(m.id) { it.copy(name = n.trim()) } }
        }
    }
}

@Composable
private fun PlusButton(onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(LColors.Accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(LIcons.Plus, "Create new mask", tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** Square black/white mask thumbnail (white = selected area); icon fallback until the GPU delivers one. */
@Composable
private fun Thumb(m: Mask, bmp: ImageBitmap?, selected: Boolean, size: Dp = 44.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(Color.Black)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else LColors.Divider, shape)
            .clickable(onClick = onClick)
            .alpha(if (m.visible) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp, m.name, Modifier.fillMaxSize().padding(if (selected) 2.dp else 1.dp), contentScale = ContentScale.Crop)
        } else {
            Icon(m.icon(), m.name, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ComponentChip(
    m: Mask, c: MaskComponent, on: Boolean,
    selection: MaskSelection, onSelection: (MaskSelection) -> Unit, actions: EditorActions,
) {
    var menu by remember(c.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.padding(start = 8.dp)) {
        Box(
            Modifier
                .size(36.dp)
                .clip(shape)
                .background(LColors.PanelRaised)
                .border(if (on) 2.dp else 0.dp, if (on) LColors.Accent else Color.Transparent, shape)
                .clickable { if (on) menu = true else onSelection(selection.copy(maskId = m.id, componentId = c.id, showOverlay = true)) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(c.icon(), c.label(), tint = if (on) Color.White else LColors.TextDim, modifier = Modifier.size(18.dp))
            val badge = when (c.mode) { MaskMode.ADD -> null; MaskMode.SUBTRACT -> "−"; MaskMode.INTERSECT -> "∩" }
            if (badge != null) {
                BasicText(badge, style = LType.Tool.copy(color = Color.White, fontSize = 10.sp), modifier = Modifier.align(Alignment.TopStart).padding(start = 3.dp))
            }
            // tiny corner triangle = has menu
            Canvas(Modifier.align(Alignment.BottomEnd).size(8.dp)) {
                val p = Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                drawPath(p, if (on) Color.White else LColors.TextDim)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = LColors.PanelRaised) {
            MenuItem(LIcons.Invert, "Invert", checked = c.invert) {
                menu = false; actions.apply { s -> s.mapComponent(m.id, c.id) { it.with(invert = !it.invert) } }
            }
            MenuItem(MaskIcons.Intersect, "Intersect with…") { menu = false; MaskingUi.addMode = MaskMode.INTERSECT }
            MenuItem(MaskActionIcons.Duplicate, "Duplicate") { menu = false; duplicateComponent(m, c, selection, actions, onSelection) }
            MenuItem(LIcons.Trash, "Delete", tint = LColors.Danger) { menu = false; deleteComponent(m, c, selection, actions, onSelection) }
        }
    }
}

/** Combined "⊖⊕" button: Add / Subtract, then the create list in that mode. */
@Composable
private fun AddSubButton() {
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(44.dp, 30.dp).clip(RoundedCornerShape(15.dp)).background(LColors.PanelRaised).clickable { menu = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MaskActionIcons.AddSub, "Add or subtract", tint = Color.White, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = LColors.PanelRaised) {
            MenuItem(MaskActionIcons.Add, "Add") { menu = false; MaskingUi.addMode = MaskMode.ADD }
            MenuItem(MaskActionIcons.Subtract, "Subtract") { menu = false; MaskingUi.addMode = MaskMode.SUBTRACT }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, checked: Boolean = false, tint: Color = LColors.Text, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = tint, fontSize = 15.sp) },
        onClick = onClick,
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) },
        trailingIcon = if (checked) ({ Icon(LIcons.Check, null, tint = LColors.Accent, modifier = Modifier.size(16.dp)) }) else null,
    )
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(LColors.PanelRaised).padding(20.dp)) {
            BasicText("Rename mask", style = LType.Title)
            Spacer(Modifier.height(14.dp))
            BasicTextField(
                text, { text = it },
                singleLine = true,
                textStyle = LType.Label,
                cursorBrush = SolidColor(LColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(text) }),
                modifier = Modifier.fillMaxWidth().border(1.dp, LColors.Divider, RoundedCornerShape(8.dp)).padding(12.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BasicText("Cancel", style = LType.Label.copy(color = LColors.TextDim), modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
                Spacer(Modifier.width(8.dp))
                BasicText("Rename", style = LType.Label.copy(color = LColors.Accent), modifier = Modifier.clickable { onDone(text) }.padding(10.dp))
            }
        }
    }
}
