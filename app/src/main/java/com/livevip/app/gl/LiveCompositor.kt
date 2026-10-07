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

    /** Last transform matrix, reused when the frame pump repeats a frame. */
    private val lastTexMatrix = FloatArray(16)
    @Volatile private var lastDrawMs: Long = 0
    @Volatile private var pumpIntervalMs: Long = 0
    @Volatile private var pumpRunning = false
    val repeatedFrames = AtomicLong(0)

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
            st.getTransformMatrix(lastTexMatrix)
            compositorMeter.tick()
            firstFrameReceived = true
            lastFrameTimeMs = System.currentTimeMillis()
            val texMatrix = lastTexMatrix
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
            lastDrawMs = System.currentTimeMillis()
        } catch (t: Throwable) {
            lastError = "Compositor draw failed: ${t.message}"
        }
    }

    /**
     * Keeps the encoder fed at the configured frame rate.
     *
     * The decoder can stall briefly (playlist item switch, loop restart, a slow
     * keyframe). Without this the encoder simply receives fewer frames, which is
     * how a 30 FPS profile ends up sending ~24 FPS and why a loop boundary can show
     * a frozen/black gap. When no new decoded frame arrived in time we re-present
     * the last decoded frame with a fresh master-clock timestamp, so output cadence
     * and PTS spacing stay correct. It never invents a frame before the first real
     * one arrives, and it stands down as soon as the decoder keeps up.
     */
    fun startFramePump(targetFps: Int) {
        val h = handler ?: return
        if (pumpRunning || targetFps <= 0) return
        pumpIntervalMs = (1000L / targetFps).coerceAtLeast(1L)
        pumpRunning = true
        h.postDelayed(object : Runnable {
            override fun run() {
                if (!pumpRunning) return
                val interval = pumpIntervalMs
                val now = System.currentTimeMillis()
                if (firstFrameReceived && encoderEglSurface != null &&
                    now - lastDrawMs >= interval + interval / 2
                ) {
                    repeatLastFrame()
                }
                handler?.postDelayed(this, (interval / 2).coerceAtLeast(1L))
            }
        }, pumpIntervalMs)
    }

    fun stopFramePump() {
        pumpRunning = false
    }

    private fun repeatLastFrame() {
        val core = egl ?: return
        val prog = program ?: return
        val es = encoderEglSurface ?: return
        try {
            val comp = composition
            core.makeCurrent(es)
            GLES20.glViewport(0, 0, comp.outputWidth, comp.outputHeight)
            prog.clearBlack()
            prog.draw(textureId, lastTexMatrix, comp.toMatrix())
            val ptsUs = ptsProviderUs?.invoke() ?: (System.nanoTime() / 1000)
            core.setPresentationTime(es, ptsUs * 1000)
            core.swapBuffers(es)
            encoderFrameCount.incrementAndGet()
            encoderInputMeter.tick()
            repeatedFrames.incrementAndGet()
            lastDrawMs = System.currentTimeMillis()
        } catch (t: Throwable) {
            lastError = "Frame pump failed: ${t.message}"
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
        pumpRunning = false
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
