package com.livevip.app.rtmp

/** Everything the developer needs to debug an ingest failure. Never holds the full key. */
data class RtmpDiagnostics(
    val mode: String = "DIRECT RTMP",
    val protocol: String = "-",
    val host: String = "-",
    val port: Int = 0,
    val application: String = "-",
    val urlValidation: String = "-",
    val dnsResult: String = "-",
    val socketResult: String = "-",
    val tlsResult: String = "-",
    val handshakeResult: String = "-",
    val maskedStreamKey: String = "-",
    val connectSent: Boolean = false,
    val connectResultCode: String = "-",
    val connectDescription: String = "-",
    val createStreamTxId: Int = 0,
    val createStreamResult: String = "-",
    val createStreamErrorCode: String = "-",
    val createStreamErrorDescription: String = "-",
    val streamId: Int = 0,
    val publishResult: String = "-",
    val publishStatusCode: String = "-",
    val publishStatusLevel: String = "-",
    val publishStatusDescription: String = "-",
    val failureStage: String = "-",
    val lastException: String = "-",
    val serverMessages: List<String> = emptyList()
)
