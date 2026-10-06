package com.livevip.app.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.livevip.app.core.ErrorCode
import com.livevip.app.core.StreamException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware H.264 surface encoder. Created ONCE per live session: playlist changes
 * and loops never restart it.
 */
class VideoEncoderController(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
    private val keyframeIntervalSec: Int,
    private val onFormat: (sps: ByteArray, pps: ByteArray) -> Unit,
    private val onEncodedFrame: (data: ByteBuffer, info: MediaCodec.BufferInfo, keyframe: Boolean) -> Unit,
    private val onError: (ErrorCode, String) -> Unit
) {
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    var inputSurface: Surface? = null
        private set
    @Volatile var encoderReady = false; private set
    @Volatile var lastError: String? = null
    @Volatile var codecName: String = "-"
    val encodedFrameCount = AtomicLong(0)
    val encodedBytes = AtomicLong(0)
    val droppedFrames = AtomicLong(0)
    @Volatile var actualFps: Float = 0f; private set

    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0L

    fun prepare() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSec)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codecName = runCatching { c.name }.getOrDefault("h264")
            try {
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (t: Throwable) {
                c.release()
                throw StreamException(ErrorCode.ENCODER_CONFIG_FAILED, t.message, t)
            }
            inputSurface = c.createInputSurface()
            c.start()
            codec = c
            encoderReady = true
        } catch (e: StreamException) {
            lastError = e.message
            onError(e.code, e.message ?: "encoder config failed")
        } catch (t: Throwable) {
            lastError = t.message
            onError(ErrorCode.ENCODER_UNAVAILABLE, t.message ?: "no H.264 encoder")
        }
    }

    fun start() {
        val c = codec ?: return
        if (running.getAndSet(true)) return
        fpsWindowStart = System.currentTimeMillis()
        thread = Thread({ drainLoop(c) }, "VideoEncoderDrain").also { it.start() }
    }

    fun requestKeyframe() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            runCatching {
                codec?.setParameters(
                    android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
                )
            }
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index = c.dequeueOutputBuffer(info, 10_000L)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = c.outputFormat
                    val sps = fmt.getByteBuffer("csd-0")
                    val pps = fmt.getByteBuffer("csd-1")
                    if (sps != null && pps != null) {
                        onFormat(sps.toArray(), pps.toArray())
                    }
                } else if (index >= 0) {
                    val buffer = c.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (isConfig) {
                            val csd = buffer.toArray()
                            val (sps, pps) = splitSpsPps(csd)
                            if (sps != null && pps != null) onFormat(sps, pps)
                        } else {
                            encodedFrameCount.incrementAndGet()
                            encodedBytes.addAndGet(info.size.toLong())
                            fpsWindowFrames++
                            val now = System.currentTimeMillis()
                            if (now - fpsWindowStart >= 1000) {
                                actualFps = fpsWindowFrames * 1000f / (now - fpsWindowStart)
                                fpsWindowStart = now
                                fpsWindowFrames = 0
                            }
                            onEncodedFrame(buffer, info, keyframe)
                        }
                    }
                    c.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                lastError = t.message
                onError(ErrorCode.ENCODER_CONFIG_FAILED, t.message ?: "encoder drain failed")
            }
        }
    }

    fun release() {
        running.set(false)
        thread?.join(2000)
        thread = null
        runCatching { codec?.signalEndOfInputStream() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        codec = null
        inputSurface = null
        encoderReady = false
    }

    private fun ByteBuffer.toArray(): ByteArray {
        val arr = ByteArray(remaining())
        get(arr)
        return arr
    }

    /** Splits an Annex-B csd blob into SPS and PPS NAL units. */
    private fun splitSpsPps(csd: ByteArray): Pair<ByteArray?, ByteArray?> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i < csd.size - 4) {
            if (csd[i].toInt() == 0 && csd[i + 1].toInt() == 0 &&
                csd[i + 2].toInt() == 0 && csd[i + 3].toInt() == 1
            ) {
                starts.add(i); i += 4
            } else if (csd[i].toInt() == 0 && csd[i + 1].toInt() == 0 && csd[i + 2].toInt() == 1) {
                starts.add(i); i += 3
            } else i++
        }
        if (starts.size < 2) return null to null
        fun nal(from: Int, to: Int): ByteArray {
            var s = from
            s += if (csd.size > s + 3 && csd[s + 2].toInt() == 1) 3 else 4
            return csd.copyOfRange(s, to)
        }
        val first = nal(starts[0], starts[1])
        val second = nal(starts[1], csd.size)
        val firstType = first.firstOrNull()?.toInt()?.and(0x1F)
        return if (firstType == 7) first to second else second to first
    }
}
