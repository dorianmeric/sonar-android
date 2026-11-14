package com.echosonar.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeProcessorTest {

    private val config = SonarConfig()
    private val processor = RangeProcessor(config)

    private fun profileFor(
        echoes: List<SynthRoom.Echo>,
        noise: Float = 0.002f,
        directAmplitude: Float = 0.6f,
        directDelayMs: Float = 30f,
    ): PingResult {
        val capture = SynthRoom.capture(
            config = config,
            directDelayMs = directDelayMs,
            directAmplitude = directAmplitude,
            echoes = echoes,
            noiseRms = noise,
        )
        return processor.process(capture, angleRad = 0f, timestampMs = 1000L, index = 1L)
    }

    private fun RangeProfile.dbNear(rangeM: Float): Float {
        var best = -120f
        for (bin in 0 until size) {
            if (kotlin.math.abs(rangeOfBin(bin) - rangeM) <= config.rangeBinM) best = maxOf(best, snrDb[bin])
        }
        return best
    }

    @Test
    fun findsSingleWallAtCorrectRange() {
        val result = profileFor(listOf(SynthRoom.Echo(2.0f, 0.25f)))
        assertEquals(2.0f, result.metrics.peakRangeM, 0.12f)
        assertTrue(result.metrics.detectedBins > 0)
        assertTrue(result.metrics.quality > 20)
    }

    @Test
    fun reportsLockedDirectPath() {
        val result = profileFor(listOf(SynthRoom.Echo(1.5f, 0.2f)))
        assertTrue(result.metrics.directLocked)
        assertEquals(30f, result.metrics.directLagMs, 6f)
        assertTrue(result.metrics.directAmp > 0.01f)
    }

    @Test
    fun detectsBothWallsInProfile() {
        val result = profileFor(
            listOf(SynthRoom.Echo(1.0f, 0.3f), SynthRoom.Echo(2.5f, 0.12f)),
        )
        assertTrue(result.profile.dbNear(1.0f) > config.thresholdDb)
        assertTrue(result.profile.dbNear(2.5f) > config.thresholdDb)
    }

    @Test
    fun noiseFloorStaysLowWithCleanSignal() {
        val result = profileFor(listOf(SynthRoom.Echo(1.5f, 0.3f)), noise = 0.0005f)
        assertTrue("noise floor ${result.metrics.noiseFloorDb}", result.metrics.noiseFloorDb < -40f)
        assertTrue(result.metrics.peakSnrDb > 20f)
    }

    @Test
    fun loudBroadbandNoiseStillAllowsDetection() {
        // The matched filter integrates over time-bandwidth product, so wideband room noise matters
        // far less than in-band ultrasonic interference.
        val quiet = profileFor(listOf(SynthRoom.Echo(1.2f, 0.2f)), noise = 0.001f)
        val loud = profileFor(listOf(SynthRoom.Echo(1.2f, 0.2f)), noise = 0.08f)
        assertTrue("echo still found in loud room", loud.metrics.detectedBins > 0)
        assertTrue(loud.metrics.quality <= quiet.metrics.quality)
    }

    @Test
    fun weakerEchoYieldsLowerQuality() {
        val strong = profileFor(listOf(SynthRoom.Echo(1.2f, 0.3f)), noise = 0.002f)
        val weak = profileFor(listOf(SynthRoom.Echo(1.2f, 0.004f)), noise = 0.002f)
        assertTrue(weak.metrics.quality < strong.metrics.quality)
        assertTrue(weak.metrics.peakSnrDb < strong.metrics.peakSnrDb)
    }

    @Test
    fun emptyRoomReportsNoDetections() {
        val result = profileFor(emptyList(), noise = 0.0005f)
        assertEquals(0, result.metrics.detectedBins)
        assertTrue(result.metrics.peakRangeM <= 0f)
    }

    @Test
    fun clippingIsFlagged() {
        val result = profileFor(listOf(SynthRoom.Echo(1.0f, 0.4f)), directAmplitude = 1.0f)
        assertTrue(result.metrics.clipped)
        assertFalse(profileFor(listOf(SynthRoom.Echo(1.0f, 0.4f))).metrics.clipped)
    }

    @Test
    fun farWeakEchoIsRecoveredByTimeVariableGain() {
        val result = profileFor(listOf(SynthRoom.Echo(3.5f, 0.02f)), noise = 0.001f)
        assertTrue("far echo snr ${result.profile.dbNear(3.5f)}", result.profile.dbNear(3.5f) > config.thresholdDb)
    }

    @Test
    fun blindZoneSuppressesVeryCloseReturns() {
        val result = profileFor(listOf(SynthRoom.Echo(0.12f, 0.5f)), noise = 0.0005f)
        assertTrue(result.profile.dbNear(0.12f) < config.thresholdDb)
    }

    @Test
    fun profileGeometryIsConsistent() {
        val result = profileFor(listOf(SynthRoom.Echo(1.0f, 0.2f)))
        assertEquals(config.rangeBins, result.profile.size)
        assertEquals(config.minRangeM + 0.5f * config.rangeBinM, result.profile.rangeOfBin(0), 1e-5f)
        assertEquals(config.windowMs, result.metrics.windowMs)
        assertTrue(result.profile.size == result.profile.intensity.size)
    }

    @Test
    fun configDerivationsAreSane() {
        assertEquals(343.42f, SonarConfig(temperatureC = 20f).speedOfSound, 0.01f)
        assertTrue(config.windowMs > config.latencyMarginMs + config.chirpMs)
        assertTrue(config.rangeBins > 10)
        assertEquals(3.43f, RangeProcessor.lagToRange(960, 48_000, 343.0f), 0.01f)
        assertTrue(config.rangeResolutionM < 0.1f)
    }
}