package com.livevip.app

import com.livevip.app.core.CompositionState
import com.livevip.app.core.FitMode
import com.livevip.app.core.MasterClock
import com.livevip.app.engine.ReconnectController
import com.livevip.app.rtmp.Amf0
import com.livevip.app.rtmp.FlvPackager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    @Test
    fun `fit mode preserves aspect ratio of a vertical video on a 16 by 9 canvas`() {
        val state = CompositionState(
            sourceWidth = 1080, sourceHeight = 1920,
            outputWidth = 1920, outputHeight = 1080,
            fitMode = FitMode.FIT
        )
        val (sx, sy) = state.baseScale()
        assertEquals(1f, sy, 0.001f)
        assertTrue("video must be pillarboxed, not stretched", sx < 1f)
    }

    @Test
    fun `fill mode covers the canvas without independent axis stretching`() {
        val state = CompositionState(
            sourceWidth = 1920, sourceHeight = 1080,
            outputWidth = 1080, outputHeight = 1920,
            fitMode = FitMode.FILL
        )
        val (sx, sy) = state.baseScale()
        assertEquals(1f, sy, 0.001f)
        assertTrue(sx > 1f)
    }

    @Test
    fun `master clock timestamps stay monotonic across loops`() {
        val clock = MasterClock()
        clock.start()
        val a = clock.videoPts(1_000_000)
        val b = clock.videoPts(0) // simulates a source loop restarting at zero
        val c = clock.videoPts(2_000_000)
        assertTrue(b > a)
        assertTrue(c > b)
    }

    @Test
    fun `reconnect uses controlled backoff then gives up`() {
        val controller = ReconnectController()
        assertEquals(1000L, controller.nextDelayMs())
        assertEquals(2000L, controller.nextDelayMs())
        assertEquals(4000L, controller.nextDelayMs())
        assertEquals(8000L, controller.nextDelayMs())
        assertEquals(15000L, controller.nextDelayMs())
        assertNull(controller.nextDelayMs())
        assertTrue(controller.exhausted())
    }

    @Test
    fun `annexB access unit is converted to length prefixed avcc`() {
        val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0, 0, 1, 0x41, 0x33)
        val avcc = FlvPackager.annexBToAvcc(annexB)
        // first NAL: 4 byte length + 3 bytes, second: 4 byte length + 2 bytes
        assertEquals(13, avcc.size)
        assertEquals(3, avcc[3].toInt())
        assertEquals(0x65, avcc[4].toInt())
    }

    @Test
    fun `avc sequence header starts with keyframe avc marker`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F, 0x11)
        val pps = byteArrayOf(0x68, 0x12)
        val header = FlvPackager.avcSequenceHeader(sps, pps)
        assertEquals(0x17, header[0].toInt() and 0xFF)
        assertEquals(0x00, header[1].toInt())
        // 3 bytes composition time, then AVCDecoderConfigurationRecord
        assertEquals(0x01, header[5].toInt())
        assertEquals(0x42, header[6].toInt())
    }

    @Test
    fun `elapsed timer formats hours minutes seconds`() {
        assertEquals("00:00:01", com.livevip.app.engine.StreamStats.formatDuration(1000))
        assertEquals("01:23:45", com.livevip.app.engine.StreamStats.formatDuration(5_025_000))
        assertEquals("10:00:00", com.livevip.app.engine.StreamStats.formatDuration(36_000_000))
    }

    @Test
    fun `only the streaming state counts as live`() {
        com.livevip.app.core.StreamState.entries.forEach { state ->
            assertEquals(state == com.livevip.app.core.StreamState.STREAMING, state.isLive)
        }
        assertTrue(com.livevip.app.core.StreamState.CONNECTED.isActive)
        assertTrue(!com.livevip.app.core.StreamState.CONNECTED.isLive)
    }

    @Test
    fun `default composition is centered`() {
        val state = CompositionState(
            sourceWidth = 1920, sourceHeight = 1080,
            outputWidth = 1080, outputHeight = 1920
        )
        assertEquals(0f, state.translationX, 0.0001f)
        assertEquals(0f, state.translationY, 0.0001f)
        val matrix = state.toMatrix()
        assertEquals(0f, matrix[12], 0.0001f)
        assertEquals(0f, matrix[13], 0.0001f)
    }

    @Test
    fun `amf0 round trips a command name`() {
        val payload = Amf0.encode { out ->
            Amf0.writeString(out, "connect")
            Amf0.writeNumber(out, 1.0)
            Amf0.writeNull(out)
        }
        val strings = Amf0.readStrings(payload)
        assertEquals("connect", strings.first())
        assertNotNull(Amf0.readNumbers(payload).firstOrNull())
        assertEquals(1.0, Amf0.readNumbers(payload).first(), 0.0001)
    }
}
