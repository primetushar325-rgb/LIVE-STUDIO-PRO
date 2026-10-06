package com.livevip.app.engine

import com.livevip.app.core.StreamState

enum class HealthLevel { GREEN, YELLOW, RED, UNKNOWN }

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
    val playlistSize: Int = 0,
    val loopTarget: String = "-",
    val statusMessage: String? = null,
    val reconnectCount: Int = 0,
    val networkTransport: String = "-",
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val lastError: String? = null,
    // --- media pipeline rates (all measured) ---
    val targetFps: Int = 0,
    val decoderFps: Float = 0f,
    val compositorFps: Float = 0f,
    val encoderInputFps: Float = 0f,
    val sentFps: Float = 0f,
    val targetBitrateKbps: Int = 0,
    // --- audio health ---
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    val audioSourceSampleRate: Int = 0,
    val audioSourceChannels: Int = 0,
    val audioUnderruns: Long = 0,
    val audioOverruns: Long = 0,
    val audioSilenceFrames: Long = 0,
    val audioEncodeErrors: Long = 0,
    val audioBufferPercent: Int = 0,
    // --- transport ---
    val sendQueueBytes: Long = 0,
    val sendErrors: Long = 0,
    val networkConnected: Boolean = false,
    // --- background ---
    val serviceRunning: Boolean = false,
    val screenOn: Boolean = true,
    val activityVisible: Boolean = true
) {
    /** Health is derived from REAL counters only. */
    fun videoHealth(): HealthLevel = when {
        !state.isLive -> HealthLevel.UNKNOWN
        targetFps > 0 && sentFps >= targetFps * 0.9f -> HealthLevel.GREEN
        targetFps > 0 && sentFps >= targetFps * 0.75f -> HealthLevel.YELLOW
        else -> HealthLevel.RED
    }

    fun audioHealth(): HealthLevel = when {
        !state.isLive -> HealthLevel.UNKNOWN
        audioEncodeErrors > 0 -> HealthLevel.RED
        audioUnderruns > 20 || audioSilenceFrames > 50 -> HealthLevel.RED
        audioUnderruns > 0 || audioSilenceFrames > 0 -> HealthLevel.YELLOW
        audioFrames > 0 -> HealthLevel.GREEN
        else -> HealthLevel.RED
    }

    fun syncHealth(): HealthLevel = when {
        !state.isLive -> HealthLevel.UNKNOWN
        kotlin.math.abs(avOffsetMs) <= 100 -> HealthLevel.GREEN
        kotlin.math.abs(avOffsetMs) <= 400 -> HealthLevel.YELLOW
        else -> HealthLevel.RED
    }

    fun networkHealth(): HealthLevel = when {
        !state.isLive -> HealthLevel.UNKNOWN
        sendErrors > 0 || reconnectCount > 0 -> HealthLevel.YELLOW
        sendQueueBytes > 1_500_000 -> HealthLevel.RED
        else -> HealthLevel.GREEN
    }

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
