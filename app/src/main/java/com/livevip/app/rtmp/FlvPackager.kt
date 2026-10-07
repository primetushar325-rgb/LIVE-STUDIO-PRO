package com.livevip.app.rtmp

import java.io.ByteArrayOutputStream

/** Builds FLV video/audio tag bodies (the payload of RTMP type 9 / type 8 messages). */
object FlvPackager {

    /** AVC sequence header (AVCDecoderConfigurationRecord) from SPS + PPS. */
    fun avcSequenceHeader(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x17) // keyframe + AVC
        out.write(0x00) // AVC sequence header
        out.write(0); out.write(0); out.write(0) // composition time
        out.write(0x01)
        out.write(sps.getOrElse(1) { 0x42 }.toInt())
        out.write(sps.getOrElse(2) { 0x00 }.toInt())
        out.write(sps.getOrElse(3) { 0x1F }.toInt())
        out.write(0xFF) // 4 byte NAL length
        out.write(0xE1) // 1 SPS
        out.write((sps.size shr 8) and 0xFF)
        out.write(sps.size and 0xFF)
        out.write(sps)
        out.write(0x01) // 1 PPS
        out.write((pps.size shr 8) and 0xFF)
        out.write(pps.size and 0xFF)
        out.write(pps)
        return out.toByteArray()
    }

    /** Converts an Annex-B access unit into an FLV AVC NALU tag body. */
    fun videoTag(annexB: ByteArray, keyframe: Boolean, compositionTimeMs: Int = 0): ByteArray {
        val avcc = annexBToAvcc(annexB)
        val out = ByteArrayOutputStream(avcc.size + 5)
        out.write(if (keyframe) 0x17 else 0x27)
        out.write(0x01) // NALU
        out.write((compositionTimeMs shr 16) and 0xFF)
        out.write((compositionTimeMs shr 8) and 0xFF)
        out.write(compositionTimeMs and 0xFF)
        out.write(avcc)
        return out.toByteArray()
    }

    fun aacSequenceHeader(audioSpecificConfig: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(audioSpecificConfig.size + 2)
        out.write(0xAF) // AAC, 44kHz, 16bit, stereo
        out.write(0x00) // sequence header
        out.write(audioSpecificConfig)
        return out.toByteArray()
    }

    fun aacTag(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size + 2)
        out.write(0xAF)
        out.write(0x01) // raw AAC frame
        out.write(raw)
        return out.toByteArray()
    }

    /** Replaces Annex-B start codes with 4 byte big endian NAL lengths. */
    fun annexBToAvcc(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        var i = 0
        val n = data.size
        var nalStart = -1

        fun startCodeLen(pos: Int): Int {
            if (pos + 3 < n && data[pos].toInt() == 0 && data[pos + 1].toInt() == 0 &&
                data[pos + 2].toInt() == 0 && data[pos + 3].toInt() == 1
            ) return 4
            if (pos + 2 < n && data[pos].toInt() == 0 && data[pos + 1].toInt() == 0 &&
                data[pos + 2].toInt() == 1
            ) return 3
            return 0
        }

        fun emit(end: Int) {
            if (nalStart < 0 || end <= nalStart) return
            val len = end - nalStart
            out.write((len ushr 24) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(data, nalStart, len)
        }

        while (i < n) {
            val sc = startCodeLen(i)
            if (sc > 0) {
                emit(i)
                i += sc
                nalStart = i
            } else i++
        }
        emit(n)
        if (out.size() == 0) {
            // Already length prefixed, pass through.
            return data
        }
        return out.toByteArray()
    }
}
