package com.livevip.app.core

import java.util.concurrent.atomic.AtomicLong

/**
 * One single monotonic output timeline shared by video, audio and the RTMP transport.
 * Source playback may loop or switch files; the output timeline never resets.
 */
class MasterClock {
    @Volatile private var startNs: Long = 0L
    @Volatile var started: Boolean = false
        private set

    private val lastVideoUs = AtomicLong(-1)
    private val lastAudioUs = AtomicLong(-1)

    fun start() {
        startNs = System.nanoTime()
        started = true
        lastVideoUs.set(-1)
        lastAudioUs.set(-1)
    }

    fun reset() {
        started = false
        startNs = 0
        lastVideoUs.set(-1)
        lastAudioUs.set(-1)
    }

    fun nowUs(): Long = if (!started) 0L else (System.nanoTime() - startNs) / 1000L

    fun elapsedMs(): Long = nowUs() / 1000L

    /** Monotonic guard: never emit a timestamp lower than the previous one. */
    fun videoPts(candidateUs: Long): Long = monotonic(lastVideoUs, candidateUs)

    fun audioPts(candidateUs: Long): Long = monotonic(lastAudioUs, candidateUs)

    fun lastVideoPtsUs(): Long = lastVideoUs.get()
    fun lastAudioPtsUs(): Long = lastAudioUs.get()

    /** Real A/V offset in milliseconds (audio - video). */
    fun avOffsetMs(): Long {
        val v = lastVideoUs.get()
        val a = lastAudioUs.get()
        return if (v < 0 || a < 0) 0 else (a - v) / 1000
    }

    private fun monotonic(holder: AtomicLong, candidateUs: Long): Long {
        while (true) {
            val prev = holder.get()
            val value = if (prev >= 0 && candidateUs <= prev) prev + 1000 else candidateUs
            if (holder.compareAndSet(prev, value)) return value
        }
    }
}
