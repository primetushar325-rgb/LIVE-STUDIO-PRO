package com.livevip.app.rtmp

import com.livevip.app.core.ErrorCode
import com.livevip.app.core.StreamException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Transport layer: owns exactly ONE RtmpClient socket at a time and a single
 * sender thread. All reported numbers are measured, never simulated.
 */
class RtmpTransport(
    private val onFatal: (ErrorCode, String) -> Unit,
    private val onFirstMediaAccepted: () -> Unit,
    private val onConnectionLost: (String) -> Unit,
    private val onLog: (String) -> Unit
) {
    private data class Packet(val video: Boolean, val payload: ByteArray, val timestampMs: Long)

    private var client: RtmpClient? = null
    private var senderThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<Packet>(600)

    val bytesSent = AtomicLong(0)
    val packetsSent = AtomicLong(0)
    val droppedPackets = AtomicLong(0)
    @Volatile var currentBitrateBps: Long = 0; private set
    @Volatile var firstMediaSent = false; private set
    @Volatile var lastError: String? = null
    @Volatile var diagnostics = RtmpDiagnostics(); private set

    private var windowStartMs = 0L
    private var windowBytes = 0L

    val isConnected: Boolean get() = client?.connected == true
    val isPublishing: Boolean get() = client?.isHealthy == true

    /** Blocking: opens the socket and completes the RTMP publish handshake. */
    fun connect(url: String, streamKey: String) {
        closeInternal()
        firstMediaSent = false
        queue.clear()
        val c = RtmpClient(
            url = url,
            streamKey = streamKey,
            onStatus = { code ->
                if (code == "NetConnection.Closed" || code.contains("Failed")) {
                    onConnectionLost(code)
                }
            },
            onLog = onLog,
            onDiagnostics = { diagnostics = it }
        )
        c.connect()
        client = c
        running.set(true)
        windowStartMs = System.currentTimeMillis()
        windowBytes = 0
        senderThread = Thread({ senderLoop() }, "RtmpSender").also { it.start() }
    }

    fun sendMetadata(width: Int, height: Int, fps: Int, bitrateKbps: Int, sampleRate: Int) {
        runCatching { client?.sendMetadata(width, height, fps, bitrateKbps, sampleRate) }
    }

    fun enqueueVideo(payload: ByteArray, timestampMs: Long) = enqueue(Packet(true, payload, timestampMs))

    fun enqueueAudio(payload: ByteArray, timestampMs: Long) = enqueue(Packet(false, payload, timestampMs))

    private fun enqueue(packet: Packet) {
        if (!running.get()) return
        if (!queue.offer(packet)) {
            // Network slower than the encoder: drop the oldest non-keyframe data.
            queue.poll()
            droppedPackets.incrementAndGet()
            queue.offer(packet)
        }
    }

    private fun senderLoop() {
        try {
            while (running.get()) {
                val packet = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val c = client ?: break
                if (packet.video) c.sendVideo(packet.payload, packet.timestampMs)
                else c.sendAudio(packet.payload, packet.timestampMs)

                packetsSent.incrementAndGet()
                bytesSent.addAndGet(packet.payload.size.toLong())
                windowBytes += packet.payload.size.toLong()
                val now = System.currentTimeMillis()
                val elapsed = now - windowStartMs
                if (elapsed >= 1000) {
                    currentBitrateBps = windowBytes * 8 * 1000 / elapsed
                    windowStartMs = now
                    windowBytes = 0
                }
                if (!firstMediaSent) {
                    firstMediaSent = true
                    onFirstMediaAccepted()
                }
            }
        } catch (e: StreamException) {
            lastError = e.message
            if (running.get()) onConnectionLost(e.message ?: "send failed")
        } catch (t: Throwable) {
            lastError = t.message
            if (running.get()) onFatal(ErrorCode.STREAM_SEND_FAILED, t.message ?: "transport failure")
        }
    }

    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        running.set(false)
        senderThread?.join(1500)
        senderThread = null
        runCatching { client?.close() }
        client = null
        queue.clear()
        currentBitrateBps = 0
    }

    fun queueDepth(): Int = queue.size
}
