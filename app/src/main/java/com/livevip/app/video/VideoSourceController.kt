package com.livevip.app.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.livevip.app.core.ErrorCode
import com.livevip.app.core.MasterClock
import com.livevip.app.data.LoopMode
import com.livevip.app.data.VideoItem
import com.livevip.app.gl.LiveCompositor
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns decoding of the video playlist.
 *
 * The playlist is treated as ONE continuous source: switching items or looping
 * never touches the encoder, the audio encoder or the RTMP transport, and never
 * resets the output timeline (output PTS always comes from the MasterClock).
 */
class VideoSourceController(
    private val context: Context,
    private val compositor: LiveCompositor,
    private val clock: MasterClock,
    private val events: PlaybackEvents
) {
    companion object {
        private const val TAG = "VideoSource"
        const val FIRST_FRAME_TIMEOUT_MS = 8000L
        private const val TIMEOUT_US = 10_000L
    }

    private val running = AtomicBoolean(false)
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    @Volatile var decoderInitialized = false; private set
    @Volatile var firstFrameReceived = false; private set
    @Volatile var lastFrameTimeMs = 0L; private set
    @Volatile var sourceDurationUs = 0L; private set
    @Volatile var currentPositionUs = 0L; private set
    @Volatile var sourceWidth = 0; private set
    @Volatile var sourceHeight = 0; private set
    @Volatile var sourceFps = 0f; private set
    @Volatile var currentIndex = 0; private set
    @Volatile var loopIndex = 0; private set
    @Volatile var lastError: String? = null

    @Volatile private var playlist: List<VideoItem> = emptyList()
    @Volatile private var loopMode: LoopMode = LoopMode.UNLIMITED
    @Volatile private var loopCount: Int = 1
    @Volatile private var audioEnabled: Boolean = true

    val isRunning: Boolean get() = running.get()

    /** Probe a single file so the UI can show real metadata before streaming. */
    fun probe(uri: Uri): Triple<Int, Int, Long>? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val track = selectTrack(extractor, "video/") ?: return null
            val fmt = extractor.getTrackFormat(track)
            Triple(
                fmt.getInteger(MediaFormat.KEY_WIDTH),
                fmt.getInteger(MediaFormat.KEY_HEIGHT),
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            )
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun start(
        items: List<VideoItem>,
        loopMode: LoopMode,
        loopCount: Int,
        audioEnabled: Boolean
    ) {
        if (running.get()) return
        if (items.isEmpty()) {
            events.onError(ErrorCode.VIDEO_URI_INVALID, "Playlist is empty")
            return
        }
        this.playlist = items
        this.loopMode = loopMode
        this.loopCount = loopCount
        this.audioEnabled = audioEnabled
        running.set(true)
        loopIndex = 0
        videoThread = Thread({ playbackLoop() }, "VideoSourceLoop").also { it.start() }
    }

    fun stop() {
        running.set(false)
        videoThread?.join(3000)
        videoThread = null
        audioThread?.join(2000)
        audioThread = null
        decoderInitialized = false
    }

    private fun playbackLoop() {
        try {
            var loop = 0
            while (running.get()) {
                for ((index, item) in playlist.withIndex()) {
                    if (!running.get()) break
                    currentIndex = index
                    events.onItemStarted(index, item.displayName.ifEmpty { "Video ${index + 1}" })
                    playItem(item)
                    events.onItemCompleted(index)
                }
                loop++
                loopIndex = loop
                events.onLoopCompleted(loop)
                val shouldContinue = when (loopMode) {
                    LoopMode.OFF -> false
                    LoopMode.COUNT -> loop < maxOf(1, loopCount)
                    LoopMode.UNLIMITED -> true
                }
                if (!shouldContinue) break
            }
            events.onPlaylistFinished()
        } catch (t: Throwable) {
            lastError = t.message
            events.onError(ErrorCode.VIDEO_DECODER_FAILED, t.message ?: "decoder loop failed")
        } finally {
            running.set(false)
        }
    }

    private fun playItem(item: VideoItem) {
        val uri = runCatching { Uri.parse(item.uri) }.getOrNull()
        if (uri == null) {
            events.onError(ErrorCode.VIDEO_URI_INVALID, "Invalid URI: ${item.displayName}")
            return
        }
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (se: SecurityException) {
                events.onError(ErrorCode.VIDEO_PERMISSION_DENIED, "No permission for ${item.displayName}")
                return
            } catch (io: Exception) {
                events.onError(ErrorCode.VIDEO_URI_INVALID, "Cannot open ${item.displayName}: ${io.message}")
                return
            }

            val videoTrack = selectTrack(extractor, "video/")
            if (videoTrack == null) {
                events.onError(ErrorCode.VIDEO_TRACK_NOT_FOUND, "No video track in ${item.displayName}")
                return
            }
            extractor.selectTrack(videoTrack)
            val format = extractor.getTrackFormat(videoTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            sourceWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            sourceHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            sourceDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            sourceFps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrDefault(0f)
            } else 0f

            compositor.setDefaultBufferSize(sourceWidth, sourceHeight)
            events.onSourceReady(sourceWidth, sourceHeight, sourceDurationUs)

            val surface = compositor.inputSurface
            if (surface == null) {
                events.onError(ErrorCode.RESOURCE_ERROR, "Compositor input surface unavailable")
                return
            }

            val createdDecoder = try {
                MediaCodec.createDecoderByType(mime).also {
                    it.configure(format, surface, null, 0)
                    it.start()
                }
            } catch (t: Throwable) {
                events.onError(ErrorCode.VIDEO_DECODER_FAILED, "Decoder failed for $mime: ${t.message}")
                return
            }
            decoder = createdDecoder
            decoderInitialized = true

            if (audioEnabled) startAudioForItem(uri)

            decodeVideo(extractor, createdDecoder)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            audioThread?.join(1500)
            audioThread = null
        }
    }

    private fun decodeVideo(extractor: MediaExtractor, decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var firstPtsUs = -1L
        val itemStartClockUs = clock.nowUs()
        val startWaitMs = System.currentTimeMillis()

        while (running.get()) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)
                    val sampleSize = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = info.size > 0
                    if (render) {
                        if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                        currentPositionUs = info.presentationTimeUs - firstPtsUs
                        // Pace the source to real time against the single output timeline.
                        val targetUs = itemStartClockUs + currentPositionUs
                        var waitUs = targetUs - clock.nowUs()
                        while (waitUs > 2000 && running.get()) {
                            Thread.sleep(minOf(waitUs / 1000, 50L))
                            waitUs = targetUs - clock.nowUs()
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, render)
                    if (render && !firstFrameReceived) {
                        firstFrameReceived = true
                        events.onFirstFrame()
                    }
                    if (render) lastFrameTimeMs = System.currentTimeMillis()
                    if (eos) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = decoder.outputFormat
                    if (f.containsKey(MediaFormat.KEY_WIDTH)) sourceWidth = f.getInteger(MediaFormat.KEY_WIDTH)
                    if (f.containsKey(MediaFormat.KEY_HEIGHT)) sourceHeight = f.getInteger(MediaFormat.KEY_HEIGHT)
                    events.onSourceReady(sourceWidth, sourceHeight, sourceDurationUs)
                }
                else -> {
                    if (!firstFrameReceived &&
                        System.currentTimeMillis() - startWaitMs > FIRST_FRAME_TIMEOUT_MS
                    ) {
                        events.onError(
                            ErrorCode.VIDEO_DECODER_TIMEOUT,
                            "No decoded frame within ${FIRST_FRAME_TIMEOUT_MS}ms"
                        )
                        return
                    }
                }
            }
        }
    }

    private fun startAudioForItem(uri: Uri) {
        audioThread = Thread({
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            try {
                extractor.setDataSource(context, uri, null)
                val track = selectTrack(extractor, "audio/") ?: return@Thread
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@Thread
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                events.onAudioFormat(sampleRate, channels)
                decoder = MediaCodec.createDecoderByType(mime).also {
                    it.configure(format, null, null, 0)
                    it.start()
                }
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (running.get()) {
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buf = decoder.getInputBuffer(inIndex)
                            val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outIndex >= 0) {
                        val out: ByteBuffer? = decoder.getOutputBuffer(outIndex)
                        if (out != null && info.size > 0) {
                            val data = ByteArray(info.size)
                            out.position(info.offset)
                            out.get(data, 0, info.size)
                            events.onPcm(data, info.size, info.presentationTimeUs)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "audio decode ended: ${t.message}")
            } finally {
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { extractor.release() }
            }
        }, "AudioSourceLoop").also { it.start() }
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return null
    }
}
