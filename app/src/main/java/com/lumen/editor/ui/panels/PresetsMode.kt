package com.lumen.editor.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumen.editor.model.*
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LIcons
import com.lumen.editor.ui.crop.UnderlineTabs
import com.lumen.editor.ui.lineIcon
import com.lumen.editor.ui.LSlider
import com.lumen.editor.ui.ModeBar
import com.lumen.editor.ui.theme.LColors
import com.lumen.editor.ui.theme.LType

/** The global look a preset owns. Crop, masks, detail and optics are never touched. */
private data class Look(
    val light: LightAdj = LightAdj(),
    val curve: ToneCurve = ToneCurve(),
    val color: ColorAdj = ColorAdj(),
    val grading: ColorGrading = ColorGrading(),
    val effects: EffectsAdj = EffectsAdj(),
) {
    fun applyTo(s: EditState) = s.copy(light = light, curve = curve, color = color, grading = grading, effects = effects)

    companion object {
        fun of(s: EditState) = Look(s.light, s.curve, s.color, s.grading, s.effects)
    }
}

private enum class PresetCategory(val label: String) { COLOR("Color"), CREATIVE("Creative"), BW("B&W"), PORTRAIT("Portrait") }

private class Preset(val name: String, val category: PresetCategory, val swatch: List<Color>, val look: Look) {
    /** Pure preset function: overwrites the global look, keeps everything else. */
    val apply: (EditState) -> EditState = { s -> look.applyTo(s) }
}

// ---------------------------------------------------------------- blending

private fun mix(a: Float, b: Float, k: Float, lo: Float = -100f, hi: Float = 100f) = (a + (b - a) * k).coerceIn(lo, hi)

private fun evalCurve(pts: List<CurvePoint>, x: Float): Float {
    if (x <= pts.first().x) return pts.first().y
    for (i in 1 until pts.size) {
        val p = pts[i - 1]; val q = pts[i]
        if (x <= q.x) return if (q.x - p.x < 1e-6f) q.y else p.y + (q.y - p.y) * (x - p.x) / (q.x - p.x)
    }
    return pts.last().y
}

private fun mixCurve(a: List<CurvePoint>, b: List<CurvePoint>, k: Float): List<CurvePoint> {
    if (a == b) return b
    val xs = (a.map { it.x } + b.map { it.x }).distinct().sorted()
    return xs.map { x -> CurvePoint(x, mix(evalCurve(a, x), evalCurve(b, x), k, 0f, 1f)) }
}

private fun mixWheel(a: GradeWheel, b: GradeWheel, k: Float) = GradeWheel(
    hue = if (b.sat > 0f) b.hue else a.hue,
    sat = mix(a.sat, b.sat, k, 0f, 100f),
    lum = mix(a.lum, b.lum, k),
)

/** Blends every numeric value from [a] (k = 0) to [b] (k = 1); k > 1 exaggerates, clamped to range. */
private fun blend(a: Look, b: Look, k: Float): Look {
    if (k == 1f) return b
    if (k == 0f) return a
    val la = a.light; val lb = b.light
    val ca = a.color; val cb = b.color
    val ga = a.grading; val gb = b.grading
    val ea = a.effects; val eb = b.effects
    return Look(
        light = LightAdj(
            exposure = mix(la.exposure, lb.exposure, k, -5f, 5f),
            contrast = mix(la.contrast, lb.contrast, k), highlights = mix(la.highlights, lb.highlights, k),
            shadows = mix(la.shadows, lb.shadows, k), whites = mix(la.whites, lb.whites, k),
            blacks = mix(la.blacks, lb.blacks, k),
        ),
        curve = ToneCurve(
            master = mixCurve(a.curve.master, b.curve.master, k), red = mixCurve(a.curve.red, b.curve.red, k),
            green = mixCurve(a.curve.green, b.curve.green, k), blue = mixCurve(a.curve.blue, b.curve.blue, k),
        ),
        color = ColorAdj(
            temp = mix(ca.temp, cb.temp, k), tint = mix(ca.tint, cb.tint, k),
            vibrance = mix(ca.vibrance, cb.vibrance, k), saturation = mix(ca.saturation, cb.saturation, k),
            hsl = ca.hsl.zip(cb.hsl) { x, y -> HslBand(mix(x.hue, y.hue, k), mix(x.sat, y.sat, k), mix(x.lum, y.lum, k)) },
        ),
        grading = ColorGrading(
            shadows = mixWheel(ga.shadows, gb.shadows, k), midtones = mixWheel(ga.midtones, gb.midtones, k),
            highlights = mixWheel(ga.highlights, gb.highlights, k), global = mixWheel(ga.global, gb.global, k),
            blending = mix(ga.blending, gb.blending, k, 0f, 100f), balance = mix(ga.balance, gb.balance, k),
        ),
        effects = EffectsAdj(
            texture = mix(ea.texture, eb.texture, k), clarity = mix(ea.clarity, eb.clarity, k),
            dehaze = mix(ea.dehaze, eb.dehaze, k), vignette = mix(ea.vignette, eb.vignette, k),
            vigMidpoint = mix(ea.vigMidpoint, eb.vigMidpoint, k, 0f, 100f),
            vigFeather = mix(ea.vigFeather, eb.vigFeather, k, 0f, 100f),
            vigRoundness = mix(ea.vigRoundness, eb.vigRoundness, k),
            vigHighlights = mix(ea.vigHighlights, eb.vigHighlights, k, 0f, 100f),
            grain = mix(ea.grain, eb.grain, k, 0f, 100f),
            grainSize = mix(ea.grainSize, eb.grainSize, k, 0f, 100f),
            grainRoughness = mix(ea.grainRoughness, eb.grainRoughness, k, 0f, 100f),
        ),
    )
}

// ---------------------------------------------------------------- built-in presets

private fun curve(vararg xy: Float) = List(xy.size / 2) { CurvePoint(xy[it * 2], xy[it * 2 + 1]) }

/** HSL bands by index: 0 Red, 1 Orange, 2 Yellow, 3 Green, 4 Aqua, 5 Blue, 6 Purple, 7 Magenta. */
private fun hsl(vararg bands: Pair<Int, HslBand>) = List(8) { i -> bands.firstOrNull { it.first == i }?.second ?: HslBand() }

private val SOFT_S = curve(0f, 0f, 0.25f, 0.23f, 0.75f, 0.78f, 1f, 1f)
private val MATTE = curve(0f, 0.09f, 0.3f, 0.31f, 0.75f, 0.76f, 1f, 0.96f)
private val FADE = curve(0f, 0.06f, 0.5f, 0.52f, 1f, 0.97f)
private val STRONG_S = curve(0f, 0f, 0.22f, 0.15f, 0.78f, 0.86f, 1f, 1f)

private fun c(hex: Long) = Color(hex)

private val PRESETS = listOf(
    // Color
    Preset("Vivid", PresetCategory.COLOR, listOf(c(0xFFE8473C), c(0xFF2F9BE0)), Look(
        light = LightAdj(contrast = 18f, highlights = -15f, shadows = 12f, whites = 8f, blacks = -8f),
        curve = ToneCurve(master = SOFT_S),
        color = ColorAdj(vibrance = 35f, saturation = 8f),
        effects = EffectsAdj(clarity = 10f, dehaze = 5f),
    )),
    Preset("Warm Glow", PresetCategory.COLOR, listOf(c(0xFFF2A65A), c(0xFFB5543A)), Look(
        light = LightAdj(exposure = 0.1f, contrast = 8f, highlights = -20f, shadows = 15f),
        color = ColorAdj(temp = 18f, tint = 4f, vibrance = 15f),
        grading = ColorGrading(highlights = GradeWheel(hue = 40f, sat = 18f), shadows = GradeWheel(hue = 25f, sat = 10f)),
    )),
    Preset("Cool Breeze", PresetCategory.COLOR, listOf(c(0xFF8FC6E8), c(0xFF2B5C8A)), Look(
        light = LightAdj(contrast = 10f, highlights = -10f, whites = 6f),
        color = ColorAdj(temp = -16f, tint = -3f, vibrance = 12f),
        grading = ColorGrading(shadows = GradeWheel(hue = 210f, sat = 15f), highlights = GradeWheel(hue = 190f, sat = 6f)),
    )),
    Preset("Golden Hour", PresetCategory.COLOR, listOf(c(0xFFFFC857), c(0xFFD1495B)), Look(
        light = LightAdj(exposure = 0.05f, contrast = 12f, highlights = -30f, shadows = 20f, blacks = -5f),
        curve = ToneCurve(master = SOFT_S),
        color = ColorAdj(temp = 25f, tint = 8f, vibrance = 20f,
            hsl = hsl(1 to HslBand(hue = -5f, sat = 12f), 2 to HslBand(hue = -10f, sat = 10f), 5 to HslBand(sat = -15f))),
        grading = ColorGrading(highlights = GradeWheel(hue = 45f, sat = 22f), midtones = GradeWheel(hue = 30f, sat = 8f)),
        effects = EffectsAdj(vignette = -12f),
    )),

    // Creative
    Preset("Faded Film", PresetCategory.CREATIVE, listOf(c(0xFFC9B79C), c(0xFF5E6E66)), Look(
        light = LightAdj(contrast = -10f, highlights = -15f, shadows = 10f),
        curve = ToneCurve(master = FADE),
        color = ColorAdj(temp = 6f, vibrance = -10f, saturation = -12f),
        grading = ColorGrading(shadows = GradeWheel(hue = 170f, sat = 12f), highlights = GradeWheel(hue = 45f, sat = 12f)),
        effects = EffectsAdj(grain = 22f, grainSize = 30f, vignette = -10f),
    )),
    Preset("Teal & Orange", PresetCategory.CREATIVE, listOf(c(0xFF1F8A8A), c(0xFFF08A3C)), Look(
        light = LightAdj(contrast = 15f, highlights = -20f, shadows = 10f, blacks = -6f),
        curve = ToneCurve(master = SOFT_S),
        color = ColorAdj(vibrance = 10f,
            hsl = hsl(1 to HslBand(sat = 15f), 3 to HslBand(hue = 40f, sat = -30f), 4 to HslBand(hue = 10f, sat = 15f), 5 to HslBand(hue = -15f, sat = 10f))),
        grading = ColorGrading(shadows = GradeWheel(hue = 190f, sat = 30f), highlights = GradeWheel(hue = 35f, sat = 25f), balance = 10f),
    )),
    Preset("Matte Fade", PresetCategory.CREATIVE, listOf(c(0xFF9A9A8E), c(0xFF4A4F55)), Look(
        light = LightAdj(contrast = -15f, highlights = -10f, whites = -10f),
        curve = ToneCurve(master = MATTE),
        color = ColorAdj(saturation = -20f),
        effects = EffectsAdj(clarity = -8f),
    )),
    Preset("Cross Process", PresetCategory.CREATIVE, listOf(c(0xFFD7E36A), c(0xFF3A6BC4)), Look(
        light = LightAdj(contrast = 20f),
        curve = ToneCurve(
            red = curve(0f, 0f, 0.25f, 0.18f, 0.75f, 0.84f, 1f, 1f),
            green = curve(0f, 0.02f, 0.5f, 0.54f, 1f, 1f),
            blue = curve(0f, 0.14f, 1f, 0.84f),
        ),
        color = ColorAdj(vibrance = 15f),
        effects = EffectsAdj(vignette = -15f),
    )),

    // B&W
    Preset("Mono", PresetCategory.BW, listOf(c(0xFFE0E0E0), c(0xFF303030)), Look(
        light = LightAdj(contrast = 10f),
        color = ColorAdj(saturation = -100f),
    )),
    Preset("Hard Mono", PresetCategory.BW, listOf(c(0xFFFFFFFF), c(0xFF000000)), Look(
        light = LightAdj(contrast = 35f, highlights = -10f, shadows = -10f, whites = 20f, blacks = -25f),
        curve = ToneCurve(master = STRONG_S),
        color = ColorAdj(saturation = -100f, hsl = hsl(5 to HslBand(lum = -30f), 1 to HslBand(lum = 15f))),
        effects = EffectsAdj(clarity = 25f, vignette = -15f),
    )),
    Preset("Soft Gray", PresetCategory.BW, listOf(c(0xFFBDBDBD), c(0xFF6E6E6E)), Look(
        light = LightAdj(contrast = -15f, highlights = -20f, shadows = 20f),
        curve = ToneCurve(master = FADE),
        color = ColorAdj(saturation = -100f),
        effects = EffectsAdj(grain = 15f),
    )),
    Preset("Selenium", PresetCategory.BW, listOf(c(0xFFD9CFC1), c(0xFF4B3A44)), Look(
        light = LightAdj(contrast = 15f, blacks = -10f),
        curve = ToneCurve(master = SOFT_S),
        color = ColorAdj(saturation = -100f),
        grading = ColorGrading(shadows = GradeWheel(hue = 300f, sat = 14f), highlights = GradeWheel(hue = 40f, sat = 8f)),
    )),

    // Portrait
    Preset("Soft Skin", PresetCategory.PORTRAIT, listOf(c(0xFFF3D3C1), c(0xFFC48E7A)), Look(
        light = LightAdj(exposure = 0.1f, contrast = -5f, highlights = -15f, shadows = 15f),
        color = ColorAdj(temp = 5f, vibrance = 5f, hsl = hsl(0 to HslBand(sat = -8f), 1 to HslBand(sat = -10f, lum = 10f))),
        effects = EffectsAdj(texture = -20f, clarity = -10f),
    )),
    Preset("Bright Airy", PresetCategory.PORTRAIT, listOf(c(0xFFFFFFFF), c(0xFFE8D9CC)), Look(
        light = LightAdj(exposure = 0.35f, contrast = -10f, highlights = -25f, shadows = 25f, whites = 10f),
        curve = ToneCurve(master = curve(0f, 0.04f, 0.5f, 0.55f, 1f, 1f)),
        color = ColorAdj(temp = 4f, vibrance = 8f, saturation = -5f),
        effects = EffectsAdj(clarity = -5f),
    )),
    Preset("Moody", PresetCategory.PORTRAIT, listOf(c(0xFF5C4A3E), c(0xFF1C2228)), Look(
        light = LightAdj(exposure = -0.2f, contrast = 15f, highlights = -30f, shadows = -5f, blacks = -15f),
        curve = ToneCurve(master = MATTE),
        color = ColorAdj(vibrance = -5f, saturation = -15f, hsl = hsl(1 to HslBand(sat = 5f), 3 to HslBand(sat = -40f))),
        grading = ColorGrading(shadows = GradeWheel(hue = 200f, sat = 15f), highlights = GradeWheel(hue = 35f, sat = 10f)),
        effects = EffectsAdj(vignette = -25f),
    )),
    Preset("Warm Portrait", PresetCategory.PORTRAIT, listOf(c(0xFFF0B98D), c(0xFF8C4B32)), Look(
        light = LightAdj(contrast = 8f, highlights = -15f, shadows = 10f),
        curve = ToneCurve(master = SOFT_S),
        color = ColorAdj(temp = 12f, tint = 5f, vibrance = 10f, hsl = hsl(1 to HslBand(lum = 8f))),
        grading = ColorGrading(midtones = GradeWheel(hue = 30f, sat = 8f)),
        effects = EffectsAdj(texture = -10f, vignette = -8f),
    )),
)

// ---------------------------------------------------------------- UI

/** Look before the first preset tap, and the last look we applied (to detect edits made elsewhere). */
private class PresetMemory {
    var base: Look? = null
    var last: Look? = null
}

private enum class PresetTab(val label: String) { RECOMMENDED("Recommended"), CATEGORIES("Categories"), YOURS("Yours") }

private val RECOMMENDED = listOf("Vivid", "Golden Hour", "Teal & Orange", "Faded Film", "Hard Mono", "Soft Skin", "Moody")
    .mapNotNull { n -> PRESETS.firstOrNull { it.name == n } }

private val Chevron = lineIcon("chevron_right", "M9 5L16 12L9 19")

/**
 * Full-screen Presets mode: Recommended · Categories · Yours tabs over preset lists, Amount slider
 * once a preset is picked, mode bar ✕ Presets ✓. Presets apply live and never stack: each one (and
 * Amount) is computed from the look as it was before the first preset was picked; "None" restores it.
 */
@Composable
fun PresetsMode(
    state: EditState,
    actions: EditorActions,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(PresetTab.CATEGORIES) }
    var openCategory by remember { mutableStateOf<PresetCategory?>(null) }
    var selected by remember { mutableStateOf<Preset?>(null) }
    var amount by remember { mutableStateOf(100f) }
    val mem = remember { PresetMemory() }

    // A look that differs from what we last applied means the user edited elsewhere (e.g. undo): start fresh.
    if (selected != null && mem.last != null && Look.of(state) != mem.last) {
        selected = null; mem.base = null; mem.last = null
    }

    fun withPreset(s: EditState, p: Preset, amt: Float): EditState {
        val cur = Look.of(s)
        val base = mem.base?.takeIf { cur == mem.last } ?: cur
        mem.base = base
        val result = blend(base, Look.of(p.apply(s)), amt / 100f)
        mem.last = result
        return result.applyTo(s)
    }

    fun pickNone() {
        val base = mem.base
        if (base != null) actions.apply { s -> base.applyTo(s) }
        selected = null; mem.base = null; mem.last = null
    }

    fun pick(p: Preset) {
        amount = 100f
        selected = p
        actions.apply { s -> withPreset(s, p, 100f) }
    }

    @Composable
    fun PresetList(presets: List<Preset>) {
        PresetRow("None", listOf(LColors.PanelRaised, LColors.Panel), selected == null) { pickNone() }
        presets.forEach { p -> PresetRow(p.name, p.swatch, p === selected) { pick(p) } }
    }

    Column(modifier.fillMaxWidth().background(LColors.Controls)) {
        UnderlineTabs(PresetTab.entries.map { it.label }, tab.ordinal) { tab = PresetTab.entries[it]; openCategory = null }
        Column(Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState())) {
            when (tab) {
                PresetTab.RECOMMENDED -> PresetList(RECOMMENDED)
                PresetTab.CATEGORIES -> {
                    val cat = openCategory
                    if (cat == null) {
                        PresetCategory.entries.forEach { c ->
                            val list = PRESETS.filter { it.category == c }
                            CategoryRow(c.label, list.first().swatch, list.size) { openCategory = c }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().clickable { openCategory = null }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(LIcons.Back, "Back", tint = LColors.Text, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            BasicText(cat.label, style = LType.Title.copy(fontSize = 17.sp))
                        }
                        PresetList(PRESETS.filter { it.category == cat })
                    }
                }
                PresetTab.YOURS -> BasicText(
                    "No presets of your own yet",
                    style = LType.Value.copy(color = LColors.TextDim, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                )
            }
        }
        val sel = selected
        if (sel != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(LColors.Divider))
            LSlider(
                label = "Amount",
                value = amount,
                range = 0f..200f,
                onChange = { v -> amount = v; actions.update { s -> withPreset(s, sel, v) } },
                onCommit = { actions.commit() },
                default = 100f,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        ModeBar("Presets", onCancel, onDone)
    }
}

@Composable
private fun CategoryRow(name: String, swatch: List<Color>, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(swatch)))
        Spacer(Modifier.width(18.dp))
        BasicText(name, style = LType.Title.copy(fontSize = 17.sp, color = LColors.TextDim), modifier = Modifier.weight(1f))
        BasicText(count.toString(), style = LType.Value.copy(color = LColors.TextDim))
        Spacer(Modifier.width(16.dp))
        Icon(Chevron, null, tint = LColors.TextDim, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PresetRow(name: String, swatch: List<Color>, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) LColors.Selected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(4.dp)
        Box(
            Modifier.size(52.dp).clip(shape).background(Brush.linearGradient(swatch))
                .border(2.dp, if (selected) LColors.Text else Color.Transparent, shape),
        )
        Spacer(Modifier.width(18.dp))
        BasicText(
            name,
            style = LType.Title.copy(fontSize = 16.sp, color = if (selected) LColors.Text else LColors.TextDim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(LIcons.More, null, tint = LColors.TextFaint, modifier = Modifier.size(20.dp))
    }
}
