package com.livevip.app

import com.livevip.app.rtmp.Amf0
import com.livevip.app.rtmp.RtmpChunkReader
import com.livevip.app.rtmp.RtmpMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Regression tests for the bug that produced
 * "RTMP_SERVER_REJECTED: createStream failed":
 * the old reader assumed OUR chunk size and dropped type 2 / type 3 chunk
 * payloads, desynchronising the stream so the createStream `_result` was lost.
 */
class RtmpProtocolTest {

    private fun chunk(
        fmt: Int,
        csid: Int,
        timestamp: Int,
        type: Int,
        streamId: Int,
        payload: ByteArray,
        chunkSize: Int = 128
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((fmt shl 6) or csid)
        if (fmt <= 2) {
            out.write((timestamp shr 16) and 0xFF)
            out.write((timestamp shr 8) and 0xFF)
            out.write(timestamp and 0xFF)
        }
        if (fmt <= 1) {
            out.write((payload.size shr 16) and 0xFF)
            out.write((payload.size shr 8) and 0xFF)
            out.write(payload.size and 0xFF)
            out.write(type)
        }
        if (fmt == 0) {
            out.write(streamId and 0xFF)
            out.write((streamId shr 8) and 0xFF)
            out.write((streamId shr 16) and 0xFF)
            out.write((streamId shr 24) and 0xFF)
        }
        var offset = 0
        while (offset < payload.size) {
            val size = minOf(chunkSize, payload.size - offset)
            if (offset > 0) out.write(0xC0 or csid)
            out.write(payload, offset, size)
            offset += size
        }
        return out.toByteArray()
    }

    private fun commandResult(txId: Double, streamId: Double?): ByteArray = Amf0.encode { out ->
        Amf0.writeString(out, "_result")
        Amf0.writeNumber(out, txId)
        Amf0.writeNull(out)
        if (streamId != null) Amf0.writeNumber(out, streamId) else Amf0.writeNull(out)
    }

    @Test
    fun `reader honours the peer default chunk size of 128 bytes`() {
        val payload = ByteArray(300) { (it % 251).toByte() }
        val bytes = chunk(0, 3, 0, RtmpMessage.TYPE_DATA_AMF0, 0, payload, chunkSize = 128)
        val reader = RtmpChunkReader(ByteArrayInputStream(bytes))
        val message = reader.readMessage()
        assertNotNull(message)
        assertEquals(300, message!!.payload.size)
        assertTrue(message.payload.contentEquals(payload))
    }

    @Test
    fun `createStream result is parsed after window ack, peer bandwidth and connect result`() {
        val stream = ByteArrayOutputStream()
        // Window Acknowledgement Size (fmt 0, csid 2)
        stream.write(chunk(0, 2, 0, RtmpMessage.TYPE_WINDOW_ACK_SIZE, 0, byteArrayOf(0, 0x26, 0x25, 0xA0.toByte())))
        // Set Peer Bandwidth (fmt 0, csid 2)
        stream.write(chunk(0, 2, 0, RtmpMessage.TYPE_SET_PEER_BANDWIDTH, 0, byteArrayOf(0, 0x26, 0x25, 0xA0.toByte(), 2)))
        // _result for connect, tx 1 (fmt 0, csid 3)
        stream.write(chunk(0, 3, 0, RtmpMessage.TYPE_COMMAND_AMF0, 0, commandResult(1.0, null)))
        // _result for createStream, tx 4, stream id 1 — sent as fmt 1 (header compression)
        stream.write(chunk(1, 3, 0, RtmpMessage.TYPE_COMMAND_AMF0, 0, commandResult(4.0, 1.0)))

        val reader = RtmpChunkReader(ByteArrayInputStream(stream.toByteArray()))
        val messages = generateSequence { reader.readMessage() }.toList()
        assertEquals(4, messages.size)

        val last = messages.last()
        assertEquals(RtmpMessage.TYPE_COMMAND_AMF0, last.type)
        val values = Amf0.decodeAll(last.payload)
        assertEquals("_result", values[0])
        assertEquals(4.0, values[1] as Double, 0.0)
        val numbers = values.filterIsInstance<Double>()
        assertEquals(1, numbers.last().toInt()) // stream id
    }

    @Test
    fun `set chunk size from the server is applied to following messages`() {
        val stream = ByteArrayOutputStream()
        stream.write(chunk(0, 2, 0, RtmpMessage.TYPE_SET_CHUNK_SIZE, 0, byteArrayOf(0, 0, 0x10, 0x00)))
        val payload = ByteArray(2000) { 7 }
        stream.write(chunk(0, 5, 0, RtmpMessage.TYPE_DATA_AMF0, 0, payload, chunkSize = 4096))
        val reader = RtmpChunkReader(ByteArrayInputStream(stream.toByteArray()))
        val first = reader.readMessage()
        assertEquals(RtmpMessage.TYPE_SET_CHUNK_SIZE, first!!.type)
        assertEquals(4096, reader.chunkSize)
        val second = reader.readMessage()
        assertEquals(2000, second!!.payload.size)
    }

    @Test
    fun `onStatus objects keep code level and description`() {
        val payload = Amf0.encode { out ->
            Amf0.writeString(out, "onStatus")
            Amf0.writeNumber(out, 0.0)
            Amf0.writeNull(out)
            Amf0.writeObject(
                out,
                listOf(
                    "level" to "error",
                    "code" to "NetStream.Publish.BadName",
                    "description" to "Stream key invalid"
                )
            )
        }
        val values = Amf0.decodeAll(payload)
        assertEquals("onStatus", values[0])
        val status = Amf0.findStatusObject(values)
        assertNotNull(status)
        assertEquals("error", status!!["level"])
        assertEquals("NetStream.Publish.BadName", status["code"])
        assertEquals("Stream key invalid", status["description"])
    }

    @Test
    fun `stream key is never exposed in full`() {
        val masked = com.livevip.app.rtmp.RtmpClient.mask("abcd-efgh-ijkl-mnop-qrst")
        assertTrue(masked.startsWith("abcd"))
        assertTrue(!masked.contains("efgh"))
    }
}
