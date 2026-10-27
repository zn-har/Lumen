package com.lumen.editor.render

import com.lumen.editor.model.CropState
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 2D affine: x' = a*x + c*y + tx, y' = b*x + d*y + ty. */
data class Mat2D(
    val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f,
    val tx: Float = 0f, val ty: Float = 0f,
) {
    /** this ∘ o (apply o first). */
    operator fun times(o: Mat2D) = Mat2D(
        a * o.a + c * o.b, b * o.a + d * o.b,
        a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.tx + c * o.ty + tx, b * o.tx + d * o.ty + ty,
    )

    fun inverse(): Mat2D {
        val det = a * d - b * c
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        return Mat2D(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty))
    }

    fun mapX(x: Float, y: Float) = a * x + c * y + tx
    fun mapY(x: Float, y: Float) = b * x + d * y + ty

    /** Column-major mat3 for glUniformMatrix3fv. */
    fun toGl(out: FloatArray = FloatArray(9)): FloatArray {
        out[0] = a; out[1] = b; out[2] = 0f
        out[3] = c; out[4] = d; out[5] = 0f
        out[6] = tx; out[7] = ty; out[8] = 1f
        return out
    }

    companion object {
        val IDENTITY = Mat2D()
        fun scale(sx: Float, sy: Float) = Mat2D(a = sx, d = sy)
        fun translate(x: Float, y: Float) = Mat2D(tx = x, ty = y)
        fun rotate(rad: Float): Mat2D {
            val cs = cos(rad); val sn = sin(rad)
            return Mat2D(cs, sn, -sn, cs)
        }
    }
}

object CropMath {
    /**
     * crop space (0..1 over the output frame) -> global image uv. The crop rect (left/top/right/bottom) is in source
     * uv; the output frame is that rect rotated by `angle` + 90°·quarterTurns about its centre, optionally mirrored.
     * imgW/imgH may be in any pixel unit (only the aspect matters).
     */
    fun cropToGlobal(crop: CropState, imgW: Float, imgH: Float): Mat2D {
        val (ow, oh) = cropPixelSize(crop, imgW, imgH)
        val cx = (crop.left + crop.right) * 0.5f
        val cy = (crop.top + crop.bottom) * 0.5f
        // output px -> source px, so a clockwise quarter turn of the picture is a -90° rotation here
        val rad = Math.toRadians((crop.angle - 90f * crop.quarterTurns).toDouble()).toFloat()
        val flip = Mat2D.scale(if (crop.flipH) -1f else 1f, if (crop.flipV) -1f else 1f)
        // cs -> centered output px -> mirror -> rotate into source px -> uv
        return Mat2D.translate(cx, cy) * Mat2D.scale(1f / imgW, 1f / imgH) * Mat2D.rotate(rad) * flip *
            Mat2D.scale(ow, oh) * Mat2D.translate(-0.5f, -0.5f)
    }

    /** Size of the output frame in pixels (source rect size, swapped for odd quarter turns). */
    fun cropPixelSize(crop: CropState, imgW: Float, imgH: Float): Pair<Float, Float> {
        val w = (crop.right - crop.left) * imgW
        val h = (crop.bottom - crop.top) * imgH
        return if (crop.quarterTurns % 2 == 1) h to w else w to h
    }

    /** Size of the source crop rect in pixels, ignoring quarter turns (what the crop handles edit). */
    fun sourceRectSize(crop: CropState, imgW: Float, imgH: Float) =
        (crop.right - crop.left) * imgW to (crop.bottom - crop.top) * imgH
}

/**
 * Everything needed to map between screen pixels, crop space and global image uv for the
 * current viewport, zoom and pan. Built on the UI thread, consumed by the renderer.
 */
class ViewTransform(
    val viewW: Float, val viewH: Float,
    val screenToCrop: Mat2D,
    val cropToGlobal: Mat2D,
) {
    val screenToGlobal = cropToGlobal * screenToCrop
    val globalToScreen = screenToGlobal.inverse()
    val cropToScreen = screenToCrop.inverse()

    companion object {
        /**
         * cropMode = true shows the whole (rotated) image around an axis-aligned crop frame.
         * zoom/pan apply in screen pixels around the fitted view.
         */
        fun compute(
            viewW: Float, viewH: Float, imgW: Float, imgH: Float, crop: CropState,
            cropMode: Boolean, zoom: Float, panX: Float, panY: Float,
            insetTop: Float = 0f, insetBottom: Float = 0f,
        ): ViewTransform {
            val c2g = CropMath.cropToGlobal(crop, imgW, imgH)
            val (cw, ch) = CropMath.cropPixelSize(crop, imgW, imgH)
            val availH = max(1f, viewH - insetTop - insetBottom)
            val centerY = insetTop + availH / 2f
            // bbox of the content to fit, in crop pixels centered at the crop center
            var minX = -cw / 2; var maxX = cw / 2; var minY = -ch / 2; var maxY = ch / 2
            if (cropMode) {
                val g2c = c2g.inverse()
                for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                    val px = (g2c.mapX(u, v) - 0.5f) * cw
                    val py = (g2c.mapY(u, v) - 0.5f) * ch
                    minX = min(minX, px); maxX = max(maxX, px); minY = min(minY, py); maxY = max(maxY, py)
                }
            }
            val margin = if (cropMode) 0.86f else 1f
            val fit = min(viewW / (maxX - minX), availH / (maxY - minY)) * margin
            val s = fit * zoom
            val bx = (minX + maxX) / 2; val by = (minY + maxY) / 2
            // screen px -> crop px (centered) -> crop space
            val screenToCropPx = Mat2D.translate(bx, by) * Mat2D.scale(1f / s, 1f / s) *
                Mat2D.translate(-(viewW / 2 + panX), -(centerY + panY))
            val cropPxToCrop = Mat2D.translate(0.5f, 0.5f) * Mat2D.scale(1f / cw, 1f / ch)
            return ViewTransform(viewW, viewH, cropPxToCrop * screenToCropPx, c2g)
        }
    }
}
