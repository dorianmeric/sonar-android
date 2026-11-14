package com.echosonar.dsp

import kotlin.math.roundToInt

/**
 * Synthetic capture generator used by the built-in demo mode.
 *
 * It produces exactly the kind of float window the audio engine produces — direct coupling plus one
 * delayed and attenuated copy of the chirp per echo — so the real [RangeProcessor] and the real map
 * accumulator run unchanged. This is how the UI can be demonstrated on a device whose speaker or
 * microphone cannot do ultrasound at all.
 */
object SyntheticRoom {

    /**
     * Builds one capture window for a room whose wall distance is given by [wallRangeM].
     * Amplitudes follow 1/r spreading plus a per-ping jitter, mimicking a hand-held sweep.
     */
    fun capture(
        config: SonarConfig,
        windowSamples: Int,
        wallRangeM: Float,
        secondaryRangeM: Float? = null,
        noiseRms: Float = 0.0009f,
        seed: Long = 0L,
        directDelayMs: Float = 30f,
        directAmplitude: Float = 0.55f,
    ): FloatArray {
        val chirp = ChirpWaveform(config.chirpSpec)
        val out = FloatArray(windowSamples)
        val fs = config.sampleRateHz
        val random = java.util.Random(seed)

        val directStart = (directDelayMs / 1000f * fs).roundToInt()
        add(out, chirp.inPhase, directStart, directAmplitude)

        val primaryAmp = (0.30f * (1.0f / wallRangeM.coerceAtLeast(0.3f)) * (0.9f + 0.2f * random.nextFloat()))
        val primaryStart = directStart + (config.roundTripMs(wallRangeM) / 1000f * fs).roundToInt()
        add(out, chirp.inPhase, primaryStart, primaryAmp)

        if (secondaryRangeM != null) {
            val secondaryAmp = (0.18f / secondaryRangeM.coerceAtLeast(0.3f))
            val secondaryStart = directStart + (config.roundTripMs(secondaryRangeM) / 1000f * fs).roundToInt()
            add(out, chirp.inPhase, secondaryStart, secondaryAmp)
        }

        if (noiseRms > 0f) {
            for (i in out.indices) {
                out[i] = (out[i] + random.nextGaussian().toFloat() * noiseRms).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    /** A wobbly room outline used by the demo: distinct walls in different directions. */
    fun demoWallRange(angleRad: Float): Float {
        val a = angleRad
        val primary = 1.55f + 0.75f * (0.5f + 0.5f * kotlin.math.cos(2f * a))
        val ripple = 0.18f * kotlin.math.sin(5f * a)
        return (primary + ripple).coerceIn(0.8f, 3.6f)
    }

    private fun add(destination: FloatArray, waveform: FloatArray, startSample: Int, amplitude: Float) {
        if (startSample < 0) return
        for (i in waveform.indices) {
            val index = startSample + i
            if (index >= destination.size) break
            destination[index] = (destination[index] + waveform[i] * amplitude).coerceIn(-1f, 1f)
        }
    }
}