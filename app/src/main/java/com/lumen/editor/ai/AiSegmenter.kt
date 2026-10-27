package com.lumen.editor.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.lumen.editor.model.AiKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Low-resolution soft mask, 0..255 per pixel, row 0 = top. */
class AiMask(val data: ByteArray, val width: Int, val height: Int)

class MissingModelException(name: String) :
    Exception("AI model '$name' is missing from assets/models. Run the downloadModels Gradle task.")

/**
 * On-device segmentation. Models are loaded per request and closed right after, so the
 * ~20 MB of interpreter memory is only held while a mask is being computed. Results are
 * cached per image (a few hundred KB) until [clear].
 */
class AiSegmenter(private val context: Context) {
    private val lock = Mutex()
    private val cache = HashMap<AiKind, AiMask>()
    // raw per-model outputs, so e.g. Subject and Background share one inference
    private var deeplabFg: FloatArray? = null
    private var selfie: Array<FloatArray>? = null
    private var dims = 0 to 0

    fun clear() {
        cache.clear(); deeplabFg = null; selfie = null
    }

    fun cached(kind: AiKind) = cache[kind]

    suspend fun compute(kind: AiKind, input: Bitmap): AiMask = lock.withLock {
        cache[kind]?.let { return it }
        withContext(Dispatchers.Default) {
            dims = input.width to input.height
            val n = input.width * input.height
            val mask: FloatArray = when (kind) {
                AiKind.SUBJECT -> subject(input)
                AiKind.BACKGROUND -> subject(input).let { fg -> FloatArray(n) { 1f - fg[it] } }
                AiKind.SKY -> SkyDetector.detect(input, runCatching { subject(input) }.getOrNull())
                AiKind.PEOPLE -> people(input).let { c -> FloatArray(n) { 1f - c[0][it] } }
                AiKind.HAIR -> people(input)[1]
                AiKind.BODY_SKIN -> people(input)[2]
                AiKind.FACE_SKIN -> people(input)[3]
                AiKind.CLOTHES -> people(input)[4]
            }
            val bytes = ByteArray(n) { (mask[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }
            AiMask(bytes, input.width, input.height).also { cache[kind] = it }
        }
    }

    private fun subject(input: Bitmap): FloatArray {
        deeplabFg?.let { return it }
        val masks = segment("deeplab_v3.tflite", input)
        val bg = masks[0]
        val fg = FloatArray(bg.size) { 1f - bg[it] }
        deeplabFg = fg
        return fg
    }

    private fun people(input: Bitmap): Array<FloatArray> {
        selfie?.let { return it }
        return segment("selfie_multiclass_256x256.tflite", input).also { selfie = it }
    }

    private fun segment(model: String, input: Bitmap): Array<FloatArray> {
        val path = "models/$model"
        runCatching { context.assets.openFd(path).close() }.onFailure { throw MissingModelException(model) }
        val segmenter = create(path, Delegate.GPU) ?: create(path, Delegate.CPU) ?: error("AI masks are not supported on this device (no ARM build of the segmentation engine)")
        try {
            val result = segmenter.segment(BitmapImageBuilder(input).build())
            val conf = result.confidenceMasks().orElseThrow { IllegalStateException("no confidence masks") }
            return Array(conf.size) { i ->
                val img = conf[i]
                val buf = ByteBufferExtractor.extract(img).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val arr = FloatArray(buf.remaining()); buf.get(arr)
                img.close()
                if (img.width != input.width || img.height != input.height) resize(arr, img.width, img.height, input.width, input.height) else arr
            }
        } finally {
            segmenter.close()
        }
    }

    private fun create(path: String, delegate: Delegate): ImageSegmenter? = try {
        val base = BaseOptions.builder().setModelAssetPath(path).setDelegate(delegate).build()
        val opts = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build()
        ImageSegmenter.createFromOptions(context, opts)
    } catch (t: Throwable) {
        Log.w("AiSegmenter", "delegate $delegate failed: ${t.message}")
        null
    }

    private fun resize(a: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val sy = ((y + 0.5f) * h / nh - 0.5f).coerceIn(0f, h - 1f)
            val y0 = sy.toInt(); val y1 = min(h - 1, y0 + 1); val fy = sy - y0
            for (x in 0 until nw) {
                val sx = ((x + 0.5f) * w / nw - 0.5f).coerceIn(0f, w - 1f)
                val x0 = sx.toInt(); val x1 = min(w - 1, x0 + 1); val fx = sx - x0
                val top = a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx
                val bot = a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx
                out[y * nw + x] = top * (1 - fy) + bot * fy
            }
        }
        return out
    }
}

/**
 * Sky heuristic: score pixels by brightness / blueness / smoothness, flood fill from the top
 * edge through similar sky-like pixels, remove the detected subject, then soften.
 */
object SkyDetector {
    fun detect(bmp: Bitmap, subject: FloatArray?): FloatArray {
        val w = bmp.width; val h = bmp.height; val n = w * h
        val px = IntArray(n); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val l = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            r[i] = (c shr 16 and 255) / 255f; g[i] = (c shr 8 and 255) / 255f; b[i] = (c and 255) / 255f
            l[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
        }
        val score = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val gx = abs(l[min(w - 1, x + 1) + y * w] - l[max(0, x - 1) + y * w])
            val gy = abs(l[x + min(h - 1, y + 1) * w] - l[x + max(0, y - 1) * w])
            val smooth = 1f - ((gx + gy) * 6f).coerceIn(0f, 1f)
            val blue = ((b[i] - r[i]) * 2.5f + 0.35f).coerceIn(0f, 1f)
            val bright = ((l[i] - 0.25f) * 2f).coerceIn(0f, 1f)
            val overcast = if (l[i] > 0.6f && abs(r[i] - b[i]) < 0.12f) 0.8f else 0f
            val pos = 1f - y / h.toFloat() * 0.6f
            score[i] = (max(blue * 0.6f + bright * 0.4f, overcast) * (0.4f + 0.6f * smooth)) * pos
        }
        val sky = FloatArray(n)
        val seen = BooleanArray(n)
        val q = ArrayDeque<Int>()
        for (x in 0 until w) if (score[x] > 0.35f) { q.add(x); seen[x] = true }
        while (q.isNotEmpty()) {
            val i = q.poll()!!
            sky[i] = 1f
            val x = i % w; val y = i / w
            fun visit(j: Int) {
                if (seen[j]) return
                val diff = abs(l[j] - l[i]) + abs(b[j] - b[i]) + abs(r[j] - r[i])
                if (score[j] > 0.28f && diff < 0.09f) { seen[j] = true; q.add(j) }
            }
            if (x > 0) visit(i - 1); if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w); if (y < h - 1) visit(i + w)
        }
        includeClouds(sky, l, r, b, w, h)
        if (subject != null) for (i in 0 until n) sky[i] *= (1f - subject[i])
        return boxBlur(boxBlur(sky, w, h, 2), w, h, 1)
    }

    /**
     * Clouds break the colour-similarity flood fill. Any non-sky region that never reaches the bottom edge, lies
     * mostly in the upper part of the frame and is bright and low-saturation is a cloud (or haze) inside the sky.
     */
    private fun includeClouds(sky: FloatArray, l: FloatArray, r: FloatArray, b: FloatArray, w: Int, h: Int) {
        val n = w * h
        if (sky.count { it > 0f } < n / 50) return  // no real sky found, nothing to fill
        val label = IntArray(n) { -1 }
        val q = ArrayDeque<Int>()
        val members = ArrayList<Int>()
        var id = 0
        for (start in 0 until n) {
            if (sky[start] > 0f || label[start] >= 0) continue
            members.clear()
            label[start] = id; q.add(start)
            var touchesBottom = false
            var sumL = 0f; var sumSat = 0f; var sumY = 0f
            while (q.isNotEmpty()) {
                val i = q.poll()!!
                members += i
                val x = i % w; val y = i / w
                if (y == h - 1) touchesBottom = true
                sumL += l[i]; sumSat += kotlin.math.abs(r[i] - b[i]); sumY += y
                fun visit(j: Int) { if (sky[j] == 0f && label[j] < 0) { label[j] = id; q.add(j) } }
                if (x > 0) visit(i - 1); if (x < w - 1) visit(i + 1)
                if (y > 0) visit(i - w); if (y < h - 1) visit(i + w)
            }
            val cnt = members.size.toFloat()
            val cloudy = !touchesBottom && sumL / cnt > 0.55f && sumSat / cnt < 0.18f && sumY / cnt < h * 0.6f
            if (cloudy) for (i in members) sky[i] = 1f
            id++
        }
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(a.size); val out = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var c = 0
            for (k in -r..r) { val xx = x + k; if (xx in 0 until w) { s += a[y * w + xx]; c++ } }
            tmp[y * w + x] = s / c
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var c = 0
            for (k in -r..r) { val yy = y + k; if (yy in 0 until h) { s += tmp[yy * w + x]; c++ } }
            out[y * w + x] = s / c
        }
        return out
    }
}
