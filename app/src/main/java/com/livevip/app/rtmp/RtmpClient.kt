package com.livevip.app.rtmp

import android.util.Log
import com.livevip.app.core.ErrorCode
import com.livevip.app.core.StreamException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import javax.net.ssl.SSLSocketFactory

/**
 * Direct RTMP / RTMPS client. No relay, no intermediate server:
 * the phone opens the socket to the ingest endpoint itself.
 */
class RtmpClient(
    private val url: String,
    private val streamKey: String,
    private val onStatus: (code: String) -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "RtmpClient"
        private const val CHUNK_SIZE = 4096
        private const val CSID_CONTROL = 2
        private const val CSID_COMMAND = 3
        private const val CSID_AUDIO = 4
        private const val CSID_VIDEO = 6
        private const val MSG_SET_CHUNK_SIZE = 1
        private const val MSG_ACK = 3
        private const val MSG_WINDOW_ACK = 5
        private const val MSG_SET_PEER_BW = 6
        private const val MSG_AUDIO = 8
        private const val MSG_VIDEO = 9
        private const val MSG_DATA_AMF0 = 18
        private const val MSG_COMMAND_AMF0 = 20
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var streamId = 0
    private var transactionId = 0
    private var readerThread: Thread? = null

    @Volatile var connected = false; private set
    @Volatile var publishAccepted = false; private set
    @Volatile var bytesSent = 0L; private set
    @Volatile var packetsSent = 0L; private set
    @Volatile var lastErrorCode: ErrorCode? = null
    @Volatile var lastErrorDetail: String? = null
    @Volatile private var running = false

    private val outLock = Any()
    private val lastTimestamps = HashMap<Int, Long>()

    /** Blocking connect + publish. Throws StreamException on failure. */
    fun connect() {
        val uri = try {
            URI(url.trim())
        } catch (t: Throwable) {
            throw StreamException(ErrorCode.RTMP_URL_INVALID, "Malformed server URL")
        }
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme != "rtmp" && scheme != "rtmps") {
            throw StreamException(ErrorCode.RTMP_URL_INVALID, "URL must start with rtmp:// or rtmps://")
        }
        if (streamKey.isBlank()) {
            throw StreamException(ErrorCode.RTMP_URL_INVALID, "Stream key is empty")
        }
        val host = uri.host ?: throw StreamException(ErrorCode.RTMP_URL_INVALID, "No host in URL")
        val port = if (uri.port > 0) uri.port else if (scheme == "rtmps") 443 else 1935
        val app = (uri.path ?: "").trim('/')
        if (app.isEmpty()) throw StreamException(ErrorCode.RTMP_URL_INVALID, "No application in URL")

        try {
            val raw = Socket()
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            val s = if (scheme == "rtmps") {
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, port, true).also { (it as javax.net.ssl.SSLSocket).startHandshake() }
            } else raw
            socket = s
            input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
            output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 256 * 1024))
        } catch (t: SocketTimeoutException) {
            throw StreamException(ErrorCode.RTMP_CONNECT_TIMEOUT, "TCP connect timed out")
        } catch (t: IOException) {
            throw StreamException(ErrorCode.RTMP_CONNECT_TIMEOUT, t.message ?: "TCP connect failed")
        }

        handshake()
        running = true
        readerThread = Thread({ readLoop() }, "RtmpReader").also { it.isDaemon = true; it.start() }

        sendSetChunkSize(CHUNK_SIZE)
        sendConnect(app, "$scheme://$host:$port/$app")
        waitFor({ connected }, 10_000, ErrorCode.RTMP_SERVER_REJECTED, "connect() was not accepted")
        sendReleaseStreamAndFcPublish()
        sendCreateStream()
        waitFor({ streamId != 0 }, 10_000, ErrorCode.RTMP_SERVER_REJECTED, "createStream failed")
        sendPublish()
        waitFor(
            { publishAccepted }, 10_000, ErrorCode.RTMP_SERVER_REJECTED,
            "server did not confirm NetStream.Publish.Start"
        )
        onLog("RTMP publish accepted by $host")
    }

    private fun waitFor(cond: () -> Boolean, timeoutMs: Long, code: ErrorCode, detail: String) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            if (!running) throw StreamException(code, lastErrorDetail ?: "connection closed")
            Thread.sleep(25)
        }
        throw StreamException(code, detail)
    }

    private fun handshake() {
        val out = output ?: throw StreamException(ErrorCode.RTMP_CONNECT_TIMEOUT, "no output stream")
        val inp = input ?: throw StreamException(ErrorCode.RTMP_CONNECT_TIMEOUT, "no input stream")
        val c1 = ByteArray(1536)
        SecureRandom().nextBytes(c1)
        // time = 0, zero = 0
        for (i in 0 until 8) c1[i] = 0
        out.writeByte(3)
        out.write(c1)
        out.flush()

        val s0 = inp.readByte().toInt() and 0xFF
        if (s0 != 3) throw StreamException(ErrorCode.RTMP_SERVER_REJECTED, "bad handshake version $s0")
        val s1 = ByteArray(1536)
        inp.readFully(s1)
        out.write(s1) // C2 echoes S1
        out.flush()
        val s2 = ByteArray(1536)
        inp.readFully(s2)
    }

    private fun sendSetChunkSize(size: Int) {
        val payload = ByteArray(4)
        payload[0] = (size ushr 24).toByte()
        payload[1] = (size ushr 16).toByte()
        payload[2] = (size ushr 8).toByte()
        payload[3] = size.toByte()
        sendMessage(CSID_CONTROL, MSG_SET_CHUNK_SIZE, 0, 0, payload)
    }

    private fun sendConnect(app: String, tcUrl: String) {
        transactionId = 1
        val payload = Amf0.encode { out ->
            Amf0.writeString(out, "connect")
            Amf0.writeNumber(out, transactionId.toDouble())
            Amf0.writeObject(
                out,
                listOf(
                    "app" to app,
                    "type" to "nonprivate",
                    "flashVer" to "FMLE/3.0 (compatible; LiveVIP)",
                    "tcUrl" to tcUrl
                )
            )
        }
        sendMessage(CSID_COMMAND, MSG_COMMAND_AMF0, 0, 0, payload)
    }

    private fun sendReleaseStreamAndFcPublish() {
        transactionId++
        sendMessage(
            CSID_COMMAND, MSG_COMMAND_AMF0, 0, 0,
            Amf0.encode { out ->
                Amf0.writeString(out, "releaseStream")
                Amf0.writeNumber(out, transactionId.toDouble())
                Amf0.writeNull(out)
                Amf0.writeString(out, streamKey)
            }
        )
        transactionId++
        sendMessage(
            CSID_COMMAND, MSG_COMMAND_AMF0, 0, 0,
            Amf0.encode { out ->
                Amf0.writeString(out, "FCPublish")
                Amf0.writeNumber(out, transactionId.toDouble())
                Amf0.writeNull(out)
                Amf0.writeString(out, streamKey)
            }
        )
    }

    private fun sendCreateStream() {
        transactionId++
        sendMessage(
            CSID_COMMAND, MSG_COMMAND_AMF0, 0, 0,
            Amf0.encode { out ->
                Amf0.writeString(out, "createStream")
                Amf0.writeNumber(out, transactionId.toDouble())
                Amf0.writeNull(out)
            }
        )
    }

    private fun sendPublish() {
        transactionId++
        sendMessage(
            CSID_COMMAND, MSG_COMMAND_AMF0, 0, streamId,
            Amf0.encode { out ->
                Amf0.writeString(out, "publish")
                Amf0.writeNumber(out, transactionId.toDouble())
                Amf0.writeNull(out)
                Amf0.writeString(out, streamKey)
                Amf0.writeString(out, "live")
            }
        )
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
        sendMessage(CSID_COMMAND, MSG_DATA_AMF0, 0, streamId, payload)
    }

    fun sendVideo(payload: ByteArray, timestampMs: Long) {
        sendMessage(CSID_VIDEO, MSG_VIDEO, timestampMs, streamId, payload)
    }

    fun sendAudio(payload: ByteArray, timestampMs: Long) {
        sendMessage(CSID_AUDIO, MSG_AUDIO, timestampMs, streamId, payload)
    }

    private fun sendMessage(csid: Int, type: Int, timestampMs: Long, msgStreamId: Int, payload: ByteArray) {
        val out = output ?: throw StreamException(ErrorCode.STREAM_SEND_FAILED, "socket closed")
        synchronized(outLock) {
            try {
                val extended = timestampMs >= 0xFFFFFF
                val tsField = if (extended) 0xFFFFFF else timestampMs.toInt()
                // fmt 0 header
                out.writeByte(csid and 0x3F)
                out.writeByte((tsField ushr 16) and 0xFF)
                out.writeByte((tsField ushr 8) and 0xFF)
                out.writeByte(tsField and 0xFF)
                out.writeByte((payload.size ushr 16) and 0xFF)
                out.writeByte((payload.size ushr 8) and 0xFF)
                out.writeByte(payload.size and 0xFF)
                out.writeByte(type)
                // message stream id, little endian
                out.writeByte(msgStreamId and 0xFF)
                out.writeByte((msgStreamId ushr 8) and 0xFF)
                out.writeByte((msgStreamId ushr 16) and 0xFF)
                out.writeByte((msgStreamId ushr 24) and 0xFF)
                if (extended) out.writeInt(timestampMs.toInt())

                var offset = 0
                while (offset < payload.size) {
                    val size = minOf(CHUNK_SIZE, payload.size - offset)
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
                lastTimestamps[csid] = timestampMs
            } catch (t: IOException) {
                lastErrorCode = ErrorCode.STREAM_SEND_FAILED
                lastErrorDetail = t.message
                running = false
                throw StreamException(ErrorCode.STREAM_SEND_FAILED, t.message ?: "send failed")
            }
        }
    }

    private fun readLoop() {
        val inp = input ?: return
        try {
            while (running) {
                val basic = inp.read()
                if (basic < 0) break
                val fmt = (basic ushr 6) and 0x03
                var csid = basic and 0x3F
                if (csid == 0) csid = (inp.read() and 0xFF) + 64
                else if (csid == 1) {
                    val b0 = inp.read() and 0xFF
                    val b1 = inp.read() and 0xFF
                    csid = (b1 shl 8) + b0 + 64
                }
                var length = 0
                var type = 0
                when (fmt) {
                    0 -> {
                        val header = ByteArray(11); inp.readFully(header)
                        length = ((header[3].toInt() and 0xFF) shl 16) or
                            ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
                        type = header[6].toInt() and 0xFF
                    }
                    1 -> {
                        val header = ByteArray(7); inp.readFully(header)
                        length = ((header[3].toInt() and 0xFF) shl 16) or
                            ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
                        type = header[6].toInt() and 0xFF
                    }
                    2 -> inp.skipBytes(3)
                    3 -> { }
                }
                if (length <= 0) continue
                val payload = ByteArray(length)
                var read = 0
                while (read < length) {
                    val chunk = minOf(CHUNK_SIZE, length - read)
                    inp.readFully(payload, read, chunk)
                    read += chunk
                    if (read < length) inp.read() // continuation basic header
                }
                handleMessage(type, payload)
            }
        } catch (t: Throwable) {
            if (running) {
                lastErrorCode = ErrorCode.NETWORK_LOST
                lastErrorDetail = t.message
                onStatus("NetConnection.Closed")
            }
        } finally {
            running = false
        }
    }

    private fun handleMessage(type: Int, payload: ByteArray) {
        when (type) {
            MSG_COMMAND_AMF0 -> {
                val strings = Amf0.readStrings(payload)
                val command = strings.firstOrNull() ?: return
                when (command) {
                    "_result" -> {
                        if (!connected) {
                            connected = true
                            onStatus("NetConnection.Connect.Success")
                        } else if (streamId == 0) {
                            val numbers = Amf0.readNumbers(payload)
                            streamId = numbers.lastOrNull()?.toInt() ?: 1
                            if (streamId == 0) streamId = 1
                        }
                    }
                    "_error" -> {
                        val detail = strings.joinToString(" ")
                        lastErrorCode = if (detail.contains("auth", true) ||
                            detail.contains("Unauthorized", true)
                        ) ErrorCode.RTMP_AUTH_FAILED else ErrorCode.RTMP_SERVER_REJECTED
                        lastErrorDetail = detail
                        onStatus("NetConnection.Connect.Rejected")
                    }
                    "onStatus" -> {
                        val code = strings.firstOrNull { it.startsWith("NetStream.") } ?: return
                        onLog("RTMP status $code")
                        if (code == "NetStream.Publish.Start") publishAccepted = true
                        if (code.contains("Failed") || code.contains("BadName") ||
                            code.contains("Rejected")
                        ) {
                            lastErrorCode = ErrorCode.RTMP_SERVER_REJECTED
                            lastErrorDetail = code
                        }
                        onStatus(code)
                    }
                }
            }
            MSG_WINDOW_ACK, MSG_SET_PEER_BW, MSG_SET_CHUNK_SIZE, MSG_ACK -> Unit
            else -> Unit
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
        streamId = 0
        Log.d(TAG, "RTMP socket closed")
    }
}
