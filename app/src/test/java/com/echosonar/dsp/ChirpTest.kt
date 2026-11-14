package com.echosonar.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChirpTest {

    private val spec = ChirpSpec(sampleRate = 48_000, centerHz = 19_000f, bandwidthHz = 2_000f, durationMs = 10)

    @Test
    fun durationMatchesMilliseconds() {
        val chirp = ChirpWaveform(spec)
        assertEquals(480, chirp.lengthSamples)
        assertEquals(480, chirp.pcm.size)
    }

    @Test
    fun waveformStaysInRangeAndUsesBothChannels() {
        val chirp = ChirpWaveform(spec)
        assertTrue(chirp.inPhase.all { it in -1.001f..1.001f })
        assertTrue(chirp.quadrature.any { abs(it) > 0.5f })
        assertTrue(chirp.pcm.any { it.toInt() != 0 })
    }

    @Test
    fun edgesAreFadedToAvoidClicks() {
        val chirp = ChirpWaveform(spec)
        assertEquals(0f, abs(chirp.inPhase.first()), 0.02f)
        assertEquals(0f, abs(chirp.inPhase.last()), 0.02f)
    }

    @Test
    fun spectrumSitsAtTheRequestedCentreFrequency() {
        val chirp = ChirpWaveform(spec)
        // Zero-pad so the estimate is not limited by the short burst length.
        val padded = FloatArray(8192)
        System.arraycopy(chirp.inPhase, 0, padded, 0, chirp.lengthSamples)
        val measured = SynthRoom.dominantFrequencyHz(padded, spec.sampleRate)
        assertTrue("centre frequency $measured Hz", abs(measured - 19_000f) < 900f)
    }

    @Test
    fun rangeResolutionFollowsBandwidth() {
        assertEquals(343f / 4000f, spec.rangeResolutionM(343f), 1e-6f)
    }

    @Test
    fun validationRejectsNyquistViolations() {
        assertNull(spec.validate())
        assertNotNull(spec.copy(centerHz = 24_000f).validate())
        assertNotNull(spec.copy(bandwidthHz = 0f).validate())
        assertNotNull(spec.copy(durationMs = 0).validate())
        assertNotNull(spec.copy(amplitude = 1.5f).validate())
    }
}