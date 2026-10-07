package com.livevip.app.rtmp

/** A fully reassembled RTMP message (all chunks joined). */
data class RtmpMessage(
    val chunkStreamId: Int,
    val type: Int,
    val timestamp: Long,
    val messageStreamId: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is RtmpMessage && other.type == type && other.payload.contentEquals(payload)

    override fun hashCode(): Int = type * 31 + payload.contentHashCode()

    companion object {
        const val TYPE_SET_CHUNK_SIZE = 1
        const val TYPE_ABORT = 2
        const val TYPE_ACK = 3
        const val TYPE_USER_CONTROL = 4
        const val TYPE_WINDOW_ACK_SIZE = 5
        const val TYPE_SET_PEER_BANDWIDTH = 6
        const val TYPE_AUDIO = 8
        const val TYPE_VIDEO = 9
        const val TYPE_DATA_AMF0 = 18
        const val TYPE_COMMAND_AMF0 = 20
    }
}
