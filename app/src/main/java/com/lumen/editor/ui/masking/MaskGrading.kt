package com.lumen.editor.ui.masking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lumen.editor.model.GradeWheel
import com.lumen.editor.model.LocalAdj
import com.lumen.editor.model.Mask
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LSlider
import com.lumen.editor.ui.hideWhileDragging
import com.lumen.editor.ui.lineIcon
import com.lumen.editor.ui.panels.ColorWheel
import com.lumen.editor.ui.panels.GradeView
import com.lumen.editor.ui.panels.LDropdown
import com.lumen.editor.ui.panels.TitleStyle
import com.lumen.editor.ui.panels.hueColor
import com.lumen.editor.ui.theme.LType

internal val GradingIcon = lineIcon(
    "m_grading",
    "M12 3.5A5 5 0 1 1 12 13.5A5 5 0 1 1 12 3.5Z",
    "M8.5 9.5A5 5 0 1 1 8.5 19.5A5 5 0 1 1 8.5 9.5Z",
    "M15.5 9.5A5 5 0 1 1 15.5 19.5A5 5 0 1 1 15.5 9.5Z",
)

/** Region shown in the mask Grading panel; kept separate from the global grading view. */
private object MaskGradeUi {
    var view by mutableStateOf(GradeView.SHADOWS)
}

private fun LocalAdj.wheel(v: GradeView) = when (v) {
    GradeView.SHADOWS -> gradeShadows; GradeView.MIDTONES -> gradeMidtones
    GradeView.HIGHLIGHTS -> gradeHighlights; else -> gradeGlobal
}

private fun LocalAdj.withWheel(v: GradeView, w: GradeWheel) = when (v) {
    GradeView.SHADOWS -> copy(gradeShadows = w); GradeView.MIDTONES -> copy(gradeMidtones = w)
    GradeView.HIGHLIGHTS -> copy(gradeHighlights = w); else -> copy(gradeGlobal = w)
}

private val hueTrack = Brush.horizontalGradient(List(13) { hueColor(it * 30f, 0.8f, 0.9f) })

/**
 * Per-mask color grading: "<Region> ⌄" dropdown, then either three small wheels (3-way) or the
 * half wheel peeking above Hue / Saturation / Luminance for one region. Wheels use the shared
 * drag focus (big wheel + teardrop pin + "Shadows  Hue: 69°  Sat: 3" pill); double-tap resets.
 * Blending / Balance stay global (Edit › Color › Grading).
 */
@Composable
internal fun MaskGrading(mask: Mask, actions: EditorActions) {
    val view = MaskGradeUi.view
    val adj = mask.adj
    fun setWheel(v: GradeView, f: (GradeWheel) -> GradeWheel) =
        actions.update { s -> s.mapMask(mask.id) { it.copy(adj = it.adj.withWheel(v, f(it.adj.wheel(v)))) } }
    val commit = { actions.commit() }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().hideWhileDragging().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            LDropdown(view.label, GradeView.entries, { it.label }, { MaskGradeUi.view = it }, style = TitleStyle)
        }
        if (view == GradeView.THREE_WAY) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(GradeView.SHADOWS, GradeView.MIDTONES, GradeView.HIGHLIGHTS).forEach { v ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ColorWheel(v.label, adj.wheel(v), { h, s -> setWheel(v) { it.copy(hue = h, sat = s) } }, commit, size = 96.dp)
                        Spacer(Modifier.height(4.dp))
                        BasicText(v.label, style = LType.Tool, modifier = Modifier.hideWhileDragging())
                    }
                }
            }
        } else {
            val w = adj.wheel(view)
            ColorWheel(
                view.label, w, { h, s -> setWheel(view) { it.copy(hue = h, sat = s) } }, commit,
                size = 196.dp, half = true, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            LSlider("Hue", w.hue, 0f..360f, { x -> setWheel(view) { it.copy(hue = x) } }, commit, suffix = "°", trackBrush = hueTrack)
            LSlider(
                "Saturation", w.sat, 0f..100f, { x -> setWheel(view) { it.copy(sat = x) } }, commit,
                trackBrush = Brush.horizontalGradient(listOf(Color(0xFF8A8A8A), hueColor(w.hue, 0.9f, 0.95f))),
            )
            LSlider("Luminance", w.lum, -100f..100f, { x -> setWheel(view) { it.copy(lum = x) } }, commit)
        }
    }
}
