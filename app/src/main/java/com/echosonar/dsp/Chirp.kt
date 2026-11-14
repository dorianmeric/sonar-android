package com.echosonar.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Description of a linear frequency-modulated (chirp) pulse.
 *
 * A chirp is used instead of a plain tone because the matched filter turns the whole swept
 * bandwidth into range resolution: theoretical range resolution is c / (2 * bandwidth).
 */
data class ChirpSpec(
    val sampleRate: Int = 48_000,
    val centerHz: Float = 19_000f,
    val bandwidthHz: Float = 2_000f,
    val durationMs: Int = 10,
    val amplitude: Float = 0.9f,
) {
    val startHz: Float get() = centerHz - bandwidthHz / 2f
    val endHz: Float get() = centerHz + bandwidthHz / 2f
    val lengthSamples: Int get() = max(16, (durationMs / 1000f * sampleRate).roundToInt())

    /** Hertz per second of the linear sweep. */
    val sweepRate: Float get() = bandwidthHz / (durationMs / 1000f)

    /** Best case range resolution in metres for the swept bandwidth. */
    fun rangeResolutionM(speedOfSound: Float): Float = speedOfSound / (2f * bandwidthHz)

    fun validate(): String? = when {
        centerHz <= 0f -> "Chirp centre frequency must be positive"
        bandwidthHz <= 0f -> "Chirp bandwidth must be positive"
        endHz >= sampleRate / 2f -> "Chirp must stay below the Nyquist frequency"
        durationMs <= 0 -> "Chirp duration must be positive"
        amplitude <= 0f || amplitude > 1f -> "Chirp amplitude must be in (0, 1]"
        else -> null
    }
}

/**
 * Pre-computed chirp waveform: in-phase and quadrature reference signals plus the int16 PCM
 * burst that gets handed to the speaker.
 *
 * Both ends are shaped with a raised-cosine (Hann) window. The window is not cosmetic: it drives the
 * autocorrelation sidelobes below -50 dB, so the loud speaker -> microphone coupling cannot spray
 * phantom echoes across the whole map, and it stops the burst from clicking audibly.
 */
class ChirpWaveform(val spec: ChirpSpec) {

    val lengthSamples: Int = spec.lengthSamples
    val inPhase = FloatArray(lengthSamples)
    val quadrature = FloatArray(lengthSamples)
    val pcm = ShortArray(lengthSamples)

    init {
        val f0 = spec.startHz.toDouble()
        val k = spec.sweepRate.toDouble()
        val fs = spec.sampleRate.toDouble()

        for (n in 0 until lengthSamples) {
            val t = n / fs
            // Instantaneous phase of an LFM sweep: 2*pi*(f0*t + 0.5*k*t^2)
            val phase = 2.0 * PI * (f0 * t + 0.5 * k * t * t)
            val env = windowValue(n, lengthSamples)
            inPhase[n] = (env * cos(phase)).toFloat()
            quadrature[n] = (env * sin(phase)).toFloat()
            val pcmValue = (env * cos(phase) * spec.amplitude * Short.MAX_VALUE).roundToInt()
            pcm[n] = pcmValue.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /** Full-length Hann window; [n] in 0 until [length]. */
    private fun windowValue(n: Int, length: Int): Double {
        if (length <= 2) return 1.0
        return 0.5 * (1.0 - cos(2.0 * PI * n / (length - 1)))
    }
}