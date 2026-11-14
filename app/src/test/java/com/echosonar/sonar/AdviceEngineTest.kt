package com.echosonar.sonar

import com.echosonar.dsp.SonarConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdviceEngineTest {

    private val config = SonarConfig()

    private fun emptyMap(): MapSnapshot = MapAccumulator(
        config.rangeBins,
        72,
        config.minRangeM,
        config.rangeBinM,
    ).snapshot()

    private fun recommended(stats: SessionStats, map: MapSnapshot = emptyMap(), rotate: Boolean = false): List<Advice> =
        AdviceEngine().evaluate(nowMs = 1_000L, stats = stats, config = config, map = map, rotateMode = rotate)

    @Test
    fun noEchoesProducesGuidance() {
        val advice = recommended(SessionStats(pingCount = 12, echoPingCount = 0, emaQuality = 5f))
        assertTrue(advice.any { it.id == "no-echoes" })
        assertTrue(advice.first().severity.ordinal >= AdviceSeverity.INFO.ordinal)
    }

    @Test
    fun clippingIsFlagged() {
        val advice = recommended(
            SessionStats(pingCount = 10, echoPingCount = 4, clippingRatio = 0.4f, emaDirectAmp = 0.3f),
        )
        val clip = advice.first { it.id == "clipping" }
        assertEquals(AdviceSeverity.WARN, clip.severity)
        assertTrue(clip.action!!.contains("volume"))
    }

    @Test
    fun fastRotationIsFlaggedOnlyInRotateMode() {
        val stats = SessionStats(pingCount = 20, echoPingCount = 10, rotationSpeedDeg = 140f, emaQuality = 60f)
        assertTrue(recommended(stats, rotate = true).any { it.id == "rotate-fast" })
        assertTrue(recommended(stats, rotate = false).none { it.id == "rotate-fast" })
    }

    @Test
    fun strongSignalProducesPositiveFeedback() {
        val mapMap = MapAccumulator(config.rangeBins, 72, config.minRangeM, config.rangeBinM)
        // Fill the map so coverage is high.
        for (a in 0 until 72) {
            mapMap.ingest(samplePing(a, config))
        }
        val advice = AdviceEngine(defaultCooldownMs = 0).evaluate(
            nowMs = 5_000L,
            stats = SessionStats(
                pingCount = 30,
                echoPingCount = 28,
                emaQuality = 78f,
                emaPeakSnrDb = 26f,
                emaDirectAmp = 0.25f,
                elapsedMs = 12_000L,
            ),
            config = config,
            map = mapMap.snapshot(),
            rotateMode = false,
        )
        assertTrue(advice.any { it.id == "good-signal" })
        assertTrue(advice.any { it.id == "map-growing" })
    }

    @Test
    fun cooldownPreventsDuplicateSpam() {
        val engine = AdviceEngine(defaultCooldownMs = 10_000L)
        val stats = SessionStats(pingCount = 12, echoPingCount = 0, emaQuality = 4f)
        val first = engine.evaluate(1_000L, stats, config, emptyMap(), rotateMode = false)
        val second = engine.evaluate(2_000L, stats, config, emptyMap(), rotateMode = false)
        assertTrue(first.any { it.id == "no-echoes" })
        assertTrue(second.none { it.id == "no-echoes" })
        val third = engine.evaluate(30_000L, stats, config, emptyMap(), rotateMode = false)
        assertTrue(third.any { it.id == "no-echoes" })
    }

    @Test
    fun audioErrorIsCritical() {
        val advice = AdviceEngine().evaluate(
            1_000L,
            SessionStats(pingCount = 1),
            config,
            emptyMap(),
            rotateMode = false,
            audioError = "AudioRecord failed to initialise",
        )
        val error = advice.first { it.id == "audio-error" }
        assertEquals(AdviceSeverity.CRITICAL, error.severity)
    }

    @Test
    fun unlockedTimingIsCritical() {
        val advice = recommended(
            SessionStats(pingCount = 10, echoPingCount = 3, unlockRatio = 0.8f, emaDirectAmp = 0.001f),
        )
        assertTrue(advice.any { it.id == "timing-unlocked" })
    }

    @Test
    fun alwaysReturnsAtLeastOneHint() {
        assertTrue(recommended(SessionStats(pingCount = 1)).isNotEmpty())
    }

    private fun samplePing(angleBin: Int, config: SonarConfig): com.echosonar.dsp.PingResult {
        val bins = config.rangeBins
        val snr = FloatArray(bins) { -5f }
        val intensity = FloatArray(bins)
        val bin = ((1.4f - config.minRangeM) / config.rangeBinM).toInt()
        intensity[bin] = 0.7f
        snr[bin] = 24f
        return com.echosonar.dsp.PingResult(
            index = angleBin.toLong(),
            timestampMs = angleBin * 100L,
            angleRad = angleBin * (2f * Math.PI.toFloat() / 72),
            profile = com.echosonar.dsp.RangeProfile(snr, intensity, config.rangeBinM, config.minRangeM),
            metrics = com.echosonar.dsp.PingMetrics(
                directAmp = 0.25f,
                directLagMs = 30f,
                directLocked = true,
                noiseFloorDb = -65f,
                peakSnrDb = 24f,
                peakRangeM = 1.4f,
                nearestRangeM = 1.4f,
                detectedBins = 1,
                clipped = false,
                inputPeak = 0.5f,
                inputRms = 0.02f,
                quality = 80,
                windowMs = config.windowMs,
                rangeResolutionM = config.rangeResolutionM,
            ),
        )
    }
}