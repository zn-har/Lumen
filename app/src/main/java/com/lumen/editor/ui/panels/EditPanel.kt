package com.lumen.editor.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumen.editor.model.CurveChannel
import com.lumen.editor.model.EditState
import com.lumen.editor.ui.EditSubMode
import com.lumen.editor.ui.EditTab
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LIcons
import com.lumen.editor.ui.PillButton
import com.lumen.editor.ui.Strip
import com.lumen.editor.ui.StripItem
import com.lumen.editor.ui.hideWhileDragging
import com.lumen.editor.ui.lineIcon
import com.lumen.editor.ui.theme.LColors
import com.lumen.editor.ui.theme.LType

/** Slider list for [tab]. Sub-views (curve, mix, grading) are opened through [onSubMode]. */
@Composable
fun EditPanel(
    tab: EditTab,
    state: EditState,
    actions: EditorActions,
    onSubMode: (EditSubMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(LColors.Controls)) {
        when (tab) {
            EditTab.LIGHT -> LightPanel(state, actions, onSubMode)
            EditTab.COLOR -> ColorPanel(state, actions, onSubMode)
            EditTab.EFFECTS -> EffectsPanel(state, actions)
            EditTab.DETAIL -> DetailPanel(state, actions)
            EditTab.OPTICS -> OpticsPanel(state, actions)
        }
    }
}

/** Category row under the slider panel: Auto (one-shot action) │ Light, Color, Effects, Detail, Optics. */
@Composable
fun EditCategoryRow(
    selected: EditTab,
    onSelect: (EditTab) -> Unit,
    onAuto: () -> Unit,
    edited: Set<EditTab>,
    modifier: Modifier = Modifier,
) {
    Strip(modifier.background(LColors.Controls).hideWhileDragging()) {
        StripItem(LIcons.Auto, "Auto", selected = false, selectedColor = LColors.Selected, onClick = onAuto)
        Box(Modifier.width(1.dp).height(40.dp).background(LColors.Divider))
        EditTab.entries.forEach { tab ->
            Box(contentAlignment = Alignment.BottomCenter) {
                StripItem(tab.icon, tab.label, selected = tab == selected, selectedColor = LColors.Selected) { onSelect(tab) }
                if (tab in edited) Box(Modifier.padding(bottom = 1.dp).size(4.dp).clip(CircleShape).background(LColors.TextDim))
            }
        }
    }
}

/** Bottom area while an Edit sub-mode is open. Done commits and calls [onDone]. */
@Composable
fun EditSubBar(
    mode: EditSubMode,
    state: EditState,
    actions: EditorActions,
    curveChannel: CurveChannel,
    onCurveChannel: (CurveChannel) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = {
        ColorUiState.targetedMix = false
        actions.commit(); onDone()
    }
    Column(modifier.fillMaxWidth().background(LColors.Controls)) {
        when (mode) {
            EditSubMode.CURVE -> {
                SubHeader(done) { BasicText("Curve", style = TitleStyle) }
                CurveChannelRow(curveChannel, onCurveChannel)
            }
            EditSubMode.MIX -> {
                SubHeader(done, center = { TargetedButton() }) { BasicText("Color mix", style = TitleStyle) }
                ColorMixer(state, actions, Modifier.weight(1f, fill = false))
            }
            EditSubMode.GRADING -> {
                SubHeader(done) { GradeRegionDropdown() }
                ColorGradingContent(state, actions, Modifier.weight(1f, fill = false))
            }
            EditSubMode.NONE -> Unit
        }
    }
}

private val EditTab.icon
    get() = when (this) {
        EditTab.LIGHT -> LIcons.Light
        EditTab.COLOR -> LIcons.Color
        EditTab.EFFECTS -> LIcons.Effects
        EditTab.DETAIL -> LIcons.Detail
        EditTab.OPTICS -> LIcons.Optics
    }

// ---------------------------------------------------------------- shared panel pieces

internal val TitleStyle = LType.Title.copy(fontSize = 17.sp)

internal object PanelIcons {
    val Curve = lineIcon("curveLine", "M4 19C11 19 13 5 20 5")
    val Mix = lineIcon("mix", "M12 4A4 4 0 1 1 12 12A4 4 0 1 1 12 4Z", "M8 11A4 4 0 1 1 8 19A4 4 0 1 1 8 11Z", "M16 11A4 4 0 1 1 16 19A4 4 0 1 1 16 11Z")
    val Grading = lineIcon("grading", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M12 9A3 3 0 1 1 12 15A3 3 0 1 1 12 9Z")
    val Eyedropper = lineIcon("eyedropper", "M14.5 4.5L19.5 9.5", "M17 7L8 16L5 19", "M13 6L18 11", "M16 3.5A2.1 2.1 0 0 1 20.5 8")
    val Target = lineIcon("target", "M12 6A6 6 0 1 1 12 18A6 6 0 1 1 12 6Z", "M12 2V8M12 16V22M2 12H8M16 12H22")
    val Chevron = lineIcon("chevron", "M7 10L12 15L17 10")
    val Parametric = lineIcon("parametric", "M4 19C8 19 9 12 12 12C15 12 16 5 20 5", "M4 15C8 15 9 8 12 8C15 8 16 2 20 2")
}

/** Vertically scrollable panel body. */
@Composable
internal fun PanelColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(LColors.Controls).verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
        content = content,
    )
}

/** Top row of a panel: optional [start] content, PillButtons right-aligned. Hidden while dragging. */
@Composable
internal fun PillRow(start: @Composable RowScope.() -> Unit = {}, pills: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.hideWhileDragging().fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        start()
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = pills)
    }
}

/** Sub-mode header: title (left) · optional centre content · outlined "Done" pill (right). */
@Composable
private fun SubHeader(onDone: () -> Unit, center: (@Composable () -> Unit)? = null, title: @Composable () -> Unit) {
    Box(Modifier.hideWhileDragging().fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { title() }
        if (center != null) Box(Modifier.align(Alignment.Center)) { center() }
        PillButton(null, "Done", Modifier.align(Alignment.CenterEnd), onClick = onDone)
    }
}

/** Text sub-tabs with an underline on the selected one (Effects | Vignette | Grain …). */
@Composable
internal fun SubTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.hideWhileDragging().fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, l ->
            val sel = i == selected
            Column(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(l, style = LType.Label.copy(color = if (sel) LColors.Text else LColors.TextDim))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(24.dp).height(2.dp).background(if (sel) LColors.Text else Color.Transparent))
            }
        }
    }
}

/** Small on/off switch. */
@Composable
internal fun LSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp, 24.dp).clip(CircleShape).clickable { onChange(!checked) }) {
        val h = size.height
        drawRoundRect(
            if (checked) LColors.Accent else LColors.Track,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
        )
        val r = h / 2 - 3.dp.toPx()
        drawCircle(Color.White, r, Offset(if (checked) size.width - h / 2 else h / 2, h / 2))
    }
}

/** Label left, switch right. */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.hideWhileDragging().fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = LType.Label, modifier = Modifier.weight(1f))
        LSwitch(checked, onChange)
    }
}

/** "<label> ⌄" text button opening a dropdown of [items]. */
@Composable
internal fun <T> LDropdown(
    label: String,
    items: List<T>,
    itemLabel: (T) -> String,
    onPick: (T) -> Unit,
    style: androidx.compose.ui.text.TextStyle = LType.Label,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = true }.padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(label, style = style)
            Spacer(Modifier.width(4.dp))
            Icon(PanelIcons.Chevron, null, tint = LColors.TextDim, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }, Modifier.background(LColors.PanelRaised)) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item), color = LColors.Text, fontSize = 15.sp) },
                    onClick = { open = false; onPick(item) },
                )
            }
        }
    }
}

/** Square dark icon button (eyedropper, crosshair). [active] tints it accent blue. */
@Composable
internal fun SquareIconButton(icon: ImageVector, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) LColors.Accent else Color(0xFF111111))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
}
