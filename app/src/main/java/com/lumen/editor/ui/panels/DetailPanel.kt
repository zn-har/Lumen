package com.lumen.editor.ui.panels

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.lumen.editor.model.DetailAdj
import com.lumen.editor.model.EditState
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LSlider

private val D = DetailAdj()

@Composable
internal fun DetailPanel(state: EditState, actions: EditorActions, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val d = state.detail
    fun set(f: (DetailAdj) -> DetailAdj) = actions.update { it.copy(detail = f(it.detail)) }
    val c = actions::commit
    PanelColumn(modifier) {
        SubTabs(listOf("Sharpening", "Noise", "Color noise"), tab) { tab = it }
        when (tab) {
            0 -> {
                LSlider("Amount", d.sharpen, 0f..150f, { v -> set { it.copy(sharpen = v) } }, c, default = D.sharpen)
                LSlider("Radius", d.sharpRadius, 0.5f..3f, { v -> set { it.copy(sharpRadius = v) } }, c, default = D.sharpRadius, decimals = 1)
                LSlider("Detail", d.sharpDetail, 0f..100f, { v -> set { it.copy(sharpDetail = v) } }, c, default = D.sharpDetail)
                LSlider("Masking", d.sharpMasking, 0f..100f, { v -> set { it.copy(sharpMasking = v) } }, c, default = D.sharpMasking)
            }
            1 -> LSlider("Noise reduction", d.noiseLum, 0f..100f, { v -> set { it.copy(noiseLum = v) } }, c, default = D.noiseLum)
            else -> LSlider("Color noise reduction", d.noiseColor, 0f..100f, { v -> set { it.copy(noiseColor = v) } }, c, default = D.noiseColor)
        }
    }
}
