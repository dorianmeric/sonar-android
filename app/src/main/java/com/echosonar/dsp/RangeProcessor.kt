package com.echosonar.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One range-binned A-scan: echo strength in dB relative to the range-local noise floor. */
class RangeProfile(
    val snrDb: FloatArray,
    val intensity: FloatArray,
    val binSizeM: Float,
    val startRangeM: Float,
) {
    val size: Int get() = snrDb.size

    fun rangeOfBin(bin: Int): Float = startRangeM + (bin + 0.5f) * binSizeM

    fun copy(): RangeProfile = RangeProfile(snrDb.copyOf(), intensity.copyOf(), binSizeM, startRangeM)
}

/** Per-ping diagnostics used for the HUD, the advice engine and the map colouring. */
data class PingMetrics(
    val directAmp: Float,
    val directLagMs: Float,
    val directLocked: Boolean,
    val noiseFloorDb: Float,
    val peakSnrDb: Float,
    val peakRangeM: Float,
    val nearestRangeM: Float,
    val detectedBins: Int,
    val clipped: Boolean,
    val inputPeak: Float,
    val inputRms: Float,
    val quality: Int,
    val windowMs: Int,
    val rangeResolutionM: Float,
)

data class PingResult(
    val index: Long,
    val timestampMs: Long,
    val angleRad: Float,
    val profile: RangeProfile,
    val metrics: PingMetrics,
)

/**
 * Turns a raw capture window into a calibrated range profile.
 *
 * Pipeline per ping:
 *  1. matched-filter the window (FFT correlation against the chirp's I/Q references),
 *  2. find the direct speaker -> microphone arrival, which is the self-calibrating zero for
 *     latency and level,
 *  3. subtract that coupling and re-correlate, removing its autocorrelation skirt,
 *  4. bin into range cells, compensate spreading loss,
 *  5. detect *peaks against a range-local noise floor* rather than a single global floor, which is
 *     what makes reverberant rooms readable.
 */
class RangeProcessor(val config: SonarConfig) {

    /** Live-tunable sensitivity and gain: adjusting the slider must not tear down the audio path. */
    var thresholdDb: Float = config.thresholdDb
    var gainDb: Float = config.gainDb

    val matched = MatchedFilter(config)
    val windowSamples: Int get() = matched.windowSamples

    private val fs = config.sampleRateHz.toFloat()
    private val c = config.speedOfSound
    private val lagCount = matched.lagCount
    private val refLengthHint = matched.refLength
    private val rangeBins = config.rangeBins
    private val amp0Minimum = 0.004f

    private val binLagStart = IntArray(rangeBins)
    private val binLagEnd = IntArray(rangeBins)
    private val tvgDb = FloatArray(rangeBins)
    private val binCenters = FloatArray(rangeBins)

    private val percentileScratch = FloatArray(lagCount)
    private val envScratch = FloatArray(lagCount)
    private val snrScratch = FloatArray(rangeBins)
    private val intensityScratch = FloatArray(rangeBins)
    private val binPeakAmp = FloatArray(rangeBins)
    private val binPeakLag = IntArray(rangeBins)
    private val binSortScratch = FloatArray(rangeBins)
    private val localFloor = FloatArray(rangeBins)

    init {
        for (bin in 0 until rangeBins) {
            val rangeStart = config.minRangeM + bin * config.rangeBinM
            val rangeEnd = rangeStart + config.rangeBinM
            binCenters[bin] = rangeStart + config.rangeBinM * 0.5f
            binLagStart[bin] = ((rangeStart * 2f * fs / c) - 0.5f).roundToInt()
            binLagEnd[bin] = max(binLagStart[bin] + 1, (rangeEnd * 2f * fs / c).roundToInt())
            tvgDb[bin] = min(38f, SonarConfig.spreadingLossDb(binCenters[bin]))
        }
        require(binLagEnd[rangeBins - 1] < lagCount) {
            "range bins exceed the capture window; raise latency margin"
        }
    }

    fun lagToRangeM(lagOffset: Int): Float = lagOffset / fs * c * 0.5f

    fun process(window: FloatArray, angleRad: Float, timestampMs: Long, index: Long): PingResult {
        var env = matched.process(window)
        System.arraycopy(env, 0, envScratch, 0, lagCount)

        // 1. Locate the direct-path coupling. The direct path is by definition the *first* arrival, so
        //    take the earliest envelope peak reaching a meaningful fraction of the strongest peak in
        //    the latency margin. A plain global maximum would lock onto a loud nearby wall.
        val globalNoise = SonarConfig.percentile(envScratch, 0, lagCount, 0.25f, percentileScratch)
        var globalMax = 0f
        for (lag in 0..matched.directSearchLags) {
            val v = envScratch[lag]
            if (v > globalMax) globalMax = v
        }
        val onsetThreshold = max(0.30f * globalMax, 4f * globalNoise)
        var t0 = -1
        val peakSearchEnd = min(matched.directSearchLags, lagCount - 2)
        for (lag in 2..peakSearchEnd) {
            if (envScratch[lag] >= onsetThreshold) {
                var bestLag = lag
                var bestVal = envScratch[lag]
                val limit = min(peakSearchEnd, lag + refLengthHint)
                var k = lag
                while (k <= limit) {
                    if (envScratch[k] > bestVal) {
                        bestVal = envScratch[k]
                        bestLag = k
                    }
                    k++
                }
                t0 = bestLag
                break
            }
        }
        if (t0 < 0) t0 = 0
        val amp0 = envScratch[t0]

        // 2. Quick global noise estimate, used only to sanity-check the direct-path lock.
        val noiseFrom = min(lagCount - 1, t0 + binLagStart[0])
        val noiseAmp = if (lagCount - noiseFrom > 16) {
            SonarConfig.percentile(envScratch, noiseFrom, lagCount, 0.3f, percentileScratch)
        } else {
            0f
        }
        val directLocked = amp0 > max(amp0Minimum, 6f * noiseAmp) && t0 > 1 && t0 <= peakSearchEnd

        // 3. Subtract the coupling (two projection passes so the first pass's own noise projection is
        //    cancelled too) and re-correlate. Without this the coupling's autocorrelation skirt, which
        //    is only ~20 dB down at short lags, looks like a wall at every distance.
        env = matched.process(window, 0, t0)
        System.arraycopy(env, 0, envScratch, 0, lagCount)

        // 4. Reduce every range cell to its strongest residual echo.
        for (bin in 0 until rangeBins) {
            var peak = 0f
            var peakLag = -1
            var lag = t0 + binLagStart[bin]
            val end = t0 + binLagEnd[bin]
            while (lag < end) {
                if (lag >= 0 && lag < lagCount) {
                    val v = envScratch[lag]
                    if (v > peak) {
                        peak = v
                        peakLag = lag
                    }
                }
                lag++
            }
            binPeakAmp[bin] = peak
            binPeakLag[bin] = peakLag
        }
        val noiseAmpBin = SonarConfig.percentile(binPeakAmp, 0, rangeBins, 0.5f, binSortScratch)
            .coerceAtLeast(1e-9f)
        val noiseRefDb = 20f * log10(noiseAmpBin)

        // 5. Compensate spreading loss, then compare each cell against a range-local floor. A single
        //    global floor cannot tell a wall from the reverberant/sidelobe plateau between walls; a
        //    low percentile keeps the reference at the true noise level even when an echo's own
        //    correlation skirt stretches across several cells.
        for (bin in 0 until rangeBins) {
            snrScratch[bin] = 20f * log10(max(binPeakAmp[bin], 1e-9f)) - noiseRefDb + tvgDb[bin] + gainDb
        }
        val localRadius = 6
        for (bin in 0 until rangeBins) {
            val from = max(0, bin - localRadius)
            val to = min(rangeBins, bin + localRadius + 1)
            localFloor[bin] = SonarConfig.percentile(snrScratch, from, to, 0.25f, binSortScratch)
        }

        var detected = 0
        var bestProminence = -120f
        var bestBin = -1
        var nearest = -1
        for (bin in 0 until rangeBins) {
            val prominence = snrScratch[bin] - localFloor[bin]
            val localMax = isLocalMaximum(bin)
            val visible = prominence >= thresholdDb && localMax && snrScratch[bin] > 0f
            intensityScratch[bin] = if (visible) {
                // A visible echo starts at 0.25 brightness: marginal returns must still draw, otherwise
                // the map looks empty in exactly the quiet rooms where it is most useful.
                0.25f + 0.75f * ((prominence - thresholdDb) / 16f).coerceIn(0f, 1f)
            } else {
                0f
            }
            if (visible) {
                detected++
                if (nearest < 0) nearest = bin
                if (prominence > bestProminence) {
                    bestProminence = prominence
                    bestBin = bin
                }
            }
        }

        // 6. Sub-bin peak range via parabolic interpolation of the envelope.
        val peakRange = if (bestBin >= 0) {
            val peakLag = binPeakLag[bestBin]
            if (peakLag < 0) {
                -1f
            } else {
                val a = envScratch[max(0, peakLag - 1)]
                val b = envScratch[min(lagCount - 1, peakLag)]
                val cc = envScratch[min(lagCount - 1, peakLag + 1)]
                val denom = a - 2f * b + cc
                val delta = if (abs(denom) > 1e-12f) (0.5f * (a - cc) / denom).coerceIn(-0.5f, 0.5f) else 0f
                lagToRangeM(peakLag - t0) + delta * config.rangeBinM
            }
        } else {
            -1f
        }
        val nearestRange = if (nearest >= 0) binCenters[nearest] else -1f

        val metrics = PingMetrics(
            directAmp = amp0,
            directLagMs = t0 * 1000f / fs,
            directLocked = directLocked,
            noiseFloorDb = 20f * log10(max(noiseAmpBin / max(amp0, 1e-9f), 1e-9f)),
            peakSnrDb = bestProminence,
            peakRangeM = peakRange,
            nearestRangeM = nearestRange,
            detectedBins = detected,
            clipped = matched.lastPeakAbs >= 0.995f,
            inputPeak = matched.lastPeakAbs,
            inputRms = matched.lastRms,
            quality = computeQuality(bestProminence, amp0, detected),
            windowMs = config.windowMs,
            rangeResolutionM = config.rangeResolutionM,
        )

        return PingResult(
            index = index,
            timestampMs = timestampMs,
            angleRad = angleRad,
            profile = RangeProfile(
                snrScratch.copyOf(),
                intensityScratch.copyOf(),
                config.rangeBinM,
                config.minRangeM,
            ),
            metrics = metrics,
        )
    }

    private fun isLocalMaximum(bin: Int): Boolean {
        val value = snrScratch[bin]
        for (offset in -2..2) {
            if (offset == 0) continue
            val other = bin + offset
            if (other in 0 until rangeBins && snrScratch[other] > value) return false
        }
        return true
    }

    private fun computeQuality(peakProminenceDb: Float, directAmp: Float, detectedBins: Int): Int {
        val snrScore = ((peakProminenceDb - 3f) / 25f).coerceIn(0f, 1f)
        val driveScore = (directAmp / 0.06f).coerceIn(0f, 1f)
        val densityScore = (detectedBins / 4f).coerceIn(0f, 1f)
        val score = 0.66f * snrScore + 0.24f * driveScore + 0.10f * densityScore
        return (score * 100f).roundToInt()
    }

    companion object {
        /** Theoretical two-way range for a sample lag. */
        fun lagToRange(lagSamples: Int, sampleRateHz: Int, speedOfSound: Float): Float =
            lagSamples.toFloat() / sampleRateHz * speedOfSound * 0.5f
    }
}