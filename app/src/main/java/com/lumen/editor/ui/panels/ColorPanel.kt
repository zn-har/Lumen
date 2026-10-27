package com.lumen.editor.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lumen.editor.model.ColorAdj
import com.lumen.editor.model.EditState
import com.lumen.editor.model.WbPreset
import com.lumen.editor.render.ViewTransform
import com.lumen.editor.ui.EditSubMode
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LSlider
import com.lumen.editor.ui.PillButton
import com.lumen.editor.ui.Tracks
import com.lumen.editor.ui.hideWhileDragging
import com.lumen.editor.ui.theme.LColors
import com.lumen.editor.ui.theme.LType
import kotlin.math.pow

/** UI-only Color state the shell reads to place the photo overlays (eyedropper, targeted mix). */
object ColorUiState {
    /** White balance eyedropper armed: shell shows [WbPickerOverlay]. */
    var wbPicking by mutableStateOf(false)

    /** Targeted color-mix adjust on: shell shows [TargetedMixOverlay]. */
    var targetedMix by mutableStateOf(false)

    /** Selected color-mix band (0..7, Red..Magenta). */
    var mixBand by mutableIntStateOf(0)

    /** Targeted adjust channel: 0 hue, 1 saturation, 2 luminance. */
    var mixChannel by mutableIntStateOf(1)

    internal var gradeView by mutableStateOf(GradeView.SHADOWS)
}

@Composable
internal fun ColorPanel(state: EditState, actions: EditorActions, onSubMode: (EditSubMode) -> Unit, modifier: Modifier = Modifier) {
    val c = state.color
    fun set(f: (ColorAdj) -> ColorAdj) = actions.update { it.copy(color = f(it.color)) }
    PanelColumn(modifier) {
        PillRow(start = {
            BasicText("B&W", style = LType.Label)
            Spacer(Modifier.width(10.dp))
            LSwitch(c.bw, { on -> actions.apply { it.copy(color = it.color.copy(bw = on)) } })
        }) {
            PillButton(PanelIcons.Grading, "Grading") { onSubMode(EditSubMode.GRADING) }
            PillButton(PanelIcons.Mix, "Color mix") { onSubMode(EditSubMode.MIX) }
        }
        Row(
            Modifier.hideWhileDragging().fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("White balance", style = LType.Label)
            Spacer(Modifier.width(8.dp))
            LDropdown(c.wbPreset.label, WbPreset.entries.filter { it != WbPreset.CUSTOM }, { it.label }, { p ->
                actions.apply { it.copy(color = it.color.copy(wbPreset = p, temp = p.temp, tint = p.tint)) }
            }, style = LType.Label.copy(color = LColors.TextDim))
            Spacer(Modifier.weight(1f))
            SquareIconButton(PanelIcons.Eyedropper, ColorUiState.wbPicking, { ColorUiState.wbPicking = !ColorUiState.wbPicking })
        }
        LSlider("Temp", c.temp, -100f..100f, { v -> set { it.copy(temp = v, wbPreset = WbPreset.CUSTOM) } }, actions::commit, trackBrush = Tracks.Temp)
        LSlider("Tint", c.tint, -100f..100f, { v -> set { it.copy(tint = v, wbPreset = WbPreset.CUSTOM) } }, actions::commit, trackBrush = Tracks.Tint)
        LSlider("Vibrance", c.vibrance, -100f..100f, { v -> set { it.copy(vibrance = v) } }, actions::commit)
        LSlider("Saturation", c.saturation, -100f..100f, { v -> set { it.copy(saturation = v) } }, actions::commit)
    }
}

/**
 * White balance eyedropper over the photo (same screen space as [view]). While [active], a tap samples the original
 * photo and sets Temp/Tint so that spot becomes neutral, then calls [onDone].
 */
@Composable
fun WbPickerOverlay(
    state: EditState,
    view: ViewTransform,
    actions: EditorActions,
    active: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!active) return
    val v by rememberUpdatedState(view)
    val acts by rememberUpdatedState(actions)
    val done by rememberUpdatedState(onDone)
    Box(
        modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { o ->
                val m = v.screenToGlobal
                val u = m.mapX(o.x, o.y); val w = m.mapY(o.x, o.y)
                if (u !in 0f..1f || w !in 0f..1f) return@detectTapGestures
                acts.sampleColor(u, w) { rgb ->
                    if (rgb != null) neutralWb(rgb)?.let { (t, n) ->
                        acts.apply { it.copy(color = it.color.copy(temp = t, tint = n, wbPreset = WbPreset.CUSTOM)) }
                    }
                    ColorUiState.wbPicking = false
                    done()
                }
            }
        },
        contentAlignment = Alignment.TopCenter,
    ) {
        BasicText(
            "Tap a neutral grey or white area",
            style = LType.Label,
            modifier = Modifier.padding(top = 16.dp).clip(RoundedCornerShape(16.dp))
                .background(Color(0xCC111111)).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * Inverts the engine's white-balance gain (Shaders.wbGain: r *= (1+.42T)(1+.18t), g *= 1-.28t, b *= (1-.42T)(1+.18t))
 * so the sampled colour ends up grey. Returns slider values (temp, tint) or null for a black sample.
 */
private fun neutralWb(rgb: FloatArray): Pair<Float, Float>? {
    val (r, g, b) = FloatArray(3) { rgb[it].coerceIn(0f, 1f).pow(2.2f) }.let { Triple(it[0], it[1], it[2]) }
    if (r < 1e-4f || g < 1e-4f || b < 1e-4f) return null
    val q = b / r
    val temp = ((q - 1f) / (0.42f * (q + 1f))).coerceIn(-1f, 1f)
    val p = r * (1f + 0.42f * temp) / g
    val tint = ((1f - p) / (0.28f + 0.18f * p)).coerceIn(-1f, 1f)
    return Math.round(temp * 100f).toFloat() to Math.round(tint * 100f).toFloat()
}
