package com.livevip.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import com.livevip.app.core.ErrorCode
import com.livevip.app.core.MasterClock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * AAC-LC audio pipeline, normalised to ONE stable output format.
 *
 *   source PCM (any rate/channels) -> continuous-phase resampler -> ring buffer
 *   -> [optional mic mix] -> AAC-LC 48 kHz stereo 128 kbps -> FLV -> RTMP
 *
 * Audio PTS advances strictly by encoded sample count, so it stays monotonic
 * across playlist items, loops and reconnects.
 */
class AudioPipeline(
    private val clock: MasterClock,
    private val micEnabled: Boolean,
    private val videoAudioEnabled: Boolean,
    private val onAudioConfig: (ByteArray) -> Unit,
    private val onAacFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    private val onError: (ErrorCode, String) -> Unit
) {
    companion object {
        /** Preferred output format. The encoder may negotiate down — see [outputSampleRate]. */
        const val OUTPUT_SAMPLE_RATE = 48_000
        const val OUTPUT_CHANNELS = 2
        const val OUTPUT_BITRATE = 128_000
        private const val FRAME_SAMPLES = 1024
        /** ~1.5 s of audio; large enough to absorb decoder bursts. */
        private const val RING_SECONDS = 1.5f
    }

    /**
     * ACTUAL negotiated output format. 48 kHz stereo is only preferred: if the device
     * AAC encoder refuses it we fall back instead of failing START LIVE, exactly like
     * the build that was publishing successfully.
     */
    @Volatile var outputSampleRate = OUTPUT_SAMPLE_RATE; private set
    @Volatile var outputChannels = OUTPUT_CHANNELS; private set
    /** Which encoder configuration actually succeeded — shown in diagnostics. */
    @Volatile var configPath: String = "-"; private set

    private var bytesPerFrame = FRAME_SAMPLES * OUTPUT_CHANNELS * 2
    private val ringCapacity = (OUTPUT_SAMPLE_RATE * OUTPUT_CHANNELS * 2 * RING_SECONDS).toInt()

    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null
    private var feedThread: Thread? = null
    private var micThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    private val videoRing = PcmRingBuffer(ringCapacity)
    private val micRing = PcmRingBuffer(ringCapacity)
    private var videoResampler: Resampler? = null
    private var videoSrcRate = 0
    private var videoSrcChannels = 0

    @Volatile var audioReady = false; private set
    @Volatile var lastError: String? = null
    val encodedFrames = AtomicLong(0)
    val encodedBytes = AtomicLong(0)
    val encodeErrors = AtomicLong(0)
    val silenceFramesInserted = AtomicLong(0)

    val underruns: Long get() = videoRing.underruns + micRing.underruns
    val overruns: Long get() = videoRing.overruns + micRing.overruns
    val bufferFillPercent: Int
        get() = ((if (videoAudioEnabled) videoRing.fillRatio() else micRing.fillRatio()) * 100).toInt()
    @Volatile var sourceSampleRate: Int = 0; private set
    @Volatile var sourceChannels: Int = 0; private set

    fun prepare() {
        // Preferred first, then the formats the previously working build used.
        val candidates = listOf(
            OUTPUT_SAMPLE_RATE to OUTPUT_CHANNELS,
            44_100 to 2,
            OUTPUT_SAMPLE_RATE to 1,
            44_100 to 1
        )
        val failures = StringBuilder()
        for ((rate, channels) in candidates) {
            val frameBytes = FRAME_SAMPLES * channels * 2
            var c: MediaCodec? = null
            try {
                val format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes * 4)
                }
                c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
                codec = c
                outputSampleRate = rate
                outputChannels = channels
                bytesPerFrame = frameBytes
                videoResampler = null
                configPath = "AAC-LC ${rate} Hz / ${channels} ch / ${OUTPUT_BITRATE / 1000} kbps"
                audioReady = true
                lastError = null
                onAudioConfig(audioSpecificConfig())
                return
            } catch (t: Throwable) {
                runCatching { c?.release() }
                failures.append(rate).append("Hz/").append(channels).append("ch: ")
                    .append(t.javaClass.simpleName).append(": ").append(t.message).append("; ")
            }
        }
        audioReady = false
        configPath = "FAILED"
        lastError = failures.toString()
        onError(ErrorCode.AUDIO_ENCODER_FAILED, "AAC encoder rejected every format -> $failures")
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val c = codec ?: return
        if (running.getAndSet(true)) return
        drainThread = Thread({ drainLoop(c) }, "AacDrain").also { it.start() }
        feedThread = Thread({ feedLoop(c) }, "AacFeed").also { it.start() }
        if (micEnabled) startMic()
    }

    /** PCM from the decoded video audio track, in the source's own format. */
    fun pushVideoPcm(data: ByteArray, size: Int, sampleRateIn: Int, channelsIn: Int) {
        if (!videoAudioEnabled || !running.get() || size <= 0) return
        if (videoResampler == null || sampleRateIn != videoSrcRate || channelsIn != videoSrcChannels) {
            // Format changed (new playlist item): rebuild, keeping the output timeline.
            videoSrcRate = sampleRateIn
            videoSrcChannels = channelsIn
            sourceSampleRate = sampleRateIn
            sourceChannels = channelsIn
            videoResampler = Resampler(sampleRateIn, channelsIn, outputSampleRate, outputChannels)
        }
        val converted = videoResampler!!.process(data, size)
        if (converted.isNotEmpty()) videoRing.write(converted)
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        try {
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val minBuf = AudioRecord.getMinBufferSize(
                outputSampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                outputSampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, bytesPerFrame * 8)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onError(ErrorCode.AUDIO_INIT_FAILED, "AudioRecord not initialized")
                return
            }
            audioRecord = record
            record.startRecording()
            micThread = Thread({
                val buf = ByteArray(bytesPerFrame)
                while (running.get()) {
                    val read = record.read(buf, 0, buf.size)
                    if (read > 0) micRing.write(buf, read)
                }
            }, "MicCapture").also { it.start() }
        } catch (t: Throwable) {
            onError(ErrorCode.AUDIO_INIT_FAILED, t.message ?: "mic init failed")
        }
    }

    /**
     * One AAC frame per iteration, paced by encoded sample count against the master
     * clock. Silence is only ever used when the source genuinely has no audio for
     * this slot, and every occurrence is counted (never hidden).
     */
    private fun feedLoop(c: MediaCodec) {
        val videoChunk = ByteArray(bytesPerFrame)
        val micChunk = ByteArray(bytesPerFrame)
        val outChunk = ByteArray(bytesPerFrame)
        var samplesSent = 0L
        try {
            while (running.get()) {
                // Pace: do not run ahead of the output timeline.
                val expectedSamples = clock.nowUs() * outputSampleRate / 1_000_000
                if (samplesSent > expectedSamples + FRAME_SAMPLES * 2) {
                    Thread.sleep(5)
                    continue
                }

                val haveVideo = videoAudioEnabled && videoRing.available() >= bytesPerFrame &&
                    videoRing.readFully(videoChunk, bytesPerFrame)
                val haveMic = micEnabled && micRing.available() >= bytesPerFrame &&
                    micRing.readFully(micChunk, bytesPerFrame)

                when {
                    haveVideo && haveMic -> mix(videoChunk, micChunk, outChunk)
                    haveVideo -> System.arraycopy(videoChunk, 0, outChunk, 0, bytesPerFrame)
                    haveMic -> System.arraycopy(micChunk, 0, outChunk, 0, bytesPerFrame)
                    else -> {
                        // Nothing buffered yet. Wait briefly for real audio instead of
                        // immediately injecting silence (that was the crackling cause).
                        if (samplesSent <= expectedSamples) {
                            Thread.sleep(3)
                            if (videoRing.available() >= bytesPerFrame || micRing.available() >= bytesPerFrame) {
                                continue
                            }
                            java.util.Arrays.fill(outChunk, 0)
                            silenceFramesInserted.incrementAndGet()
                        } else {
                            Thread.sleep(3)
                            continue
                        }
                    }
                }

                val index = c.dequeueInputBuffer(10_000L)
                if (index >= 0) {
                    val buffer = c.getInputBuffer(index)
                    buffer?.clear()
                    buffer?.put(outChunk)
                    val ptsUs = clock.audioPts(samplesSent * 1_000_000 / outputSampleRate)
                    c.queueInputBuffer(index, 0, bytesPerFrame, ptsUs, 0)
                    samplesSent += FRAME_SAMPLES
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                lastError = t.message
                encodeErrors.incrementAndGet()
                onError(ErrorCode.AUDIO_ENCODER_FAILED, t.message ?: "audio feed failed")
            }
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index = c.dequeueOutputBuffer(info, 10_000L)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val csd = c.outputFormat.getByteBuffer("csd-0")
                    if (csd != null) {
                        val arr = ByteArray(csd.remaining())
                        csd.get(arr)
                        onAudioConfig(arr)
                    }
                } else if (index >= 0) {
                    val buffer = c.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        encodedFrames.incrementAndGet()
                        encodedBytes.addAndGet(info.size.toLong())
                        onAacFrame(buffer, info)
                    }
                    c.releaseOutputBuffer(index, false)
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                lastError = t.message
                encodeErrors.incrementAndGet()
                onError(ErrorCode.AUDIO_ENCODER_FAILED, t.message ?: "audio drain failed")
            }
        }
    }

    fun release() {
        running.set(false)
        feedThread?.join(1500); feedThread = null
        drainThread?.join(1500); drainThread = null
        micThread?.join(1000); micThread = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        audioReady = false
        videoRing.clear()
        micRing.clear()
        videoResampler = null
    }

    private fun mix(a: ByteArray, b: ByteArray, out: ByteArray) {
        var i = 0
        while (i + 1 < bytesPerFrame) {
            val s1 = ((a[i + 1].toInt() shl 8) or (a[i].toInt() and 0xFF)).toShort().toInt()
            val s2 = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            val m = (s1 + s2).coerceIn(-32768, 32767)
            out[i] = (m and 0xFF).toByte()
            out[i + 1] = ((m shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /** AAC-LC AudioSpecificConfig matching the ACTUAL encoder format (48 kHz stereo). */
    private fun audioSpecificConfig(): ByteArray {
        val freqIndex = when (outputSampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 3
        }
        val objectType = 2 // AAC LC
        val b0 = ((objectType shl 3) or (freqIndex shr 1)).toByte()
        val b1 = (((freqIndex and 1) shl 7) or (outputChannels shl 3)).toByte()
        return byteArrayOf(b0, b1)
    }
}
