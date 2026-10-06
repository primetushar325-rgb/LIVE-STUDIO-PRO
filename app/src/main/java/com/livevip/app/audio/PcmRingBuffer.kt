package com.livevip.app.audio

/**
 * Fixed-capacity PCM FIFO with real underrun / overrun accounting.
 * Never silently corrupts audio: callers are told exactly what happened.
 */
class PcmRingBuffer(private val capacityBytes: Int) {

    private val buffer = ByteArray(capacityBytes)
    private var head = 0
    private var tail = 0
    private var size = 0

    var underruns: Long = 0; private set
    var overruns: Long = 0; private set
    var bytesWritten: Long = 0; private set
    var bytesRead: Long = 0; private set

    @Synchronized fun available(): Int = size

    @Synchronized fun capacity(): Int = capacityBytes

    @Synchronized fun fillRatio(): Float = size.toFloat() / capacityBytes.toFloat()

    /** Writes data, dropping the oldest bytes when full (counted as an overrun). */
    @Synchronized fun write(data: ByteArray, length: Int = data.size) {
        var offset = 0
        var remaining = length
        if (remaining > capacityBytes) {
            offset = remaining - capacityBytes
            remaining = capacityBytes
            overruns++
        }
        if (size + remaining > capacityBytes) {
            val drop = size + remaining - capacityBytes
            head = (head + drop) % capacityBytes
            size -= drop
            overruns++
        }
        for (i in 0 until remaining) {
            buffer[tail] = data[offset + i]
            tail = (tail + 1) % capacityBytes
        }
        size += remaining
        bytesWritten += remaining
    }

    /**
     * Reads exactly [length] bytes. Returns false (and counts an underrun) when not
     * enough audio is buffered; the caller decides what to do, no silent corruption.
     */
    @Synchronized fun readFully(out: ByteArray, length: Int): Boolean {
        if (size < length) {
            underruns++
            return false
        }
        for (i in 0 until length) {
            out[i] = buffer[head]
            head = (head + 1) % capacityBytes
        }
        size -= length
        bytesRead += length
        return true
    }

    @Synchronized fun clear() {
        head = 0; tail = 0; size = 0
    }
}
