package com.echosonar.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT.
 *
 * Not thread safe: create one instance per worker.
 */
class Fft(val size: Int) {

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT size must be a power of two, got $size" }
    }

    private val levels: Int = Integer.numberOfTrailingZeros(size)
    private val cosTable = FloatArray(size / 2) { cos(2.0 * Math.PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2.0 * Math.PI * it / size).toFloat() }
    private val bitRev = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }

    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, inverse = false)

    fun inverse(re: FloatArray, im: FloatArray) = transform(re, im, inverse = true)

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        require(re.size == size && im.size == size) { "buffers must be FFT sized" }

        for (i in 0 until size) {
            val j = bitRev[i]
            if (j > i) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        val sign = if (inverse) 1f else -1f
        var span = 2
        while (span <= size) {
            val half = span / 2
            val tableStep = size / span
            var base = 0
            while (base < size) {
                var k = 0
                var j = base
                while (j < base + half) {
                    val l = j + half
                    val c = cosTable[k]
                    val s = sign * sinTable[k]
                    val tr = re[l] * c - im[l] * s
                    val ti = re[l] * s + im[l] * c
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += tableStep
                    j++
                }
                base += span
            }
            span = span shl 1
        }

        if (inverse) {
            val scale = 1f / size
            for (i in 0 until size) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }

    companion object {
        fun nextPowerOfTwo(value: Int): Int {
            var v = 1
            while (v < value) v = v shl 1
            return v
        }
    }
}