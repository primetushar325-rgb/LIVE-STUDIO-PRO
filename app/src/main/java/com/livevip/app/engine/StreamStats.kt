package com.livevip.app.engine

import com.livevip.app.core.StreamState

/** Real runtime values only. Nothing here is simulated. */
data class StreamStats(
    val state: StreamState = StreamState.IDLE,
    val elapsedMs: Long = 0,
    val maxDurationMs: Long = 0,
    val videoReady: Boolean = false,
    val decoderReady: Boolean = false,
    val firstFrame: Boolean = false,
    val previewFrames: Long = 0,
    val encoderReady: Boolean = false,
    val encoderName: String = "-",
    val encodedFrames: Long = 0,
    val encodedBytes: Long = 0,
    val actualFps: Float = 0f,
    val droppedFrames: Long = 0,
    val audioReady: Boolean = false,
    val audioFrames: Long = 0,
    val rtmpConnected: Boolean = false,
    val rtmpPublishing: Boolean = false,
    val packetsSent: Long = 0,
    val bytesSent: Long = 0,
    val bitrateBps: Long = 0,
    val queueDepth: Int = 0,
    val avOffsetMs: Long = 0,
    val loopIndex: Int = 0,
    val currentVideoIndex: Int = 0,
    val currentVideoName: String = "-",
    val reconnectCount: Int = 0,
    val networkTransport: String = "-",
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val lastError: String? = null
) {
    fun elapsedLabel(): String = formatDuration(elapsedMs)

    companion object {
        fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return String.format("%02d:%02d:%02d", h, m, s)
        }
    }
}
