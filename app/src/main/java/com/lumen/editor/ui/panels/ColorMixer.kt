package com.lumen.editor.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lumen.editor.model.EditState
import com.lumen.editor.model.HSL_CENTERS
import com.lumen.editor.model.HslBand
import com.lumen.editor.render.ViewTransform
import com.lumen.editor.ui.DragFocus
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LSlider
import com.lumen.editor.ui.LocalSliderFeedback
import com.lumen.editor.ui.hideWhileDragging
import com.lumen.editor.ui.theme.LColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val BAND_NAMES = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
private val MIX_CHANNELS = listOf("Hue", "Saturation", "Luminance")

internal fun hueColor(hue: Float, sat: Float = 1f, value: Float = 1f) =
    Color.hsv(((hue % 360f) + 360f) % 360f, sat, value)

private fun HslBand.get(ch: Int) = when (ch) { 0 -> hue; 1 -> sat; else -> lum }
private fun HslBand.with(ch: Int, v: Float) = when (ch) { 0 -> copy(hue = v); 1 -> copy(sat = v); else -> copy(lum = v) }

private fun EditState.withBand(band: Int, f: (HslBand) -> HslBand) =
    copy(color = color.copy(hsl = color.hsl.toMutableList().also { it[band] = f(it[band]) }))

/** Targeted-adjust crosshair in the Color mix header; blue while active. */
@Composable
internal fun TargetedButton() {
    SquareIconButton(PanelIcons.Target, ColorUiState.targetedMix, { ColorUiState.targetedMix = !ColorUiState.targetedMix })
}

/** Content of the Color mix sub-mode: colour rings, then H/S/L sliders (or channel tabs in targeted mode). */
@Composable
internal fun ColorMixer(state: EditState, actions: EditorActions, modifier: Modifier = Modifier) {
    val band = ColorUiState.mixBand
    val b = state.color.hsl[band]
    val h = HSL_CENTERS[band]
    PanelColumn(modifier) {
        Row(
            Modifier.hideWhileDragging().fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HSL_CENTERS.forEachIndexed { i, hue ->
                val sel = i == band
                val c = hueColor(hue, 0.85f, 0.95f)
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { ColorUiState.mixBand = i }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(if (sel) 32.dp else 24.dp)) {
                        val sw = 2.5.dp.toPx()
                        if (sel) drawCircle(c) else drawCircle(c, size.minDimension / 2 - sw / 2, style = Stroke(sw))
                    }
                }
            }
        }
        if (ColorUiState.targetedMix) {
            SubTabs(MIX_CHANNELS, ColorUiState.mixChannel) { ColorUiState.mixChannel = it }
        } else {
            fun set(f: (HslBand) -> HslBand) = actions.update { it.withBand(band, f) }
            LSlider(
                "Hue", b.hue, -100f..100f, { v -> set { it.copy(hue = v) } }, actions::commit,
                trackBrush = Brush.horizontalGradient(listOf(hueColor(h - 30f), hueColor(h), hueColor(h + 30f))),
            )
            LSlider(
                "Saturation", b.sat, -100f..100f, { v -> set { it.copy(sat = v) } }, actions::commit,
                trackBrush = Brush.horizontalGradient(listOf(hueColor(h, 0f, 0.6f), hueColor(h, 1f, 0.9f))),
            )
            LSlider(
                "Luminance", b.lum, -100f..100f, { v -> set { it.copy(lum = v) } }, actions::commit,
                trackBrush = Brush.horizontalGradient(listOf(hueColor(h, 0.9f, 0.15f), hueColor(h, 0.8f, 0.6f), hueColor(h, 0.25f, 1f))),
            )
        }
    }
}

/**
 * Targeted colour-mix adjust over the photo (same screen space as [view]). Touching the photo picks the band of the
 * colour under the finger; dragging up/down changes that band's selected channel (ColorUiState.mixChannel).
 * Rendered only while ColorUiState.targetedMix is true.
 */
@Composable
fun TargetedMixOverlay(state: EditState, view: ViewTransform, actions: EditorActions, modifier: Modifier = Modifier) {
    if (!ColorUiState.targetedMix) return
    val v by rememberUpdatedState(view)
    val acts by rememberUpdatedState(actions)
    val st by rememberUpdatedState(state)
    val feedback = LocalSliderFeedback.current
    Box(
        modifier.fillMaxSize().pointerInput(Unit) {
            var acc = 0f
            var band = -1
            val perPx = 200f / (size.height * 0.5f) // half the view height = full -100..100 range
            detectVerticalDragGestures(
                onDragStart = { o ->
                    band = -1
                    val m = v.screenToGlobal
                    val u = m.mapX(o.x, o.y); val w = m.mapY(o.x, o.y)
                    if (u in 0f..1f && w in 0f..1f) acts.sampleColor(u, w) { rgb ->
                        if (rgb != null) {
                            band = nearestBand(rgb)
                            ColorUiState.mixBand = band
                            acc = st.color.hsl[band].get(ColorUiState.mixChannel)
                        }
                    }
                },
                onDragEnd = { feedback.value = null; acts.commit() },
                onDragCancel = { feedback.value = null; acts.commit() },
            ) { ch, dy ->
                ch.consume()
                if (band < 0) return@detectVerticalDragGestures
                val c = ColorUiState.mixChannel
                acc = (acc - dy * perPx).coerceIn(-100f, 100f)
                val nv = acc.roundToInt().toFloat()
                val b = band
                acts.update { it.withBand(b) { hb -> hb.with(c, nv) } }
                val sign = if (nv > 0) "+ " else if (nv < 0) "- " else ""
                feedback.value = DragFocus("targeted", "${BAND_NAMES[b]} ${MIX_CHANNELS[c]}: $sign${abs(nv).roundToInt()}")
            }
        },
    )
}

/** Mix band whose centre hue is closest to the hue of [rgb]. */
private fun nearestBand(rgb: FloatArray): Int {
    val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
    if (d < 1e-4f) return ColorUiState.mixBand
    var hue = when (mx) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    if (hue < 0) hue += 360f
    return HSL_CENTERS.indices.minBy { i ->
        val diff = abs(HSL_CENTERS[i] - hue)
        min(diff, 360f - diff)
    }
}
