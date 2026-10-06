package com.livevip.app.audio

/**
 * Continuous-phase linear resampler for interleaved 16-bit PCM.
 *
 * The previous implementation resampled each decoded buffer independently with
 * nearest-neighbour sampling, so the phase reset on every buffer boundary and the
 * last sample of a buffer was never interpolated with the first sample of the next
 * one. That produced the audible clicking / "gar gar" distortion on YouTube.
 * This class keeps the fractional read position AND the tail samples across calls.
 */
class Resampler(
    private val srcRate: Int,
    private val srcChannels: Int,
    private val dstRate: Int,
    private val dstChannels: Int
) {
    private val ratio = srcRate.toDouble() / dstRate.toDouble()
    private var position = 0.0
    private var previous: ShortArray = ShortArray(srcChannels) // last source frame of previous buffer
    private var hasPrevious = false

    var inputFrames: Long = 0; private set
    var outputFrames: Long = 0; private set

    fun reset() {
        position = 0.0
        hasPrevious = false
        inputFrames = 0
        outputFrames = 0
    }

    /** Converts interleaved little-endian 16-bit PCM. Returns interleaved output PCM. */
    fun process(input: ByteArray, size: Int): ByteArray {
        val srcFrames = size / (2 * srcChannels)
        if (srcFrames <= 0) return ByteArray(0)
        inputFrames += srcFrames

        val samples = ShortArray(srcFrames * srcChannels)
        var si = 0
        var bi = 0
        while (si < samples.size) {
            samples[si] = ((input[bi].toInt() and 0xFF) or (input[bi + 1].toInt() shl 8)).toShort()
            si++
            bi += 2
        }

        if (srcRate == dstRate && srcChannels == dstChannels) {
            outputFrames += srcFrames
            return input.copyOf(size)
        }

        val out = ArrayList<Short>(((srcFrames / ratio).toInt() + 2) * dstChannels)
        // position is relative to the start of this buffer; negative values interpolate
        // with the tail of the previous buffer so the waveform stays continuous.
        while (true) {
            val base = Math.floor(position).toInt()
            val frac = (position - base).toFloat()
            if (base + 1 > srcFrames - 1) break
            for (ch in 0 until dstChannels) {
                val srcCh = if (ch < srcChannels) ch else srcChannels - 1
                val a: Short = if (base < 0) {
                    if (hasPrevious) previous[srcCh] else samples[srcCh]
                } else samples[base * srcChannels + srcCh]
                val b: Short = samples[(base + 1) * srcChannels + srcCh]
                val value = a + (b - a) * frac
                out.add(value.toInt().coerceIn(-32768, 32767).toShort())
            }
            outputFrames++
            position += ratio
        }

        // Carry the remainder into the next buffer.
        position -= srcFrames
        for (ch in 0 until srcChannels) {
            previous[ch] = samples[(srcFrames - 1) * srcChannels + ch]
        }
        hasPrevious = true

        val bytes = ByteArray(out.size * 2)
        var oi = 0
        for (s in out) {
            bytes[oi] = (s.toInt() and 0xFF).toByte()
            bytes[oi + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            oi += 2
        }
        return bytes
    }
}
