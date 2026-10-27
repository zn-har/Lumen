package com.lumen.editor.ui.masking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.lumen.editor.ui.LIcons
import com.lumen.editor.ui.hideWhileDragging
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lumen.editor.model.BrushStroke
import com.lumen.editor.model.EditState
import com.lumen.editor.model.MaskComponent
import com.lumen.editor.render.ViewTransform
import com.lumen.editor.ui.EditorActions
import com.lumen.editor.ui.MaskSelection
import com.lumen.editor.ui.theme.LColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Transparent layer over the image (same pixel space as [view]) with the on-image handles of the
 * selected mask component, brush painting and color-range sampling.
 *
 * Only single-finger gestures that start on a handle (or any single-finger gesture for Brush /
 * Color Range) are consumed, and only once they move past touch slop; a second finger aborts the
 * gesture without consuming, so pinch / pan detectors on an ancestor keep working. For that the
 * shell's zoom / pan pointerInput must sit on an ancestor of this layer (pointer events bubble
 * child -> parent); sibling layers below would not receive the events.
 */
@Composable
fun MaskOverlay(
    state: EditState,
    selection: MaskSelection,
    view: ViewTransform,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    val mask = state.selectedMask(selection)
    val comp = state.selectedComponent(selection)
    val density = LocalDensity.current
    val dens = density.density

    val curState by rememberUpdatedState(state)
    val curSel by rememberUpdatedState(selection)
    val curView by rememberUpdatedState(view)
    val curActions by rememberUpdatedState(actions)
    var cursor by remember { mutableStateOf<Offset?>(null) }

    val interactive = mask != null && comp != null && comp !is MaskComponent.LuminanceRange && comp !is MaskComponent.Ai
    val input = if (!interactive) Modifier else Modifier.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val g = Geo(curView)
            val s = curState
            val sel = curSel
            val m = s.selectedMask(sel) ?: return@awaitEachGesture
            val c = s.selectedComponent(sel) ?: return@awaitEachGesture
            val target = hitTest(c, g, down.position, dens) ?: return@awaitEachGesture
            val ctx = Ctx(curActions, m.id, c.id, g, { cursor = it })
            var drag: Drag? = null
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.count { it.pressed } > 1) {
                    drag?.cancel(ctx)
                    break
                }
                val ch = ev.changes.firstOrNull { it.id == down.id }
                if (ch == null) { drag?.end(ctx); break }
                if (!ch.pressed) {
                    if (drag != null) { drag.end(ctx); ch.consume() }
                    else if (!ch.isConsumed && target.tap(ctx, ch.position)) ch.consume()
                    break
                }
                if (drag == null) {
                    if ((ch.position - down.position).getDistance() > slop) {
                        if (ch.isConsumed) break
                        drag = target.begin(ctx, c, down.position)
                        drag.move(ctx, ch.position)
                        ch.consume()
                    }
                } else if (ch.position != ch.previousPosition) {
                    drag.move(ctx, ch.position)
                    ch.consume()
                }
            }
            cursor = null
        }
    }

    val aiPainter = rememberVectorPainter((comp as? MaskComponent.Ai)?.kind?.icon() ?: LIcons.Subject)
    Box(modifier.fillMaxSize().hideWhileDragging().then(input)) {
        if (mask == null || comp == null) return@Box
        Canvas(Modifier.fillMaxSize()) {
            val g = Geo(view)
            when (comp) {
                is MaskComponent.Linear -> drawLinear(comp, g)
                is MaskComponent.Radial -> drawRadial(comp, g)
                is MaskComponent.Brush -> cursor?.let { p ->
                    val r = BrushSettings.size * g.pxW
                    drawCircle(Color.Black.copy(alpha = 0.5f), r, p, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, r, p, style = Stroke(1.2.dp.toPx()))
                    val ri = r * (1f - BrushSettings.feather)
                    if (ri > 2f) drawCircle(Color.White.copy(alpha = 0.6f), ri, p, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                    if (BrushSettings.erase) drawLine(Color.White, p - Offset(r * 0.3f, 0f), p + Offset(r * 0.3f, 0f), 1.5.dp.toPx())
                }
                is MaskComponent.ColorRange -> cursor?.let { p ->
                    val r = 22.dp.toPx()
                    drawCircle(Color.Black.copy(alpha = 0.5f), r, p, style = Stroke(4.dp.toPx()))
                    drawCircle(Color.White, r, p, style = Stroke(2.dp.toPx()))
                    drawLine(Color.White, p - Offset(6.dp.toPx(), 0f), p + Offset(6.dp.toPx(), 0f), 1.dp.toPx())
                    drawLine(Color.White, p - Offset(0f, 6.dp.toPx()), p + Offset(0f, 6.dp.toPx()), 1.dp.toPx())
                }
                is MaskComponent.Ai -> drawAiPin(g, aiPainter)
                else -> Unit
            }
        }
    }
}

// ------------------------------------------------------------------ geometry

/** Screen <-> global uv mapping helpers. "h-space" = uv with x scaled by image aspect (isotropic). */
private class Geo(view: ViewTransform) {
    private val m = view.globalToScreen
    private val inv = view.screenToGlobal
    fun s(u: Float, v: Float) = Offset(m.mapX(u, v), m.mapY(u, v))
    fun g(p: Offset) = Offset(inv.mapX(p.x, p.y), inv.mapY(p.x, p.y))
    /** Screen px per full image width / height. */
    val pxW = hypot(m.a, m.b)
    val pxH = hypot(m.c, m.d)
    val aspect = if (pxH > 0f) pxW / pxH else 1f
    val diag = hypot(view.viewW, view.viewH)
}

private fun radialPoint(c: MaskComponent.Radial, aspect: Float, theta: Float, k: Float = 1f): Offset {
    val a = Math.toRadians(c.angle.toDouble()).toFloat()
    val ca = cos(a); val sa = sin(a)
    val rxh = c.rx * aspect * k; val ryh = c.ry * k
    val x = rxh * cos(theta); val y = ryh * sin(theta)
    return Offset(c.cx + (ca * x - sa * y) / aspect, c.cy + (sa * x + ca * y))
}

// ------------------------------------------------------------------ drawing

private fun DrawScope.handle(p: Offset, r: Float = 7.dp.toPx(), fill: Color = Color.White) {
    drawCircle(Color.Black.copy(alpha = 0.45f), r + 1.5.dp.toPx(), p)
    drawCircle(fill, r, p)
}

private fun DrawScope.line(a: Offset, b: Offset, dashed: Boolean = false) {
    val fx = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())) else null
    drawLine(Color.Black.copy(alpha = 0.45f), a, b, 3.dp.toPx(), pathEffect = fx)
    drawLine(Color.White, a, b, 1.3.dp.toPx(), pathEffect = fx)
}

private fun DrawScope.pin(p: Offset) {
    drawCircle(Color.Black.copy(alpha = 0.45f), 11.dp.toPx(), p)
    drawCircle(LColors.Accent, 9.dp.toPx(), p)
    drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(2.dp.toPx()))
}

private fun DrawScope.drawLinear(c: MaskComponent.Linear, g: Geo) {
    val s0 = g.s(c.x0, c.y0); val s1 = g.s(c.x1, c.y1)
    val mid = (s0 + s1) / 2f
    val d = s1 - s0
    val len = d.getDistance()
    val dir = if (len > 1f) d / len else Offset(0f, 1f)
    val perp = Offset(-dir.y, dir.x) * g.diag
    line(s0 - perp, s0 + perp, dashed = true)
    line(mid - perp, mid + perp, dashed = true)
    line(s1 - perp, s1 + perp, dashed = true)
    pin(mid)
}

private fun DrawScope.drawRadial(c: MaskComponent.Radial, g: Geo) {
    fun ellipse(k: Float): Path {
        val p = Path()
        val n = 72
        for (i in 0..n) {
            val q = radialPoint(c, g.aspect, (i * 2 * PI / n).toFloat(), k).let { g.s(it.x, it.y) }
            if (i == 0) p.moveTo(q.x, q.y) else p.lineTo(q.x, q.y)
        }
        p.close()
        return p
    }
    val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
    val outer = ellipse(1f)
    drawPath(outer, Color.Black.copy(alpha = 0.45f), style = Stroke(3.dp.toPx(), pathEffect = dash))
    drawPath(outer, Color.White, style = Stroke(1.3.dp.toPx(), pathEffect = dash))
    val f = (c.feather / 100f).coerceIn(0f, 1f)
    if (f > 0.01f && f < 0.99f) {
        drawPath(ellipse(1f - f), Color.White.copy(alpha = 0.45f), style = Stroke(1.dp.toPx(), pathEffect = dash))
    }
    for (k in 0 until 4) {
        val q = radialPoint(c, g.aspect, (k * PI / 2).toFloat()).let { g.s(it.x, it.y) }
        handle(q, 6.dp.toPx())
    }
    pin(g.s(c.cx, c.cy))
}

/** AI components: a pin with the component's icon at the photo centre (no centroid is known). */
private fun DrawScope.drawAiPin(g: Geo, painter: VectorPainter) {
    val p = g.s(0.5f, 0.5f)
    val r = 15.dp.toPx()
    drawCircle(Color.Black.copy(alpha = 0.45f), r + 2.dp.toPx(), p)
    drawCircle(LColors.Accent, r, p)
    val s = 18.dp.toPx()
    translate(p.x - s / 2, p.y - s / 2) {
        with(painter) { draw(Size(s, s), colorFilter = ColorFilter.tint(Color.White)) }
    }
}

// ------------------------------------------------------------------ gestures

private class Ctx(
    val actions: EditorActions,
    val maskId: Long,
    val compId: Long,
    val g: Geo,
    val setCursor: (Offset?) -> Unit,
) {
    fun update(f: (MaskComponent) -> MaskComponent) = actions.update { s -> s.mapComponent(maskId, compId, f) }
}

private interface Drag {
    fun move(ctx: Ctx, p: Offset)
    fun end(ctx: Ctx) { ctx.actions.commit() }
    fun cancel(ctx: Ctx) = end(ctx)
}

private interface Target {
    /** Returns true if the tap was handled (and should be consumed). */
    fun tap(ctx: Ctx, p: Offset): Boolean = false
    fun begin(ctx: Ctx, c: MaskComponent, down: Offset): Drag
}

/** Generic drag: hands the uv delta from the gesture start plus the component snapshot to [f]. */
private class DeltaTarget(val f: (start: MaskComponent, startUv: Offset, uv: Offset) -> MaskComponent) : Target {
    override fun begin(ctx: Ctx, c: MaskComponent, down: Offset): Drag {
        val startUv = ctx.g.g(down)
        return object : Drag {
            override fun move(ctx: Ctx, p: Offset) {
                val uv = ctx.g.g(p)
                val nc = f(c, startUv, uv)
                ctx.update { nc }
            }
        }
    }
}

private fun hitTest(c: MaskComponent, g: Geo, p: Offset, dp: Float): Target? {
    val touch = 28f * dp
    when (c) {
        is MaskComponent.Linear -> {
            val a = g.aspect
            val s0 = g.s(c.x0, c.y0); val s1 = g.s(c.x1, c.y1)
            val mid = (s0 + s1) / 2f
            val d = s1 - s0
            val len = d.getDistance()
            val dir = if (len > 1f) d / len else Offset(0f, 1f)
            fun lineDist(o: Offset) = kotlin.math.abs((p - o).x * dir.x + (p - o).y * dir.y)
            // outer lines: slide that end along the gradient direction (changes the falloff only)
            fun slide(start: Boolean) = DeltaTarget { st, su, uv ->
                st as MaskComponent.Linear
                val dx = (st.x1 - st.x0) * a; val dy = st.y1 - st.y0
                val l = max(hypot(dx, dy), 1e-6f)
                val ux = dx / l; val uy = dy / l
                val proj = (uv.x - su.x) * a * ux + (uv.y - su.y) * uy
                if (start) st.copy(x0 = st.x0 + ux * proj / a, y0 = st.y0 + uy * proj)
                else st.copy(x1 = st.x1 + ux * proj / a, y1 = st.y1 + uy * proj)
            }
            val move = DeltaTarget { st, su, uv ->
                val dx = uv.x - su.x; val dy = uv.y - su.y
                (st as MaskComponent.Linear).copy(x0 = st.x0 + dx, y0 = st.y0 + dy, x1 = st.x1 + dx, y1 = st.y1 + dy)
            }
            // centre line away from the pin: rotate both ends around the centre
            val rotate = DeltaTarget { st, su, uv ->
                st as MaskComponent.Linear
                val mx = (st.x0 + st.x1) / 2f; val my = (st.y0 + st.y1) / 2f
                val da = atan2(uv.y - my, (uv.x - mx) * a) - atan2(su.y - my, (su.x - mx) * a)
                val cs = cos(da); val sn = sin(da)
                fun rx(x: Float, y: Float) = mx + (cs * (x - mx) * a - sn * (y - my)) / a
                fun ry(x: Float, y: Float) = my + (sn * (x - mx) * a + cs * (y - my))
                st.copy(x0 = rx(st.x0, st.y0), y0 = ry(st.x0, st.y0), x1 = rx(st.x1, st.y1), y1 = ry(st.x1, st.y1))
            }
            if ((p - mid).getDistance() < touch) return move
            val l0 = lineDist(s0); val l1 = lineDist(s1); val lm = lineDist(mid)
            val best = minOf(l0, l1, lm)
            if (best > 20f * dp) return null
            return when (best) { lm -> rotate; l0 -> slide(true); else -> slide(false) }
        }
        is MaskComponent.Radial -> {
            val a = g.aspect
            val ctr = g.s(c.cx, c.cy)
            for (k in 0 until 4) {
                val q = radialPoint(c, a, (k * PI / 2).toFloat()).let { g.s(it.x, it.y) }
                if ((p - q).getDistance() < touch) {
                    val alongX = k % 2 == 0
                    return DeltaTarget { st, _, uv ->
                        st as MaskComponent.Radial
                        val ang = Math.toRadians(st.angle.toDouble()).toFloat()
                        val dx = (uv.x - st.cx) * a; val dy = uv.y - st.cy
                        val lx = cos(ang) * dx + sin(ang) * dy
                        val ly = -sin(ang) * dx + cos(ang) * dy
                        if (alongX) st.copy(rx = max(kotlin.math.abs(lx) / a, 0.005f))
                        else st.copy(ry = max(kotlin.math.abs(ly), 0.005f))
                    }
                }
            }
            val move = DeltaTarget { st, s0, uv -> (st as MaskComponent.Radial).copy(cx = st.cx + uv.x - s0.x, cy = st.cy + uv.y - s0.y) }
            if ((p - ctr).getDistance() < touch) return move
            val uv = g.g(p)
            val ang = Math.toRadians(c.angle.toDouble()).toFloat()
            val dx = (uv.x - c.cx) * a; val dy = uv.y - c.cy
            val lx = (cos(ang) * dx + sin(ang) * dy) / max(c.rx * a, 1e-5f)
            val ly = (-sin(ang) * dx + cos(ang) * dy) / max(c.ry, 1e-5f)
            // on the edge: rotate
            val edge = radialPoint(c, a, atan2(ly, lx)).let { g.s(it.x, it.y) }
            if ((p - edge).getDistance() < 20f * dp) return DeltaTarget { st, su, uv2 ->
                st as MaskComponent.Radial
                val da = atan2(uv2.y - st.cy, (uv2.x - st.cx) * a) - atan2(su.y - st.cy, (su.x - st.cx) * a)
                var deg = st.angle + Math.toDegrees(da.toDouble()).toFloat()
                while (deg > 180f) deg -= 360f
                while (deg < -180f) deg += 360f
                st.copy(angle = deg)
            }
            return if (lx * lx + ly * ly < 1f) move else null
        }
        is MaskComponent.Brush -> return BrushTarget
        is MaskComponent.ColorRange -> return ColorTarget
        else -> return null
    }
}

private object BrushTarget : Target {
    private fun stroke(pts: FloatArray, n: Int) = BrushStroke(
        points = pts.copyOf(n),
        size = BrushSettings.size, feather = BrushSettings.feather, flow = BrushSettings.flow, erase = BrushSettings.erase,
    )

    private fun append(ctx: Ctx, idx: Int, s: BrushStroke) = ctx.update {
        if (it is MaskComponent.Brush) it.copy(strokes = it.strokes.take(idx) + s) else it
    }

    override fun tap(ctx: Ctx, p: Offset): Boolean {
        val uv = ctx.g.g(p)
        ctx.actions.apply { s ->
            s.mapComponent(ctx.maskId, ctx.compId) {
                if (it is MaskComponent.Brush) it.copy(strokes = it.strokes + stroke(floatArrayOf(uv.x, uv.y, 1f), 3)) else it
            }
        }
        return true
    }

    override fun begin(ctx: Ctx, c: MaskComponent, down: Offset): Drag {
        val idx = (c as? MaskComponent.Brush)?.strokes?.size ?: 0
        var pts = FloatArray(96)
        var n = 0
        var last = Offset.Unspecified
        val minStep = 2f
        fun add(p: Offset, pressure: Float) {
            if (last.isSpecified && (p - last).getDistance() < minStep) return
            last = p
            if (n + 3 > pts.size) pts = pts.copyOf(pts.size * 2)
            val uv = ctx.g.g(p)
            pts[n] = uv.x; pts[n + 1] = uv.y; pts[n + 2] = pressure
            n += 3
        }
        add(down, 1f)
        ctx.setCursor(down)
        return object : Drag {
            override fun move(ctx: Ctx, p: Offset) {
                add(p, 1f)
                ctx.setCursor(p)
                append(ctx, idx, stroke(pts, n))
            }

            override fun cancel(ctx: Ctx) {
                // a second finger right after starting = the user meant to pinch: drop the stub
                if (n < 8 * 3) ctx.actions.update { s ->
                    s.mapComponent(ctx.maskId, ctx.compId) { if (it is MaskComponent.Brush) it.copy(strokes = it.strokes.take(idx)) else it }
                }
                ctx.setCursor(null)
                ctx.actions.commit()
            }

            override fun end(ctx: Ctx) {
                ctx.setCursor(null)
                ctx.actions.commit()
            }
        }
    }
}

private object ColorTarget : Target {
    private fun sample(ctx: Ctx, p: Offset) {
        val uv = ctx.g.g(p)
        if (uv.x !in 0f..1f || uv.y !in 0f..1f) return
        val actions = ctx.actions
        actions.sampleColor(uv.x, uv.y) { rgb ->
            if (rgb != null) actions.apply { s ->
                s.mapComponent(ctx.maskId, ctx.compId) {
                    if (it is MaskComponent.ColorRange && it.samples.size < 5) it.copy(samples = it.samples + rgb.copyOf(3)) else it
                }
            }
        }
    }

    override fun tap(ctx: Ctx, p: Offset): Boolean { sample(ctx, p); return true }

    override fun begin(ctx: Ctx, c: MaskComponent, down: Offset): Drag {
        ctx.setCursor(down)
        return object : Drag {
            var last = down
            override fun move(ctx: Ctx, p: Offset) { last = p; ctx.setCursor(p) }
            override fun end(ctx: Ctx) { ctx.setCursor(null); sample(ctx, last) }
            override fun cancel(ctx: Ctx) { ctx.setCursor(null) }
        }
    }
}
