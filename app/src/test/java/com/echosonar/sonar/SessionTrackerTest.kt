package com.echosonar.sonar

import com.echosonar.dsp.PingMetrics
import com.echosonar.dsp.PingResult
import com.echosonar.dsp.RangeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrackerTest {

    private fun ping(
        detected: Boolean,
        quality: Int = 70,
        clipped: Boolean = false,
        locked: Boolean = true,
        nearest: Float = 1.5f,
    ): PingResult {
        val bins = 40
        val snr = FloatArray(bins) { if (detected) 20f else 0f }
        val intensity = FloatArray(bins) { if (detected) 0.6f else 0f }
        return PingResult(
            index = 1,
            timestampMs = 0,
            angleRad = 0f,
            profile = RangeProfile(snr, intensity, 0.05f, 0.3f),
            metrics = PingMetrics(
                directAmp = if (locked) 0.2f else 0.0005f,
                directLagMs = 30f,
                directLocked = locked,
                noiseFloorDb = -60f,
                peakSnrDb = if (detected) 22f else -5f,
                peakRangeM = if (detected) nearest else -1f,
                nearestRangeM = if (detected) nearest else -1f,
                detectedBins = if (detected) 1 else 0,
                clipped = clipped,
                inputPeak = 0.4f,
                inputRms = 0.01f,
                quality = quality,
                windowMs = 280,
                rangeResolutionM = 0.06f,
            ),
        )
    }

    @Test
    fun countsAndEmaTrackSession() {
        val tracker = SessionTracker(alpha = 0.5f)
        tracker.reset(nowMs = 1_000L)
        tracker.record(ping(detected = false, quality = 10, clipped = true), rotationSpeedDeg = -30f, nowMs = 1_000L)
        tracker.record(ping(detected = true, quality = 90), rotationSpeedDeg = 20f, nowMs = 2_000L)
        tracker.record(ping(detected = true, quality = 90), rotationSpeedDeg = 20f, nowMs = 3_000L)

        val stats = tracker.stats(nowMs = 5_000L)
        assertEquals(3, stats.pingCount)
        assertEquals(2, stats.echoPingCount)
        assertEquals(4_000L, stats.elapsedMs)
        assertTrue(stats.clippingRatio in 0.3f..0.34f)
        assertTrue(stats.emaQuality > 60f)
        assertTrue(stats.emaNearestRangeM > 1f)
        assertEquals(20f, stats.rotationSpeedDeg, 0.01f)
        assertTrue(stats.detectedRate > 0.6f)
    }

    @Test
    fun unlockRatioUsesLockedFlag() {
        val tracker = SessionTracker()
        tracker.reset(0L)
        repeat(4) { tracker.record(ping(detected = true, locked = false), 0f, it * 100L) }
        val stats = tracker.stats(1_000L)
        assertEquals(1f, stats.unlockRatio, 0.001f)
    }

    @Test
    fun resetClearsEverything() {
        val tracker = SessionTracker()
        tracker.reset(0L)
        tracker.record(ping(detected = true), 0f, 100L)
        tracker.reset(500L)
        val stats = tracker.stats(1_000L)
        assertEquals(0, stats.pingCount)
        assertEquals(0f, stats.emaQuality, 0f)
        assertEquals(500L, stats.elapsedMs)
    }
}