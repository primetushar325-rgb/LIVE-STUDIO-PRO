package com.livevip.app.engine

/** Measures a real rate from actual events over a sliding one second window. */
class FpsMeter {
    private var windowStartMs = 0L
    private var count = 0L

    @Volatile var fps: Float = 0f
        private set
    @Volatile var total: Long = 0
        private set

    fun tick(nowMs: Long = System.currentTimeMillis()) {
        if (windowStartMs == 0L) windowStartMs = nowMs
        count++
        total++
        val elapsed = nowMs - windowStartMs
        if (elapsed >= 1000) {
            fps = count * 1000f / elapsed
            windowStartMs = nowMs
            count = 0
        }
    }

    /** Decays to zero when events stop arriving. */
    fun refresh(nowMs: Long = System.currentTimeMillis()) {
        if (windowStartMs != 0L && nowMs - windowStartMs > 2000) {
            fps = 0f
            windowStartMs = nowMs
            count = 0
        }
    }

    fun reset() {
        windowStartMs = 0; count = 0; fps = 0f; total = 0
    }
}
