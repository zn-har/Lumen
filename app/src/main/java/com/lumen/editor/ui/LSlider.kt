package com.lumen.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lumen.editor.ui.theme.LColors
import com.lumen.editor.ui.theme.LType
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Editor slider row: label left, value right, thin track below with a fill from the default
 * value. Drag anywhere on the row (relative drag, fine control), double-tap to reset.
 *
 * [onChange] fires continuously while dragging; [onCommit] fires once when the gesture ends
 * (use it to push an undo snapshot).
 */
@Composable
fun LSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit = {},
    default: Float = 0f,
    decimals: Int = 0,
    suffix: String = "",
    trackBrush: Brush? = null,
    modifier: Modifier = Modifier,
) {
    val v by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val feedback = LocalSliderFeedback.current
    Column(
        modifier
            .hideWhileDragging(label)
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .pointerInput(range, default) {
                detectTapGestures(onDoubleTap = { change(default); commit() })
            }
            .pointerInput(range) {
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragStart = { acc = v },
                    onDragEnd = { feedback.value = null; commit() },
                    onDragCancel = { feedback.value = null; commit() },
                ) { ch, dx ->
                    ch.consume()
                    val span = range.endInclusive - range.start
                    acc = (acc + dx / size.width * span).coerceIn(range.start, range.endInclusive)
                    val nv = round(acc, decimals)
                    feedback.value = DragFocus(label, label + ": " + format(nv, decimals, suffix))
                    change(nv)
                }
            },
    ) {
        Row(Modifier.fillMaxWidth()) {
            BasicText(label, style = LType.Label, modifier = Modifier.weight(1f))
            BasicText(format(value, decimals, suffix), style = LType.Value)
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val y = size.height / 2
            val span = range.endInclusive - range.start
            fun xOf(f: Float) = (f - range.start) / span * size.width
            if (trackBrush != null) {
                drawLine(trackBrush, Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
            } else {
                drawLine(LColors.Track, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), StrokeCap.Round)
            }
            // hollow ring thumb
            val c = Offset(xOf(value), y)
            drawCircle(LColors.Controls, 10.dp.toPx(), c)
            drawCircle(LColors.Thumb, 10.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
}

private fun round(v: Float, decimals: Int): Float {
    if (decimals == 0) return v.roundToInt().toFloat()
    var m = 1f; repeat(decimals) { m *= 10f }
    return (v * m).roundToInt() / m
}

private fun format(v: Float, decimals: Int, suffix: String): String {
    val s = if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)
    val signed = if (v > 0f && abs(v) > 1e-4f && !s.startsWith("+")) "+$s" else s
    return signed + suffix
}

/** Uppercase section header used inside panels. */
@Composable
fun LHeader(text: String, modifier: Modifier = Modifier) {
    BasicText(text.uppercase(), style = LType.Header, modifier = modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
}

/** Hue gradient brush helpers for HSL / temperature tracks. */
object Tracks {
    val Temp = Brush.horizontalGradient(listOf(Color(0xFF3A6FD8), Color(0xFFD9D9D9), Color(0xFFE0B13A)))
    val Tint = Brush.horizontalGradient(listOf(Color(0xFF3CB043), Color(0xFFD9D9D9), Color(0xFFC63FC6)))
    val Sat = Brush.horizontalGradient(listOf(Color(0xFF808080), Color(0xFFE04040)))
}

/** The control currently being dragged: [owner] identifies it, [text] ("Temp: -9") is shown over the photo. */
data class DragFocus(val owner: String, val text: String)

/**
 * Drag focus shared by the whole editor. While a slider or wheel is dragged, every other control fades out so only the
 * dragged one and the photo remain (the reference app's behaviour). Write it from custom controls the same way.
 */
val LocalSliderFeedback = staticCompositionLocalOf { mutableStateOf<DragFocus?>(null) }

/** Fades this element out while any control other than [owner] is being dragged. */
@Composable
fun Modifier.hideWhileDragging(owner: String? = null): Modifier {
    val f = LocalSliderFeedback.current.value
    return if (f != null && f.owner != owner) this.alpha(0f) else this
}
