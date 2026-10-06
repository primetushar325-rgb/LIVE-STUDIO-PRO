package com.livevip.app.rtmp

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Correct RTMP chunk stream de-multiplexer.
 *
 * The previous implementation assumed the peer used OUR chunk size and skipped
 * the payload of type 2 / type 3 chunk headers, which permanently desynchronised
 * the input stream — every message after the first (including the createStream
 * `_result`) was then parsed as garbage. This reader keeps per chunk-stream
 * state, honours the peer's chunk size and handles extended timestamps.
 */
class RtmpChunkReader(input: InputStream) {

    private val data = DataInputStream(input)

    /** Peer chunk size. RTMP default is 128 until the peer sends Set Chunk Size. */
    var chunkSize: Int = 128
        set(value) {
            if (value in 1..0xFFFFFF) field = value
        }

    var bytesReceived: Long = 0
        private set

    private class ChunkStream {
        var timestamp: Long = 0
        var timestampDelta: Long = 0
        var messageLength: Int = 0
        var messageType: Int = 0
        var messageStreamId: Int = 0
        var extendedTimestamp: Boolean = false
        var payload: ByteArray = ByteArray(0)
        var filled: Int = 0
    }

    private val streams = HashMap<Int, ChunkStream>()

    /** Blocks until one complete message is available. Returns null on clean EOF. */
    fun readMessage(): RtmpMessage? {
        while (true) {
            val basic = data.read()
            if (basic < 0) return null
            bytesReceived++
            val fmt = (basic ushr 6) and 0x03
            var csid = basic and 0x3F
            when (csid) {
                0 -> {
                    csid = readByte() + 64
                }
                1 -> {
                    val b0 = readByte()
                    val b1 = readByte()
                    csid = (b1 shl 8) + b0 + 64
                }
            }
            val cs = streams.getOrPut(csid) { ChunkStream() }

            when (fmt) {
                0 -> {
                    val ts = readUInt24()
                    cs.messageLength = readUInt24()
                    cs.messageType = readByte()
                    cs.messageStreamId = readIntLittleEndian()
                    cs.extendedTimestamp = ts == 0xFFFFFF
                    cs.timestamp = if (cs.extendedTimestamp) readUInt32() else ts.toLong()
                    cs.timestampDelta = 0
                    cs.payload = ByteArray(cs.messageLength)
                    cs.filled = 0
                }
                1 -> {
                    val delta = readUInt24()
                    cs.messageLength = readUInt24()
                    cs.messageType = readByte()
                    cs.extendedTimestamp = delta == 0xFFFFFF
                    cs.timestampDelta = if (cs.extendedTimestamp) readUInt32() else delta.toLong()
                    cs.timestamp += cs.timestampDelta
                    cs.payload = ByteArray(cs.messageLength)
                    cs.filled = 0
                }
                2 -> {
                    val delta = readUInt24()
                    cs.extendedTimestamp = delta == 0xFFFFFF
                    cs.timestampDelta = if (cs.extendedTimestamp) readUInt32() else delta.toLong()
                    cs.timestamp += cs.timestampDelta
                    if (cs.filled == 0) {
                        cs.payload = ByteArray(cs.messageLength)
                    }
                }
                3 -> {
                    // Continuation. A new message reusing the previous header repeats
                    // the extended timestamp field when one is in use.
                    if (cs.filled == 0) {
                        if (cs.extendedTimestamp) cs.timestamp = readUInt32()
                        else cs.timestamp += cs.timestampDelta
                        cs.payload = ByteArray(cs.messageLength)
                    }
                }
            }

            if (cs.messageLength <= 0) continue

            val remaining = cs.messageLength - cs.filled
            val toRead = minOf(chunkSize, remaining)
            if (toRead > 0) {
                readFully(cs.payload, cs.filled, toRead)
                cs.filled += toRead
            }

            if (cs.filled >= cs.messageLength) {
                val message = RtmpMessage(
                    chunkStreamId = csid,
                    type = cs.messageType,
                    timestamp = cs.timestamp,
                    messageStreamId = cs.messageStreamId,
                    payload = cs.payload
                )
                cs.filled = 0
                cs.payload = ByteArray(0)
                if (message.type == RtmpMessage.TYPE_SET_CHUNK_SIZE && message.payload.size >= 4) {
                    chunkSize = ((message.payload[0].toInt() and 0x7F) shl 24) or
                        ((message.payload[1].toInt() and 0xFF) shl 16) or
                        ((message.payload[2].toInt() and 0xFF) shl 8) or
                        (message.payload[3].toInt() and 0xFF)
                }
                return message
            }
        }
    }

    private fun readByte(): Int {
        val value = data.read()
        if (value < 0) throw EOFException("socket closed")
        bytesReceived++
        return value and 0xFF
    }

    private fun readUInt24(): Int = (readByte() shl 16) or (readByte() shl 8) or readByte()

    private fun readUInt32(): Long =
        ((readByte().toLong() shl 24) or (readByte().toLong() shl 16) or
            (readByte().toLong() shl 8) or readByte().toLong())

    private fun readIntLittleEndian(): Int =
        readByte() or (readByte() shl 8) or (readByte() shl 16) or (readByte() shl 24)

    private fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        data.readFully(buffer, offset, length)
        bytesReceived += length
    }
}
