package com.livevip.app.engine

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import android.view.Surface
import com.livevip.app.core.CompositionState
import com.livevip.app.core.ErrorCode
import com.livevip.app.core.FitMode
import com.livevip.app.core.MasterClock
import com.livevip.app.core.StreamError
import com.livevip.app.core.StreamException
import com.livevip.app.core.StreamState
import com.livevip.app.data.StreamProfile
import com.livevip.app.gl.LiveCompositor
import com.livevip.app.audio.AudioPipeline
import com.livevip.app.encoder.VideoEncoderController
import com.livevip.app.rtmp.FlvPackager
import com.livevip.app.rtmp.RtmpTransport
import com.livevip.app.video.PlaybackEvents
import com.livevip.app.video.VideoSourceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The ONE owner of the streaming lifecycle. It lives inside the foreground
 * service, never inside an Activity, so the stream survives minimise,
 * screen-off and activity destruction.
 */
class LiveStreamingEngine(private val context: Context) {

    companion object {
        private const val TAG = "LiveEngine"
        @Volatile private var instance: LiveStreamingEngine? = null
        fun get(context: Context): LiveStreamingEngine =
            instance ?: synchronized(this) {
                instance ?: LiveStreamingEngine(context.applicationContext).also { instance = it }
            }
    }

    val compositor = LiveCompositor()
    private val clock = MasterClock()
    private val reconnect = ReconnectController()
    private val network = NetworkController(context)

    private var videoSource: VideoSourceController? = null
    private var encoder: VideoEncoderController? = null
    private var audio: AudioPipeline? = null
    private var transport: RtmpTransport? = null
    private var watchdog: Thread? = null

    private val liveRequested = AtomicBoolean(false)
    private var profile: StreamProfile? = null
    private var streamKey: String = ""
    private var videoConfig: Pair<ByteArray, ByteArray>? = null
    private var audioConfig: ByteArray? = null
    private var maxDurationMs: Long = 0
    private var audioSampleRate = 44100
    private var audioChannels = 2
    @Volatile private var sourcePcmRate = 44100

    private val _state = MutableStateFlow(StreamState.IDLE)
    val state: StateFlow<StreamState> = _state

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    @Volatile var lastError: String? = null
        private set
    @Volatile var statusMessage: String? = null
        private set
    @Volatile var currentProfileId: String? = null
        private set
    @Volatile private var currentVideoName: String = "-"

    // ---------------------------------------------------------------- preview

    /** Preview works fully offline: no network, no stream key, no RTMP needed. */
    fun startPreview(profile: StreamProfile) {
        if (_state.value.isActive && liveRequested.get()) return
        this.profile = profile
        currentProfileId = profile.id
        compositor.start()
        compositor.ptsProviderUs = { clock.nowUs() }
        applyComposition(profile)
        if (!clock.started) clock.start()
        if (videoSource?.isRunning != true) {
            val source = VideoSourceController(context, compositor, clock, playbackEvents)
            videoSource = source
            source.start(profile.playlist, profile.loopMode, profile.loopCount, profile.videoAudioEnabled)
        }
        startStatsPump()
        log("Preview started (${profile.playlist.size} video(s))")
    }

    fun stopPreview() {
        if (liveRequested.get()) return
        videoSource?.stop()
        videoSource = null
        clock.reset()
        compositor.detachPreview()
        log("Preview stopped")
    }

    fun attachPreviewSurface(surface: Surface, width: Int, height: Int) {
        compositor.start()
        compositor.attachPreview(surface, width, height)
    }

    fun detachPreviewSurface() = compositor.detachPreview()

    fun updateComposition(transform: (CompositionState) -> CompositionState) {
        compositor.composition = transform(compositor.composition).clamped()
    }

    fun composition(): CompositionState = compositor.composition

    private fun applyComposition(profile: StreamProfile) {
        compositor.composition = CompositionState(
            sourceWidth = compositor.composition.sourceWidth,
            sourceHeight = compositor.composition.sourceHeight,
            outputWidth = profile.resolution.width,
            outputHeight = profile.resolution.height,
            scale = profile.transform.scale,
            translationX = profile.transform.translationX,
            translationY = profile.transform.translationY,
            fitMode = if (profile.transform.fill) FitMode.FILL else FitMode.FIT
        )
    }

    // ------------------------------------------------------------------- live

    /**
     * Pre-flight validation. RTMP is never opened unless every required
     * condition is satisfied; failures surface the exact error code.
     */
    fun validate(profile: StreamProfile, streamKey: String): StreamError? {
        if (profile.playlist.isEmpty() && !profile.useCamera) {
            return StreamError(ErrorCode.VIDEO_URI_INVALID, "Playlist is empty")
        }
        if (profile.resolution.width % 2 != 0 || profile.resolution.height % 2 != 0 ||
            profile.resolution.width < 128 || profile.resolution.height < 128
        ) {
            return StreamError(ErrorCode.UNSUPPORTED_FORMAT, "Output resolution not supported")
        }
        val url = profile.serverUrl.trim()
        if (!url.startsWith("rtmp://") && !url.startsWith("rtmps://")) {
            return StreamError(ErrorCode.RTMP_URL_INVALID, "Server URL must start with rtmp:// or rtmps://")
        }
        if (runCatching { java.net.URI(url).host }.getOrNull().isNullOrBlank()) {
            return StreamError(ErrorCode.RTMP_URL_INVALID, "Server URL has no host")
        }
        if (streamKey.isBlank()) {
            return StreamError(ErrorCode.STREAM_KEY_MISSING, "Stream key is not set for this profile")
        }
        if (profile.microphoneEnabled &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return StreamError(ErrorCode.AUDIO_INIT_FAILED, "Microphone permission not granted")
        }
        return null
    }

    fun startLive(profile: StreamProfile, streamKey: String) {
        if (liveRequested.get()) return
        validate(profile, streamKey)?.let { error ->
            lastError = error.toString()
            log("Pre-flight validation failed: $error")
            _state.value = StreamState.ERROR
            publishStats()
            return
        }
        this.profile = profile
        this.streamKey = streamKey
        this.currentProfileId = profile.id
        this.maxDurationMs = profile.maxDurationMinutes * 60_000L
        liveRequested.set(true)
        lastError = null
        statusMessage = null
        reconnect.reset()
        Thread({ prepareAndStart(profile) }, "LiveStart").start()
    }

    private fun prepareAndStart(profile: StreamProfile) {
        try {
            setState(StreamState.PREPARING)
            startStatsPump()
            network.start(
                onAvailable = { log("Network available (${network.transportName})") },
                onLost = { onNetworkLost("OS reported network loss") }
            )

            compositor.start()
            compositor.ptsProviderUs = { clock.videoPts(clock.nowUs()) }
            applyComposition(profile)
            if (!compositor.eglInitialized) {
                throw StreamException(ErrorCode.RESOURCE_ERROR, compositor.lastError ?: "EGL unavailable")
            }

            // 1. source
            clock.reset()
            clock.start()
            if (videoSource?.isRunning != true) {
                val source = VideoSourceController(context, compositor, clock, playbackEvents)
                videoSource = source
                source.start(profile.playlist, profile.loopMode, profile.loopCount, profile.videoAudioEnabled)
            }
            waitUntil({ compositor.firstFrameReceived }, VideoSourceController.FIRST_FRAME_TIMEOUT_MS) {
                throw StreamException(ErrorCode.NO_VIDEO_FRAME, "No video frame produced before live start")
            }
            setState(StreamState.VIDEO_READY)

            // 2. audio
            val ap = AudioPipeline(
                clock = clock,
                sampleRate = audioSampleRate,
                channelCount = 2,
                micEnabled = profile.microphoneEnabled,
                videoAudioEnabled = profile.videoAudioEnabled,
                onAudioConfig = { cfg ->
                    audioConfig = cfg
                    transport?.let { t ->
                        if (t.isPublishing) t.enqueueAudio(FlvPackager.aacSequenceHeader(cfg), 0)
                    }
                },
                onAacFrame = { buffer, info -> onAacFrame(buffer, info) },
                onError = { code, detail -> fail(code, detail) }
            )
            audio = ap
            ap.prepare()
            if (!ap.audioReady) throw StreamException(ErrorCode.AUDIO_INIT_FAILED, "AAC encoder unavailable")
            ap.start()
            setState(StreamState.AUDIO_READY)

            // 3. encoder (created once, reused across playlist items and loops)
            val enc = VideoEncoderController(
                width = profile.resolution.width,
                height = profile.resolution.height,
                fps = profile.fps,
                bitrateBps = profile.bitrateKbps * 1000,
                keyframeIntervalSec = profile.keyframeIntervalSec,
                onFormat = { sps, pps ->
                    videoConfig = sps to pps
                    transport?.let { t ->
                        if (t.isPublishing) t.enqueueVideo(FlvPackager.avcSequenceHeader(sps, pps), 0)
                    }
                },
                onEncodedFrame = { buffer, info, keyframe -> onEncodedVideo(buffer, info, keyframe) },
                onError = { code, detail -> fail(code, detail) }
            )
            encoder = enc
            enc.prepare()
            val encSurface = enc.inputSurface
                ?: throw StreamException(ErrorCode.ENCODER_UNAVAILABLE, "No encoder input surface")
            compositor.attachEncoder(encSurface)
            enc.start()
            setState(StreamState.ENCODER_READY)

            // 4. transport
            connectTransport()

            startWatchdog()
        } catch (e: StreamException) {
            fail(e.code, e.message ?: e.code.name)
        } catch (t: Throwable) {
            fail(ErrorCode.RESOURCE_ERROR, t.message ?: "start failed")
        }
    }

    private fun connectTransport() {
        val p = profile ?: return
        setState(StreamState.CONNECTING)
        val t = RtmpTransport(
            onFatal = { code, detail -> fail(code, detail) },
            onFirstMediaAccepted = {
                if (_state.value != StreamState.STREAMING) setState(StreamState.SENDING)
            },
            onConnectionLost = { detail -> onNetworkLost(detail) },
            onLog = { log(it) }
        )
        transport = t
        t.connect(p.serverUrl, streamKey)
        setState(StreamState.CONNECTED)
        t.sendMetadata(p.resolution.width, p.resolution.height, p.fps, p.bitrateKbps, audioSampleRate)
        videoConfig?.let { (sps, pps) -> t.enqueueVideo(FlvPackager.avcSequenceHeader(sps, pps), 0) }
        audioConfig?.let { cfg -> t.enqueueAudio(FlvPackager.aacSequenceHeader(cfg), 0) }
        encoder?.requestKeyframe()
        reconnect.onReconnected()
        log("RTMP connected and publishing")
    }

    private fun onEncodedVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo, keyframe: Boolean) {
        val t = transport ?: return
        if (!t.isPublishing) return
        val data = ByteArray(info.size)
        buffer.get(data)
        val ptsUs = clock.videoPts(info.presentationTimeUs)
        if (keyframe && videoConfig != null && t.packetsSent.get() == 0L) {
            val (sps, pps) = videoConfig!!
            t.enqueueVideo(FlvPackager.avcSequenceHeader(sps, pps), 0)
        }
        t.enqueueVideo(FlvPackager.videoTag(data, keyframe), ptsUs / 1000)
        if (_state.value == StreamState.SENDING || _state.value == StreamState.CONNECTED) {
            if (t.packetsSent.get() > 5) setState(StreamState.STREAMING)
        }
    }

    private fun onAacFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val t = transport ?: return
        if (!t.isPublishing) return
        val data = ByteArray(info.size)
        buffer.get(data)
        t.enqueueAudio(FlvPackager.aacTag(data), info.presentationTimeUs / 1000)
    }

    // -------------------------------------------------------------- reconnect

    @Volatile private var reconnecting = false

    private fun onNetworkLost(detail: String) {
        if (!liveRequested.get() || reconnecting) return
        reconnecting = true
        setState(StreamState.NETWORK_LOST)
        log("Connection lost: $detail")
        Thread({
            try {
                while (liveRequested.get()) {
                    val delay = reconnect.nextDelayMs()
                    if (delay == null) {
                        fail(ErrorCode.NETWORK_LOST, "Reconnect attempts exhausted")
                        return@Thread
                    }
                    setState(StreamState.RECONNECTING)
                    log("Reconnecting in ${delay / 1000}s (attempt ${reconnect.attempt})")
                    Thread.sleep(delay)
                    if (!liveRequested.get()) return@Thread
                    // Always close the stale socket before creating a new one.
                    runCatching { transport?.close() }
                    transport = null
                    try {
                        connectTransport()
                        reconnecting = false
                        return@Thread
                    } catch (e: StreamException) {
                        log("Reconnect failed: ${e.code}")
                    } catch (t: Throwable) {
                        log("Reconnect failed: ${t.message}")
                    }
                }
            } finally {
                reconnecting = false
            }
        }, "RtmpReconnect").start()
    }

    // --------------------------------------------------------------- watchdog

    private fun startWatchdog() {
        if (watchdog != null) return
        watchdog = Thread({
            try {
                while (liveRequested.get()) {
                    Thread.sleep(1000)
                    if (maxDurationMs > 0 && clock.elapsedMs() >= maxDurationMs) {
                        statusMessage = "Maximum live duration reached."
                        log("Maximum live duration reached — stopping gracefully")
                        stopLive(auto = true)
                        return@Thread
                    }
                    val src = videoSource
                    if (src != null && !src.isRunning && _state.value.isActive) {
                        statusMessage = "Playlist finished."
                        log("Playlist finished — stopping gracefully")
                        stopLive(auto = true)
                        return@Thread
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "LiveWatchdog").also { it.start() }
    }

    // ------------------------------------------------------------------- stop

    private var statsThread: Thread? = null

    private fun startStatsPump() {
        if (statsThread != null) return
        statsThread = Thread({
            while (true) {
                try {
                    Thread.sleep(500)
                    publishStats()
                    if (!liveRequested.get() && videoSource?.isRunning != true &&
                        !_state.value.isActive
                    ) {
                        publishStats()
                        break
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                }
            }
            statsThread = null
        }, "StatsPump").also { it.start() }
    }

    private fun publishStats() {
        val src = videoSource
        val enc = encoder
        val t = transport
        val ap = audio
        _stats.value = StreamStats(
            state = _state.value,
            elapsedMs = clock.elapsedMs(),
            maxDurationMs = maxDurationMs,
            videoReady = src?.decoderInitialized == true,
            decoderReady = src?.decoderInitialized == true,
            firstFrame = compositor.firstFrameReceived,
            previewFrames = compositor.previewFrameCount.get(),
            encoderReady = enc?.encoderReady == true,
            encoderName = enc?.codecName ?: "-",
            encodedFrames = enc?.encodedFrameCount?.get() ?: 0,
            encodedBytes = enc?.encodedBytes?.get() ?: 0,
            actualFps = enc?.actualFps ?: 0f,
            droppedFrames = (enc?.droppedFrames?.get() ?: 0) + (t?.droppedPackets?.get() ?: 0),
            audioReady = ap?.audioReady == true,
            audioFrames = ap?.encodedFrames?.get() ?: 0,
            rtmpConnected = t?.isConnected == true,
            rtmpPublishing = t?.isPublishing == true,
            packetsSent = t?.packetsSent?.get() ?: 0,
            bytesSent = t?.bytesSent?.get() ?: 0,
            bitrateBps = t?.currentBitrateBps ?: 0,
            queueDepth = t?.queueDepth() ?: 0,
            avOffsetMs = clock.avOffsetMs(),
            loopIndex = src?.loopIndex ?: 0,
            currentVideoIndex = src?.currentIndex ?: 0,
            currentVideoName = currentVideoName,
            playlistSize = profile?.playlist?.size ?: 0,
            loopTarget = when (profile?.loopMode) {
                com.livevip.app.data.LoopMode.UNLIMITED -> "∞"
                com.livevip.app.data.LoopMode.COUNT -> "${profile?.loopCount}"
                else -> "1"
            },
            statusMessage = statusMessage,
            reconnectCount = reconnect.totalReconnects,
            networkTransport = network.transportName,
            sourceWidth = src?.sourceWidth ?: 0,
            sourceHeight = src?.sourceHeight ?: 0,
            outputWidth = compositor.composition.outputWidth,
            outputHeight = compositor.composition.outputHeight,
            lastError = lastError
        )
    }

    fun stopLive(auto: Boolean = false) {
        if (!liveRequested.getAndSet(false)) {
            // Not live: make sure preview resources are freed too.
            releaseAll()
            setState(StreamState.STOPPED)
            return
        }
        setState(StreamState.STOPPING)
        log(if (auto) "Automatic stop" else "Stop requested by user")
        releaseAll()
        setState(StreamState.STOPPED)
        publishStats()
    }

    private fun releaseAll() {
        runCatching { watchdog?.interrupt() }
        watchdog = null
        runCatching { transport?.close() }
        transport = null
        runCatching { audio?.release() }
        audio = null
        runCatching { compositor.detachEncoder() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { videoSource?.stop() }
        videoSource = null
        runCatching { network.stop() }
        clock.reset()
        videoConfig = null
        audioConfig = null
        compositor.previewFrameCount.set(0)
        compositor.encoderFrameCount.set(0)
    }

    /** Full teardown, used when the service itself goes away. */
    fun shutdown() {
        stopLive()
        compositor.release()
        setState(StreamState.IDLE)
    }

    // ------------------------------------------------------------------ utils

    private fun waitUntil(cond: () -> Boolean, timeoutMs: Long, onTimeout: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        onTimeout()
    }

    private fun setState(state: StreamState) {
        _state.value = state
        publishStats()
    }

    private fun fail(code: ErrorCode, detail: String) {
        lastError = "$code: $detail"
        Log.e(TAG, "FAIL $code $detail")
        log("ERROR $code — $detail")
        liveRequested.set(false)
        releaseAll()
        _state.value = StreamState.ERROR
        publishStats()
    }

    private fun log(message: String) {
        val line = "${StreamStats.formatDuration(clock.elapsedMs())}  $message"
        _logs.value = (_logs.value + line).takeLast(200)
        Log.i(TAG, message)
    }

    private val playbackEvents = object : PlaybackEvents {
        override fun onSourceReady(width: Int, height: Int, durationUs: Long) {
            compositor.composition = compositor.composition.copy(
                sourceWidth = width, sourceHeight = height
            )
        }

        override fun onFirstFrame() {
            log("First decoded frame received")
        }

        override fun onItemStarted(index: Int, name: String) {
            currentVideoName = name
            log("Playing item ${index + 1}: $name")
        }

        override fun onLoopCompleted(loopIndex: Int) {
            log("Loop $loopIndex completed (no encoder/RTMP restart)")
        }

        override fun onPlaylistFinished() {
            log("Playlist finished")
        }

        override fun onAudioFormat(sampleRate: Int, channels: Int) {
            sourcePcmRate = sampleRate
            audioChannels = channels
        }

        override fun onPcm(data: ByteArray, size: Int, presentationTimeUs: Long) {
            audio?.pushVideoPcm(data, size, sourcePcmRate, audioChannels)
        }

        override fun onError(code: ErrorCode, detail: String) {
            if (liveRequested.get()) fail(code, detail) else {
                lastError = "$code: $detail"
                log("ERROR $code — $detail")
                _state.value = StreamState.ERROR
            }
        }
    }

}
