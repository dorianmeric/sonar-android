package com.echosonar.audio

/**
 * Single-producer / single-consumer sample ring with a monotonically increasing frame counter.
 *
 * The recorder thread appends raw microphone frames; the ping loop later extracts the exact window
 * that belongs to one chirp. Timestamps are frame counts rather than wall-clock time, so scheduling
 * jitter cannot shift a window.
 */
class SampleRing(val capacity: Int) {

    private val data = FloatArray(capacity)
    private var writeIndex = 0
    private var written = 0L

    @get:Synchronized
    val framesWritten: Long get() = written

    @Synchronized
    fun write(samples: ShortArray, offset: Int, count: Int) {
        if (count <= 0) return
        var src = offset
        var remaining = count
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - writeIndex)
            for (i in 0 until chunk) {
                data[writeIndex + i] = samples[src + i] / 32768f
            }
            writeIndex = (writeIndex + chunk) % capacity
            written += chunk
            src += chunk
            remaining -= chunk
        }
    }

    /**
     * Copy [length] frames starting at absolute frame [startFrame].
     * Returns null when that window is not fully captured yet or has already been overwritten.
     */
    @Synchronized
    fun read(startFrame: Long, length: Int, destination: FloatArray): Boolean {
        if (length > capacity) return false
        if (startFrame < 0 || startFrame + length > written) return false
        if (written - startFrame > capacity) return false
        var index = (startFrame % capacity).toInt()
        var remaining = length
        var dst = 0
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - index)
            System.arraycopy(data, index, destination, dst, chunk)
            index = (index + chunk) % capacity
            dst += chunk
            remaining -= chunk
        }
        return true
    }

    @Synchronized
    fun clear() {
        data.fill(0f)
        writeIndex = 0
        written = 0
    }
}