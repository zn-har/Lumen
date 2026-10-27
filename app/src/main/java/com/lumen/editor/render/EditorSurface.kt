package com.lumen.editor.render

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lumen.editor.model.EditState
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView hosting the [Engine]. Renders only when dirty. The UI pushes the latest
 * EditState and display parameters; the GL thread picks up the newest values each frame,
 * so fast slider drags never queue up stale renders.
 */
class EditorSurface(context: Context) : GLSurfaceView(context) {
    val engine = Engine()
    private val main = Handler(Looper.getMainLooper())

    private val pendingState = AtomicReference<EditState?>(null)
    private val pendingImage = AtomicReference<Pair<Bitmap, Int>?>(null)
    @Volatile var display: Engine.DisplayParams? = null
        set(v) { field = v; requestRender() }

    /** Called on the main thread with a fresh histogram after renders (throttled). */
    var onHistogram: ((Histogram) -> Unit)? = null
    /** Called on the main thread when the GL context was recreated and the image must be re-supplied. */
    var onContextLost: (() -> Unit)? = null
    /** Called on the main thread after a new image finished uploading. */
    var onImageReady: ((Int, Int) -> Unit)? = null

    private var lastState: EditState? = null
    private var fullWidth = 1
    private var surfaceW = 1
    private var surfaceH = 1
    private var lastHist = 0L
    private var histDirty = false
    @Volatile private var histScheduled = false
    private var contextGeneration = 0

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                engine.init()
                contextGeneration++
                if (contextGeneration > 1 || lastState != null) {
                    lastState = null
                    main.post { onContextLost?.invoke() }
                }
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                surfaceW = width; surfaceH = height
            }

            override fun onDrawFrame(gl: GL10?) = frame()
        })
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Hands a decoded preview to the GL thread. The bitmap is recycled after upload. */
    fun setImage(preview: Bitmap, fullWidth: Int) {
        pendingImage.getAndSet(preview to fullWidth)?.first?.recycle()
        requestRender()
    }

    fun submit(state: EditState) {
        pendingState.set(state)
        requestRender()
    }

    private fun frame() {
        pendingImage.getAndSet(null)?.let { (bmp, fw) ->
            engine.setImage(bmp)
            bmp.recycle()
            fullWidth = fw
            lastState = null
            val w = engine.width; val h = engine.height
            main.post { onImageReady?.invoke(w, h) }
        }
        val s = pendingState.getAndSet(null) ?: lastState
        if (s != null && engine.hasImage && s !== lastState) {
            engine.render(s, fullWidth)
            lastState = s
            histDirty = true
        }
        if (histDirty && onHistogram != null && engine.hasImage) {
            val now = SystemClock.uptimeMillis()
            if (now - lastHist >= 120) {
                lastHist = now
                histDirty = false
                engine.histogram()?.let { h -> main.post { onHistogram?.invoke(h) } }
            } else if (!histScheduled) {
                // trailing update so the end of a drag always gets a histogram
                histScheduled = true
                main.postDelayed({ histScheduled = false; requestRender() }, 130)
            }
        }
        display?.let { engine.display(surfaceW, surfaceH, it) }
    }

    /** Runs [block] on the GL thread and suspends until it returns. */
    suspend fun <T> onGl(block: (Engine) -> T): T {
        val d = CompletableDeferred<T>()
        queueEvent {
            try { d.complete(block(engine)) } catch (t: Throwable) { d.completeExceptionally(t) }
        }
        return d.await()
    }

    fun releaseGl() {
        queueEvent { engine.release() }
    }
}
