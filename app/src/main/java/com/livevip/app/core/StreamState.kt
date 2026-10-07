package com.livevip.app.core

/**
 * Explicit stream state machine. The UI is NEVER allowed to invent a LIVE state;
 * it may only render the state reported by the engine.
 */
enum class StreamState {
    IDLE,
    PREPARING,
    VIDEO_READY,
    AUDIO_READY,
    ENCODER_READY,
    CONNECTING,
    CONNECTED,
    PUBLISHING,
    SENDING,
    STREAMING,
    NETWORK_LOST,
    STREAM_SEND_FAILED,
    RECONNECTING,
    STOPPING,
    STOPPED,
    ERROR;

    val isActive: Boolean
        get() = this != IDLE && this != STOPPED && this != ERROR && this != STREAM_SEND_FAILED

    /** Only true when real media is being accepted by the transport. */
    val isLive: Boolean
        get() = this == STREAMING

    fun label(): String = when (this) {
        IDLE -> "OFFLINE"
        PREPARING -> "PREPARING"
        VIDEO_READY -> "VIDEO READY"
        AUDIO_READY -> "AUDIO READY"
        ENCODER_READY -> "ENCODER READY"
        CONNECTING -> "CONNECTING"
        CONNECTED -> "CONNECTED"
        PUBLISHING -> "PUBLISHING"
        SENDING -> "SENDING"
        STREAMING -> "STREAMING"
        NETWORK_LOST -> "NETWORK LOST"
        STREAM_SEND_FAILED -> "SEND FAILED"
        RECONNECTING -> "RECONNECTING"
        STOPPING -> "STOPPING"
        STOPPED -> "OFFLINE"
        ERROR -> "ERROR"
    }
}
