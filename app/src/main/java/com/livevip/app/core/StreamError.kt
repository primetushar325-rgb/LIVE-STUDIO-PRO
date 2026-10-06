package com.livevip.app.core

enum class ErrorCode {
    VIDEO_URI_INVALID,
    VIDEO_PERMISSION_DENIED,
    VIDEO_TRACK_NOT_FOUND,
    VIDEO_DECODER_FAILED,
    VIDEO_DECODER_TIMEOUT,
    NO_VIDEO_FRAME,
    ENCODER_UNAVAILABLE,
    ENCODER_CONFIG_FAILED,
    AUDIO_INIT_FAILED,
    AUDIO_ENCODER_FAILED,
    RTMP_URL_INVALID,
    RTMP_CONNECT_TIMEOUT,
    RTMP_AUTH_FAILED,
    RTMP_SERVER_REJECTED,
    NETWORK_TIMEOUT,
    NETWORK_LOST,
    STREAM_SEND_FAILED,
    YOUTUBE_INGEST_FAILED,
    TIMESTAMP_ERROR,
    UNSUPPORTED_FORMAT,
    RESOURCE_ERROR
}

class StreamException(
    val code: ErrorCode,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message ?: code.name, cause)

data class StreamError(
    val code: ErrorCode,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
) {
    override fun toString(): String = "$code: $detail"
}
