package com.lumen.editor.ui.panels

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lumen.editor.model.EditState
import com.lumen.editor.model.OpticsAdj
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LSlider

@Composable
internal fun OpticsPanel(state: EditState, actions: EditorActions, modifier: Modifier = Modifier) {
    val o = state.optics
    fun set(f: (OpticsAdj) -> OpticsAdj) = actions.update { it.copy(optics = f(it.optics)) }
    fun toggle(f: (OpticsAdj) -> OpticsAdj) = actions.apply { it.copy(optics = f(it.optics)) }
    PanelColumn(modifier) {
        SwitchRow("Remove chromatic aberration", o.removeCA) { on -> toggle { it.copy(removeCA = on) } }
        SwitchRow("Enable lens corrections", o.lensCorrections) { on -> toggle { it.copy(lensCorrections = on) } }
        LSlider("Distortion", o.distortion, -100f..100f, { v -> set { it.copy(distortion = v) } }, actions::commit)
        LSlider("Vignetting", o.lensVignette, -100f..100f, { v -> set { it.copy(lensVignette = v) } }, actions::commit)
        LSlider("Defringe", o.defringe, 0f..100f, { v -> set { it.copy(defringe = v) } }, actions::commit)
    }
}
