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

    /** Full AMF0 value decoder: numbers, strings, booleans, nulls and objects/arrays. */
    fun decodeAll(data: ByteArray): List<Any?> {
        val values = mutableListOf<Any?>()
        var i = 0
        while (i < data.size) {
            val (value, next) = decodeValue(data, i) ?: break
            values.add(value)
            if (next <= i) break
            i = next
        }
        return values
    }

    private fun decodeValue(data: ByteArray, start: Int): Pair<Any?, Int>? {
        if (start >= data.size) return null
        return when (data[start].toInt() and 0xFF) {
            NUMBER -> {
                if (start + 9 > data.size) return null
                var bits = 0L
                for (b in 1..8) bits = (bits shl 8) or (data[start + b].toLong() and 0xFF)
                Double.fromBits(bits) to (start + 9)
            }
            BOOLEAN -> {
                if (start + 2 > data.size) return null
                (data[start + 1].toInt() != 0) to (start + 2)
            }
            STRING -> {
                if (start + 3 > data.size) return null
                val len = ((data[start + 1].toInt() and 0xFF) shl 8) or (data[start + 2].toInt() and 0xFF)
                if (start + 3 + len > data.size) return null
                String(data, start + 3, len, Charsets.UTF_8) to (start + 3 + len)
            }
            NULL, 0x06 -> null to (start + 1)
            OBJECT -> decodeObject(data, start + 1)
            ECMA_ARRAY -> {
                if (start + 5 > data.size) return null
                decodeObject(data, start + 5)
            }
            OBJECT_END -> null to (start + 1)
            else -> null to (start + 1)
        }
    }

    private fun decodeObject(data: ByteArray, start: Int): Pair<Map<String, Any?>, Int> {
        val map = LinkedHashMap<String, Any?>()
        var i = start
        while (i + 2 <= data.size) {
            val len = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            if (len == 0) {
                // 0x00 0x00 0x09 terminator
                return map to minOf(i + 3, data.size)
            }
            if (i + 2 + len > data.size) break
            val key = String(data, i + 2, len, Charsets.UTF_8)
            val decoded = decodeValue(data, i + 2 + len) ?: break
            map[key] = decoded.first
            if (decoded.second <= i) break
            i = decoded.second
        }
        return map to i
    }

    /** Finds the first AMF0 object containing a "code" property (NetStatus object). */
    fun findStatusObject(values: List<Any?>): Map<String, Any?>? =
        values.filterIsInstance<Map<String, Any?>>().firstOrNull { it.containsKey("code") }
}
