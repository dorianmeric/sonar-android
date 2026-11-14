package com.echosonar.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class MatchedFilterTest {

    private val config = SonarConfig()
    private val fs = config.sampleRateHz

    @Test
    fun findsEchoAtExpectedLag() {
        val filter = MatchedFilter(config)
        val range = 2.0f
        val directMs = 30f
        val capture = SynthRoom.capture(
            config = config,
            directDelayMs = directMs,
            echoes = listOf(SynthRoom.Echo(range, 0.2f)),
        )
        val env = filter.process(capture)

        val expectedLag = ((directMs + config.roundTripMs(range)) / 1000f * fs).roundToInt()
        var bestLag = 0
        var bestVal = 0f
        for (lag in expectedLag - 200..expectedLag + 200) {
            if (lag in 0 until filter.lagCount && env[lag] > bestVal) {
                bestVal = env[lag]
                bestLag = lag
            }
        }
        assertTrue("peak lag $bestLag vs expected $expectedLag", abs(bestLag - expectedLag) <= 2)
    }

    @Test
    fun directPathIsStrongerThanWallEcho() {
        val filter = MatchedFilter(config)
        val capture = SynthRoom.capture(
            config = config,
            directDelayMs = 40f,
            directAmplitude = 0.6f,
            echoes = listOf(SynthRoom.Echo(1.5f, 0.25f)),
        )
        val env = filter.process(capture)
        val directLag = (40f / 1000f * fs).roundToInt()
        val echoLag = ((40f + config.roundTripMs(1.5f)) / 1000f * fs).roundToInt()
        assertTrue(env[directLag] > 2f * env[echoLag])
    }

    @Test
    fun rejectsOutOfBandInterference() {
        val filter = MatchedFilter(config)
        val capture = SynthRoom.capture(
            config = config,
            echoes = listOf(SynthRoom.Echo(1.2f, 0.2f)),
        )
        // Add a loud 600 Hz rumble (think HVAC or a table thump).
        for (i in capture.indices) {
            capture[i] = (capture[i] + 0.25f * kotlin.math.sin(2f * Math.PI.toFloat() * 600f * i / fs))
                .coerceIn(-1f, 1f)
        }
        val env = filter.process(capture)
        val echoLag = ((30f + config.roundTripMs(1.2f)) / 1000f * fs).roundToInt()
        var inBand = 0f
        for (lag in echoLag - 30..echoLag + 30) {
            if (lag in 0 until filter.lagCount) inBand = maxOf(inBand, env[lag])
        }
        // The out-of-band tone must not out-correlate a real echo: the matched filter's
        // envelope at the rumble's own lag stays far below the in-band echo.
        var outOfBand = 0f
        for (lag in 0 until echoLag - 400) {
            outOfBand = maxOf(outOfBand, env[lag])
        }
        assertTrue("in-band $inBand out-of-band $outOfBand", inBand > 3f * outOfBand)
    }

    @Test
    fun bufferedProcessingMatchesFreshOffset() {
        val filter = MatchedFilter(config)
        val capture = SynthRoom.capture(config, echoes = listOf(SynthRoom.Echo(2.5f, 0.2f)))
        val padded = FloatArray(capture.size + 300)
        System.arraycopy(capture, 0, padded, 300, capture.size)
        val a = filter.process(capture).copyOf(filter.lagCount)
        val b = filter.process(padded, 300).copyOf(filter.lagCount)
        for (i in 0 until filter.lagCount step 97) {
            assertTrue(abs(a[i] - b[i]) < 1e-4f)
        }
    }
}