package com.lumen.editor.render

import android.opengl.GLES30.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val TAG = "LumenGl"

class GlProgram(vs: String, fs: String) {
    val id: Int
    private val locs = HashMap<String, Int>()

    init {
        val v = compile(GL_VERTEX_SHADER, vs)
        val f = compile(GL_FRAGMENT_SHADER, fs)
        id = glCreateProgram()
        glAttachShader(id, v); glAttachShader(id, f)
        glBindAttribLocation(id, 0, "aPos")
        glLinkProgram(id)
        val st = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, st, 0)
        if (st[0] == 0) error("link: " + glGetProgramInfoLog(id))
        glDeleteShader(v); glDeleteShader(f)
    }

    fun use() = glUseProgram(id)
    fun loc(name: String) = locs.getOrPut(name) { glGetUniformLocation(id, name) }
    fun f(name: String, v: Float) = glUniform1f(loc(name), v)
    fun f2(name: String, a: Float, b: Float) = glUniform2f(loc(name), a, b)
    fun f3(name: String, a: Float, b: Float, c: Float) = glUniform3f(loc(name), a, b, c)
    fun f4(name: String, a: Float, b: Float, c: Float, d: Float) = glUniform4f(loc(name), a, b, c, d)
    fun i(name: String, v: Int) = glUniform1i(loc(name), v)
    fun fv(name: String, v: FloatArray, count: Int = v.size) = glUniform1fv(loc(name), count, v, 0)
    fun f3v(name: String, v: FloatArray, count: Int) = glUniform3fv(loc(name), count, v, 0)
    fun f4v(name: String, v: FloatArray, count: Int) = glUniform4fv(loc(name), count, v, 0)
    fun m3(name: String, v: FloatArray) = glUniformMatrix3fv(loc(name), 1, false, v, 0)

    fun tex(name: String, unit: Int, tex: Int) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(loc(name), unit)
    }

    fun release() = glDeleteProgram(id)

    private fun compile(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val st = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            val log = glGetShaderInfoLog(s)
            Log.e(TAG, "shader compile failed:\n$log\n$src")
            error("compile: $log")
        }
        return s
    }
}

/** A texture with an optional framebuffer. Format is a sized internal format (GL_RGBA8, GL_R8, GL_RGBA16F...). */
class GlTarget(val width: Int, val height: Int, val internal: Int, filter: Int = GL_LINEAR, withFbo: Boolean = true) {
    val tex: Int
    val fbo: Int

    init {
        val t = IntArray(1)
        glGenTextures(1, t, 0)
        tex = t[0]
        glActiveTexture(GL_TEXTURE15)  // never disturb sampler units bound for a pass
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexStorage2D(GL_TEXTURE_2D, 1, internal, width, height)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        if (withFbo) {
            val f = IntArray(1)
            glGenFramebuffers(1, f, 0)
            fbo = f[0]
            glBindFramebuffer(GL_FRAMEBUFFER, fbo)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
            val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
            if (status != GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "fbo incomplete $status fmt=$internal")
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
        } else fbo = 0
    }

    val bytes: Long get() = width.toLong() * height * when (internal) {
        GL_R8 -> 1; GL_RGBA16F -> 8; GL_R16F -> 2; GL_RGBA32F -> 16; else -> 4
    }

    fun bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, width, height)
    }

    fun release() {
        glDeleteTextures(1, intArrayOf(tex), 0)
        if (fbo != 0) glDeleteFramebuffers(1, intArrayOf(fbo), 0)
    }
}

/** Fullscreen triangle-strip quad, attribute 0 = clip-space position. */
object Quad {
    private var vao = 0
    private var vbo = 0

    fun init() {
        val data = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = floatBuffer(data)
        val a = IntArray(1)
        glGenVertexArrays(1, a, 0); vao = a[0]
        glGenBuffers(1, a, 0); vbo = a[0]
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, buf, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0)
        glBindVertexArray(0)
    }

    fun draw() {
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glBindVertexArray(0)
    }

    fun release() {
        glDeleteVertexArrays(1, intArrayOf(vao), 0)
        glDeleteBuffers(1, intArrayOf(vbo), 0)
    }
}

fun floatBuffer(data: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(data); position(0)
    }

fun checkGl(op: String) {
    val e = glGetError()
    if (e != GL_NO_ERROR) Log.e(TAG, "$op: glError 0x${Integer.toHexString(e)}")
}
