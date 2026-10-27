package com.lumen.editor.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumen.editor.model.ColorGrading
import com.lumen.editor.model.EditState
import com.lumen.editor.model.GradeWheel
import com.lumen.editor.ui.DragFocus
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LSlider
import com.lumen.editor.ui.LocalSliderFeedback
import com.lumen.editor.ui.hideWhileDragging
import com.lumen.editor.ui.theme.LColors
import com.lumen.editor.ui.theme.LType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class GradeView(val label: String) {
    THREE_WAY("3-way"), SHADOWS("Shadows"), MIDTONES("Midtones"), HIGHLIGHTS("Highlights"), GLOBAL("Global")
}

private fun ColorGrading.wheel(v: GradeView) = when (v) {
    GradeView.SHADOWS -> shadows; GradeView.MIDTONES -> midtones
    GradeView.HIGHLIGHTS -> highlights; else -> global
}

private fun ColorGrading.withWheel(v: GradeView, w: GradeWheel) = when (v) {
    GradeView.SHADOWS -> copy(shadows = w); GradeView.MIDTONES -> copy(midtones = w)
    GradeView.HIGHLIGHTS -> copy(highlights = w); else -> copy(global = w)
}

private const val WHEEL_OWNER = "wheel"
private val hueTrack = Brush.horizontalGradient(List(13) { hueColor(it * 30f, 0.8f, 0.9f) })

/** Grading header title: "<Region> ⌄" dropdown. */
@Composable
internal fun GradeRegionDropdown() {
    LDropdown(ColorUiState.gradeView.label, GradeView.entries, { it.label }, { ColorUiState.gradeView = it }, style = TitleStyle)
}

/** Content of the Color grading sub-mode. */
@Composable
internal fun ColorGradingContent(state: EditState, actions: EditorActions, modifier: Modifier = Modifier) {
    val view = ColorUiState.gradeView
    val g = state.grading
    fun setGrading(f: (ColorGrading) -> ColorGrading) = actions.update { it.copy(grading = f(it.grading)) }
    fun setWheel(v: GradeView, f: (GradeWheel) -> GradeWheel) = setGrading { it.withWheel(v, f(it.wheel(v))) }
    val c = actions::commit

    PanelColumn(modifier) {
        if (view == GradeView.THREE_WAY) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(GradeView.SHADOWS, GradeView.MIDTONES, GradeView.HIGHLIGHTS).forEach { v ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ColorWheel(v.label, g.wheel(v), { h, s -> setWheel(v) { it.copy(hue = h, sat = s) } }, c, size = 96.dp)
                        Spacer(Modifier.height(4.dp))
                        BasicText(v.label, style = LType.Tool, modifier = Modifier.hideWhileDragging())
                    }
                }
            }
        } else {
            val w = g.wheel(view)
            ColorWheel(
                view.label, w, { h, s -> setWheel(view) { it.copy(hue = h, sat = s) } }, c,
                size = 240.dp, half = true, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            LSlider("Hue", w.hue, 0f..360f, { x -> setWheel(view) { it.copy(hue = x) } }, c, suffix = "°", trackBrush = hueTrack)
            LSlider(
                "Saturation", w.sat, 0f..100f, { x -> setWheel(view) { it.copy(sat = x) } }, c,
                trackBrush = Brush.horizontalGradient(listOf(Color(0xFF8A8A8A), hueColor(w.hue, 0.9f, 0.95f))),
            )
            LSlider("Luminance", w.lum, -100f..100f, { x -> setWheel(view) { it.copy(lum = x) } }, c)
        }
        LSlider("Blending", g.blending, 0f..100f, { x -> setGrading { it.copy(blending = x) } }, c, default = 50f)
        LSlider("Balance", g.balance, -100f..100f, { x -> setGrading { it.copy(balance = x) } }, c)
    }
}

/**
 * Hue/saturation wheel. Angle (clockwise from 3 o'clock) = hue 0..360, distance from the centre = saturation 0..100.
 * Drag moves the pin relatively (drag focus shows "<region>  Hue: 69°  Sat: 3"), double-tap resets.
 * With [half] only the top half peeks out until it is touched, then the full wheel is shown.
 */
@Composable
internal fun ColorWheel(
    region: String,
    wheel: GradeWheel,
    onChange: (hue: Float, sat: Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
    half: Boolean = false,
) {
    val w by rememberUpdatedState(wheel)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val feedback = LocalSliderFeedback.current
    val owner = "$WHEEL_OWNER:$region"
    val focused = feedback.value?.owner == owner
    fun focus(h: Float, s: Float) {
        feedback.value = DragFocus(owner, "$region  Hue: ${h.roundToInt()}°  Sat: ${s.roundToInt()}")
    }
    Canvas(
        modifier
            .hideWhileDragging(owner)
            .size(size, if (half && !focused) size / 2 else size)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { change(0f, 0f); commit() })
            }
            .pointerInput(Unit) {
                var pos = Offset.Zero // pin offset from the centre, px
                detectDragGestures(
                    onDragStart = {
                        val r = this.size.width / 2f
                        val a = Math.toRadians(w.hue.toDouble())
                        val d = w.sat / 100f * r
                        pos = Offset((cos(a) * d).toFloat(), (sin(a) * d).toFloat())
                        focus(w.hue, w.sat)
                    },
                    onDragEnd = { feedback.value = null; commit() },
                    onDragCancel = { feedback.value = null; commit() },
                ) { ch, d ->
                    ch.consume()
                    val r = this.size.width / 2f
                    pos += d
                    val dist = pos.getDistance()
                    if (dist > r) pos *= r / dist
                    val hue = ((Math.toDegrees(atan2(pos.y, pos.x).toDouble()).toFloat() + 360f) % 360f).roundToInt() % 360
                    val sat = (pos.getDistance() / r * 100f).roundToInt().toFloat()
                    change(hue.toFloat(), sat)
                    focus(hue.toFloat(), sat)
                }
            },
    ) {
        val r = this.size.width / 2f
        val ctr = Offset(r, r)
        drawCircle(Brush.sweepGradient(List(13) { hueColor(it * 30f) }, ctr), r, ctr, alpha = 0.85f)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFBDBDBD), Color(0x00BDBDBD)), ctr, r), r, ctr)
        drawCircle(LColors.Divider, r, ctr, style = Stroke(1.dp.toPx()))
        val a = Math.toRadians(wheel.hue.toDouble())
        val d = wheel.sat / 100f * r
        val p = Offset(ctr.x + (cos(a) * d).toFloat(), ctr.y + (sin(a) * d).toFloat())
        val fill = hueColor(wheel.hue, wheel.sat / 100f)
        if (focused) drawTeardrop(p, fill) else {
            drawCircle(fill, 7.dp.toPx(), p)
            drawCircle(LColors.Thumb, 7.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
    }
}

/** Map-pin shape: tip at [tip], round head above it filled with [fill]. */
private fun DrawScope.drawTeardrop(tip: Offset, fill: Color) {
    val hr = 13.dp.toPx()
    val head = Offset(tip.x, tip.y - hr * 2.1f)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(head.x - hr * 0.8f, head.y + hr * 0.6f)
        arcTo(androidx.compose.ui.geometry.Rect(head, hr), 143f, 254f, false)
        close()
    }
    drawPath(path, fill)
    drawPath(path, Color.White, style = Stroke(2.dp.toPx()))
    drawCircle(Color.White, 2.dp.toPx(), tip)
}
