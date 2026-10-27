package com.lumen.editor.ui.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumen.editor.model.CropState
import com.lumen.editor.model.EditState
import com.lumen.editor.render.CropMath
import com.lumen.editor.render.ViewTransform
import com.lumen.editor.ui.DragFocus
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LIcons
import com.lumen.editor.ui.LSlider
import com.lumen.editor.ui.LocalSliderFeedback
import com.lumen.editor.ui.ModeBar
import com.lumen.editor.ui.PillButton
import com.lumen.editor.ui.Strip
import com.lumen.editor.ui.StripItem
import com.lumen.editor.ui.hideWhileDragging
import com.lumen.editor.ui.lineIcon
import com.lumen.editor.ui.theme.LColors
import com.lumen.editor.ui.theme.LType
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private object CropIcons {
    val Orientation = lineIcon("crop_orient", "M4 8H14V20H4Z", "M10 4H20V10", "M17 13A4 4 0 0 0 13 4.5", "M14.5 3L13 4.5L14.5 6")
    val Aspect = lineIcon("crop_aspect", "M4 9V4H9", "M15 4H20V9", "M20 15V20H15", "M9 20H4V15", "M8 8H16V16H8Z")
    val Locked = lineIcon("crop_lock", "M6 11H18V20H6Z", "M8.5 11V8A3.5 3.5 0 0 1 15.5 8V11", "M12 14.5V16.5")
    val Unlocked = lineIcon("crop_unlock", "M6 11H18V20H6Z", "M8.5 11V8A3.5 3.5 0 0 1 15.2 6.6", "M12 14.5V16.5")
    val Straighten = lineIcon("crop_level", "M3 12H21", "M5 17L19 7", "M12 4V6M12 18V20")
    val Rotate = lineIcon("crop_rotate", "M5 8H15V20H5Z", "M12 3A7 7 0 0 1 19 10", "M17 8.5L19 10.5L21 8.5")
    val FlipH = lineIcon("crop_fliph", "M12 3V21", "M9 6L3 18H9Z", "M15 6L21 18H15Z")
    val FlipV = lineIcon("crop_flipv", "M3 12H21", "M6 9L18 3V9Z", "M6 15L18 21V15Z")
    val Pencil = lineIcon("crop_pencil", "M4 20L8.5 19L19 8.5L15.5 5L5 15.5Z", "M13.5 7L17 10.5")
}

/** Which entry of the aspect list the crop currently corresponds to. */
private sealed class AspectKey {
    object Original : AspectKey()
    object Custom : AspectKey()
    class Fixed(val ratio: Ratio) : AspectKey()
}

private fun currentAspect(c: CropState, imgW: Float, imgH: Float): AspectKey {
    val orig = CropGeo.originalAspect(c, imgW, imgH)
    if (c.aspect <= 0f) {
        return if (abs(CropGeo.outputAspect(c, imgW, imgH) - orig) / orig < 2e-3f) AspectKey.Original else AspectKey.Custom
    }
    if (abs(c.aspect - orig) < 2e-3f) return AspectKey.Original
    return RATIOS.firstOrNull { it.matches(c.aspect) }?.let { AspectKey.Fixed(it) } ?: AspectKey.Custom
}

private fun aspectLabel(c: CropState, imgW: Float, imgH: Float): String {
    val portrait = CropGeo.outputAspect(c, imgW, imgH) < 1f
    return when (val k = currentAspect(c, imgW, imgH)) {
        AspectKey.Original -> "Original"
        is AspectKey.Fixed -> k.ratio.label(portrait)
        AspectKey.Custom -> if (c.aspect > 0f) ratioLabel(c.aspect) else "Custom"
    }
}

private fun angleText(a: Float): String {
    val r = (a * 10f).roundToInt() / 10f
    return if (r == r.roundToInt().toFloat()) "${r.roundToInt()}°" else "%.1f°".format(r)
}

// ---------------------------------------------------------------- overlay

/**
 * Full-screen crop overlay over the loupe: orientation swap button top-left (the shell owns the
 * top-right), the crop frame with handles and dimmed outside, and the rotation dial under the frame.
 * Corner/edge drags resize, a drag inside moves the crop; touches outside the frame are left
 * unconsumed for the shell's pan/zoom.
 */
@Composable
fun CropOverlay(
    state: EditState,
    view: ViewTransform,
    imageWidth: Int,
    imageHeight: Int,
    actions: EditorActions,
    onGestureActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val st by rememberUpdatedState(state)
    val vt by rememberUpdatedState(view)
    val act by rememberUpdatedState(actions)
    val onActive by rememberUpdatedState(onGestureActive)
    var dragging by remember { mutableStateOf(false) }
    var rotating by remember { mutableStateOf(false) }
    val lastAngle = remember { floatArrayOf(state.crop.angle) }
    LaunchedEffect(state.crop.angle) {
        if (state.crop.angle != lastAngle[0]) {
            lastAngle[0] = state.crop.angle
            rotating = true
            delay(600)
            rotating = false
        }
    }
    if (imageWidth <= 0 || imageHeight <= 0) return
    val imgW = imageWidth.toFloat(); val imgH = imageHeight.toFloat()

    // frame corners on screen: crop space (0..1 over the output frame) -> global -> screen
    val m = view.globalToScreen * CropMath.cropToGlobal(state.crop, imgW, imgH)
    fun p(x: Float, y: Float) = Offset(m.mapX(x, y), m.mapY(x, y))
    val p0 = p(0f, 0f); val p1 = p(1f, 0f); val p2 = p(1f, 1f); val p3 = p(0f, 1f)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(imageWidth, imageHeight) {
                    val touch = 28.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val startCrop = st.crop
                        val toScreen = vt.globalToScreen * CropMath.cropToGlobal(startCrop, imgW, imgH)
                        val toCrop = toScreen.inverse()
                        val zone = hitZone(down.position, toScreen, toCrop, touch) ?: return@awaitEachGesture
                        down.consume()
                        var total = Offset.Zero
                        var started = false
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } > 1) {
                                    // multi-touch belongs to the shell: undo our part and let go
                                    if (started) act.update { it.copy(crop = startCrop) }
                                    break
                                }
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                total += ch.position - ch.previousPosition
                                ch.consume()
                                if (!started && total.getDistance() > viewConfiguration.touchSlop) {
                                    started = true
                                    dragging = true
                                    onActive(true)
                                }
                                if (started) {
                                    val c = dragCrop(startCrop, zone, total, toCrop, imgW, imgH)
                                    act.update { it.copy(crop = c) }
                                }
                            }
                        } finally {
                            if (started) {
                                act.commit()
                                dragging = false
                                onActive(false)
                            }
                        }
                    }
                },
        ) {
            val quad = Path().apply { moveTo(p0.x, p0.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close() }
            val dim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addPath(quad)
            }
            drawPath(dim, Color.Black.copy(alpha = if (dragging) 0.5f else 0.7f))

            // thirds grid while dragging, finer grid while the angle changes
            if (dragging || rotating) {
                val n = if (rotating) 9 else 3
                val gridColor = Color.White.copy(alpha = if (rotating) 0.3f else 0.55f)
                val gw = if (rotating) 0.7.dp.toPx() else 1.dp.toPx()
                for (i in 1 until n) {
                    val k = i / n.toFloat()
                    drawLine(gridColor, p(k, 0f), p(k, 1f), gw)
                    drawLine(gridColor, p(0f, k), p(1f, k), gw)
                }
            }
            drawPath(quad, Color.White.copy(alpha = 0.85f), style = Stroke(1.dp.toPx()))

            // corner L-handles and edge bars just outside the frame line (screen axes: the frame is axis-aligned)
            val hw = 4.dp.toPx()
            val l = min(p0.x, p2.x); val r = max(p0.x, p2.x)
            val t = min(p0.y, p2.y); val b = max(p0.y, p2.y)
            val cornerLen = min(20.dp.toPx(), min(r - l, b - t) / 3)
            val o = hw / 2
            for ((cx, sx) in listOf(l - o to 1f, r + o to -1f)) for ((cy, sy) in listOf(t - o to 1f, b + o to -1f)) {
                drawLine(Color.White, Offset(cx - sx * o, cy), Offset(cx + sx * cornerLen, cy), hw)
                drawLine(Color.White, Offset(cx, cy - sy * o), Offset(cx, cy + sy * cornerLen), hw)
            }
            val half = min(12.dp.toPx(), cornerLen / 2)
            val mx = (l + r) / 2; val my = (t + b) / 2
            drawLine(Color.White, Offset(mx - half, t - o), Offset(mx + half, t - o), hw)
            drawLine(Color.White, Offset(mx - half, b + o), Offset(mx + half, b + o), hw)
            drawLine(Color.White, Offset(l - o, my - half), Offset(l - o, my + half), hw)
            drawLine(Color.White, Offset(r + o, my - half), Offset(r + o, my + half), hw)
        }

        // orientation swap (portrait <-> landscape)
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 8.dp)
                .hideWhileDragging()
                .size(44.dp)
                .clip(CircleShape)
                .clickable { act.apply { s -> s.copy(crop = CropGeo.swapOrientation(s.crop, imgW, imgH)) } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(CropIcons.Orientation, "Swap orientation", tint = Color.White, modifier = Modifier.size(24.dp))
        }

        // rotation dial just under the frame, kept on screen
        val dialH = 64.dp
        val dialTop = with(density) {
            val bottom = max(max(p0.y, p1.y), max(p2.y, p3.y)) + 6.dp.toPx()
            bottom.coerceAtMost(constraints.maxHeight - dialH.toPx()).coerceAtLeast(0f).roundToInt()
        }
        RotationDial(
            state.crop.angle, imgW, imgH, actions,
            Modifier.offset { IntOffset(0, dialTop) }.fillMaxWidth().height(dialH).hideWhileDragging("dial"),
        )
    }
}

/**
 * Dotted arc rotation dial. Horizontal drag turns the crop (auto-shrinking it to stay inside the
 * image, measured from the crop at drag start); double tap levels it.
 */
@Composable
private fun RotationDial(angle: Float, imgW: Float, imgH: Float, actions: EditorActions, modifier: Modifier) {
    val ang by rememberUpdatedState(angle)
    val act by rememberUpdatedState(actions)
    val feedback = LocalSliderFeedback.current
    Box(
        modifier
            .pointerInput(imgW, imgH) {
                detectTapGestures(onDoubleTap = {
                    act.apply { s -> s.copy(crop = CropGeo.straighten(s.crop, 0f, imgW, imgH)) }
                })
            }
            .pointerInput(imgW, imgH) {
                val pxPerDeg = 7.dp.toPx()
                var base: CropState? = null
                var acc = 0f
                fun end() {
                    base = null
                    feedback.value = null
                    act.commit()
                }
                detectHorizontalDragGestures(
                    onDragStart = { acc = ang; base = null },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                ) { ch, dx ->
                    ch.consume()
                    acc = (acc - dx / pxPerDeg).coerceIn(-45f, 45f)
                    val a = (acc * 10f).roundToInt() / 10f
                    feedback.value = DragFocus("dial", "Angle " + angleText(a))
                    act.update { s ->
                        val b = base ?: s.crop.also { base = it }
                        s.copy(crop = CropGeo.straighten(b, a, imgW, imgH))
                    }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 7.dp.toPx()
            val r = size.width * 0.9f
            val bottom = 40.dp.toPx()
            val cx = size.width / 2; val cy = bottom - r
            val maxPhi = asin(((size.width / 2 - 24.dp.toPx()) / r).coerceIn(0f, 1f))
            val dot = 1.3.dp.toPx()
            for (d in -45..45) {
                val phi = (d - angle) * step / r
                if (abs(phi) > maxPhi) continue
                val dir = Offset(sin(phi), cos(phi))
                val pt = Offset(cx, cy) + dir * r
                val fade = 1f - abs(phi) / maxPhi * 0.6f
                if (d % 5 == 0) {
                    drawLine(Color.White.copy(alpha = fade), pt - dir * 4.dp.toPx(), pt + dir * 4.dp.toPx(), 1.5.dp.toPx(), StrokeCap.Round)
                } else {
                    drawCircle(Color.White.copy(alpha = 0.8f * fade), dot, pt)
                }
            }
            drawLine(Color.White, Offset(cx, bottom + 8.dp.toPx()), Offset(cx, bottom + 20.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        }
        BasicText(angleText(angle), style = LType.Label.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 6.dp))
    }
}

// ---------------------------------------------------------------- bottom

/**
 * Crop and geometry bottom area, bottom-up: mode bar, tool row (Aspect, Lock, Straighten, Rotate,
 * Flip H, Flip V), "Auto geometry" pill + pencil (toggles the Geometry sliders). Aspect opens the
 * "Aspect ratio" list in place of the tools.
 */
@Composable
fun CropBottom(
    state: EditState,
    imageWidth: Int,
    imageHeight: Int,
    actions: EditorActions,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imgW = imageWidth.coerceAtLeast(1).toFloat()
    val imgH = imageHeight.coerceAtLeast(1).toFloat()
    var aspectSheet by remember { mutableStateOf(false) }
    var geometry by remember { mutableStateOf(false) }
    val crop = state.crop

    Column(modifier.fillMaxWidth().background(LColors.Controls)) {
        if (aspectSheet) {
            AspectSheet(crop, imgW, imgH, onClose = { aspectSheet = false }) { aspect ->
                actions.apply { s -> s.copy(crop = CropGeo.withAspect(s.crop, aspect, imgW, imgH)) }
                aspectSheet = false
            }
        } else {
            if (geometry) GeometrySliders(state, imgW, imgH, actions)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).hideWhileDragging(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(null, "Auto geometry") {
                    // stand-in for real auto upright: level the photo and clear lens distortion
                    actions.apply { s ->
                        s.copy(crop = CropGeo.straighten(s.crop, 0f, imgW, imgH), optics = s.optics.copy(distortion = 0f))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (geometry) LColors.Selected else Color(0xFF111111))
                        .clickable { geometry = !geometry },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CropIcons.Pencil, "Geometry", tint = if (geometry) LColors.Accent else LColors.Text, modifier = Modifier.size(20.dp))
                }
            }
            Strip(Modifier.hideWhileDragging()) {
                val sel = LColors.Selected
                StripItem(CropIcons.Aspect, aspectLabel(crop, imgW, imgH), false, sel) { aspectSheet = true }
                val locked = crop.aspect > 0f
                StripItem(if (locked) CropIcons.Locked else CropIcons.Unlocked, if (locked) "Locked" else "Unlocked", false, sel) {
                    actions.apply { s -> s.copy(crop = CropGeo.toggleLock(s.crop, imgW, imgH)) }
                }
                StripItem(CropIcons.Straighten, "Straighten", false, sel) {
                    actions.apply { s -> s.copy(crop = CropGeo.straighten(s.crop, 0f, imgW, imgH)) }
                }
                StripItem(CropIcons.Rotate, "Rotate", false, sel) {
                    actions.apply { s -> s.copy(crop = CropGeo.rotate90(s.crop)) }
                }
                StripItem(CropIcons.FlipH, "Flip H", crop.flipH, sel) {
                    actions.apply { s -> s.copy(crop = s.crop.copy(flipH = !s.crop.flipH)) }
                }
                StripItem(CropIcons.FlipV, "Flip V", crop.flipV, sel) {
                    actions.apply { s -> s.copy(crop = s.crop.copy(flipV = !s.crop.flipV)) }
                }
            }
        }
        ModeBar("Crop and geometry", onCancel, onDone, Modifier.hideWhileDragging())
    }
}

/** Geometry sliders that have model fields: Rotate (fine angle) and lens Distortion. */
@Composable
private fun GeometrySliders(state: EditState, imgW: Float, imgH: Float, actions: EditorActions) {
    // crop at the start of a rotate drag, so shrinking is undone when the angle goes back
    val rotateBase = remember { arrayOfNulls<CropState>(1) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        LSlider(
            label = "Rotate",
            value = state.crop.angle,
            range = -45f..45f,
            onChange = { a ->
                val base = rotateBase[0] ?: state.crop.also { rotateBase[0] = it }
                actions.update { s -> s.copy(crop = CropGeo.straighten(base, a, imgW, imgH)) }
            },
            onCommit = { rotateBase[0] = null; actions.commit() },
            decimals = 1,
            suffix = "°",
        )
        LSlider(
            label = "Distortion",
            value = state.optics.distortion,
            range = -100f..100f,
            onChange = { v -> actions.update { s -> s.copy(optics = s.optics.copy(distortion = v)) } },
            onCommit = { actions.commit() },
        )
    }
}

/** "Aspect ratio" list: Original (W × H), Custom, then the fixed ratios in the frame's orientation. */
@Composable
private fun AspectSheet(crop: CropState, imgW: Float, imgH: Float, onClose: () -> Unit, onPick: (Float) -> Unit) {
    val cur = currentAspect(crop, imgW, imgH)
    val portrait = CropGeo.outputAspect(crop, imgW, imgH) < 1f
    val orig = CropGeo.originalAspect(crop, imgW, imgH)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(LIcons.Back, "Back", tint = LColors.Text, modifier = Modifier.size(22.dp))
            }
            BasicText("Aspect ratio", style = LType.Title.copy(fontSize = 17.sp), modifier = Modifier.padding(start = 6.dp))
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            AspectRow("Original (${ratioLabel(orig)})", orig, cur == AspectKey.Original) { onPick(orig) }
            AspectRow("Custom", 0f, cur == AspectKey.Custom) { onPick(0f) }
            RATIOS.forEach { r ->
                val selected = cur is AspectKey.Fixed && cur.ratio === r
                AspectRow(r.label(portrait), r.aspect(portrait), selected) { onPick(r.aspect(portrait)) }
            }
        }
    }
}

@Composable
private fun AspectRow(label: String, aspect: Float, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) LColors.Accent else LColors.Text
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val box = size.minDimension * 0.8f
            val (w, h) = when {
                aspect <= 0f -> box to box * 0.7f
                aspect >= 1f -> box to box / aspect
                else -> box * aspect to box
            }
            val stroke = 1.5.dp.toPx()
            val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2)
            if (aspect <= 0f) {
                // free: corner marks only
                val c = min(w, h) / 3
                for ((x, sx) in listOf(topLeft.x to 1f, topLeft.x + w to -1f)) for ((y, sy) in listOf(topLeft.y to 1f, topLeft.y + h to -1f)) {
                    drawLine(color, Offset(x, y), Offset(x + sx * c, y), stroke)
                    drawLine(color, Offset(x, y), Offset(x, y + sy * c), stroke)
                }
            } else {
                drawRect(color, topLeft, Size(w, h), style = Stroke(stroke))
            }
        }
        Spacer(Modifier.width(18.dp))
        BasicText(label, style = LType.Label.copy(color = color))
    }
}

/** Text tabs with an underline under the selected one and a hairline below the row. */
@Composable
internal fun UnderlineTabs(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { i, label ->
                Box(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText(
                            label,
                            style = LType.Title.copy(fontSize = 17.sp, color = if (i == selected) LColors.Text else LColors.TextDim),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                        )
                        Box(
                            Modifier.fillMaxWidth().height(2.dp)
                                .background(if (i == selected) LColors.Text else Color.Transparent),
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LColors.Divider))
    }
}
