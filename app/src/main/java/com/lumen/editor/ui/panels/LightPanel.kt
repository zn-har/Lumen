package com.lumen.editor.ui.panels

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lumen.editor.model.EditState
import com.lumen.editor.model.LightAdj
import com.lumen.editor.ui.EditSubMode
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LSlider
import com.lumen.editor.ui.PillButton

@Composable
internal fun LightPanel(state: EditState, actions: EditorActions, onSubMode: (EditSubMode) -> Unit, modifier: Modifier = Modifier) {
    val l = state.light
    fun set(f: (LightAdj) -> LightAdj) = actions.update { it.copy(light = f(it.light)) }
    PanelColumn(modifier) {
        PillRow { PillButton(PanelIcons.Curve, "Curve") { onSubMode(EditSubMode.CURVE) } }
        LSlider("Exposure", l.exposure, -5f..5f, { v -> set { it.copy(exposure = v) } }, actions::commit, decimals = 2)
        LSlider("Contrast", l.contrast, -100f..100f, { v -> set { it.copy(contrast = v) } }, actions::commit)
        LSlider("Highlights", l.highlights, -100f..100f, { v -> set { it.copy(highlights = v) } }, actions::commit)
        LSlider("Shadows", l.shadows, -100f..100f, { v -> set { it.copy(shadows = v) } }, actions::commit)
        LSlider("Whites", l.whites, -100f..100f, { v -> set { it.copy(whites = v) } }, actions::commit)
        LSlider("Blacks", l.blacks, -100f..100f, { v -> set { it.copy(blacks = v) } }, actions::commit)
    }
}
