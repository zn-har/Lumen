package com.lumen.editor.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lumen.editor.model.CurveChannel
import com.lumen.editor.model.CurvePoint
import com.lumen.editor.model.Curves
import com.lumen.editor.model.EditState
import com.lumen.editor.render.ViewTransform
import com.lumen.editor.ui.DragFocus
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.LocalSliderFeedback
import com.lumen.editor.ui.hideWhileDragging
import com.lumen.editor.ui.theme.LColors
import androidx.compose.material3.Icon
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MIN_GAP = 0.01f
/** How far (fraction of the curve box) a point must leave the box vertically to be deleted. */
private const val DELETE_MARGIN = 0.12f

internal val CurveChannel.color
    get() = when (this) {
        CurveChannel.MASTER -> Color.White
        CurveChannel.RED -> Color(0xFFE5484D)
        CurveChannel.GREEN -> Color(0xFF5BC236)
        CurveChannel.BLUE -> Color(0xFF3E64D8)
    }

/**
 * Curve box on screen: a square fitted to the photo width (crop space 0..1 mapped by [view], clamped to the view),
 * bottom-aligned with the photo, with a small margin.
 */
private fun curveRect(view: ViewTransform): Rect {
    val m = view.cropToScreen
    val xs = floatArrayOf(m.mapX(0f, 0f), m.mapX(1f, 0f), m.mapX(0f, 1f), m.mapX(1f, 1f))
    val ys = floatArrayOf(m.mapY(0f, 0f), m.mapY(1f, 0f), m.mapY(0f, 1f), m.mapY(1f, 1f))
    val l = max(xs.min(), 0f); val r = min(xs.max(), view.viewW)
    val t = max(ys.min(), 0f); val b = min(ys.max(), view.viewH)
    val margin = (r - l) * 0.06f
    val side = min(r - l, b - t) - 2 * margin
    val cx = (l + r) / 2
    return Rect(cx - side / 2, b - margin - side, cx + side / 2, b - margin)
}

/**
 * Tone curve drawn over the photo, in the same screen-pixel space as [view]. Tap to add a point,
 * drag to move (dragging on empty space adds a point), drag a point out of the top/bottom or
 * double-tap it to delete. Only single-pointer gestures inside the box are consumed.
 */
@Composable
fun CurveOverlay(
    state: EditState,
    view: ViewTransform,
    actions: EditorActions,
    channel: CurveChannel,
    modifier: Modifier = Modifier,
) {
    val points = state.curve.channel(channel)
    val box = curveRect(view)
    val pts by rememberUpdatedState(points)
    val rect by rememberUpdatedState(box)
    val ch by rememberUpdatedState(channel)
    val acts by rememberUpdatedState(actions)
    val feedback = LocalSliderFeedback.current
    fun change(p: List<CurvePoint>) = acts.update { it.copy(curve = it.curve.with(ch, p)) }
    fun focus(p: CurvePoint?) {
        feedback.value = if (p == null) null
        else DragFocus("curve", "Input: ${(p.x * 255).roundToInt()}  Output: ${(p.y * 255).roundToInt()}")
    }

    Canvas(
        modifier.fillMaxSize().pointerInput(Unit) {
            val hitR = 24.dp.toPx()
            var lastTapTime = 0L
            var lastTapPos = Offset.Zero
            fun toCurve(o: Offset) = Offset((o.x - rect.left) / rect.width, 1 - (o.y - rect.top) / rect.height)
            fun toScreen(p: CurvePoint) = Offset(rect.left + p.x * rect.width, rect.top + (1 - p.y) * rect.height)
            fun hit(o: Offset) = pts.indices
                .map { it to (toScreen(pts[it]) - o).getDistance() }
                .filter { it.second <= hitR }.minByOrNull { it.second }?.first

            awaitEachGesture {
                val down = awaitFirstDown()
                if (!rect.inflate(hitR).contains(down.position)) return@awaitEachGesture
                val start = pts
                var idx = -1
                var endpoint = false
                var base = emptyList<CurvePoint>() // points without the dragged one
                var pos = down.position
                var dragging = false
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.count { it.pressed } > 1) { // second finger: hand over to zoom/pan
                        if (dragging) { focus(null); change(start); acts.commit() }
                        return@awaitEachGesture
                    }
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!c.pressed) {
                        if (dragging) { focus(null); acts.commit(); c.consume(); return@awaitEachGesture }
                        // tap
                        c.consume()
                        val i = hit(c.position)
                        val now = c.uptimeMillis
                        val isDouble = now - lastTapTime < viewConfiguration.doubleTapTimeoutMillis &&
                            (c.position - lastTapPos).getDistance() < hitR
                        lastTapTime = now; lastTapPos = c.position
                        if (i != null) {
                            if (isDouble && i != 0 && i != pts.lastIndex) { change(pts - pts[i]); acts.commit() }
                        } else if (rect.contains(c.position)) {
                            val p = toCurve(c.position)
                            insert(pts, p.x, p.y)?.let { change(it.first); acts.commit() }
                        }
                        return@awaitEachGesture
                    }
                    if (!dragging) {
                        if ((c.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
                        val hitIdx = hit(down.position)
                        val list = if (hitIdx != null) { idx = hitIdx; start } else {
                            val p = toCurve(down.position)
                            insert(start, p.x, p.y)?.also { idx = it.second }?.first
                        } ?: return@awaitEachGesture
                        endpoint = idx == 0 || idx == list.lastIndex
                        base = list - list[idx]
                        pos = toScreen(list[idx])
                        dragging = true
                    }
                    c.consume()
                    pos += c.position - c.previousPosition
                    val raw = toCurve(pos)
                    if (!endpoint && (raw.y > 1 + DELETE_MARGIN || raw.y < -DELETE_MARGIN)) { focus(null); change(base); continue }
                    val lo = if (idx == 0) 0f else base[idx - 1].x + MIN_GAP
                    val hi = if (idx == base.size) 1f else base[idx].x - MIN_GAP
                    val p = CurvePoint(raw.x.coerceIn(lo, max(lo, hi)), raw.y.coerceIn(0f, 1f))
                    focus(p)
                    change(base.toMutableList().apply { add(idx, p) })
                }
            }
        },
    ) {
        val grid = Color.White.copy(alpha = 0.18f)
        for (k in 0..4) {
            val x = box.left + box.width * k / 4f
            val y = box.top + box.height * k / 4f
            drawLine(grid, Offset(x, box.top), Offset(x, box.bottom), 1f)
            drawLine(grid, Offset(box.left, y), Offset(box.right, y), 1f)
        }
        val lut = Curves.sample(points, FloatArray(128))
        val path = Path()
        lut.forEachIndexed { i, y ->
            val px = box.left + i / (lut.size - 1f) * box.width
            val py = box.top + (1 - y) * box.height
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val line = if (channel == CurveChannel.MASTER) Color.White else channel.color
        drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(4.dp.toPx()))
        drawPath(path, line, style = Stroke(2.dp.toPx()))
        val r = 6.dp.toPx()
        points.forEach { p ->
            val c = Offset(box.left + p.x * box.width, box.top + (1 - p.y) * box.height)
            drawCircle(Color.Black.copy(alpha = 0.5f), r + 1.5f, c)
            drawCircle(line, r, c)
        }
    }
}

/** Channel selector rings (white, red, green, blue; the selected ring gets a filled inner dot) + parametric stub. */
@Composable
internal fun CurveChannelRow(selected: CurveChannel, onSelect: (CurveChannel) -> Unit) {
    Row(
        Modifier.hideWhileDragging().fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CurveChannel.entries.forEach { ch ->
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { onSelect(ch) }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(30.dp)) {
                    val stroke = 2.5.dp.toPx()
                    drawCircle(ch.color, size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                    if (ch == selected) drawCircle(ch.color, size.minDimension / 4)
                }
            }
        }
        // parametric curve: not implemented yet, shown for layout parity
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(PanelIcons.Parametric, null, tint = LColors.TextFaint, modifier = Modifier.size(28.dp))
        }
    }
}

/** Inserts a point keeping the list sorted; null if it would be closer than [MIN_GAP] to another. */
private fun insert(pts: List<CurvePoint>, x: Float, y: Float): Pair<List<CurvePoint>, Int>? {
    val cx = x.coerceIn(0f, 1f)
    if (pts.any { abs(it.x - cx) < MIN_GAP }) return null
    val i = pts.indexOfFirst { it.x > cx }.let { if (it < 0) pts.size else it }
    return pts.toMutableList().apply { add(i, CurvePoint(cx, y.coerceIn(0f, 1f))) } to i
}
