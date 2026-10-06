package com.livevip.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.livevip.app.core.ErrorCode
import com.livevip.app.core.MasterClock
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * AAC audio pipeline.
 *
 * Sources: decoded video audio (PCM pushed in) and/or the microphone.
 * Both are mixed into one stream and encoded with a single AAC encoder that is
 * created once per live session. Timestamps come from the shared MasterClock,
 * so audio stays monotonic across playlist items and loops.
 */
class AudioPipeline(
    private val clock: MasterClock,
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 2,
    private val bitrate: Int = 128_000,
    private val micEnabled: Boolean,
    private val videoAudioEnabled: Boolean,
    private val onAudioConfig: (ByteArray) -> Unit,
    private val onAacFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    private val onError: (ErrorCode, String) -> Unit
) {
    private val frameSamples = 1024
    private val bytesPerFrame = frameSamples * channelCount * 2

    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null
    private var feedThread: Thread? = null
    private var micThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    private val videoPcmQueue = ArrayBlockingQueue<ByteArray>(64)
    private val micPcmQueue = ArrayBlockingQueue<ByteArray>(64)

    @Volatile var audioReady = false; private set
    @Volatile var lastError: String? = null
    val encodedFrames = AtomicLong(0)
    val encodedBytes = AtomicLong(0)
    @Volatile var sourceSampleRate = sampleRate
    @Volatile var sourceChannels = channelCount

    fun prepare() {
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bytesPerFrame * 4)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            audioReady = true
            onAudioConfig(aacAudioSpecificConfig())
        } catch (t: Throwable) {
            lastError = t.message
            onError(ErrorCode.AUDIO_ENCODER_FAILED, t.message ?: "AAC encoder failed")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val c = codec ?: return
        if (running.getAndSet(true)) return
        drainThread = Thread({ drainLoop(c) }, "AacDrain").also { it.start() }
        feedThread = Thread({ feedLoop(c) }, "AacFeed").also { it.start() }
        if (micEnabled) startMic()
    }

    /** PCM coming from the decoded video audio track. */
    fun pushVideoPcm(data: ByteArray, size: Int, sampleRateIn: Int, channelsIn: Int) {
        if (!videoAudioEnabled || !running.get()) return
        sourceSampleRate = sampleRateIn
        sourceChannels = channelsIn
        val converted = convert(data, size, sampleRateIn, channelsIn)
        if (!videoPcmQueue.offer(converted)) {
            videoPcmQueue.poll()
            videoPcmQueue.offer(converted)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startMic() {
        try {
            val channelMask =
                if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, bytesPerFrame * 4)
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
                    if (read > 0) {
                        val copy = buf.copyOf(read)
                        if (!micPcmQueue.offer(copy)) {
                            micPcmQueue.poll(); micPcmQueue.offer(copy)
                        }
                    }
                }
            }, "MicCapture").also { it.start() }
        } catch (t: Throwable) {
            onError(ErrorCode.AUDIO_INIT_FAILED, t.message ?: "mic init failed")
        }
    }

    /**
     * Produces exactly one AAC frame worth of PCM per iteration, paced by the
     * master clock, mixing video audio and microphone when both are active.
     */
    private fun feedLoop(c: MediaCodec) {
        var pending = ByteArray(0)
        var micPending = ByteArray(0)
        var samplesSent = 0L
        try {
            while (running.get()) {
                if (videoAudioEnabled) {
                    while (pending.size < bytesPerFrame && running.get()) {
                        val next = videoPcmQueue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (next == null) break else pending += next
                    }
                }
                if (micEnabled) {
                    while (micPending.size < bytesPerFrame && running.get()) {
                        val next = micPcmQueue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (next == null) break else micPending += next
                    }
                }

                val expectedSamples = clock.nowUs() * sampleRate / 1_000_000
                if (samplesSent > expectedSamples + frameSamples) {
                    Thread.sleep(5)
                    continue
                }

                val chunk = ByteArray(bytesPerFrame)
                val haveVideo = videoAudioEnabled && pending.size >= bytesPerFrame
                val haveMic = micEnabled && micPending.size >= bytesPerFrame
                when {
                    haveVideo && haveMic -> {
                        mix(pending, micPending, chunk)
                        pending = pending.copyOfRange(bytesPerFrame, pending.size)
                        micPending = micPending.copyOfRange(bytesPerFrame, micPending.size)
                    }
                    haveVideo -> {
                        System.arraycopy(pending, 0, chunk, 0, bytesPerFrame)
                        pending = pending.copyOfRange(bytesPerFrame, pending.size)
                    }
                    haveMic -> {
                        System.arraycopy(micPending, 0, chunk, 0, bytesPerFrame)
                        micPending = micPending.copyOfRange(bytesPerFrame, micPending.size)
                    }
                    else -> {
                        // silence keeps the audio timeline continuous (gaps break ingest)
                    }
                }

                val index = c.dequeueInputBuffer(10_000L)
                if (index >= 0) {
                    val buffer = c.getInputBuffer(index)
                    buffer?.clear()
                    buffer?.put(chunk)
                    val ptsUs = clock.audioPts(samplesSent * 1_000_000 / sampleRate)
                    c.queueInputBuffer(index, 0, chunk.size, ptsUs, 0)
                    samplesSent += frameSamples
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                lastError = t.message
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
        videoPcmQueue.clear()
        micPcmQueue.clear()
    }

    private fun mix(a: ByteArray, b: ByteArray, out: ByteArray) {
        var i = 0
        while (i + 1 < bytesPerFrame) {
            val s1 = ((a[i + 1].toInt() shl 8) or (a[i].toInt() and 0xFF)).toShort().toInt()
            val s2 = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            var m = s1 + s2
            if (m > 32767) m = 32767
            if (m < -32768) m = -32768
            out[i] = (m and 0xFF).toByte()
            out[i + 1] = ((m shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /** Nearest-neighbour resample + channel adaptation to the encoder format. */
    private fun convert(data: ByteArray, size: Int, srcRate: Int, srcChannels: Int): ByteArray {
        if (srcRate == sampleRate && srcChannels == channelCount) return data.copyOf(size)
        val srcSamples = size / (2 * srcChannels)
        val dstSamples = (srcSamples.toLong() * sampleRate / srcRate).toInt()
        val out = ByteArray(dstSamples * 2 * channelCount)
        for (i in 0 until dstSamples) {
            val srcIndex = (i.toLong() * srcRate / sampleRate).toInt().coerceAtMost(srcSamples - 1)
            for (ch in 0 until channelCount) {
                val srcCh = if (ch < srcChannels) ch else srcChannels - 1
                val sPos = (srcIndex * srcChannels + srcCh) * 2
                val dPos = (i * channelCount + ch) * 2
                if (sPos + 1 < size && dPos + 1 < out.size) {
                    out[dPos] = data[sPos]
                    out[dPos + 1] = data[sPos + 1]
                }
            }
        }
        return out
    }

    /** AAC LC AudioSpecificConfig used by the FLV AAC sequence header. */
    private fun aacAudioSpecificConfig(): ByteArray {
        val freqIndex = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 4
        }
        val objectType = 2 // AAC LC
        val b0 = ((objectType shl 3) or (freqIndex shr 1)).toByte()
        val b1 = (((freqIndex and 1) shl 7) or (channelCount shl 3)).toByte()
        return byteArrayOf(b0, b1)
    }
}
