package com.echosonar.dsp

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Deterministic synthetic captures for unit tests and the offline map simulation.
 *
 * A capture window is the sum of
 *  - the direct speaker -> microphone coupling (always first),
 *  - one delayed chirp per echo, delayed by the round-trip time of its range,
 *  - optional white noise.
 */
object SynthRoom {

    data class Echo(val rangeM: Float, val amplitude: Float)

    fun capture(
        config: SonarConfig,
        directDelayMs: Float = 30f,
        directAmplitude: Float = 0.6f,
        echoes: List<Echo> = emptyList(),
        noiseRms: Float = 0f,
        seed: Long = 42,
        lengthSamples: Int = MatchedFilter(config).windowSamples,
    ): FloatArray {
        val chirp = ChirpWaveform(config.chirpSpec)
        val out = FloatArray(lengthSamples)
        val fs = config.sampleRateHz
        val rnd = java.util.Random(seed)

        val directStart = (directDelayMs / 1000f * fs).roundToInt()
        addChirp(out, chirp.inPhase, directStart, directAmplitude)

        for (echo in echoes) {
            val delayMs = directDelayMs + config.roundTripMs(echo.rangeM)
            val start = (delayMs / 1000f * fs).roundToInt()
            addChirp(out, chirp.inPhase, start, echo.amplitude)
        }

        if (noiseRms > 0f) {
            for (i in out.indices) {
                out[i] += (rnd.nextGaussian() * noiseRms).toFloat()
            }
        }
        return out
    }

    fun addChirp(destination: FloatArray, waveform: FloatArray, startSample: Int, amplitude: Float) {
        for (i in waveform.indices) {
            val idx = startSample + i
            if (idx in destination.indices) destination[idx] += waveform[i] * amplitude
        }
        // Guard against numeric explosion if a test over-doses the amplitude.
        for (i in destination.indices) {
            destination[i] = destination[i].coerceIn(-1f, 1f)
        }
    }

    fun dominantFrequencyHz(samples: FloatArray, sampleRate: Int): Float {
        var size = 2
        while (size < samples.size) size = size shl 1
        val re = FloatArray(size)
        val im = FloatArray(size)
        System.arraycopy(samples, 0, re, 0, samples.size)
        Fft(size).forward(re, im)
        var bestBin = 0
        var best = 0f
        for (bin in 1 until size / 2) {
            val magnitude = kotlin.math.hypot(re[bin], im[bin])
            if (magnitude > best) {
                best = magnitude
                bestBin = bin
            }
        }
        return bestBin.toFloat() * sampleRate / size
    }
}