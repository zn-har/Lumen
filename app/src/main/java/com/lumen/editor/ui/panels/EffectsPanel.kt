package com.lumen.editor.ui.panels

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.lumen.editor.model.EditState
import com.lumen.editor.model.EffectsAdj
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LSlider

private val D = EffectsAdj()

@Composable
internal fun EffectsPanel(state: EditState, actions: EditorActions, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val e = state.effects
    fun set(f: (EffectsAdj) -> EffectsAdj) = actions.update { it.copy(effects = f(it.effects)) }
    val c = actions::commit
    PanelColumn(modifier) {
        SubTabs(listOf("Effects", "Vignette", "Grain"), tab) { tab = it }
        when (tab) {
            0 -> {
                LSlider("Texture", e.texture, -100f..100f, { v -> set { it.copy(texture = v) } }, c)
                LSlider("Clarity", e.clarity, -100f..100f, { v -> set { it.copy(clarity = v) } }, c)
                LSlider("Dehaze", e.dehaze, -100f..100f, { v -> set { it.copy(dehaze = v) } }, c)
            }
            1 -> {
                LSlider("Amount", e.vignette, -100f..100f, { v -> set { it.copy(vignette = v) } }, c)
                LSlider("Midpoint", e.vigMidpoint, 0f..100f, { v -> set { it.copy(vigMidpoint = v) } }, c, default = D.vigMidpoint)
                LSlider("Feather", e.vigFeather, 0f..100f, { v -> set { it.copy(vigFeather = v) } }, c, default = D.vigFeather)
                LSlider("Roundness", e.vigRoundness, -100f..100f, { v -> set { it.copy(vigRoundness = v) } }, c)
                LSlider("Highlights", e.vigHighlights, 0f..100f, { v -> set { it.copy(vigHighlights = v) } }, c)
            }
            else -> {
                LSlider("Amount", e.grain, 0f..100f, { v -> set { it.copy(grain = v) } }, c)
                LSlider("Size", e.grainSize, 0f..100f, { v -> set { it.copy(grainSize = v) } }, c, default = D.grainSize)
                LSlider("Roughness", e.grainRoughness, 0f..100f, { v -> set { it.copy(grainRoughness = v) } }, c, default = D.grainRoughness)
            }
        }
    }
}
