package com.echosonar.sonar

import com.echosonar.dsp.PingResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Immutable view of the polar reflectivity map, handed to the UI once per ping.
 *
 * [cells] is angle-major: index = angleBin * rangeBins + rangeBin, values 0..1.
 * [nearestRangeM] / [strongestRangeM] hold one entry per angle bin (NaN where nothing was seen)
 * and are what the wireframe contour is drawn from.
 */
class MapSnapshot(
    val rangeBins: Int,
    val angleBins: Int,
    val cells: FloatArray,
    val nearestRangeM: FloatArray,
    val strongestRangeM: FloatArray,
    val strongestIntensity: FloatArray,
    val coverage: Float,
    val fill: Float,
    val meanRangeM: Float,
    val startRangeM: Float,
    val binSizeM: Float,
) {
    val hasData: Boolean get() = fill > 0f

    /** Circular 3-tap smoothing of the strongest-return contour for a cleaner wireframe. */
    fun smoothedStrongest(): FloatArray {
        val out = FloatArray(angleBins) { Float.NaN }
        for (a in 0 until angleBins) {
            var sum = 0f
            var count = 0
            for (d in -1..1) {
                val v = strongestRangeM[(a + d + angleBins) % angleBins]
                if (!v.isNaN()) {
                    sum += v
                    count++
                }
            }
            if (count > 0 && strongestIntensity[a] > 0f) out[a] = sum / count
        }
        return out
    }
}

/**
 * Accumulates range profiles into a decaying polar map.
 *
 * A single speaker and a single microphone cannot measure bearing, so the angle is supplied by the
 * sweep source (the device's rotation, or a synthetic sweep). What the map really shows is "this
 * echo came back while the phone was pointing this way", which is how phone-based sonar imaging ends
 * up looking like a floor plan.
 */
class MapAccumulator(
    val rangeBins: Int,
    val angleBins: Int,
    val startRangeM: Float,
    val binSizeM: Float,
) {
    private val cells = FloatArray(rangeBins * angleBins)
    private val support = FloatArray(rangeBins * angleBins)
    private val nearest = FloatArray(angleBins)
    private val strongest = FloatArray(angleBins)
    private val strongestIntensity = FloatArray(angleBins)

    var ingestCount: Long = 0
        private set

    fun reset() {
        cells.fill(0f)
        support.fill(0f)
        ingestCount = 0
    }

    fun angleToBin(angleRad: Float): Int {
        var a = angleRad % (2f * PI.toFloat())
        if (a < 0f) a += 2f * PI.toFloat()
        return ((a / (2f * PI.toFloat())) * angleBins).roundToInt().mod(angleBins)
    }

    fun ingest(result: PingResult, neighbourWeight: Float = 0.42f) {
        val profile = result.profile
        if (profile.size != rangeBins) return
        val angle = angleToBin(result.angleRad)
        val neighbours = intArrayOf((angle + 1) % angleBins, (angle - 1 + angleBins) % angleBins)
        for (bin in 0 until rangeBins) {
            val value = profile.intensity[bin]
            if (value <= 0f) continue
            blend(angle, bin, value, 1f)
            for (n in neighbours) blend(n, bin, value, neighbourWeight)
        }
        ingestCount++
    }

    private fun blend(angleBin: Int, rangeBin: Int, value: Float, weight: Float) {
        val index = angleBin * rangeBins + rangeBin
        val v = value * weight
        if (v > cells[index]) {
            // Max-blend keeps walls crisp instead of smearing them into clouds.
            cells[index] = v
            support[index] = (support[index] + 1f).coerceAtMost(64f)
        } else {
            support[index] = (support[index] + 0.25f).coerceAtMost(64f)
        }
    }

    /** Exponential phosphor decay. [halfLifeSec] <= 0 keeps the map indefinitely. */
    fun decay(dtSeconds: Float, halfLifeSec: Float) {
        if (dtSeconds <= 0f) return
        if (halfLifeSec <= 0f) return
        val factor = exp(-ln(2f) * (dtSeconds / halfLifeSec))
        for (i in cells.indices) {
            cells[i] *= factor
            support[i] *= factor
        }
    }

    fun snapshot(): MapSnapshot {
        nearest.fill(Float.NaN)
        strongest.fill(Float.NaN)
        strongestIntensity.fill(0f)
        var occupied = 0
        var occupiedAngles = 0
        var rangeSum = 0f
        var rangeCount = 0

        for (a in 0 until angleBins) {
            val base = a * rangeBins
            var bestBin = -1
            var bestValue = 0f
            var nearBin = -1
            for (bin in 0 until rangeBins) {
                val v = cells[base + bin]
                if (v >= 0.08f) {
                    occupied++
                    if (nearBin < 0) nearBin = bin
                }
                if (v > bestValue) {
                    bestValue = v
                    bestBin = bin
                }
            }
            if (bestValue >= 0.08f && bestBin >= 0) {
                strongest[a] = startRangeM + (bestBin + 0.5f) * binSizeM
                strongestIntensity[a] = bestValue
            }
            if (nearBin >= 0) {
                nearest[a] = startRangeM + (nearBin + 0.5f) * binSizeM
                occupiedAngles++
                rangeSum += nearest[a]
                rangeCount++
            }
        }

        val total = (rangeBins * angleBins).toFloat()
        return MapSnapshot(
            rangeBins = rangeBins,
            angleBins = angleBins,
            cells = cells.copyOf(),
            nearestRangeM = nearest.copyOf(),
            strongestRangeM = strongest.copyOf(),
            strongestIntensity = strongestIntensity.copyOf(),
            coverage = occupiedAngles.toFloat() / angleBins,
            fill = occupied / max(total, 1f),
            meanRangeM = if (rangeCount > 0) rangeSum / rangeCount else 0f,
            startRangeM = startRangeM,
            binSizeM = binSizeM,
        )
    }

    /** Cell at a given polar coordinate, for diagnostics and tests. */
    fun cellAt(angleRad: Float, rangeM: Float): Float {
        val bin = ((rangeM - startRangeM) / binSizeM).toInt().coerceIn(0, rangeBins - 1)
        return cells[angleToBin(angleRad) * rangeBins + bin]
    }

    companion object {
        /** Sanity helper for tests: absolute angular difference in radians. */
        fun angleDistance(a: Float, b: Float): Float {
            val d = abs((a - b).mod(2f * PI.toFloat()))
            return if (d > PI.toFloat()) 2f * PI.toFloat() - d else d
        }
    }
}