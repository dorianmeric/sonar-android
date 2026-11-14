package com.echosonar.dsp

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * All user-tunable sonar parameters plus the geometry derived from them.
 *
 * Everything downstream (chirp length, FFT window, range bins) is computed from this single
 * object, so changing a slider cannot desynchronise the emitter and the analyser.
 */
data class SonarConfig(
    val sampleRateHz: Int = 48_000,
    val centerHz: Float = 19_000f,
    val bandwidthHz: Float = 3_000f,
    val chirpMs: Int = 12,
    val amplitude: Float = 0.9f,
    val maxRangeM: Float = 4f,
    val minRangeM: Float = 0.3f,
    val latencyMarginMs: Int = 220,
    val temperatureC: Float = 21f,
    val gainDb: Float = 6f,
    val thresholdDb: Float = 12f,
    val pingIntervalMs: Int = 320,
    val rangeBinM: Float = 0.05f,
) {
    val speedOfSound: Float get() = 331.3f + 0.606f * temperatureC

    val chirpSpec: ChirpSpec
        get() = ChirpSpec(
            sampleRate = sampleRateHz,
            centerHz = centerHz,
            bandwidthHz = bandwidthHz,
            durationMs = chirpMs,
            amplitude = amplitude,
        )

    /** Time for the sound to travel out to [rangeM] and back. */
    fun roundTripMs(rangeM: Float): Float = 2000f * rangeM / speedOfSound

    val roundTripMaxMs: Float get() = roundTripMs(maxRangeM)

    /** Duration of the capture window needed for one ping, including output latency slack. */
    val windowMs: Int get() = latencyMarginMs + chirpMs + ceil(roundTripMaxMs).toInt() + 24

    val rangeBins: Int get() = max(4, floor(maxRangeM / rangeBinM).toInt())

    val rangeResolutionM: Float get() = chirpSpec.rangeResolutionM(speedOfSound)

    fun validate(): String? {
        chirpSpec.validate()?.let { return it }
        if (maxRangeM <= minRangeM) return "Maximum range must exceed the blind zone"
        if (maxRangeM > 15f) return "Maximum range must stay below 15 m"
        if (minRangeM < 0f) return "Blind zone cannot be negative"
        if (pingIntervalMs < 40) return "Ping interval must be at least 40 ms"
        if (latencyMarginMs < 40) return "Latency margin must be at least 40 ms"
        if (latencyMarginMs > 1200) return "Latency margin must stay below 1.2 s"
        if (temperatureC < -20f || temperatureC > 55f) return "Temperature out of range"
        if (bandwidthHz < 200f) return "Bandwidth must be at least 200 Hz"
        return null
    }

    /** Clamp every value into a sane operating envelope (used when loading persisted settings). */
    fun sanitized(): SonarConfig = copy(
        centerHz = centerHz.coerceIn(15_000f, 22_000f),
        bandwidthHz = bandwidthHz.coerceIn(200f, 6_000f),
        chirpMs = chirpMs.coerceIn(2, 60),
        amplitude = amplitude.coerceIn(0.05f, 1f),
        maxRangeM = maxRangeM.coerceIn(minRangeM + 0.5f, 15f),
        minRangeM = minRangeM.coerceIn(0.05f, 2f),
        latencyMarginMs = latencyMarginMs.coerceIn(40, 1200),
        temperatureC = temperatureC.coerceIn(-20f, 55f),
        gainDb = gainDb.coerceIn(-12f, 30f),
        thresholdDb = thresholdDb.coerceIn(0f, 30f),
        pingIntervalMs = pingIntervalMs.coerceIn(60, 2000),
        rangeBinM = rangeBinM.coerceIn(0.02f, 0.25f),
    ).let { if (it.validate() == null) it else it.copy(maxRangeM = max(it.minRangeM + 0.5f, 4f)) }

    companion object {
        /**
         * Time-variable gain in dB for a planar reflector (mirror-image spreading, ~1/r amplitude).
         * Small objects lose roughly twice this, which the sensitivity control lets the user tune in.
         */
        fun spreadingLossDb(rangeM: Float, referenceM: Float = 0.5f): Float =
            20f * log10(max(rangeM, 0.01f) / referenceM)

        fun amplitudeToDb(value: Float, reference: Float): Float {
            val ratio = value / max(reference, 1e-9f)
            return 20f * log10(max(ratio, 1e-9f))
        }

        fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
            if (to <= from) return 0f
            var sum = 0.0
            for (i in from until to) sum += samples[i].toDouble() * samples[i]
            return sqrt(sum / (to - from)).toFloat()
        }

        /** Percentile of a slice of samples; [q] in 0..1. Does not mutate [samples]. */
        fun percentile(samples: FloatArray, from: Int, to: Int, q: Float, scratch: FloatArray): Float {
            val count = to - from
            if (count <= 0) return 0f
            require(scratch.size >= count) { "scratch buffer too small" }
            System.arraycopy(samples, from, scratch, 0, count)
            java.util.Arrays.sort(scratch, 0, count)
            val idx = ((count - 1) * q.coerceIn(0f, 1f)).toInt()
            return scratch[idx]
        }
    }
}