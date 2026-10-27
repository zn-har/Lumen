package com.lumen.editor.ui.crop

import androidx.compose.ui.geometry.Offset
import com.lumen.editor.model.CropState
import com.lumen.editor.render.Mat2D
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Crop geometry in pixels, matching CropMath.cropToGlobal: the source rect (cx, cy, w, h) in unrotated
 * image pixels; the OUTPUT frame is that rect seen through flip -> rotate(angle + 90°·quarterTurns).
 * Handles, aspect ratios and sizes all live in output-frame space; a rotated rect's extremes are its
 * axis-aligned bounding box, so "inside the image" reduces to a bbox test.
 */

/** Crop in image pixels: source rect + the full transform that turns it into the output frame. */
internal data class CropFrame(
    val cx: Float, val cy: Float, val w: Float, val h: Float,
    val angle: Float, val quarterTurns: Int, val flipH: Boolean, val flipV: Boolean,
) {
    val odd get() = quarterTurns % 2 == 1
    val totalAngle get() = angle + 90f * quarterTurns

    /** Output frame size (swapped for odd quarter turns). */
    val outW get() = if (odd) h else w
    val outH get() = if (odd) w else h

    /** Same source rect centre, new output size. */
    fun withOutSize(ow: Float, oh: Float) = if (odd) copy(w = oh, h = ow) else copy(w = ow, h = oh)

    /** Moves the centre by (lx, ly) given in output-frame pixels. */
    fun shiftedOut(lx: Float, ly: Float): CropFrame {
        val fx = if (flipH) -lx else lx
        val fy = if (flipV) -ly else ly
        val rad = Math.toRadians(totalAngle.toDouble())
        val cs = cos(rad).toFloat(); val sn = sin(rad).toFloat()
        return copy(cx = cx + cs * fx - sn * fy, cy = cy + sn * fx + cs * fy)
    }
}

internal object CropGeo {
    fun toPx(c: CropState, imgW: Float, imgH: Float) = CropFrame(
        (c.left + c.right) * 0.5f * imgW, (c.top + c.bottom) * 0.5f * imgH,
        (c.right - c.left) * imgW, (c.bottom - c.top) * imgH,
        c.angle, c.quarterTurns.mod(4), c.flipH, c.flipV,
    )

    /** Writes the rect and angle of [f] into [base]; turns, flips and aspect come from [base]. */
    fun toCrop(f: CropFrame, base: CropState, imgW: Float, imgH: Float) = base.copy(
        left = (f.cx - f.w / 2) / imgW, right = (f.cx + f.w / 2) / imgW,
        top = (f.cy - f.h / 2) / imgH, bottom = (f.cy + f.h / 2) / imgH,
        angle = f.angle,
    )

    /** Half extents of the rotated rect's axis-aligned bounding box. */
    private fun extents(f: CropFrame): Pair<Float, Float> {
        val rad = Math.toRadians(f.totalAngle.toDouble())
        val cs = abs(cos(rad)).toFloat(); val sn = abs(sin(rad)).toFloat()
        return (cs * f.w + sn * f.h) / 2 to (sn * f.w + cs * f.h) / 2
    }

    fun fits(f: CropFrame, imgW: Float, imgH: Float, eps: Float = 0.5f): Boolean {
        val (ex, ey) = extents(f)
        return f.cx - ex >= -eps && f.cx + ex <= imgW + eps && f.cy - ey >= -eps && f.cy + ey <= imgH + eps
    }

    /** Largest scale k <= 1 so the rect, scaled about its centre, stays inside the image. */
    fun fitScale(f: CropFrame, imgW: Float, imgH: Float): Float {
        val (ex, ey) = extents(f)
        val kx = min(f.cx, imgW - f.cx) / ex
        val ky = min(f.cy, imgH - f.cy) / ey
        return min(1f, min(kx, ky)).coerceAtLeast(0.01f)
    }

    /** Scales [f] about its centre so it fits, pulling the centre inside the image first. */
    fun shrinkToFit(f: CropFrame, imgW: Float, imgH: Float): CropFrame {
        val c = f.copy(cx = f.cx.coerceIn(0f, imgW), cy = f.cy.coerceIn(0f, imgH))
        val k = fitScale(c, imgW, imgH)
        return c.copy(w = c.w * k, h = c.h * k)
    }

    fun minSize(imgW: Float, imgH: Float) = max(16f, 0.03f * min(imgW, imgH))

    /** Output aspect (w/h) of the uncropped image for the current quarter turns. */
    fun originalAspect(c: CropState, imgW: Float, imgH: Float) =
        if (c.quarterTurns.mod(2) == 1) imgH / imgW else imgW / imgH

    fun outputAspect(c: CropState, imgW: Float, imgH: Float): Float {
        val f = toPx(c, imgW, imgH)
        return f.outW / f.outH
    }

    /** Sets the fine angle about the crop centre, shrinking the crop (aspect kept) until it fits. */
    fun straighten(base: CropState, angle: Float, imgW: Float, imgH: Float): CropState {
        val f = toPx(base, imgW, imgH).copy(angle = angle)
        val k = fitScale(f, imgW, imgH)
        return toCrop(f.copy(w = f.w * k, h = f.h * k), base, imgW, imgH)
    }

    /** Sets [aspect] (output w/h, 0 = free) and fits the largest centred frame of it inside the crop. */
    fun withAspect(c: CropState, aspect: Float, imgW: Float, imgH: Float): CropState {
        if (aspect <= 0f) return c.copy(aspect = 0f)
        val f = toPx(c, imgW, imgH)
        val ow = f.outW; val oh = f.outH
        val (w, h) = if (ow / oh > aspect) oh * aspect to oh else ow to ow / aspect
        return toCrop(f.withOutSize(w, h), c, imgW, imgH).copy(aspect = aspect)
    }

    /** Swaps portrait/landscape: output w and h trade places (aspect inverted), shrunk to fit. */
    fun swapOrientation(c: CropState, imgW: Float, imgH: Float): CropState {
        val f = toPx(c, imgW, imgH)
        val g = shrinkToFit(f.withOutSize(f.outH, f.outW), imgW, imgH)
        return toCrop(g, c, imgW, imgH).copy(aspect = if (c.aspect > 0f) 1f / c.aspect else 0f)
    }

    /** Turns the output a quarter clockwise; the frame turns with the photo. */
    fun rotate90(c: CropState) = c.copy(
        quarterTurns = (c.quarterTurns + 1).mod(4),
        aspect = if (c.aspect > 0f) 1f / c.aspect else 0f,
    )

    /** Locks the current output aspect, or unlocks. */
    fun toggleLock(c: CropState, imgW: Float, imgH: Float) =
        c.copy(aspect = if (c.aspect > 0f) 0f else outputAspect(c, imgW, imgH))
}

// ---------------------------------------------------------------- ratios

/** A named ratio, orientation-free: [a] <= [b]; "8.5 × 11" etc. */
internal class Ratio(val a: Float, val b: Float) {
    private fun fmt(v: Float) = if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else v.toString()

    /** "3 × 4" for portrait, "4 × 3" for landscape. */
    fun label(portrait: Boolean) = if (portrait || a == b) "${fmt(a)} × ${fmt(b)}" else "${fmt(b)} × ${fmt(a)}"
    fun aspect(portrait: Boolean) = if (portrait) a / b else b / a
    fun matches(aspect: Float) = abs(aspect - a / b) < 2e-3f || abs(aspect - b / a) < 2e-3f
}

internal val RATIOS = listOf(
    Ratio(1f, 1f), Ratio(1f, 2f), Ratio(2f, 3f), Ratio(3f, 4f), Ratio(4f, 5f),
    Ratio(5f, 7f), Ratio(8.5f, 11f), Ratio(9f, 16f), Ratio(10f, 16f),
)

/** "W × H" reduced to a small whole-number ratio (within 1%), else one decimal ("1.85 × 1"). */
internal fun ratioLabel(aspect: Float): String {
    if (aspect <= 0f) return "Custom"
    for (n in 1..20) {
        val m = (aspect * n).roundToInt()
        if (m in 1..32 && abs(m / n.toFloat() - aspect) / aspect < 0.01f) return "$m × $n"
    }
    return if (aspect >= 1f) "%.2f × 1".format(aspect) else "1 × %.2f".format(1f / aspect)
}

// ---------------------------------------------------------------- drag

/** Frame-local rectangle in output pixels, origin at the gesture-start frame centre. */
private data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun shift(dx: Float, dy: Float) = Box(l + dx, t + dy, r + dx, b + dy)
    fun lerp(o: Box, k: Float) = Box(l + (o.l - l) * k, t + (o.t - t) * k, r + (o.r - r) * k, b + (o.b - b) * k)
}

/** Which output-frame edges a gesture moves; none = move the whole crop. */
internal data class Zone(val l: Boolean, val t: Boolean, val r: Boolean, val b: Boolean) {
    val isMove get() = !l && !t && !r && !b
}

private fun frameOf(start: CropFrame, box: Box): CropFrame =
    start.withOutSize(box.r - box.l, box.b - box.t).shiftedOut((box.l + box.r) / 2, (box.t + box.b) / 2)

/** Moves from valid [from] toward [to], stopping at the last position that satisfies [ok]. */
private inline fun clampTo(from: Box, to: Box, ok: (Box) -> Boolean): Box {
    if (ok(to)) return to
    if (!ok(from)) return from
    var lo = 0f; var hi = 1f
    repeat(18) {
        val mid = (lo + hi) / 2
        if (ok(from.lerp(to, mid))) lo = mid else hi = mid
    }
    return from.lerp(to, lo)
}

/** New crop for a drag of [d] screen px; [toCrop] maps screen -> crop space (0..1 over the output frame). */
internal fun dragCrop(c: CropState, z: Zone, d: Offset, toCrop: Mat2D, imgW: Float, imgH: Float): CropState {
    val f = CropGeo.toPx(c, imgW, imgH)
    val ow = f.outW; val oh = f.outH
    val du = (toCrop.a * d.x + toCrop.c * d.y) * ow
    val dv = (toCrop.b * d.x + toCrop.d * d.y) * oh
    val start = Box(-ow / 2, -oh / 2, ow / 2, oh / 2)
    val ok = { b: Box -> CropGeo.fits(frameOf(f, b), imgW, imgH) }
    val minS = CropGeo.minSize(imgW, imgH)

    val result = if (z.isMove) {
        // per axis so the crop slides along an image edge instead of sticking to it
        val cur = clampTo(start, start.shift(du, 0f), ok)
        clampTo(cur, cur.shift(0f, dv), ok)
    } else {
        var l = start.l; var t = start.t; var r = start.r; var b = start.b
        if (z.l) l = min(start.l + du, start.r - minS)
        if (z.r) r = max(start.r + du, start.l + minS)
        if (z.t) t = min(start.t + dv, start.b - minS)
        if (z.b) b = max(start.b + dv, start.t + minS)
        val a = c.aspect
        if (a > 0f) {
            val xs = z.l || z.r; val ys = z.t || z.b
            var w = r - l; var h = b - t
            if (xs && ys) { if (w / h > a) h = w / a else w = h * a }
            else if (xs) h = w / a
            else w = h * a
            if (h < minS) { h = minS; w = h * a }
            if (w < minS) { w = minS; h = w / a }
            if (xs) { if (z.l) l = r - w else r = l + w } else { l = -w / 2; r = w / 2 }
            if (ys) { if (z.t) t = b - h else b = t + h } else { t = -h / 2; b = h / 2 }
            clampTo(start, Box(l, t, r, b), ok)
        } else {
            val cur = clampTo(start, start.copy(l = l, r = r), ok)
            clampTo(cur, cur.copy(t = t, b = b), ok)
        }
    }
    return CropGeo.toCrop(frameOf(f, result), c, imgW, imgH)
}

/** Hit test against the frame: [toScreen] maps crop space (0..1) to screen px. */
internal fun hitZone(p: Offset, toScreen: Mat2D, toCrop: Mat2D, touch: Float): Zone? {
    val fx = toCrop.mapX(p.x, p.y); val fy = toCrop.mapY(p.x, p.y)
    val sw = hypot(toScreen.a, toScreen.b); val sh = hypot(toScreen.c, toScreen.d)
    if (sw <= 0f || sh <= 0f) return null
    val dl = abs(fx * sw); val dr = abs((1 - fx) * sw)
    val dt = abs(fy * sh); val db = abs((1 - fy) * sh)
    val inX = fx > -touch / sw && fx < 1 + touch / sw
    val inY = fy > -touch / sh && fy < 1 + touch / sh
    if (!inX || !inY) return null
    val nearL = dl < touch && dl <= dr; val nearR = dr < touch && dr < dl
    val nearT = dt < touch && dt <= db; val nearB = db < touch && db < dt
    if (nearL || nearR || nearT || nearB) return Zone(nearL, nearT, nearR, nearB)
    return if (fx in 0f..1f && fy in 0f..1f) Zone(false, false, false, false) else null
}
