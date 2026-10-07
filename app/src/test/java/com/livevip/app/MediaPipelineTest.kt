package com.livevip.app

import com.livevip.app.audio.PcmRingBuffer
import com.livevip.app.audio.Resampler
import com.livevip.app.engine.FpsMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class MediaPipelineTest {

    private fun tone(frames: Int, channels: Int, startPhase: Double, step: Double): ByteArray {
        val out = ByteArray(frames * channels * 2)
        var idx = 0
        for (f in 0 until frames) {
            val v = (sin(startPhase + f * step) * 12000).toInt().toShort()
            for (c in 0 until channels) {
                out[idx++] = (v.toInt() and 0xFF).toByte()
                out[idx++] = ((v.toInt() shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    @Test
    fun resamplerPassthroughWhenFormatsMatch() {
        val r = Resampler(48000, 2, 48000, 2)
        val input = tone(128, 2, 0.0, 0.01)
        val out = r.process(input, input.size)
        assertEquals(input.size, out.size)
        assertEquals(128L, r.inputFrames)
    }

    @Test
    fun resampler44kTo48kProducesMoreFrames() {
        val r = Resampler(44100, 2, 48000, 2)
        val input = tone(4410, 2, 0.0, 0.002)
        val out = r.process(input, input.size)
        val frames = out.size / 4
        // ~4800 output frames for 4410 input frames (boundary frames may lag by a few).
        assertTrue("got $frames", frames in 4700..4810)
    }

    @Test
    fun resamplerKeepsPhaseAcrossBuffers() {
        val r = Resampler(44100, 2, 48000, 2)
        var produced = 0
        var phase = 0.0
        val step = 0.002
        repeat(10) {
            val buf = tone(441, 2, phase, step)
            phase += 441 * step
            produced += r.process(buf, buf.size).size / 4
        }
        // Ten 10 ms buffers at 44.1k -> ~4800 frames at 48k, no per-buffer phase reset loss.
        assertTrue("produced $produced", abs(produced - 4800) < 60)
        assertEquals(4410L, r.inputFrames)
    }

    @Test
    fun ringBufferUnderrunIsReportedNotFaked() {
        val b = PcmRingBuffer(1024)
        val out = ByteArray(512)
        assertFalse(b.readFully(out, 512))
        assertEquals(1L, b.underruns)
        b.write(ByteArray(512) { 7 })
        assertTrue(b.readFully(out, 512))
        assertEquals(7, out[0].toInt())
        assertEquals(512L, b.bytesRead)
    }

    @Test
    fun ringBufferOverrunDropsOldestAndCounts() {
        val b = PcmRingBuffer(256)
        b.write(ByteArray(200) { 1 })
        b.write(ByteArray(100) { 2 })
        assertTrue(b.overruns >= 1)
        assertEquals(256, b.available())
        val out = ByteArray(256)
        assertTrue(b.readFully(out, 256))
        assertEquals(2, out[255].toInt())
    }

    @Test
    fun fpsMeterMeasuresRealRate() {
        val m = FpsMeter()
        var t = 1_000_000L
        repeat(40) {
            m.tick(t)
            t += 33
        }
        assertTrue("fps=${m.fps}", m.fps in 28f..32f)
        assertEquals(40L, m.total)
        m.refresh(t + 5000)
        assertEquals(0f, m.fps, 0.001f)
    }

    @Test
    fun audioPtsFrom48kSampleCountIsMonotonic() {
        // 1024-sample AAC frames at 48 kHz -> 21333 us per frame, strictly increasing.
        var previous = -1L
        for (frame in 0 until 1000) {
            val pts = frame.toLong() * 1024L * 1_000_000L / 48_000L
            assertTrue(pts > previous)
            previous = pts
        }
        assertEquals(21333L, 1024L * 1_000_000L / 48_000L)
    }
}

class StreamStatsHealthTest {
    @Test
    fun healthAndLabelsComeFromRealCounters() {
        val live = com.livevip.app.engine.StreamStats(
            state = com.livevip.app.core.StreamState.STREAMING,
            targetFps = 30,
            sentFps = 29.5f,
            audioFrames = 500,
            avOffsetMs = 20,
            bitrateBps = 2_000_000,
            uploadCapacityBps = 5_000_000
        )
        assertEquals(com.livevip.app.engine.HealthLevel.GREEN, live.videoHealth())
        assertEquals(com.livevip.app.engine.HealthLevel.GREEN, live.audioHealth())
        assertEquals(com.livevip.app.engine.HealthLevel.GREEN, live.syncHealth())
        assertEquals(3_000_000L, live.headroomBps())
        assertEquals("OK", live.syncLabel())

        val degraded = live.copy(sentFps = 19f, audioUnderruns = 37, avOffsetMs = 800)
        assertEquals(com.livevip.app.engine.HealthLevel.RED, degraded.videoHealth())
        assertEquals(com.livevip.app.engine.HealthLevel.RED, degraded.audioHealth())
        assertEquals("AV_SYNC_ERROR", degraded.syncLabel())
        assertEquals("AUDIO_BUFFER_UNDERRUN", degraded.audioBufferLabel())
    }

    @Test
    fun offlineStateNeverReportsGreen() {
        val off = com.livevip.app.engine.StreamStats(state = com.livevip.app.core.StreamState.IDLE)
        assertEquals(com.livevip.app.engine.HealthLevel.UNKNOWN, off.videoHealth())
        assertEquals(com.livevip.app.engine.HealthLevel.UNKNOWN, off.audioHealth())
        assertEquals(0L, off.headroomBps())
    }
}
