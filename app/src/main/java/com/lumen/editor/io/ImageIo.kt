package com.lumen.editor.io

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ImageInfo(val width: Int, val height: Int, val rawWidth: Int, val rawHeight: Int, val rotation: Int, val mime: String?)

object ImageIo {

    fun info(ctx: Context, uri: Uri): ImageInfo {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var rw = o.outWidth; var rh = o.outHeight
        val rotation = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rw <= 0 || rh <= 0) {
            // formats BitmapFactory can't size (e.g. some HEIF): fall back to a real decode
            val b = decodeSampled(ctx, uri, 4096)
            rw = b.width; rh = b.height; b.recycle()
            return ImageInfo(rw, rh, rw, rh, 0, o.outMimeType)
        }
        val swap = rotation == 90 || rotation == 270
        return ImageInfo(if (swap) rh else rw, if (swap) rw else rh, rw, rh, rotation, o.outMimeType)
    }

    /** Decodes an oriented, software ARGB_8888 bitmap whose long edge is <= maxEdge. */
    fun decodeSampled(ctx: Context, uri: Uri, maxEdge: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(ctx.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                dec.isMutableRequired = false
                val w = info.size.width; val h = info.size.height
                val s = min(1f, maxEdge / max(w, h).toFloat())
                if (s < 1f) dec.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            }.let { ensureArgb(it) }
        }
        val inf = info(ctx, uri)
        val longRaw = max(inf.rawWidth, inf.rawHeight)
        var sample = 1
        while (longRaw / (sample * 2) >= maxEdge) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var b = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o)!! }
        val s = min(1f, maxEdge / max(b.width, b.height).toFloat())
        val m = Matrix()
        if (s < 1f) m.postScale(s, s)
        if (inf.rotation != 0) m.postRotate(inf.rotation.toFloat())
        if (!m.isIdentity) {
            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (r != b) b.recycle()
            b = r
        }
        return b
    }

    private fun ensureArgb(b: Bitmap): Bitmap {
        if (b.config == Bitmap.Config.ARGB_8888) return b
        val c = b.copy(Bitmap.Config.ARGB_8888, false)
        b.recycle()
        return c
    }

    /**
     * Streams full-resolution regions of the image in its oriented coordinate space. Falls back
     * to a single (possibly downsampled) decode when the format has no region decoder.
     */
    class RegionSource(private val ctx: Context, private val uri: Uri, val info: ImageInfo, maxFallbackEdge: Int) : AutoCloseable {
        private val decoder: BitmapRegionDecoder? = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(it) else BitmapRegionDecoder.newInstance(it, false)
            }
        }.getOrNull()
        private val fallback: Bitmap? = if (decoder == null) decodeSampled(ctx, uri, maxFallbackEdge) else null

        /** Oriented pixel rect [x0,x1)x[y0,y1) at full resolution; returns an oriented tile, decoded at 1/sample. */
        fun decode(x0: Int, y0: Int, x1: Int, y1: Int, sample: Int): Bitmap {
            fallback?.let { fb ->
                val sx = fb.width / info.width.toFloat(); val sy = fb.height / info.height.toFloat()
                val l = (x0 * sx).toInt().coerceIn(0, fb.width - 1); val t = (y0 * sy).toInt().coerceIn(0, fb.height - 1)
                val r = ceil(x1 * sx).toInt().coerceIn(l + 1, fb.width); val b = ceil(y1 * sy).toInt().coerceIn(t + 1, fb.height)
                return Bitmap.createBitmap(fb, l, t, r - l, b - t)
            }
            val d = decoder!!
            val rw = info.rawWidth; val rh = info.rawHeight
            val raw = when (info.rotation) {
                90 -> Rect(y0, rh - x1, y1, rh - x0)
                180 -> Rect(rw - x1, rh - y1, rw - x0, rh - y0)
                270 -> Rect(rw - y1, x0, rw - y0, x1)
                else -> Rect(x0, y0, x1, y1)
            }
            raw.intersect(0, 0, rw, rh)
            val o = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val b = d.decodeRegion(raw, o)
            if (info.rotation == 0) return b
            val m = Matrix().apply { postRotate(info.rotation.toFloat()) }
            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, false)
            if (r != b) b.recycle()
            return r
        }

        /** Fraction of full resolution actually available (1 unless we fell back to a sampled decode). */
        val resolution: Float get() = fallback?.let { it.width / info.width.toFloat() } ?: 1f

        override fun close() {
            decoder?.recycle()
            fallback?.recycle()
        }
    }

    fun saveToGallery(ctx: Context, bmp: Bitmap, png: Boolean, quality: Int, name: String): Uri {
        val mime = if (png) "image/png" else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name + if (png) ".png" else ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lumen")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, it)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }
}
