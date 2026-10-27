package com.lumen.editor.io

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.lumen.editor.model.EditState
import com.lumen.editor.render.CropMath
import com.lumen.editor.render.EditorSurface
import com.lumen.editor.render.Mat2D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ExportOptions(val maxEdge: Int = 0, val png: Boolean = false, val quality: Int = 95)

/**
 * Full-resolution export without ever decoding the whole image: the output is rendered in
 * 1024px tiles, each fed by a region decode of exactly the source pixels it needs. Peak
 * memory ≈ output bitmap + one source tile + one 4 MB tile buffer.
 */
object Exporter {
    private const val TILE = 1024
    private const val MARGIN = 12

    suspend fun export(
        ctx: Context, uri: Uri, state: EditState, surface: EditorSurface,
        opts: ExportOptions, progress: (Float) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val info = ImageIo.info(ctx, uri)
        val fw = info.width.toFloat(); val fh = info.height.toFloat()
        val (cw, ch) = CropMath.cropPixelSize(state.crop, fw, fh)
        var scale = if (opts.maxEdge > 0) min(1f, opts.maxEdge / max(cw, ch)) else 1f

        ImageIo.RegionSource(ctx, uri, info, 4096).use { source ->
            scale = min(scale, source.resolution.coerceAtLeast(0.05f))
            var out: Bitmap? = null
            while (out == null) {
                try {
                    out = Bitmap.createBitmap(max(1, (cw * scale).roundToInt()), max(1, (ch * scale).roundToInt()), Bitmap.Config.ARGB_8888)
                } catch (oom: OutOfMemoryError) {
                    scale *= 0.75f
                    if (scale < 0.1f) throw oom
                }
            }
            val bmp = out!!
            val outW = bmp.width; val outH = bmp.height
            // decode at a power-of-two subsample when the output is much smaller than the source
            var sample = 1
            while (sample * 2 <= 1f / (scale / source.resolution.coerceAtMost(1f))) sample *= 2

            surface.onGl { it.prepareExport(state) }
            val c2g = CropMath.cropToGlobal(state.crop, fw, fh)
            val k = -state.optics.distortion / 100f * 0.12f
            val aspect = fw / fh
            val ints = IntArray(TILE * TILE)
            val tilesX = ceil(outW / TILE.toFloat()).toInt(); val tilesY = ceil(outH / TILE.toFloat()).toInt()
            var done = 0
            try {
                for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
                    val ox = tx * TILE; val oy = ty * TILE
                    val tw = min(TILE, outW - ox); val th = min(TILE, outH - oy)
                    val tileToCrop = Mat2D.translate(ox / outW.toFloat(), oy / outH.toFloat()) *
                        Mat2D.scale(tw / outW.toFloat(), th / outH.toFloat())
                    val tileToGlobal = c2g * tileToCrop
                    val bb = sourceBounds(tileToGlobal, k, aspect)
                    val x0 = (floor(bb[0] * fw).toInt() - MARGIN).coerceIn(0, info.width - 1)
                    val y0 = (floor(bb[1] * fh).toInt() - MARGIN).coerceIn(0, info.height - 1)
                    val x1 = (ceil(bb[2] * fw).toInt() + MARGIN).coerceIn(x0 + 1, info.width)
                    val y1 = (ceil(bb[3] * fh).toInt() + MARGIN).coerceIn(y0 + 1, info.height)
                    val tile = source.decode(x0, y0, x1, y1, sample)
                    val srcRect = floatArrayOf(x0 / fw, y0 / fh, (x1 - x0) / fw, (y1 - y0) / fh)
                    val texelPerFull = tile.width / (x1 - x0).toFloat()
                    val radius = max(0.6f, state.detail.sharpRadius * texelPerFull)
                    surface.onGl { it.renderTile(state, tile, srcRect, tileToGlobal, tileToCrop, tw, th, radius, ints) }
                    tile.recycle()
                    bmp.setPixels(ints, 0, tw, ox, oy, tw, th)
                    done++
                    progress(done / (tilesX * tilesY).toFloat() * 0.9f)
                }
            } finally {
                surface.onGl { it.endExport() }
            }
            val name = "Lumen_" + System.currentTimeMillis()
            val saved = ImageIo.saveToGallery(ctx, bmp, opts.png, opts.quality, name)
            bmp.recycle()
            progress(1f)
            saved
        }
    }

    /** Bounding box (global uv) of the source pixels sampled by a tile, including lens distortion. */
    private fun sourceBounds(tileToGlobal: Mat2D, k: Float, aspect: Float): FloatArray {
        var minU = Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        val steps = 8
        for (i in 0..steps) for (edge in 0 until 4) {
            val t = i / steps.toFloat()
            val (x, y) = when (edge) { 0 -> t to 0f; 1 -> t to 1f; 2 -> 0f to t; else -> 1f to t }
            var u = tileToGlobal.mapX(x, y); var v = tileToGlobal.mapY(x, y)
            if (k != 0f) {
                val dx = (u - 0.5f) * aspect; val dy = v - 0.5f
                val r2 = (dx * dx + dy * dy) / (0.25f * (aspect * aspect + 1f))
                val f = (1f + k * r2) / (1f + max(k, 0f))
                u = 0.5f + (u - 0.5f) * f; v = 0.5f + (v - 0.5f) * f
            }
            minU = min(minU, u); maxU = max(maxU, u); minV = min(minV, v); maxV = max(maxV, v)
        }
        return floatArrayOf(minU.coerceIn(0f, 1f), minV.coerceIn(0f, 1f), maxU.coerceIn(0f, 1f), maxV.coerceIn(0f, 1f))
    }
}
