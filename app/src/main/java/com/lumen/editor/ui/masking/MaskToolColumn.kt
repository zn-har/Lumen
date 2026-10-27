package com.lumen.editor.ui.masking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumen.editor.model.EditState
import com.lumen.editor.model.MaskComponent
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LIcons
import com.lumen.editor.ui.MaskSelection
import com.lumen.editor.ui.DragFocus
import com.lumen.editor.ui.LocalSliderFeedback
import com.lumen.editor.ui.hideWhileDragging
import com.lumen.editor.ui.theme.LColors
import com.lumen.editor.ui.theme.LType
import kotlin.math.roundToInt

/**
 * Left-edge vertical tool column over the photo with the selected component's settings
 * (meant for the bottom-left corner): component icon, its values, invert, delete. Brush adds the
 * brush / eraser toggle and size / feather / flow. Tapping a value opens a vertical scrubber next
 * to the column (drag or tap the track; double-tap resets) with the drag-focus pill.
 */
@Composable
fun MaskToolColumn(
    state: EditState,
    selection: MaskSelection,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    val mask = state.selectedMask(selection) ?: return
    val c = state.selectedComponent(selection) ?: return
    var active by remember(c.id) { mutableStateOf<String?>(null) }

    fun upd(f: (MaskComponent) -> MaskComponent) = actions.update { s -> s.mapComponent(mask.id, c.id, f) }
    fun set(f: (MaskComponent) -> MaskComponent) = actions.apply { s -> s.mapComponent(mask.id, c.id, f) }
    val commit = { actions.commit() }

    val params: List<Param> = when (c) {
        is MaskComponent.Brush -> listOf(
            Param("Size", BrushSettings.size * 100f, 0.5f..30f, 5f, 1, { BrushSettings.size = it / 100f }, {}),
            Param("Feather", BrushSettings.feather * 100f, 0f..100f, 50f, 0, { BrushSettings.feather = it / 100f }, {}),
            Param("Flow", BrushSettings.flow * 100f, 1f..100f, 100f, 0, { BrushSettings.flow = it / 100f }, {}),
        )
        is MaskComponent.Radial -> listOf(
            Param("Feather", c.feather, 0f..100f, 50f, 0, { v -> upd { (it as MaskComponent.Radial).copy(feather = v) } }, commit),
        )
        is MaskComponent.ColorRange -> listOf(
            Param("Refine", c.refine, 0f..100f, 50f, 0, { v -> upd { (it as MaskComponent.ColorRange).copy(refine = v) } }, commit),
        )
        is MaskComponent.LuminanceRange -> listOf(
            Param("Low", c.lo * 100f, 0f..100f, 50f, 0, { v -> upd { (it as MaskComponent.LuminanceRange).let { l -> l.copy(lo = minOf(v / 100f, l.hi)) } } }, commit, LumTrack),
            Param("High", c.hi * 100f, 0f..100f, 100f, 0, { v -> upd { (it as MaskComponent.LuminanceRange).let { l -> l.copy(hi = maxOf(v / 100f, l.lo)) } } }, commit, LumTrack),
            Param("Fth lo", c.featherLo * 100f, 0f..100f, 15f, 0, { v -> upd { (it as MaskComponent.LuminanceRange).copy(featherLo = v / 100f) } }, commit),
            Param("Fth hi", c.featherHi * 100f, 0f..100f, 0f, 0, { v -> upd { (it as MaskComponent.LuminanceRange).copy(featherHi = v / 100f) } }, commit),
        )
        else -> emptyList()
    }

    Box(modifier) {
    Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.Bottom) {
        Column(
            Modifier
                .hideWhileDragging()
                .width(60.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xE61E1E1E))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(LColors.Accent), contentAlignment = Alignment.Center) {
                Icon(c.icon(), c.label(), tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Sep()
            if (c is MaskComponent.Brush) {
                ToolBtn(MaskIcons.Paint, "Brush", on = !BrushSettings.erase) { BrushSettings.erase = false }
                ToolBtn(MaskIcons.Erase, "Erase", on = BrushSettings.erase) { BrushSettings.erase = true }
                Sep()
            }
            for (p in params) {
                ValueBtn(p, p.label == active) { active = if (active == p.label) null else p.label }
            }
            if (c is MaskComponent.ColorRange) {
                SampleDots(c.samples)
                if (c.samples.isNotEmpty()) ToolBtn(LIcons.Reset, "Clear colors") {
                    set { (it as MaskComponent.ColorRange).copy(samples = emptyList()) }
                }
            }
            if (params.isNotEmpty() || c is MaskComponent.ColorRange) Sep()
            ToolBtn(LIcons.Invert, "Invert", on = c.invert) { set { it.with(invert = !it.invert) } }
            ToolBtn(LIcons.Trash, "Delete ${c.label()}", tint = LColors.Danger) {
                // deleting the last component deletes the mask (selection then falls back on its own)
                actions.apply { s ->
                    if (mask.components.size <= 1) s.copy(masks = s.masks.filter { it.id != mask.id })
                    else s.mapMask(mask.id) { m -> m.copy(components = m.components.filter { it.id != c.id }) }
                }
            }
        }
        val p = params.find { it.label == active }
        if (p != null) {
            Spacer(Modifier.width(8.dp))
            VSlider(p)
        }
    }
    }
}

private val LumTrack = Brush.verticalGradient(listOf(Color.White, Color.Black))

private class Param(
    val label: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val default: Float,
    val decimals: Int,
    val onChange: (Float) -> Unit,
    val onCommit: () -> Unit,
    val track: Brush? = null,
)

private fun fmt(v: Float, decimals: Int) = if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)

@Composable
private fun Sep() = Box(Modifier.width(26.dp).height(1.dp).background(LColors.Divider))

@Composable
private fun ToolBtn(icon: ImageVector, desc: String, on: Boolean = false, tint: Color = LColors.Text, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(if (on) LColors.Selected else Color.Transparent)
            .border(if (on) 1.5.dp else 0.dp, if (on) Color.White else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (on) Color.White else tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ValueBtn(p: Param, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(52.dp).clip(RoundedCornerShape(10.dp)).background(if (on) LColors.Selected else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(fmt(p.value, if (p.decimals > 0 && p.value >= 10f) 0 else p.decimals), style = LType.Label.copy(color = if (on) LColors.Accent else Color.White, fontSize = 15.sp))
        BasicText(p.label, style = LType.Tool.copy(fontSize = 10.sp, textAlign = TextAlign.Center), maxLines = 1)
    }
}

@Composable
private fun SampleDots(samples: List<FloatArray>) {
    if (samples.isEmpty()) {
        BasicText("Tap\nphoto", style = LType.Tool.copy(fontSize = 10.sp, textAlign = TextAlign.Center))
        return
    }
    for (rgb in samples) {
        Box(
            Modifier.size(16.dp).clip(CircleShape)
                .background(Color(rgb[0].coerceIn(0f, 1f), rgb[1].coerceIn(0f, 1f), rgb[2].coerceIn(0f, 1f)))
                .border(1.dp, LColors.Divider, CircleShape),
        )
    }
}

/** Vertical slider: top = max. Relative drag for fine control, tap jumps, double-tap resets. */
@Composable
private fun VSlider(p: Param) {
    val cur by rememberUpdatedState(p)
    val feedback = LocalSliderFeedback.current
    fun show(v: Float) { feedback.value = DragFocus(FOCUS, "${cur.label} ${fmt(v, cur.decimals)}") }
    Column(
        Modifier.hideWhileDragging(FOCUS).width(56.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xE61E1E1E)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(fmt(p.value, p.decimals), style = LType.Label.copy(fontSize = 14.sp))
        BasicText(p.label, style = LType.Tool.copy(fontSize = 10.sp))
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .width(40.dp)
                .height(200.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { cur.onChange(cur.default); cur.onCommit() },
                        onTap = { o ->
                            val r = cur.range
                            val t = 1f - ((o.y - PAD) / (size.height - 2 * PAD)).coerceIn(0f, 1f)
                            cur.onChange(r.start + t * (r.endInclusive - r.start)); cur.onCommit()
                        },
                    )
                }
                .pointerInput(Unit) {
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragStart = { acc = cur.value; show(acc) },
                        onDragEnd = { feedback.value = null; cur.onCommit() },
                        onDragCancel = { feedback.value = null; cur.onCommit() },
                    ) { ch, dy ->
                        ch.consume()
                        val r = cur.range
                        acc = (acc - dy / (size.height - 2 * PAD) * (r.endInclusive - r.start)).coerceIn(r.start, r.endInclusive)
                        cur.onChange(acc)
                        show(acc)
                    }
                },
        ) {
            val x = size.width / 2
            val top = PAD; val bottom = size.height - PAD
            val r = p.range
            val t = ((p.value - r.start) / (r.endInclusive - r.start)).coerceIn(0f, 1f)
            val y = bottom - t * (bottom - top)
            if (p.track != null) drawLine(p.track, Offset(x, top), Offset(x, bottom), 4.dp.toPx(), StrokeCap.Round)
            else drawLine(LColors.Track, Offset(x, top), Offset(x, bottom), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(Color(0xFF1E1E1E), 10.dp.toPx(), Offset(x, y))
            drawCircle(Color.White, 10.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
        }
    }
}

private const val PAD = 30f
private const val FOCUS = "mask-tool"
