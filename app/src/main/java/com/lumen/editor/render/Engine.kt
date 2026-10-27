package com.lumen.editor.render

import android.graphics.Bitmap
import android.opengl.GLES30.*
import android.opengl.GLUtils
import android.util.Log
import com.lumen.editor.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class Histogram(val r: IntArray, val g: IntArray, val b: IntArray, val l: IntArray)

/**
 * GPU develop engine. Every method must run on the GL thread.
 *
 * Memory budget for a 2048x1536 preview: source 12.6 MB (sRGB8), output 12.6 MB (RGBA8),
 * local-adjust field 4 x 6.3 MB (RGBA16F at half res), blur pyramid ~ 8 MB, each mask 0.8 MB (R8 half res).
 * Nothing is kept on the Java heap: the decoded preview bitmap is recycled by the caller after upload.
 */
class Engine {
    private companion object { const val UPLOAD_UNIT = GL_TEXTURE15 }

    private lateinit var pDown: GlProgram
    private lateinit var pBlur: GlProgram
    private lateinit var pCopyR: GlProgram
    private lateinit var pCopy: GlProgram
    private lateinit var pMask: GlProgram
    private lateinit var pDab: GlProgram
    private lateinit var pAcc: GlProgram
    private lateinit var pDev: GlProgram
    private lateinit var pDisp: GlProgram

    var width = 0; private set
    var height = 0; private set
    val hasImage get() = src != null

    private var src: GlTarget? = null
    private var fine: GlTarget? = null
    private var mid: GlTarget? = null
    private var large: GlTarget? = null
    private var out: GlTarget? = null
    private var scratch: GlTarget? = null
    private var params: Array<GlTarget>? = null
    private var paramFbo = 0
    private var hasLocal = false
    private var lutTex = 0
    private var zeroTex = 0
    private var hist: GlTarget? = null
    private var dabVbo = 0

    private class MaskEntry(val target: GlTarget, var components: List<MaskComponent>, var aiGen: Int)

    private val masks = HashMap<Long, MaskEntry>()
    private val ai = HashMap<AiKind, GlTarget>()
    private var aiGen = 0
    private val strokeCache = WeakHashMap<BrushStroke, FloatBuffer>()

    private var lastCurve: ToneCurve? = null
    private val tmpMat = FloatArray(9)

    fun init() {
        Quad.init()
        pDown = GlProgram(Shaders.VS, Shaders.DOWNSAMPLE)
        pBlur = GlProgram(Shaders.VS, Shaders.BLUR)
        pCopyR = GlProgram(Shaders.VS, Shaders.COPY_R)
        pCopy = GlProgram(Shaders.VS, Shaders.COPY)
        pMask = GlProgram(Shaders.VS, Shaders.MASK_COMPONENT)
        pDab = GlProgram(Shaders.DAB_VS, Shaders.DAB_FS)
        pAcc = GlProgram(Shaders.VS, Shaders.ACCUMULATE)
        pDev = GlProgram(Shaders.VS, Shaders.DEVELOP)
        pDisp = GlProgram(Shaders.VS, Shaders.DISPLAY)

        val ext = glGetString(GL_EXTENSIONS) ?: ""
        if (!ext.contains("GL_EXT_color_buffer_half_float") && !ext.contains("GL_EXT_color_buffer_float")) {
            Log.w("LumenEngine", "half-float render targets not advertised; local adjustments may fail")
        }

        val t = IntArray(2)
        glGenTextures(2, t, 0)
        lutTex = t[0]; zeroTex = t[1]
        glBindTexture(GL_TEXTURE_2D, lutTex)
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, Curves.LUT_SIZE, 1)
        texParams(GL_LINEAR)
        lastCurve = null
        glBindTexture(GL_TEXTURE_2D, zeroTex)
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 1, 1)
        texParams(GL_NEAREST)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4))

        val b = IntArray(1)
        glGenBuffers(1, b, 0); dabVbo = b[0]
    }

    private fun texParams(filter: Int) {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    // ------------------------------------------------------------------ image

    fun setImage(bmp: Bitmap) {
        releaseImage()
        width = bmp.width; height = bmp.height
        glActiveTexture(UPLOAD_UNIT)
        val s = GlTarget(width, height, GL_SRGB8_ALPHA8)
        glBindTexture(GL_TEXTURE_2D, s.tex)
        GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bmp)
        checkGl("upload src")
        src = s
        buildPyramid()
        out = GlTarget(width, height, GL_RGBA8)
        val mw = max(1, (width + 1) / 2); val mh = max(1, (height + 1) / 2)
        scratch = GlTarget(mw, mh, GL_R8)
        val ps = Array(4) { GlTarget(mw, mh, GL_RGBA16F, withFbo = false) }
        params = ps
        val f = IntArray(1)
        glGenFramebuffers(1, f, 0); paramFbo = f[0]
        glBindFramebuffer(GL_FRAMEBUFFER, paramFbo)
        for (i in 0 until 4) glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, ps[i].tex, 0)
        glDrawBuffers(4, intArrayOf(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT3), 0)
        val st = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (st != GL_FRAMEBUFFER_COMPLETE) Log.e("LumenEngine", "param fbo incomplete $st")
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        val hw = 192; val hh = max(1, (hw * height / width.toFloat()).toInt())
        hist = GlTarget(hw, hh, GL_RGBA8)
    }

    private fun buildPyramid() {
        val s = src ?: return
        fun down(from: GlTarget, w: Int, h: Int, dark: Boolean): GlTarget {
            val t = GlTarget(max(1, w), max(1, h), GL_RGBA16F)
            t.bind(); pDown.use()
            pDown.tex("uTex", 0, from.tex)
            pDown.f2("uTexel", 1f / from.width, 1f / from.height)
            pDown.i("uDark", if (dark) 1 else 0)
            Quad.draw()
            return t
        }
        fun blur(t: GlTarget, iterations: Int): GlTarget {
            val tmp = GlTarget(t.width, t.height, GL_RGBA16F)
            val dst = GlTarget(t.width, t.height, GL_RGBA16F)
            var input = t
            pBlur.use()
            repeat(iterations) {
                tmp.bind(); pBlur.tex("uTex", 0, input.tex); pBlur.f2("uDir", 1f / t.width, 0f); Quad.draw()
                dst.bind(); pBlur.tex("uTex", 0, tmp.tex); pBlur.f2("uDir", 0f, 1f / t.height); Quad.draw()
                input = dst
            }
            tmp.release()
            return dst
        }
        val d2 = down(s, width / 2, height / 2, true)
        fine = blur(d2, 1)
        val d4 = down(d2, d2.width / 2, d2.height / 2, false)
        val d8 = down(d4, d4.width / 2, d4.height / 2, false)
        mid = blur(d8, 1)
        val d16 = down(d8, d8.width / 2, d8.height / 2, false)
        val d32 = down(d16, d16.width / 2, d16.height / 2, false)
        large = blur(d32, 2)
        d2.release(); d4.release(); d8.release(); d16.release(); d32.release()
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        checkGl("pyramid")
    }

    fun releaseImage() {
        listOfNotNull(src, fine, mid, large, out, scratch, hist, thumb).forEach { it.release() }
        thumb = null
        params?.forEach { it.release() }
        if (paramFbo != 0) glDeleteFramebuffers(1, intArrayOf(paramFbo), 0)
        grade?.forEach { it.release() }; grade = null
        if (gradeFbo != 0) glDeleteFramebuffers(1, intArrayOf(gradeFbo), 0); gradeFbo = 0
        src = null; fine = null; mid = null; large = null; out = null; scratch = null; hist = null
        params = null; paramFbo = 0
        masks.values.forEach { it.target.release() }; masks.clear()
        ai.values.forEach { it.release() }; ai.clear()
        strokeCache.clear()
        width = 0; height = 0
    }

    fun release() {
        releaseImage()
        listOf(pDown, pBlur, pCopyR, pCopy, pMask, pDab, pAcc, pDev, pDisp).forEach { it.release() }
        glDeleteTextures(2, intArrayOf(lutTex, zeroTex), 0)
        glDeleteBuffers(1, intArrayOf(dabVbo), 0)
        Quad.release()
    }

    fun gpuBytes(): Long =
        listOfNotNull(src, fine, mid, large, out, scratch, hist).sumOf { it.bytes } +
            (params?.sumOf { it.bytes } ?: 0) + masks.values.sumOf { it.target.bytes } + ai.values.sumOf { it.bytes }

    // ------------------------------------------------------------------ AI masks

    fun setAiMask(kind: AiKind, data: ByteArray, w: Int, h: Int) {
        ai.remove(kind)?.release()
        glActiveTexture(UPLOAD_UNIT)
        val t = GlTarget(w, h, GL_R8, withFbo = false)
        glBindTexture(GL_TEXTURE_2D, t.tex)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RED, GL_UNSIGNED_BYTE, ByteBuffer.wrap(data))
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
        ai[kind] = t
        aiGen++
    }

    fun hasAi(kind: AiKind) = ai.containsKey(kind)

    // ------------------------------------------------------------------ masks

    private fun updateMasks(state: EditState) {
        val live = HashSet<Long>()
        for (m in state.masks) {
            live += m.id
            val usesAi = m.components.any { it is MaskComponent.Ai }
            val e = masks[m.id]
            if (e == null) {
                val sc = scratch ?: return
                val t = GlTarget(sc.width, sc.height, GL_R8)
                masks[m.id] = MaskEntry(t, m.components, aiGen)
                rasterize(m.components, t)
            } else if (e.components != m.components || (usesAi && e.aiGen != aiGen)) {
                e.components = m.components; e.aiGen = aiGen
                rasterize(m.components, e.target)
            }
        }
        val dead = masks.keys.filter { it !in live }
        dead.forEach { masks.remove(it)?.target?.release() }
    }

    private fun rasterize(components: List<MaskComponent>, target: GlTarget) {
        val sc = scratch ?: return
        target.bind()
        glClearColor(0f, 0f, 0f, 0f); glClear(GL_COLOR_BUFFER_BIT)
        for (c in components) {
            sc.bind()
            glClearColor(0f, 0f, 0f, 0f); glClear(GL_COLOR_BUFFER_BIT)
            drawComponent(c)
            target.bind()
            glEnable(GL_BLEND)
            when (c.mode) {
                MaskMode.ADD -> { glBlendEquation(GL_MAX); glBlendFunc(GL_ONE, GL_ONE) }
                MaskMode.SUBTRACT -> { glBlendEquation(GL_FUNC_ADD); glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_COLOR) }
                MaskMode.INTERSECT -> { glBlendEquation(GL_FUNC_ADD); glBlendFunc(GL_ZERO, GL_SRC_COLOR) }
            }
            pCopyR.use()
            pCopyR.tex("uTex", 0, sc.tex)
            pCopyR.i("uInvert", if (c.invert) 1 else 0)
            Quad.draw()
            glDisable(GL_BLEND)
            glBlendEquation(GL_FUNC_ADD)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private val sampleBuf = FloatArray(24)

    private fun drawComponent(c: MaskComponent) {
        val s = src ?: return
        if (c is MaskComponent.Brush) { drawBrush(c); return }
        pMask.use()
        pMask.f("uAspect", width / height.toFloat())
        pMask.i("uInvert", 0)
        pMask.tex("uSrc", 0, s.tex)
        pMask.tex("uGuide", 1, fine!!.tex)
        pMask.tex("uAi", 2, zeroTex)
        when (c) {
            is MaskComponent.Linear -> { pMask.i("uType", 0); pMask.f4("uA", c.x0, c.y0, c.x1, c.y1) }
            is MaskComponent.Radial -> {
                pMask.i("uType", 1)
                pMask.f4("uA", c.cx, c.cy, c.rx, c.ry)
                pMask.f4("uB", Math.toRadians(c.angle.toDouble()).toFloat(), c.feather / 100f, 0f, 0f)
            }
            is MaskComponent.ColorRange -> {
                pMask.i("uType", 2)
                pMask.f4("uA", c.refine / 100f, 0f, 0f, 0f)
                val n = min(8, c.samples.size)
                for (i in 0 until n) for (k in 0 until 3) sampleBuf[i * 3 + k] = c.samples[i][k]
                pMask.f3v("uSamples", sampleBuf, 8)
                pMask.i("uSampleCount", n)
            }
            is MaskComponent.LuminanceRange -> {
                pMask.i("uType", 3); pMask.f4("uA", c.lo, c.hi, c.featherLo, c.featherHi)
            }
            is MaskComponent.Ai -> {
                val t = ai[c.kind] ?: return
                pMask.i("uType", 4)
                pMask.tex("uAi", 2, t.tex)
                pMask.f2("uAiTexel", 1f / t.width, 1f / t.height)
            }
            is MaskComponent.Brush -> Unit
        }
        Quad.draw()
    }

    private fun drawBrush(c: MaskComponent.Brush) {
        if (c.strokes.isEmpty()) return
        pDab.use()
        glEnable(GL_BLEND)
        glBlendEquation(GL_FUNC_ADD)
        glBindBuffer(GL_ARRAY_BUFFER, dabVbo)
        glEnableVertexAttribArray(0); glEnableVertexAttribArray(1); glEnableVertexAttribArray(2)
        for (s in c.strokes) {
            val buf = strokeCache.getOrPut(s) { buildStroke(s) }
            if (buf.limit() == 0) continue
            if (s.erase) glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_COLOR) else glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_COLOR)
            glBufferData(GL_ARRAY_BUFFER, buf.limit() * 4, buf, GL_STREAM_DRAW)
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 24, 0)
            glVertexAttribPointer(1, 2, GL_FLOAT, false, 24, 8)
            glVertexAttribPointer(2, 2, GL_FLOAT, false, 24, 16)
            glDrawArrays(GL_TRIANGLES, 0, buf.limit() / 6)
        }
        glDisableVertexAttribArray(1); glDisableVertexAttribArray(2)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glDisable(GL_BLEND)
    }

    /** Expands a stroke into dab quads: 6 vertices x (clipPos2, local2, flow, feather). */
    private fun buildStroke(s: BrushStroke): FloatBuffer {
        val aspect = width / height.toFloat()
        val rx = s.size; val ry = s.size * aspect
        val step = max(rx * 0.18f, 0.0004f)
        val dabs = ArrayList<Float>()
        val p = s.points
        var px = Float.NaN; var py = 0f; var carry = 0f
        fun dab(x: Float, y: Float, pr: Float) { dabs += x; dabs += y; dabs += pr }
        var i = 0
        while (i + 2 < p.size) {
            val x = p[i]; val y = p[i + 1]; val pr = p[i + 2]
            if (px.isNaN()) { dab(x, y, pr); px = x; py = y }
            else {
                val dx = x - px; val dy = (y - py) / aspect
                val len = sqrt(dx * dx + dy * dy)
                var t = step - carry
                while (t <= len) {
                    val f = t / len
                    dab(px + (x - px) * f, py + (y - py) * f, pr)
                    t += step
                }
                carry = len - (t - step)
                px = x; py = y
            }
            i += 3
        }
        val n = dabs.size / 3
        val fb = ByteBuffer.allocateDirect(n * 36 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val lx = floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f)
        val ly = floatArrayOf(-1f, -1f, 1f, 1f, -1f, 1f)
        for (k in 0 until n) {
            val cx = dabs[k * 3] * 2f - 1f; val cy = dabs[k * 3 + 1] * 2f - 1f
            val flow = s.flow * dabs[k * 3 + 2]
            for (v in 0 until 6) {
                fb.put(cx + lx[v] * rx * 2f); fb.put(cy + ly[v] * ry * 2f)
                fb.put(lx[v]); fb.put(ly[v]); fb.put(flow); fb.put(s.feather)
            }
        }
        fb.flip()
        return fb
    }

    // ------------------------------------------------------------------ local adjustment field

    private fun accumulate(state: EditState) {
        val ps = params ?: return
        val active = state.masks.filter { it.visible && masks.containsKey(it.id) && it.adj != LocalAdj() }
        hasLocal = active.isNotEmpty()
        if (!hasLocal) return
        glBindFramebuffer(GL_FRAMEBUFFER, paramFbo)
        glViewport(0, 0, ps[0].width, ps[0].height)
        val zero = floatArrayOf(0f, 0f, 0f, 0f)
        for (i in 0 until 4) glClearBufferfv(GL_COLOR, i, zero, 0)
        glEnable(GL_BLEND)
        glBlendEquation(GL_FUNC_ADD)
        glBlendFunc(GL_ONE, GL_ONE)
        pAcc.use()
        for (m in active) {
            val a = m.adj
            val k = a.amount / 100f
            val hr = Math.toRadians(a.colorHue.toDouble())
            pAcc.tex("uMask", 0, masks[m.id]!!.target.tex)
            pAcc.i("uInvert", if (m.inverted) 1 else 0)
            pAcc.f4("uP0", a.exposure * k, a.contrast / 100f * k, a.highlights / 100f * k, a.shadows / 100f * k)
            pAcc.f4("uP1", a.whites / 100f * k, a.blacks / 100f * k, a.temp / 100f * k, a.tint / 100f * k)
            pAcc.f4("uP2", a.saturation / 100f * k, a.texture / 100f * k, a.clarity / 100f * k, a.dehaze / 100f * k)
            pAcc.f4(
                "uP3",
                (a.colorSat / 100f * cos(hr)).toFloat() * k, (a.colorSat / 100f * sin(hr)).toFloat() * k,
                a.sharpness / 100f * 0.8f * k, a.noise / 100f * k,
            )
            Quad.draw()
        }
        accumulateGrading(active)
        glDisable(GL_BLEND)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private var grade: Array<GlTarget>? = null
    private var gradeFbo = 0
    private var hasLocalGrade = false

    /** Same wheel scaling as the global grading uniforms: (sat·0.22·cos h, sat·0.22·sin h, lum·0.2). */
    private fun wheelVec(w: GradeWheel, k: Float): FloatArray {
        val r = Math.toRadians(w.hue.toDouble())
        val s = w.sat / 100f * 0.22f
        return floatArrayOf((s * cos(r)).toFloat() * k, (s * sin(r)).toFloat() * k, w.lum / 100f * 0.2f * k)
    }

    /** Second additive pass into 3 more targets (allocated lazily, only when some mask uses grading). */
    private fun accumulateGrading(active: List<Mask>) {
        val graded = active.filter { it.adj.hasGrading }
        hasLocalGrade = graded.isNotEmpty()
        if (!hasLocalGrade) return
        val ps = params ?: return
        val gs = grade ?: Array(3) { GlTarget(ps[0].width, ps[0].height, GL_RGBA16F, withFbo = false) }.also { g ->
            grade = g
            val f = IntArray(1); glGenFramebuffers(1, f, 0); gradeFbo = f[0]
            glBindFramebuffer(GL_FRAMEBUFFER, gradeFbo)
            for (i in 0 until 3) glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, g[i].tex, 0)
            glDrawBuffers(3, intArrayOf(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2), 0)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, gradeFbo)
        glViewport(0, 0, gs[0].width, gs[0].height)
        val zero = floatArrayOf(0f, 0f, 0f, 0f)
        for (i in 0 until 3) glClearBufferfv(GL_COLOR, i, zero, 0)
        pAcc.use()  // same shader: outputs 0..2 land in the grading targets, output 3 is discarded
        for (m in graded) {
            val a = m.adj
            val k = a.amount / 100f
            val s = wheelVec(a.gradeShadows, k); val mm = wheelVec(a.gradeMidtones, k)
            val h = wheelVec(a.gradeHighlights, k); val g = wheelVec(a.gradeGlobal, k)
            pAcc.tex("uMask", 0, masks[m.id]!!.target.tex)
            pAcc.i("uInvert", if (m.inverted) 1 else 0)
            pAcc.f4("uP0", s[0], s[1], s[2], mm[0])
            pAcc.f4("uP1", mm[1], mm[2], h[0], h[1])
            pAcc.f4("uP2", h[2], g[0], g[1], g[2])
            pAcc.f4("uP3", 0f, 0f, 0f, 0f)
            Quad.draw()
        }
    }

    // ------------------------------------------------------------------ develop

    private val lutData = FloatArray(Curves.LUT_SIZE * 4)
    private val lutTmp = Array(4) { FloatArray(Curves.LUT_SIZE) }

    private fun updateLut(curve: ToneCurve) {
        if (curve == lastCurve) return
        lastCurve = curve
        Curves.sample(curve.red, lutTmp[0]); Curves.sample(curve.green, lutTmp[1])
        Curves.sample(curve.blue, lutTmp[2]); Curves.sample(curve.master, lutTmp[3])
        for (i in 0 until Curves.LUT_SIZE) for (c in 0 until 4) lutData[i * 4 + c] = lutTmp[c][i]
        glActiveTexture(UPLOAD_UNIT)
        glBindTexture(GL_TEXTURE_2D, lutTex)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, Curves.LUT_SIZE, 1, GL_RGBA, GL_FLOAT, floatBuffer(lutData))
    }

    private val hslC = FloatArray(8) { HSL_CENTERS[it] }
    private val hslH = FloatArray(8)
    private val hslS = FloatArray(8)
    private val hslL = FloatArray(8)

    private fun setDevelopUniforms(state: EditState, radiusScale: Float, toGlobal: Mat2D, toCrop: Mat2D) {
        val p = pDev
        val l = state.light; val c = state.color; val e = state.effects; val d = state.detail; val o = state.optics
        p.f4("uL0", l.exposure, l.contrast / 100f, l.highlights / 100f, l.shadows / 100f)
        p.f4("uL1", l.whites / 100f, l.blacks / 100f, c.temp / 100f, c.tint / 100f)
        p.f4("uL2", c.saturation / 100f, c.vibrance / 100f, e.texture / 100f, e.clarity / 100f)
        p.f4("uL3", e.dehaze / 100f, d.sharpen / 100f, radiusScale, d.sharpMasking / 100f)
        // "Enable lens corrections" adds a mild generic vignetting/barrel fix on top of the manual sliders
        val lensVig = o.lensVignette / 100f + if (o.lensCorrections) 0.12f else 0f
        val lensDist = -o.distortion / 100f * 0.12f + if (o.lensCorrections) -0.006f else 0f
        p.f4("uL4", d.noiseLum / 100f, d.noiseColor / 100f, lensDist, lensVig)
        p.i("uBw", if (c.bw) 1 else 0)
        p.f4("uVig", e.vignette / 100f, e.vigMidpoint / 100f, e.vigFeather / 100f, e.vigRoundness / 100f)
        p.f4("uVig2", e.vigHighlights / 100f, e.grain / 100f, e.grainSize / 100f, e.grainRoughness / 100f)
        var hslOn = false
        for (i in 0 until 8) {
            val b = c.hsl[i]
            hslH[i] = b.hue / 100f * 30f; hslS[i] = b.sat / 100f; hslL[i] = b.lum / 100f
            if (b != HslBand()) hslOn = true
        }
        p.fv("uHslCenter", hslC); p.fv("uHslHue", hslH); p.fv("uHslSat", hslS); p.fv("uHslLum", hslL)
        p.i("uHslOn", if (hslOn) 1 else 0)
        val g = state.grading
        fun wheel(name: String, w: GradeWheel) {
            val r = Math.toRadians(w.hue.toDouble())
            val s = w.sat / 100f * 0.22f
            p.f4(name, (s * cos(r)).toFloat(), (s * sin(r)).toFloat(), w.lum / 100f * 0.2f, 0f)
        }
        wheel("uGradeS", g.shadows); wheel("uGradeM", g.midtones); wheel("uGradeH", g.highlights); wheel("uGradeG", g.global)
        p.f2("uGradeBB", g.blending / 100f, g.balance / 100f)
        val gradeOn = g.shadows != GradeWheel() || g.midtones != GradeWheel() || g.highlights != GradeWheel() || g.global != GradeWheel()
        p.i("uGradeOn", if (gradeOn) 1 else 0)
        updateLut(state.curve)
        p.i("uCurveOn", if (state.curve.isIdentity) 0 else 1)
        p.m3("uToGlobal", toGlobal.toGl(tmpMat))
        p.m3("uToCrop", toCrop.toGl(tmpMat))
        p.f("uAspect", width / height.toFloat())
        val (cw, ch) = CropMath.cropPixelSize(state.crop, width.toFloat(), height.toFloat())
        p.f("uCropAspect", cw / ch)
        p.f("uGrainScale", width.toFloat())
        p.i("uHasLocal", if (hasLocal) 1 else 0)
        p.tex("uFine", 1, fine!!.tex); p.tex("uMid", 2, mid!!.tex); p.tex("uLarge", 3, large!!.tex)
        val ps = params!!
        for (i in 0 until 4) p.tex("uP$i", 4 + i, if (hasLocal) ps[i].tex else zeroTex)
        p.tex("uLut", 8, lutTex)
        val gs = grade
        val localGrade = hasLocal && hasLocalGrade && gs != null
        for (i in 0 until 3) p.tex("uP${4 + i}", 9 + i, if (localGrade) gs!![i].tex else zeroTex)
        p.i("uHasLocalGrade", if (localGrade) 1 else 0)
    }

    /** Full preview pipeline: masks → local field → develop into [out]. */
    fun render(state: EditState, fullWidth: Int) {
        val s = src ?: return
        val o = out ?: return
        updateMasks(state)
        accumulate(state)
        o.bind()
        pDev.use()
        pDev.tex("uSrc", 0, s.tex)
        pDev.f4("uSrcRect", 0f, 0f, 1f, 1f)
        pDev.f2("uSrcTexel", 1f / width, 1f / height)
        pDev.i("uSwizzle", 0)
        val radius = max(0.75f, state.detail.sharpRadius * width / max(1, fullWidth).toFloat() * 2f)
        val c2g = CropMath.cropToGlobal(state.crop, width.toFloat(), height.toFloat())
        setDevelopUniforms(state, radius, Mat2D.IDENTITY, c2g.inverse())
        Quad.draw()
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        checkGl("render")
    }

    // ------------------------------------------------------------------ display

    class DisplayParams(
        val view: ViewTransform,
        val compare: Int = 0,
        val split: Float = 0.5f,
        val showMaskId: Long? = null,
        val maskColor: FloatArray = floatArrayOf(0.95f, 0.15f, 0.15f, 0.55f),
        val cropMode: Boolean = false,
    )

    fun display(vw: Int, vh: Int, dp: DisplayParams) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, vw, vh)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        val o = out ?: return
        val s = src ?: return
        pDisp.use()
        pDisp.tex("uImg", 0, o.tex)
        pDisp.tex("uOrig", 1, s.tex)
        val mask = dp.showMaskId?.let { masks[it] }
        pDisp.tex("uMask", 2, mask?.target?.tex ?: zeroTex)
        pDisp.i("uShowMask", if (mask != null) 1 else 0)
        pDisp.f4("uMaskColor", dp.maskColor[0], dp.maskColor[1], dp.maskColor[2], dp.maskColor[3])
        val uvToPx = Mat2D.scale(vw.toFloat(), vh.toFloat())
        pDisp.m3("uScreenToGlobal", (dp.view.screenToGlobal * uvToPx).toGl(tmpMat))
        pDisp.m3("uScreenToCrop", (dp.view.screenToCrop * uvToPx).toGl(tmpMat))
        pDisp.i("uCompare", dp.compare)
        pDisp.f("uSplit", dp.split)
        pDisp.i("uCropMode", if (dp.cropMode) 1 else 0)
        pDisp.f3("uBg", 0f, 0f, 0f)
        Quad.draw()
    }

    // ------------------------------------------------------------------ readbacks

    private var histBuf: ByteBuffer? = null

    fun histogram(): Histogram? {
        val o = out ?: return null
        val h = hist ?: return null
        h.bind()
        pCopy.use(); pCopy.tex("uTex", 0, o.tex); Quad.draw()
        val n = h.width * h.height
        val buf = histBuf?.takeIf { it.capacity() == n * 4 }
            ?: ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).also { histBuf = it }
        buf.position(0)
        glReadPixels(0, 0, h.width, h.height, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        val r = IntArray(256); val g = IntArray(256); val b = IntArray(256); val l = IntArray(256)
        for (i in 0 until n) {
            val rr = buf.get(i * 4).toInt() and 255
            val gg = buf.get(i * 4 + 1).toInt() and 255
            val bb = buf.get(i * 4 + 2).toInt() and 255
            r[rr]++; g[gg]++; b[bb]++
            l[(rr * 54 + gg * 183 + bb * 19) shr 8]++
        }
        return Histogram(r, g, b, l)
    }

    /** Source colour at a global uv, as perceptual (gamma 2.2) rgb – the space colour-range masks use. */
    fun sampleSource(u: Float, v: Float): FloatArray? {
        val s = src ?: return null
        val x = (u * width).toInt().coerceIn(0, width - 1)
        val y = (v * height).toInt().coerceIn(0, height - 1)
        val buf = ByteBuffer.allocateDirect(4 * 9).order(ByteOrder.nativeOrder())
        glBindFramebuffer(GL_FRAMEBUFFER, s.fbo)
        val x0 = max(0, x - 1); val y0 = max(0, y - 1)
        val w = min(3, width - x0); val h = min(3, height - y0)
        glReadPixels(x0, y0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        val acc = FloatArray(3)
        val n = w * h
        for (i in 0 until n) for (c in 0 until 3) {
            val e = (buf.get(i * 4 + c).toInt() and 255) / 255f
            val lin = if (e <= 0.04045f) e / 12.92f else ((e + 0.055f) / 1.055f).pow(2.4f)
            acc[c] += lin.pow(1f / 2.2f) / n
        }
        return acc
    }

    // ------------------------------------------------------------------ mask thumbnails

    private var thumb: GlTarget? = null

    /** Grey ARGB pixels of a mask (white = selected), long edge [edge] px; null if the mask isn't rasterized. */
    fun maskThumbnail(maskId: Long, edge: Int = 96): Bitmap? {
        val m = masks[maskId] ?: return null
        val tw: Int; val th: Int
        if (width >= height) { tw = edge; th = max(1, edge * height / width) } else { th = edge; tw = max(1, edge * width / height) }
        val t = thumb?.takeIf { it.width == tw && it.height == th }
            ?: run { thumb?.release(); GlTarget(tw, th, GL_RGBA8).also { thumb = it } }
        t.bind()
        pCopyR.use(); pCopyR.tex("uTex", 0, m.target.tex); pCopyR.i("uInvert", 0)
        Quad.draw()
        val buf = ByteBuffer.allocateDirect(tw * th * 4).order(ByteOrder.nativeOrder())
        glReadPixels(0, 0, tw, th, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        val px = IntArray(tw * th)
        for (i in px.indices) {
            val g = buf.get(i * 4).toInt() and 255
            px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------------ export

    private var tileOut: GlTarget? = null

    /** Call once before rendering export tiles so masks / local field match [state]. */
    fun prepareExport(state: EditState) {
        updateMasks(state)
        accumulate(state)
    }

    /**
     * Renders one export tile. [tile] holds full-resolution source pixels covering [srcRect]
     * (global uv). The output tile covers [tileToGlobal]/[tileToCrop] mapped vUv. Pixels come back
     * as ARGB ints, row 0 = top.
     */
    fun renderTile(
        state: EditState, tile: Bitmap, srcRect: FloatArray, tileToGlobal: Mat2D, tileToCrop: Mat2D,
        outW: Int, outH: Int, radiusScale: Float, into: IntArray,
    ) {
        glActiveTexture(UPLOAD_UNIT)
        val tSrc = GlTarget(tile.width, tile.height, GL_SRGB8_ALPHA8, withFbo = false)
        glBindTexture(GL_TEXTURE_2D, tSrc.tex)
        GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, tile)
        val o = tileOut?.takeIf { it.width == outW && it.height == outH }
            ?: run { tileOut?.release(); GlTarget(outW, outH, GL_RGBA8).also { tileOut = it } }
        o.bind()
        pDev.use()
        pDev.tex("uSrc", 0, tSrc.tex)
        pDev.f4("uSrcRect", srcRect[0], srcRect[1], srcRect[2], srcRect[3])
        pDev.f2("uSrcTexel", 1f / tile.width, 1f / tile.height)
        pDev.i("uSwizzle", 1)
        setDevelopUniforms(state, radiusScale, tileToGlobal, tileToCrop)
        Quad.draw()
        val buf = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.LITTLE_ENDIAN)
        glReadPixels(0, 0, outW, outH, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        buf.asIntBuffer().get(into, 0, outW * outH)
        tSrc.release()
        checkGl("tile")
    }

    fun endExport() {
        tileOut?.release(); tileOut = null
    }
}
