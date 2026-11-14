package com.echosonar.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FftTest {

    private fun naiveDft(re: DoubleArray, im: DoubleArray, inverse: Boolean): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        val sign = if (inverse) 1.0 else -1.0
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val angle = sign * 2.0 * PI * k * t / n
                val c = cos(angle)
                val s = sin(angle)
                sr += re[t] * c - im[t] * s
                si += re[t] * s + im[t] * c
            }
            val scale = if (inverse) 1.0 / n else 1.0
            outRe[k] = sr * scale
            outIm[k] = si * scale
        }
        return outRe to outIm
    }

    @Test
    fun forwardMatchesNaiveDft() {
        val n = 64
        val rnd = Random(7)
        val re = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val im = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val expected = naiveDft(
            DoubleArray(n) { re[it].toDouble() },
            DoubleArray(n) { im[it].toDouble() },
            inverse = false,
        )
        Fft(n).forward(re, im)
        for (i in 0 until n) {
            assertEquals(expected.first[i].toFloat(), re[i], 1e-3f)
            assertEquals(expected.second[i].toFloat(), im[i], 1e-3f)
        }
    }

    @Test
    fun inverseUndoesForward() {
        val n = 128
        val rnd = Random(11)
        val original = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val re = original.copyOf()
        val im = FloatArray(n)
        val fft = Fft(n)
        fft.forward(re, im)
        fft.inverse(re, im)
        for (i in 0 until n) {
            assertEquals(original[i], re[i], 1e-5f)
            assertEquals(0f, im[i], 1e-5f)
        }
    }

    @Test
    fun impulseProducesFlatSpectrum() {
        val n = 32
        val re = FloatArray(n)
        val im = FloatArray(n)
        re[0] = 1f
        Fft(n).forward(re, im)
        for (i in 0 until n) {
            assertEquals(1f, re[i], 1e-5f)
            assertEquals(0f, im[i], 1e-5f)
        }
    }

    @Test
    fun nextPowerOfTwoRoundsUp() {
        assertEquals(1, Fft.nextPowerOfTwo(1))
        assertEquals(16, Fft.nextPowerOfTwo(9))
        assertEquals(4096, Fft.nextPowerOfTwo(4096))
        assertTrue(Fft.nextPowerOfTwo(4097) == 8192)
    }
}