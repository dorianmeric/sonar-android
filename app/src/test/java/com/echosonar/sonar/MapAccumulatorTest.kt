package com.echosonar.sonar

import com.echosonar.dsp.PingMetrics
import com.echosonar.dsp.PingResult
import com.echosonar.dsp.RangeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class MapAccumulatorTest {

    private val bins = 80
    private val start = 0.3f
    private val binSize = 0.05f
    private val angles = 72

    private fun ping(
        rangeM: Float,
        angleRad: Float,
        intensity: Float = 0.8f,
        index: Long = 1L,
    ): PingResult {
        val snr = FloatArray(bins) { -10f }
        val values = FloatArray(bins)
        val bin = ((rangeM - start) / binSize).toInt().coerceIn(0, bins - 1)
        snr[bin] = 30f
        values[bin] = intensity
        return PingResult(
            index = index,
            timestampMs = index * 100,
            angleRad = angleRad,
            profile = RangeProfile(snr, values, binSize, start),
            metrics = PingMetrics(
                directAmp = 0.2f,
                directLagMs = 30f,
                directLocked = true,
                noiseFloorDb = -70f,
                peakSnrDb = 30f,
                peakRangeM = rangeM,
                nearestRangeM = rangeM,
                detectedBins = 1,
                clipped = false,
                inputPeak = 0.4f,
                inputRms = 0.01f,
                quality = 80,
                windowMs = 280,
                rangeResolutionM = 0.06f,
            ),
        )
    }

    @Test
    fun ingestsEchoAtItsAngle() {
        val map = MapAccumulator(bins, angles, start, binSize)
        map.ingest(ping(1.5f, 0f))
        assertTrue(map.cellAt(0f, 1.5f) > 0.7f)
        assertTrue(map.cellAt(PI.toFloat(), 1.5f) < 0.01f)
    }

    @Test
    fun smearsIntoNeighbouringAngles() {
        val map = MapAccumulator(bins, angles, start, binSize)
        map.ingest(ping(2.0f, PI.toFloat() / 2f))
        val centre = map.cellAt(PI.toFloat() / 2f, 2.0f)
        val neighbour = map.cellAt(PI.toFloat() / 2f + (2f * PI.toFloat() / angles), 2.0f)
        assertTrue(centre > neighbour)
        assertTrue(neighbour > 0.1f)
    }

    @Test
    fun maxBlendKeepsStrongestReturn() {
        val map = MapAccumulator(bins, angles, start, binSize)
        map.ingest(ping(1.0f, 0f, intensity = 0.4f))
        map.ingest(ping(1.0f, 0f, intensity = 0.9f, index = 2))
        assertTrue(map.cellAt(0f, 1.0f) > 0.85f)
    }

    @Test
    fun decayHalvesAfterHalfLife() {
        val map = MapAccumulator(bins, angles, start, binSize)
        map.ingest(ping(1.0f, 0f, intensity = 1f))
        val before = map.cellAt(0f, 1.0f)
        map.decay(dtSeconds = 4f, halfLifeSec = 4f)
        val after = map.cellAt(0f, 1.0f)
        assertEquals(before * 0.5f, after, 1e-4f)
        map.decay(dtSeconds = 100f, halfLifeSec = 4f)
        assertTrue(map.cellAt(0f, 1.0f) < 1e-6f)
    }

    @Test
    fun snapshotReportsCoverageAndContours() {
        val map = MapAccumulator(bins, angles, start, binSize)
        for (i in 0 until 12) {
            map.ingest(ping(1.0f + i * 0.1f, i * (2f * PI.toFloat() / 12f), index = i.toLong()))
        }
        val snapshot = map.snapshot()
        assertTrue(snapshot.hasData)
        assertEquals(72, snapshot.angleBins)
        assertEquals(80, snapshot.rangeBins)
        assertTrue(snapshot.coverage > 0.3f)
        assertTrue(snapshot.fill > 0f)
        assertTrue(snapshot.meanRangeM > 0.9f)
        assertTrue(snapshot.strongestRangeM.none { it < 0f } || snapshot.strongestRangeM.any { !it.isNaN() })
        assertEquals(1.025f, snapshot.nearestRangeM[0], 0.001f)
    }

    @Test
    fun emptySnapshotHasNoData() {
        val snapshot = MapAccumulator(bins, angles, start, binSize).snapshot()
        assertFalse(snapshot.hasData)
        assertEquals(0f, snapshot.coverage, 0f)
        assertTrue(snapshot.nearestRangeM.all { it.isNaN() })
    }

    @Test
    fun resetClearsCells() {
        val map = MapAccumulator(bins, angles, start, binSize)
        map.ingest(ping(2.0f, 0f))
        map.reset()
        assertFalse(map.snapshot().hasData)
        assertEquals(0L, map.ingestCount)
    }

    @Test
    fun angleDistanceHandlesWraparound() {
        assertEquals(0.1f, MapAccumulator.angleDistance(0.05f, -0.05f), 1e-4f)
        assertEquals(0f, MapAccumulator.angleDistance(0f, 0f), 1e-6f)
    }
}