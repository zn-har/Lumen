package com.lumen.editor.ui.masking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumen.editor.model.AiKind
import com.lumen.editor.model.EditState
import com.lumen.editor.model.LocalAdj
import com.lumen.editor.model.Mask
import com.lumen.editor.model.MaskMode
import com.lumen.editor.model.newId
import com.lumen.editor.ui.DragFocus
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LIcons
import com.lumen.editor.ui.LSlider
import com.lumen.editor.ui.LocalSliderFeedback
import com.lumen.editor.ui.MaskSelection
import com.lumen.editor.ui.ModeBar
import com.lumen.editor.ui.Strip
import com.lumen.editor.ui.StripItem
import com.lumen.editor.ui.Tracks
import com.lumen.editor.ui.hideWhileDragging
import com.lumen.editor.ui.theme.LColors
import com.lumen.editor.ui.theme.LType
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Whole bottom area of Masking mode, stacked bottom-up: mode bar, then either the
 * "Create new Mask" sheet (no mask selected, or Add / Subtract from the mask panel) or the
 * selected mask's category row + slider panel. Mask actions live in [MaskList], component
 * settings in [MaskToolColumn].
 */
@Composable
fun MaskingBottom(
    state: EditState,
    selection: MaskSelection,
    onSelection: (MaskSelection) -> Unit,
    actions: EditorActions,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mask = state.selectedMask(selection)
    val addMode = if (mask == null) null else MaskingUi.addMode
    LaunchedEffect(mask == null) { if (mask == null) MaskingUi.addMode = null }
    var pendingAi by remember { mutableStateOf<AiKind?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(error) {
        if (error != null) { delay(5000); error = null }
    }

    fun create(kind: CreateKind) {
        val mode = addMode
        if (mask != null && mode != null) {
            val comp = kind.build(mode)
            actions.apply { s -> s.mapMask(mask.id) { it.copy(components = it.components + comp) } }
            onSelection(selection.copy(maskId = mask.id, componentId = comp.id, showOverlay = true))
        } else {
            val comp = kind.build(MaskMode.ADD)
            val m = Mask(id = newId(), name = nextMaskName(state), components = listOf(comp))
            actions.apply { s -> s.copy(masks = s.masks + m) }
            onSelection(selection.copy(maskId = m.id, componentId = comp.id, showOverlay = true))
        }
        MaskingUi.addMode = null
        MaskingUi.listExpanded = true
        if (kind is CreateKind.Ai) {
            pendingAi = kind.kind
            error = null
            actions.ensureAi(kind.kind) { err ->
                if (pendingAi == kind.kind) pendingAi = null
                if (err != null) error = err
            }
        }
    }

    Column(modifier.fillMaxWidth().background(LColors.Controls)) {
        StatusRow(pendingAi, error, onDismiss = { error = null })
        if (mask == null || addMode != null) {
            CreateSheet(
                title = when (addMode) {
                    MaskMode.ADD -> "Add to \"${mask?.name}\""
                    MaskMode.SUBTRACT -> "Subtract from \"${mask?.name}\""
                    MaskMode.INTERSECT -> "Intersect with \"${mask?.name}\""
                    null -> "Create new Mask"
                },
                onPick = ::create,
                onClose = if (addMode != null) ({ MaskingUi.addMode = null }) else null,
            )
        } else {
            Adjustments(mask, actions)
        }
        ModeBar("Masking", onCancel, onDone, Modifier.hideWhileDragging())
    }
}

// ------------------------------------------------------------------ status

@Composable
private fun StatusRow(pending: AiKind?, error: String?, onDismiss: () -> Unit) {
    if (pending == null && error == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .hideWhileDragging()
            .background(if (error != null) Color(0xFF3A1E20) else LColors.PanelRaised)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (error != null) {
            BasicText(error, style = LType.Label.copy(color = LColors.Danger, fontSize = 13.sp), modifier = Modifier.weight(1f), maxLines = 2)
            Icon(LIcons.Close, "Dismiss", tint = LColors.TextDim, modifier = Modifier.size(18.dp).clickable(onClick = onDismiss))
        } else {
            CircularProgressIndicator(Modifier.size(16.dp), color = LColors.Accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            BasicText("Detecting ${pending!!.label.lowercase()}…", style = LType.Label.copy(fontSize = 13.sp))
        }
    }
}

// ------------------------------------------------------------------ create new mask (list sheet)

@Composable
private fun CreateSheet(title: String, onPick: (CreateKind) -> Unit, onClose: (() -> Unit)?) {
    var help by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 400.dp).hideWhileDragging()
            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, style = LType.Title.copy(fontSize = 17.sp), modifier = Modifier.weight(1f), maxLines = 1)
            RoundIcon(MaskActionIcons.Help, "Help", tint = if (help) LColors.Accent else LColors.TextDim) { help = !help }
            if (onClose != null) RoundIcon(LIcons.Close, "Close", onClick = onClose)
        }
        if (help) {
            BasicText(
                "Masks limit the adjustments below to part of the photo. Select subject, sky, background or people " +
                    "to detect them automatically, or draw one with a brush or gradient. Add or subtract more parts later " +
                    "from the mask panel on the right.",
                style = LType.Tool, modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Card(CreateKind.AI.map { it to "Select ${it.label.lowercase()}" }, onPick)
        Card(listOf(CreateKind.Brush to "Brush", CreateKind.Linear to "Linear gradient", CreateKind.Radial to "Radial gradient"), onPick)
        Card(listOf(CreateKind.ColorRange to "Color range", CreateKind.LumRange to "Luminance range"), onPick)
    }
}

@Composable
private fun Card(rows: List<Pair<CreateKind, String>>, onPick: (CreateKind) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LColors.PanelRaised)) {
        rows.forEachIndexed { i, (k, label) ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 54.dp).height(1.dp).background(LColors.Divider))
            Row(
                Modifier.fillMaxWidth().height(50.dp).clickable { onPick(k) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(k.icon, null, tint = LColors.Text, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                BasicText(label, style = LType.Label)
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, tint: Color = LColors.Text, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

// ------------------------------------------------------------------ adjustments

private enum class MaskCat(val label: String, val icon: ImageVector) {
    LIGHT("Light", LIcons.Light), COLOR("Color", LIcons.Color), GRADING("Grading", GradingIcon),
    EFFECTS("Effects", LIcons.Effects), DETAIL("Detail", LIcons.Detail),
}

@Composable
private fun Adjustments(mask: Mask, actions: EditorActions) {
    var cat by remember { mutableStateOf(MaskCat.LIGHT) }
    fun slider(r: AdjRow, range: ClosedFloatingPointRange<Float> = r.range, decimals: Int = r.decimals) = @Composable {
        LSlider(
            r.label, r.get(mask.adj), range,
            onChange = { v -> actions.update { s -> s.mapMask(mask.id) { it.copy(adj = r.set(it.adj, v)) } } },
            onCommit = { actions.commit() },
            default = r.default, decimals = decimals, suffix = r.suffix, trackBrush = r.track,
        )
    }
    Column(Modifier.fillMaxWidth().height(212.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        if (cat == MaskCat.COLOR) {
            ColorCategory(mask, actions) { r, range, dec -> slider(r, range, dec)() }
        } else if (cat == MaskCat.GRADING) {
            MaskGrading(mask, actions)
        } else {
            for (r in rowsFor(cat)) slider(r)()
        }
        if (mask.adj != LocalAdj()) {
            BasicText(
                "Reset mask adjustments", style = LType.Tool.copy(color = LColors.Accent),
                modifier = Modifier.hideWhileDragging().padding(horizontal = 20.dp, vertical = 12.dp)
                    .clickable { actions.apply { s -> s.mapMask(mask.id) { it.copy(adj = LocalAdj()) } } },
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).hideWhileDragging().background(LColors.Divider))
    Strip(Modifier.hideWhileDragging().background(LColors.ControlsSolid)) {
        for (c in MaskCat.entries) {
            StripItem(c.icon, c.label, cat == c, LColors.Selected) { cat = c }
        }
    }
}

/** Temp, Tint, Saturation, Hue (rainbow, optional fine steps), then the 2-D Color swatch. */
@Composable
private fun ColorCategory(
    mask: Mask,
    actions: EditorActions,
    slider: @Composable (AdjRow, ClosedFloatingPointRange<Float>, Int) -> Unit,
) {
    var fine by remember { mutableStateOf(false) }
    var fineCenter by remember { mutableFloatStateOf(0f) }
    for (r in COLOR_ROWS) slider(r, r.range, r.decimals)
    val hueRange = if (fine) (fineCenter - 30f).coerceAtLeast(0f)..(fineCenter + 30f).coerceAtMost(360f) else 0f..360f
    slider(HUE_ROW, hueRange, if (fine) 1 else 0)
    Row(
        Modifier.fillMaxWidth().hideWhileDragging().padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("Use fine adjustment", style = LType.Label, modifier = Modifier.weight(1f))
        Switch(
            checked = fine,
            onCheckedChange = { fine = it; fineCenter = mask.adj.colorHue },
            colors = SwitchDefaults.colors(checkedTrackColor = LColors.Accent, checkedThumbColor = Color.White),
        )
    }
    ColorSwatch(mask, actions)
}

private const val SWATCH = "mask-color"

/** 2-D hue (x) × saturation (y, top = full) swatch with a ring picker. */
@Composable
private fun ColorSwatch(mask: Mask, actions: EditorActions) {
    val adj = mask.adj
    val feedback = LocalSliderFeedback.current
    val cur by rememberUpdatedState(mask)
    fun label(h: Float, s: Float) = "${h.roundToInt()}°  ${s.roundToInt()}"
    Column(Modifier.fillMaxWidth().hideWhileDragging(SWATCH).padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            BasicText("Color", style = LType.Label, modifier = Modifier.weight(1f))
            BasicText(label(adj.colorHue, adj.colorSat), style = LType.Value)
        }
        Spacer(Modifier.height(8.dp))
        val hue = remember { Brush.horizontalGradient((0..6).map { Color.hsv((it * 60f) % 360f, 1f, 1f) }) }
        val desat = remember { Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF8A8A8A))) }
        fun setAt(o: Offset, w: Float, h: Float, live: Boolean) {
            val hh = (o.x / w).coerceIn(0f, 1f) * 360f
            val ss = (1f - o.y / h).coerceIn(0f, 1f) * 100f
            actions.update { s -> s.mapMask(cur.id) { it.copy(adj = it.adj.copy(colorHue = hh, colorSat = ss)) } }
            if (live) feedback.value = DragFocus(SWATCH, "Color  " + label(hh, ss))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            actions.apply { s -> s.mapMask(cur.id) { it.copy(adj = it.adj.copy(colorHue = 0f, colorSat = 0f)) } }
                        },
                        onTap = { o -> setAt(o, size.width.toFloat(), size.height.toFloat(), false); actions.commit() },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> setAt(o, size.width.toFloat(), size.height.toFloat(), true) },
                        onDragEnd = { feedback.value = null; actions.commit() },
                        onDragCancel = { feedback.value = null; actions.commit() },
                    ) { ch, _ ->
                        ch.consume()
                        setAt(ch.position, size.width.toFloat(), size.height.toFloat(), true)
                    }
                },
        ) {
            drawRect(hue)
            drawRect(desat)
            val p = Offset(adj.colorHue / 360f * size.width, (1f - adj.colorSat / 100f) * size.height)
            drawCircle(Color.Black.copy(alpha = 0.5f), 10.dp.toPx(), p, style = Stroke(4.dp.toPx()))
            drawCircle(Color.White, 10.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
    }
}

private class AdjRow(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val get: (LocalAdj) -> Float,
    val set: (LocalAdj, Float) -> LocalAdj,
    val default: Float = 0f,
    val decimals: Int = 0,
    val suffix: String = "",
    val track: Brush? = null,
)

private val P = -100f..100f
private val HueTrack = Brush.horizontalGradient(
    listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it % 360f, 0.75f, 0.9f) },
)

private val LIGHT_ROWS = listOf(
    AdjRow("Exposure", -4f..4f, { it.exposure }, { a, v -> a.copy(exposure = v) }, decimals = 2),
    AdjRow("Contrast", P, { it.contrast }, { a, v -> a.copy(contrast = v) }),
    AdjRow("Highlights", P, { it.highlights }, { a, v -> a.copy(highlights = v) }),
    AdjRow("Shadows", P, { it.shadows }, { a, v -> a.copy(shadows = v) }),
    AdjRow("Whites", P, { it.whites }, { a, v -> a.copy(whites = v) }),
    AdjRow("Blacks", P, { it.blacks }, { a, v -> a.copy(blacks = v) }),
)
private val COLOR_ROWS = listOf(
    AdjRow("Temp", P, { it.temp }, { a, v -> a.copy(temp = v) }, track = Tracks.Temp),
    AdjRow("Tint", P, { it.tint }, { a, v -> a.copy(tint = v) }, track = Tracks.Tint),
    AdjRow("Saturation", P, { it.saturation }, { a, v -> a.copy(saturation = v) }),
)
private val HUE_ROW = AdjRow("Hue", 0f..360f, { it.colorHue }, { a, v -> a.copy(colorHue = v) }, suffix = "°", track = HueTrack)
private val EFFECTS_ROWS = listOf(
    AdjRow("Texture", P, { it.texture }, { a, v -> a.copy(texture = v) }),
    AdjRow("Clarity", P, { it.clarity }, { a, v -> a.copy(clarity = v) }),
    AdjRow("Dehaze", P, { it.dehaze }, { a, v -> a.copy(dehaze = v) }),
    AdjRow("Amount", 0f..200f, { it.amount }, { a, v -> a.copy(amount = v) }, default = 100f),
)
private val DETAIL_ROWS = listOf(
    AdjRow("Sharpness", P, { it.sharpness }, { a, v -> a.copy(sharpness = v) }),
    AdjRow("Noise", 0f..100f, { it.noise }, { a, v -> a.copy(noise = v) }),
)

private fun rowsFor(c: MaskCat) = when (c) {
    MaskCat.LIGHT -> LIGHT_ROWS
    MaskCat.COLOR -> COLOR_ROWS
    MaskCat.GRADING -> emptyList()
    MaskCat.EFFECTS -> EFFECTS_ROWS
    MaskCat.DETAIL -> DETAIL_ROWS
}
