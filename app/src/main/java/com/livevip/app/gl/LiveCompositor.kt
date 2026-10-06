package com.livevip.app.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.livevip.app.core.CompositionState
import com.livevip.app.engine.FpsMeter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * GPU compositor.
 *
 * Decoder -> SurfaceTexture (OES texture) -> OpenGL ES -> preview surface AND encoder surface.
 * Frames are never copied into CPU bitmaps. Preview and encoder share one CompositionState,
 * so preview output and encoded output are geometrically identical.
 */
class LiveCompositor {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var egl: EglCore? = null
    private var program: OesTextureProgram? = null
    private var textureId = 0

    @Volatile private var dummySurface: EGLSurface? = null
    @Volatile private var previewEglSurface: EGLSurface? = null
    @Volatile private var encoderEglSurface: EGLSurface? = null
    @Volatile private var previewWidth = 0
    @Volatile private var previewHeight = 0

    var surfaceTexture: SurfaceTexture? = null
        private set
    var inputSurface: Surface? = null
        private set

    @Volatile var composition: CompositionState = CompositionState()

    val compositorMeter = FpsMeter()
    val encoderInputMeter = FpsMeter()
    val previewFrameCount = AtomicLong(0)
    val encoderFrameCount = AtomicLong(0)
    @Volatile var firstFrameReceived = false
        private set
    @Volatile var lastFrameTimeMs: Long = 0
        private set
    @Volatile var eglInitialized = false
        private set
    @Volatile var lastError: String? = null

    /** Timestamp provider for the encoder surface (master clock, in microseconds). */
    @Volatile var ptsProviderUs: (() -> Long)? = null

    fun start() {
        if (thread != null) return
        val t = HandlerThread("LiveCompositor").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val latch = CountDownLatch(1)
        h.post {
            try {
                val core = EglCore()
                egl = core
                dummySurface = core.createOffscreenSurface(16, 16)
                core.makeCurrent(dummySurface!!)
                program = OesTextureProgram().also { it.create() }
                textureId = program!!.createOesTexture()
                val st = SurfaceTexture(textureId)
                st.setOnFrameAvailableListener({ onFrameAvailable() }, h)
                surfaceTexture = st
                inputSurface = Surface(st)
                eglInitialized = true
            } catch (t2: Throwable) {
                lastError = "EGL init failed: ${t2.message}"
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    fun setDefaultBufferSize(width: Int, height: Int) {
        if (width > 0 && height > 0) surfaceTexture?.setDefaultBufferSize(width, height)
    }

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        handler?.post {
            try {
                val core = egl ?: return@post
                core.releaseSurface(previewEglSurface)
                previewEglSurface = core.createWindowSurface(surface)
                previewWidth = width
                previewHeight = height
            } catch (t: Throwable) {
                lastError = "Preview surface failed: ${t.message}"
            }
        }
    }

    fun detachPreview() {
        handler?.post {
            egl?.releaseSurface(previewEglSurface)
            previewEglSurface = null
        }
    }

    fun attachEncoder(surface: Surface) {
        val latch = CountDownLatch(1)
        handler?.post {
            try {
                val core = egl ?: return@post
                core.releaseSurface(encoderEglSurface)
                encoderEglSurface = core.createWindowSurface(surface)
            } catch (t: Throwable) {
                lastError = "Encoder surface failed: ${t.message}"
            } finally {
                latch.countDown()
            }
        } ?: latch.countDown()
        latch.await()
    }

    fun detachEncoder() {
        val latch = CountDownLatch(1)
        handler?.post {
            egl?.releaseSurface(encoderEglSurface)
            encoderEglSurface = null
            latch.countDown()
        } ?: latch.countDown()
        latch.await()
    }

    private fun onFrameAvailable() {
        val core = egl ?: return
        val prog = program ?: return
        val st = surfaceTexture ?: return
        try {
            core.makeCurrent(dummySurface!!)
            st.updateTexImage()
            compositorMeter.tick()
            firstFrameReceived = true
            lastFrameTimeMs = System.currentTimeMillis()
            val texMatrix = FloatArray(16)
            st.getTransformMatrix(texMatrix)
            val comp = composition
            val mvp = comp.toMatrix()

            previewEglSurface?.let { ps ->
                core.makeCurrent(ps)
                drawLetterboxed(prog, texMatrix, mvp, previewWidth, previewHeight, comp)
                core.swapBuffers(ps)
                previewFrameCount.incrementAndGet()
            }

            encoderEglSurface?.let { es ->
                core.makeCurrent(es)
                GLES20.glViewport(0, 0, comp.outputWidth, comp.outputHeight)
                prog.clearBlack()
                prog.draw(textureId, texMatrix, mvp)
                val ptsUs = ptsProviderUs?.invoke() ?: (System.nanoTime() / 1000)
                core.setPresentationTime(es, ptsUs * 1000)
                core.swapBuffers(es)
                encoderFrameCount.incrementAndGet()
                encoderInputMeter.tick()
            }
        } catch (t: Throwable) {
            lastError = "Compositor draw failed: ${t.message}"
        }
    }

    /** The preview shows exactly the output canvas, letterboxed inside the view. */
    private fun drawLetterboxed(
        prog: OesTextureProgram,
        texMatrix: FloatArray,
        mvp: FloatArray,
        viewW: Int,
        viewH: Int,
        comp: CompositionState
    ) {
        GLES20.glViewport(0, 0, viewW, viewH)
        prog.clearBlack()
        if (viewW <= 0 || viewH <= 0) return
        val outAr = comp.outputWidth.toFloat() / comp.outputHeight.toFloat()
        val viewAr = viewW.toFloat() / viewH.toFloat()
        val w: Int
        val h: Int
        if (viewAr > outAr) {
            h = viewH
            w = (viewH * outAr).toInt()
        } else {
            w = viewW
            h = (viewW / outAr).toInt()
        }
        GLES20.glViewport((viewW - w) / 2, (viewH - h) / 2, w, h)
        prog.draw(textureId, texMatrix, mvp)
    }

    fun release() {
        val h = handler ?: return
        val latch = CountDownLatch(1)
        h.post {
            try {
                egl?.releaseSurface(previewEglSurface)
                egl?.releaseSurface(encoderEglSurface)
                egl?.releaseSurface(dummySurface)
                previewEglSurface = null
                encoderEglSurface = null
                dummySurface = null
                program?.release()
                inputSurface?.release()
                surfaceTexture?.release()
                egl?.release()
            } catch (_: Throwable) {
            } finally {
                eglInitialized = false
                latch.countDown()
            }
        }
        latch.await()
        thread?.quitSafely()
        thread = null
        handler = null
        egl = null
        program = null
        surfaceTexture = null
        inputSurface = null
    }
}
