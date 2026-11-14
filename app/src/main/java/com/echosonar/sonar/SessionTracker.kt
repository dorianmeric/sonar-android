package com.echosonar.sonar

import com.echosonar.dsp.PingResult
import kotlin.math.abs
import kotlin.math.max

/**
 * Rolling session statistics feeding both the HUD and the advice engine.
 */
data class SessionStats(
    val elapsedMs: Long = 0L,
    val pingCount: Int = 0,
    val echoPingCount: Int = 0,
    val emaQuality: Float = 0f,
    val emaPeakSnrDb: Float = 0f,
    val emaDirectAmp: Float = 0f,
    val emaNearestRangeM: Float = 0f,
    val lastNearestRangeM: Float = 0f,
    val lastPeakRangeM: Float = 0f,
    val emaNoiseFloorDb: Float = 0f,
    val emaInputRms: Float = 0f,
    val clippingRatio: Float = 0f,
    val unlockRatio: Float = 0f,
    val rotationSpeedDeg: Float = 0f,
) {
    val detectedRate: Float get() = if (pingCount > 0) echoPingCount.toFloat() / pingCount else 0f
}

/**
 * Exponentially weighted averaging keeps the advice reactive without flip-flopping on single pings.
 */
class SessionTracker(private val alpha: Float = 0.18f) {

    private var startedAtMs = 0L
    private var pingCount = 0
    private var echoPingCount = 0
    private var clippedCount = 0
    private var unlockedCount = 0
    private var emaQuality = 0f
    private var emaPeakSnr = 0f
    private var emaDirectAmp = 0f
    private var emaNearest = 0f
    private var lastNearest = 0f
    private var lastPeak = 0f
    private var emaNoiseFloor = 0f
    private var emaInputRms = 0f
    private var lastRotationSpeed = 0f
    private var initialised = false

    fun reset(nowMs: Long) {
        startedAtMs = nowMs
        pingCount = 0
        echoPingCount = 0
        clippedCount = 0
        unlockedCount = 0
        emaQuality = 0f
        emaPeakSnr = 0f
        emaDirectAmp = 0f
        emaNearest = 0f
        lastNearest = 0f
        lastPeak = 0f
        emaNoiseFloor = 0f
        emaInputRms = 0f
        lastRotationSpeed = 0f
        initialised = false
    }

    fun record(result: PingResult, rotationSpeedDeg: Float, nowMs: Long) {
        if (pingCount == 0) startedAtMs = nowMs
        val m = result.metrics
        pingCount++
        if (m.detectedBins > 0) echoPingCount++
        if (m.clipped) clippedCount++
        if (!m.directLocked) unlockedCount++
        lastRotationSpeed = abs(rotationSpeedDeg)

        if (!initialised) {
            emaQuality = m.quality.toFloat()
            emaPeakSnr = m.peakSnrDb
            emaDirectAmp = m.directAmp
            emaNoiseFloor = m.noiseFloorDb
            emaInputRms = m.inputRms
            emaNearest = if (m.nearestRangeM > 0f) m.nearestRangeM else 0f
            initialised = true
        } else {
            emaQuality += alpha * (m.quality - emaQuality)
            emaPeakSnr += alpha * (m.peakSnrDb.coerceAtLeast(-30f) - emaPeakSnr)
            emaDirectAmp += alpha * (m.directAmp - emaDirectAmp)
            emaNoiseFloor += alpha * (m.noiseFloorDb - emaNoiseFloor)
            emaInputRms += alpha * (m.inputRms - emaInputRms)
            if (m.nearestRangeM > 0f) emaNearest += alpha * (m.nearestRangeM - emaNearest)
        }
        if (m.nearestRangeM > 0f) lastNearest = m.nearestRangeM
        if (m.peakRangeM > 0f) lastPeak = m.peakRangeM
    }

    fun stats(nowMs: Long): SessionStats = SessionStats(
        elapsedMs = max(0L, nowMs - startedAtMs),
        pingCount = pingCount,
        echoPingCount = echoPingCount,
        emaQuality = emaQuality,
        emaPeakSnrDb = emaPeakSnr,
        emaDirectAmp = emaDirectAmp,
        emaNearestRangeM = emaNearest,
        lastNearestRangeM = lastNearest,
        lastPeakRangeM = lastPeak,
        emaNoiseFloorDb = emaNoiseFloor,
        emaInputRms = emaInputRms,
        clippingRatio = if (pingCount > 0) clippedCount.toFloat() / pingCount else 0f,
        unlockRatio = if (pingCount > 0) unlockedCount.toFloat() / pingCount else 0f,
        rotationSpeedDeg = lastRotationSpeed,
    )
}