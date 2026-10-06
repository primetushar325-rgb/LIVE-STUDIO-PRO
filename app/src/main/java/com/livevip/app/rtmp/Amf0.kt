package com.livevip.app.rtmp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Minimal AMF0 writer/reader covering what RTMP publishing needs. */
object Amf0 {
    const val NUMBER = 0x00
    const val BOOLEAN = 0x01
    const val STRING = 0x02
    const val OBJECT = 0x03
    const val NULL = 0x05
    const val ECMA_ARRAY = 0x08
    const val OBJECT_END = 0x09
    const val STRICT_ARRAY = 0x0A

    fun writeString(out: DataOutputStream, value: String) {
        out.writeByte(STRING)
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    fun writeNumber(out: DataOutputStream, value: Double) {
        out.writeByte(NUMBER)
        out.writeDouble(value)
    }

    fun writeBoolean(out: DataOutputStream, value: Boolean) {
        out.writeByte(BOOLEAN)
        out.writeByte(if (value) 1 else 0)
    }

    fun writeNull(out: DataOutputStream) {
        out.writeByte(NULL)
    }

    fun writeObject(out: DataOutputStream, props: List<Pair<String, Any?>>) {
        out.writeByte(OBJECT)
        writeProps(out, props)
    }

    fun writeEcmaArray(out: DataOutputStream, props: List<Pair<String, Any?>>) {
        out.writeByte(ECMA_ARRAY)
        out.writeInt(props.size)
        writeProps(out, props)
    }

    private fun writeProps(out: DataOutputStream, props: List<Pair<String, Any?>>) {
        for ((key, value) in props) {
            val k = key.toByteArray(Charsets.UTF_8)
            out.writeShort(k.size)
            out.write(k)
            when (value) {
                is String -> writeString(out, value)
                is Boolean -> writeBoolean(out, value)
                is Number -> writeNumber(out, value.toDouble())
                null -> writeNull(out)
                else -> writeString(out, value.toString())
            }
        }
        out.writeShort(0)
        out.writeByte(OBJECT_END)
    }

    fun encode(block: (DataOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use(block)
        return bos.toByteArray()
    }

    /** Very small decoder: returns the strings found in an AMF0 payload (command name, status codes). */
    fun readStrings(data: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < data.size) {
            when (data[i].toInt() and 0xFF) {
                STRING -> {
                    if (i + 3 > data.size) return result
                    val len = ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
                    if (i + 3 + len > data.size) return result
                    result.add(String(data, i + 3, len, Charsets.UTF_8))
                    i += 3 + len
                }
                NUMBER -> i += 9
                BOOLEAN -> i += 2
                NULL, OBJECT_END -> i += 1
                OBJECT, ECMA_ARRAY -> i += 1
                else -> i += 1
            }
        }
        return result
    }

    /** Reads the first AMF0 number after the command name (the transaction id). */
    fun readFirstNumber(data: ByteArray): Double? {
        var i = 0
        while (i < data.size) {
            when (data[i].toInt() and 0xFF) {
                NUMBER -> {
                    if (i + 9 > data.size) return null
                    var bits = 0L
                    for (b in 1..8) bits = (bits shl 8) or (data[i + b].toLong() and 0xFF)
                    return Double.fromBits(bits)
                }
                STRING -> {
                    val len = ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
                    i += 3 + len
                }
                else -> i += 1
            }
        }
        return null
    }

    /** Reads all AMF0 numbers in order from a payload. */
    fun readNumbers(data: ByteArray): List<Double> {
        val result = mutableListOf<Double>()
        var i = 0
        while (i < data.size) {
            when (data[i].toInt() and 0xFF) {
                NUMBER -> {
                    if (i + 9 > data.size) return result
                    var bits = 0L
                    for (b in 1..8) bits = (bits shl 8) or (data[i + b].toLong() and 0xFF)
                    result.add(Double.fromBits(bits))
                    i += 9
                }
                STRING -> {
                    if (i + 3 > data.size) return result
                    val len = ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
                    i += 3 + len
                }
                BOOLEAN -> i += 2
                else -> i += 1
            }
        }
        return result
    }
}
