package com.livevip.app.rtmp

import android.util.Log
import com.livevip.app.core.ErrorCode
import com.livevip.app.core.StreamException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Direct RTMP / RTMPS publisher.
 *
 * Protocol order: handshake -> connect -> (releaseStream, FCPublish) ->
 * createStream -> publish -> metadata/sequence headers -> media.
 * Each stage reports its own error code and preserves the server's status
 * object (code / level / description) for diagnostics.
 */
class RtmpClient(
    private val url: String,
    private val streamKey: String,
    private val onStatus: (code: String) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val onDiagnostics: (RtmpDiagnostics) -> Unit = {}
) {
    companion object {
        private const val TAG = "RtmpClient"
        private const val OUT_CHUNK_SIZE = 4096
        private const val CSID_CONTROL = 2
        private const val CSID_COMMAND = 3
        private const val CSID_AUDIO = 4
        private const val CSID_VIDEO = 6
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        private const val STAGE_TIMEOUT_MS = 10_000L

        fun mask(key: String): String = when {
            key.isEmpty() -> "NOT SET"
            key.length <= 6 -> "••••"
            else -> "${key.take(4)}••••••••${key.takeLast(2)}"
        }
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var reader: RtmpChunkReader? = null
    private var readerThread: Thread? = null

    private var transactionId = 0
    private val pendingTransactions = HashMap<Int, String>()
    private var connectTxId = 0
    private var createStreamTxId = 0

    @Volatile var connected = false; private set
    @Volatile var publishAccepted = false; private set
    @Volatile var streamId = 0; private set
    @Volatile var bytesSent = 0L; private set
    @Volatile var packetsSent = 0L; private set
    @Volatile var lastErrorCode: ErrorCode? = null
    @Volatile var lastErrorDetail: String? = null
    @Volatile private var running = false
    @Volatile private var createStreamFailure: Pair<ErrorCode, String>? = null
    @Volatile private var publishFailure: Pair<ErrorCode, String>? = null

    @Volatile var diagnostics = RtmpDiagnostics()
        private set

    private val outLock = Any()
    private var windowAckSize = 2_500_000L
    private var lastAckSent = 0L

    private fun diag(block: (RtmpDiagnostics) -> RtmpDiagnostics) {
        diagnostics = block(diagnostics)
        onDiagnostics(diagnostics)
    }

    private fun note(message: String) {
        diag { it.copy(serverMessages = (it.serverMessages + message).takeLast(40)) }
        onLog(message)
    }

    // --------------------------------------------------------------- connect

    fun connect() {
        val uri = try {
            URI(url.trim())
        } catch (t: Throwable) {
            diag { it.copy(urlValidation = "FAIL (malformed)", failureStage = "URL_PARSE") }
            throw StreamException(ErrorCode.RTMP_URL_INVALID, "Malformed server URL")
        }
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme != "rtmp" && scheme != "rtmps") {
            diag { it.copy(urlValidation = "FAIL (scheme=$scheme)", failureStage = "URL_PARSE") }
            throw StreamException(ErrorCode.RTMP_URL_INVALID, "URL must start with rtmp:// or rtmps://")
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            diag { it.copy(urlValidation = "FAIL (no host)", failureStage = "URL_PARSE") }
            throw StreamException(ErrorCode.RTMP_URL_INVALID, "No host in URL")
        }
        val port = if (uri.port > 0) uri.port else if (scheme == "rtmps") 443 else 1935
        // Everything after the host except a trailing stream name is the application.
        val application = (uri.path ?: "").trim('/')
        if (application.isEmpty()) {
            diag { it.copy(urlValidation = "FAIL (no application)", failureStage = "URL_PARSE") }
            throw StreamException(ErrorCode.RTMP_URL_INVALID, "No application in URL (e.g. /live2)")
        }
        if (streamKey.isBlank()) {
            diag { it.copy(failureStage = "STREAM_KEY") }
            throw StreamException(ErrorCode.STREAM_KEY_MISSING, "Stream key is empty")
        }
        diag {
            it.copy(
                mode = "DIRECT ${scheme.uppercase()}",
                protocol = scheme.uppercase(),
                host = host,
                port = port,
                application = application,
                urlValidation = "PASS",
                maskedStreamKey = mask(streamKey)
            )
        }
        onLog("RTMP target host=$host port=$port app=$application key=${mask(streamKey)}")

        // DNS
        try {
            val address = InetAddress.getByName(host)
            diag { it.copy(dnsResult = "OK (${address.hostAddress})") }
        } catch (t: Throwable) {
            diag { it.copy(dnsResult = "FAIL", failureStage = "DNS", lastException = "${t.message}") }
            throw StreamException(ErrorCode.NETWORK_TIMEOUT, "DNS lookup failed for $host")
        }

        // TCP (+TLS)
        try {
            val raw = Socket()
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            diag { it.copy(socketResult = "CONNECTED") }
            val s = if (scheme == "rtmps") {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, port, true) as SSLSocket
                tls.startHandshake()
                diag { it.copy(tlsResult = "OK (${tls.session.protocol}/${tls.session.cipherSuite})") }
                tls
            } else {
                diag { it.copy(tlsResult = "N/A (plain RTMP)") }
                raw
            }
            socket = s
            input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
            output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 256 * 1024))
        } catch (t: SocketTimeoutException) {
            diag { it.copy(socketResult = "TIMEOUT", failureStage = "TCP_CONNECT") }
            throw StreamException(ErrorCode.RTMP_CONNECT_TIMEOUT, "TCP connect timed out")
        } catch (t: IOException) {
            diag {
                it.copy(
                    socketResult = "FAIL", failureStage = "TCP_CONNECT",
                    lastException = t.message ?: "io error"
                )
            }
            throw StreamException(ErrorCode.RTMP_CONNECT_TIMEOUT, t.message ?: "TCP connect failed")
        }

        handshake()
        reader = RtmpChunkReader(input!!)
        running = true
        readerThread = Thread({ readLoop() }, "RtmpReader").also { it.isDaemon = true; it.start() }

        sendWindowAckSize(windowAckSize.toInt())
        sendSetChunkSize(OUT_CHUNK_SIZE)
        sendConnect(application, "$scheme://$host:$port/$application")

        awaitStage(
            label = "connect",
            condition = { connected },
            failure = { lastErrorCode to (lastErrorDetail ?: "no _result for connect") },
            timeoutError = ErrorCode.RTMP_CONNECT_TIMEOUT to "NetConnection.connect timed out"
        )

        sendReleaseStreamAndFcPublish()
        sendCreateStream()
        awaitStage(
            label = "createStream",
            condition = { streamId != 0 },
            failure = { createStreamFailure?.first to (createStreamFailure?.second ?: "") },
            timeoutError = ErrorCode.CREATE_STREAM_TIMEOUT to
                "No _result for createStream (txId=$createStreamTxId) within ${STAGE_TIMEOUT_MS}ms"
        )
        diag { it.copy(createStreamResult = "OK (streamId=$streamId)", streamId = streamId) }

        sendPublish()
        awaitStage(
            label = "publish",
            condition = { publishAccepted },
            failure = { publishFailure?.first to (publishFailure?.second ?: "") },
            timeoutError = ErrorCode.RTMP_PUBLISH_REJECTED to
                "No NetStream.Publish.Start within ${STAGE_TIMEOUT_MS}ms"
        )
        diag { it.copy(publishResult = "ACCEPTED") }
        onLog("RTMP publish accepted by $host (streamId=$streamId)")
    }

    private fun awaitStage(
        label: String,
        condition: () -> Boolean,
        failure: () -> Pair<ErrorCode?, String>,
        timeoutError: Pair<ErrorCode, String>
    ) {
        val deadline = System.currentTimeMillis() + STAGE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            val (code, detail) = failure()
            if (code != null) {
                diag { it.copy(failureStage = label.uppercase()) }
                throw StreamException(code, detail)
            }
            if (!running) {
                diag { it.copy(failureStage = "${label.uppercase()}_CONNECTION_CLOSED") }
                throw StreamException(
                    ErrorCode.NETWORK_LOST,
                    lastErrorDetail ?: "connection closed during $label"
                )
            }
            Thread.sleep(20)
        }
        diag { it.copy(failureStage = "${label.uppercase()}_TIMEOUT") }
        throw StreamException(timeoutError.first, timeoutError.second)
    }

    private fun handshake() {
        val out = output ?: throw StreamException(ErrorCode.RTMP_CONNECT_TIMEOUT, "no output stream")
        val inp = input ?: throw StreamException(ErrorCode.RTMP_CONNECT_TIMEOUT, "no input stream")
        try {
            val c1 = ByteArray(1536)
            SecureRandom().nextBytes(c1)
            for (i in 0 until 8) c1[i] = 0 // time + zero
            out.writeByte(3)
            out.write(c1)
            out.flush()

            val s0 = inp.readByte().toInt() and 0xFF
            if (s0 != 3) {
                diag { it.copy(handshakeResult = "FAIL (version $s0)", failureStage = "HANDSHAKE") }
                throw StreamException(ErrorCode.RTMP_HANDSHAKE_FAILED, "server handshake version $s0")
            }
            val s1 = ByteArray(1536)
            inp.readFully(s1)
            out.write(s1)
            out.flush()
            val s2 = ByteArray(1536)
            inp.readFully(s2)
            diag { it.copy(handshakeResult = "OK (simple C0/C1/C2)") }
        } catch (e: StreamException) {
            throw e
        } catch (t: Throwable) {
            diag {
                it.copy(
                    handshakeResult = "FAIL", failureStage = "HANDSHAKE",
                    lastException = t.message ?: "io"
                )
            }
            throw StreamException(ErrorCode.RTMP_HANDSHAKE_FAILED, t.message ?: "handshake failed")
        }
    }

    // ------------------------------------------------------------- commands

    private fun nextTransaction(name: String): Int {
        transactionId++
        pendingTransactions[transactionId] = name
        return transactionId
    }

    private fun sendSetChunkSize(size: Int) {
        val payload = byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(),
            (size ushr 8).toByte(), size.toByte()
        )
        sendMessage(CSID_CONTROL, RtmpMessage.TYPE_SET_CHUNK_SIZE, 0, 0, payload)
    }

    private fun sendWindowAckSize(size: Int) {
        val payload = byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(),
            (size ushr 8).toByte(), size.toByte()
        )
        sendMessage(CSID_CONTROL, RtmpMessage.TYPE_WINDOW_ACK_SIZE, 0, 0, payload)
    }

    private fun sendAcknowledgement(sequence: Long) {
        val payload = byteArrayOf(
            (sequence ushr 24).toByte(), (sequence ushr 16).toByte(),
            (sequence ushr 8).toByte(), sequence.toByte()
        )
        sendMessage(CSID_CONTROL, RtmpMessage.TYPE_ACK, 0, 0, payload)
    }

    private fun sendConnect(app: String, tcUrl: String) {
        connectTxId = nextTransaction("connect")
        val payload = Amf0.encode { out ->
            Amf0.writeString(out, "connect")
            Amf0.writeNumber(out, connectTxId.toDouble())
            Amf0.writeObject(
                out,
                listOf(
                    "app" to app,
                    "type" to "nonprivate",
                    "flashVer" to "FMLE/3.0 (compatible; LiveVIP/1.0)",
                    "tcUrl" to tcUrl,
                    "fpad" to false,
                    "capabilities" to 239.0,
                    "audioCodecs" to 3575.0,
                    "videoCodecs" to 252.0,
                    "videoFunction" to 1.0,
                    "objectEncoding" to 0.0
                )
            )
        }
        diag { it.copy(connectSent = true) }
        sendMessage(CSID_COMMAND, RtmpMessage.TYPE_COMMAND_AMF0, 0, 0, payload)
        onLog("-> connect(app=$app, tcUrl=$tcUrl, tx=$connectTxId)")
    }

    private fun sendReleaseStreamAndFcPublish() {
        val releaseTx = nextTransaction("releaseStream")
        sendMessage(
            CSID_COMMAND, RtmpMessage.TYPE_COMMAND_AMF0, 0, 0,
            Amf0.encode { out ->
                Amf0.writeString(out, "releaseStream")
                Amf0.writeNumber(out, releaseTx.toDouble())
                Amf0.writeNull(out)
                Amf0.writeString(out, streamKey)
            }
        )
        val fcTx = nextTransaction("FCPublish")
        sendMessage(
            CSID_COMMAND, RtmpMessage.TYPE_COMMAND_AMF0, 0, 0,
            Amf0.encode { out ->
                Amf0.writeString(out, "FCPublish")
                Amf0.writeNumber(out, fcTx.toDouble())
                Amf0.writeNull(out)
                Amf0.writeString(out, streamKey)
            }
        )
        onLog("-> releaseStream(tx=$releaseTx), FCPublish(tx=$fcTx) [key masked]")
    }

    private fun sendCreateStream() {
        createStreamTxId = nextTransaction("createStream")
        diag { it.copy(createStreamTxId = createStreamTxId, createStreamResult = "PENDING") }
        sendMessage(
            CSID_COMMAND, RtmpMessage.TYPE_COMMAND_AMF0, 0, 0,
            Amf0.encode { out ->
                Amf0.writeString(out, "createStream")
                Amf0.writeNumber(out, createStreamTxId.toDouble())
                Amf0.writeNull(out)
            }
        )
        onLog("-> createStream(tx=$createStreamTxId)")
    }

    private fun sendPublish() {
        val publishTx = nextTransaction("publish")
        sendMessage(
            CSID_COMMAND, RtmpMessage.TYPE_COMMAND_AMF0, 0, streamId,
            Amf0.encode { out ->
                Amf0.writeString(out, "publish")
                Amf0.writeNumber(out, publishTx.toDouble())
                Amf0.writeNull(out)
                Amf0.writeString(out, streamKey)
                Amf0.writeString(out, "live")
            }
        )
        onLog("-> publish(streamId=$streamId, tx=$publishTx) [key masked]")
    }

    fun sendMetadata(width: Int, height: Int, fps: Int, videoBitrateKbps: Int, audioSampleRate: Int) {
        val payload = Amf0.encode { out ->
            Amf0.writeString(out, "@setDataFrame")
            Amf0.writeString(out, "onMetaData")
            Amf0.writeEcmaArray(
                out,
                listOf(
                    "duration" to 0.0,
                    "width" to width.toDouble(),
                    "height" to height.toDouble(),
                    "videocodecid" to 7.0,
                    "videodatarate" to videoBitrateKbps.toDouble(),
                    "framerate" to fps.toDouble(),
                    "audiocodecid" to 10.0,
                    "audiodatarate" to 128.0,
                    "audiosamplerate" to audioSampleRate.toDouble(),
                    "audiosamplesize" to 16.0,
                    "stereo" to true,
                    "encoder" to "LIVE VIP"
                )
            )
        }
        sendMessage(CSID_COMMAND, RtmpMessage.TYPE_DATA_AMF0, 0, streamId, payload)
    }

    fun sendVideo(payload: ByteArray, timestampMs: Long) =
        sendMessage(CSID_VIDEO, RtmpMessage.TYPE_VIDEO, timestampMs, streamId, payload)

    fun sendAudio(payload: ByteArray, timestampMs: Long) =
        sendMessage(CSID_AUDIO, RtmpMessage.TYPE_AUDIO, timestampMs, streamId, payload)

    private fun sendMessage(csid: Int, type: Int, timestampMs: Long, msgStreamId: Int, payload: ByteArray) {
        val out = output ?: throw StreamException(ErrorCode.STREAM_SEND_FAILED, "socket closed")
        synchronized(outLock) {
            try {
                val extended = timestampMs >= 0xFFFFFF
                val tsField = if (extended) 0xFFFFFF else timestampMs.toInt()
                out.writeByte(csid and 0x3F) // fmt 0
                out.writeByte((tsField ushr 16) and 0xFF)
                out.writeByte((tsField ushr 8) and 0xFF)
                out.writeByte(tsField and 0xFF)
                out.writeByte((payload.size ushr 16) and 0xFF)
                out.writeByte((payload.size ushr 8) and 0xFF)
                out.writeByte(payload.size and 0xFF)
                out.writeByte(type)
                out.writeByte(msgStreamId and 0xFF)
                out.writeByte((msgStreamId ushr 8) and 0xFF)
                out.writeByte((msgStreamId ushr 16) and 0xFF)
                out.writeByte((msgStreamId ushr 24) and 0xFF)
                if (extended) out.writeInt(timestampMs.toInt())

                var offset = 0
                while (offset < payload.size) {
                    val size = minOf(OUT_CHUNK_SIZE, payload.size - offset)
                    if (offset > 0) {
                        out.writeByte(0xC0 or (csid and 0x3F))
                        if (extended) out.writeInt(timestampMs.toInt())
                    }
                    out.write(payload, offset, size)
                    offset += size
                }
                out.flush()
                bytesSent += payload.size.toLong() + 12
                packetsSent++
            } catch (t: IOException) {
                lastErrorCode = ErrorCode.STREAM_SEND_FAILED
                lastErrorDetail = t.message
                running = false
                throw StreamException(ErrorCode.STREAM_SEND_FAILED, t.message ?: "send failed")
            }
        }
    }

    // ---------------------------------------------------------------- reader

    private fun readLoop() {
        val r = reader ?: return
        try {
            while (running) {
                val message = r.readMessage() ?: break
                handleMessage(message)
                if (r.bytesReceived - lastAckSent >= windowAckSize / 2) {
                    lastAckSent = r.bytesReceived
                    runCatching { sendAcknowledgement(r.bytesReceived) }
                }
            }
        } catch (t: Throwable) {
            if (running) {
                lastErrorCode = ErrorCode.NETWORK_LOST
                lastErrorDetail = t.message
                diag { it.copy(lastException = t.message ?: "reader stopped") }
                onStatus("NetConnection.Closed")
            }
        } finally {
            running = false
        }
    }

    private fun handleMessage(message: RtmpMessage) {
        when (message.type) {
            RtmpMessage.TYPE_WINDOW_ACK_SIZE -> {
                if (message.payload.size >= 4) {
                    windowAckSize = ((message.payload[0].toLong() and 0xFF) shl 24) or
                        ((message.payload[1].toLong() and 0xFF) shl 16) or
                        ((message.payload[2].toLong() and 0xFF) shl 8) or
                        (message.payload[3].toLong() and 0xFF)
                }
            }
            RtmpMessage.TYPE_SET_PEER_BANDWIDTH -> sendWindowAckSize(windowAckSize.toInt())
            RtmpMessage.TYPE_COMMAND_AMF0 -> handleCommand(message)
            else -> Unit
        }
    }

    private fun handleCommand(message: RtmpMessage) {
        val values = Amf0.decodeAll(message.payload)
        val command = values.firstOrNull() as? String ?: return
        val txId = (values.getOrNull(1) as? Double)?.toInt() ?: 0
        val requested = pendingTransactions.remove(txId)
        val status = Amf0.findStatusObject(values)
        val code = status?.get("code") as? String
        val level = status?.get("level") as? String
        val description = status?.get("description") as? String

        when (command) {
            "_result" -> {
                when {
                    txId == connectTxId -> {
                        connected = true
                        diag {
                            it.copy(
                                connectResultCode = code ?: "NetConnection.Connect.Success",
                                connectDescription = description ?: "accepted"
                            )
                        }
                        note("<- _result(connect) ${code ?: "Success"}")
                        onStatus("NetConnection.Connect.Success")
                    }
                    txId == createStreamTxId -> {
                        val numbers = values.filterIsInstance<Double>()
                        val id = numbers.lastOrNull { it != txId.toDouble() }?.toInt()
                        if (id == null || id < 0) {
                            createStreamFailure = ErrorCode.CREATE_STREAM_INVALID_RESPONSE to
                                "createStream _result (tx=$txId) carried no stream id"
                            diag {
                                it.copy(
                                    createStreamResult = "INVALID RESPONSE",
                                    createStreamErrorDescription = "no numeric stream id in _result"
                                )
                            }
                        } else {
                            streamId = if (id == 0) 1 else id
                            note("<- _result(createStream) streamId=$streamId")
                        }
                    }
                    else -> note("<- _result(${requested ?: "tx$txId"})")
                }
            }
            "_error" -> {
                val detail = listOfNotNull(code, level, description).joinToString(" | ")
                    .ifEmpty { "server returned _error" }
                when {
                    txId == connectTxId -> {
                        lastErrorCode = if ((code ?: "").contains("Rejected", true) ||
                            (description ?: "").contains("auth", true)
                        ) ErrorCode.RTMP_AUTH_FAILED else ErrorCode.RTMP_SERVER_REJECTED
                        lastErrorDetail = detail
                        diag {
                            it.copy(
                                connectResultCode = code ?: "_error",
                                connectDescription = description ?: detail
                            )
                        }
                    }
                    txId == createStreamTxId -> {
                        createStreamFailure = ErrorCode.CREATE_STREAM_REJECTED to detail
                        diag {
                            it.copy(
                                createStreamResult = "REJECTED",
                                createStreamErrorCode = code ?: "_error",
                                createStreamErrorDescription = description ?: detail
                            )
                        }
                    }
                    else -> {
                        lastErrorCode = ErrorCode.RTMP_SERVER_REJECTED
                        lastErrorDetail = detail
                    }
                }
                note("<- _error(${requested ?: "tx$txId"}) $detail")
                onStatus("NetConnection.Connect.Rejected")
            }
            "onStatus" -> {
                val statusCode = code ?: return
                note("<- onStatus $statusCode (${level ?: "-"}) ${description ?: ""}")
                diag {
                    it.copy(
                        publishStatusCode = statusCode,
                        publishStatusLevel = level ?: "-",
                        publishStatusDescription = description ?: "-"
                    )
                }
                when {
                    statusCode == "NetStream.Publish.Start" -> publishAccepted = true
                    statusCode.contains("Publish.BadName") || statusCode.contains("Failed") ||
                        statusCode.contains("Rejected") || level == "error" -> {
                        publishFailure = ErrorCode.RTMP_PUBLISH_REJECTED to
                            "$statusCode: ${description ?: "server rejected publish"}"
                        lastErrorCode = ErrorCode.RTMP_PUBLISH_REJECTED
                        lastErrorDetail = statusCode
                    }
                }
                onStatus(statusCode)
            }
            "onBWDone", "onFCPublish", "onBWCheck" -> note("<- $command")
            else -> note("<- $command")
        }
    }

    val isHealthy: Boolean get() = running && connected && publishAccepted

    fun close() {
        running = false
        connected = false
        publishAccepted = false
        runCatching { output?.flush() }
        runCatching { socket?.close() }
        readerThread?.join(1000)
        readerThread = null
        input = null
        output = null
        socket = null
        reader = null
        streamId = 0
        pendingTransactions.clear()
        Log.d(TAG, "RTMP socket closed")
    }
}
