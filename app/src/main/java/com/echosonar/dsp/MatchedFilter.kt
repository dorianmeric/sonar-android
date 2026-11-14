package com.echosonar.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * FFT-based quadrature matched filter for the emitted chirp.
 *
 * The captured window is correlated against a cosine/sine reference pair; the magnitude of the
 * resulting complex envelope is the echo amplitude at each lag. Correlation against the chirp is
 * itself a band-pass operation, so HVAC rumble and speech are rejected before echo detection.
 *
 * Output envelope index == the sample offset in the capture window where the chirp would start,
 * therefore `lag / sampleRate * c / 2` is the round-trip range (after subtracting the direct-path
 * coupling lag, which the processor measures on every ping).
 *
 * Instances are stateful (reused buffers) and single-threaded.
 */
class MatchedFilter(val config: SonarConfig) {

    val chirp = ChirpWaveform(config.chirpSpec)
    val refLength: Int = chirp.lengthSamples
    private val fs = config.sampleRateHz

    private val latencySamples = (config.latencyMarginMs / 1000f * fs).roundToInt()
    private val maxDelaySamples = ceil(2f * config.maxRangeM / config.speedOfSound * fs).toInt()

    /** Total samples needed in the capture window for a full-range ping. */
    val windowSamples: Int = latencySamples + maxDelaySamples + refLength + 8

    val fftSize: Int = Fft.nextPowerOfTwo(windowSamples + refLength)

    /** Number of usable correlation lags (one per possible chirp start position). */
    val lagCount: Int = windowSamples - refLength + 1

    /** Latest search window for the direct-path (speaker -> microphone) coupling peak. */
    val directSearchLags: Int = (latencySamples + refLength).coerceAtMost(lagCount - 1)

    val latencyMarginSamples: Int get() = latencySamples

    private val fft = Fft(fftSize)
    private val kernelCosRe = FloatArray(fftSize)
    private val kernelCosIm = FloatArray(fftSize)
    private val kernelSinRe = FloatArray(fftSize)
    private val kernelSinIm = FloatArray(fftSize)

    private val xRe = FloatArray(fftSize)
    private val xIm = FloatArray(fftSize)
    private val ycRe = FloatArray(fftSize)
    private val ycIm = FloatArray(fftSize)
    private val ysRe = FloatArray(fftSize)
    private val ysIm = FloatArray(fftSize)

    /** Reused envelope buffer; valid for [lagCount] entries after [process] returns. */
    val envelope = FloatArray(lagCount)

    /** Maximum absolute input sample of the last processed window (1.0 == full scale). */
    var lastPeakAbs: Float = 0f
        private set

    /** RMS of the last processed window. */
    var lastRms: Float = 0f
        private set

    /** Amplitude of the coupling component removed by the last cancelled pass (0 when disabled). */
    var lastCancelledAmp: Float = 0f
        private set

    private var cancelledAmp: Float = 0f

    init {
        System.arraycopy(chirp.inPhase, 0, kernelCosRe, 0, refLength)
        System.arraycopy(chirp.quadrature, 0, kernelSinRe, 0, refLength)
        fft.forward(kernelCosRe, kernelCosIm)
        fft.forward(kernelSinRe, kernelSinIm)
        // Store conj(K) so the multiplier below is a plain complex product.
        for (i in 0 until fftSize) {
            kernelCosIm[i] = -kernelCosIm[i]
            kernelSinIm[i] = -kernelSinIm[i]
        }
    }

    /**
     * Correlate [input] (a capture window, [windowSamples] long) with the chirp.
     *
     * When [cancelDirectLag] is non-negative, the direct speaker -> microphone coupling at that lag is
     * estimated and subtracted (least-squares projection onto the chirp's I/Q basis) before the
     * correlation runs. That removes the coupling's autocorrelation skirt, which would otherwise
     * masquerade as walls at a whole range of distances.
     *
     * Returns the internal [envelope] buffer, valid until the next call.
     */
    fun process(input: FloatArray, offset: Int = 0, cancelDirectLag: Int = -1): FloatArray {
        val count = minOf(windowSamples, input.size - offset)
        require(count > refLength) { "capture window too short" }

        java.util.Arrays.fill(xRe, 0f)
        java.util.Arrays.fill(xIm, 0f)
        var peak = 0f
        for (i in 0 until count) {
            val v = input[offset + i]
            xRe[i] = v
            val a = if (v < 0f) -v else v
            if (a > peak) peak = a
        }
        lastPeakAbs = peak
        lastRms = SonarConfig.rms(xRe, 0, count)

        if (cancelDirectLag >= 0) {
            // Two projection passes: the first cancels the coupling, the second removes the small
            // chirp-shaped residual that the first pass itself created out of noise.
            cancelDirectPath(xRe, cancelDirectLag, count)
            val firstAmp = cancelledAmp
            cancelDirectPath(xRe, cancelDirectLag, count)
            lastCancelledAmp = hypot(firstAmp, cancelledAmp)
        }

        fft.forward(xRe, xIm)

        // I channel: X * conj(Kc) is a plain complex product because the kernel already stores conj(FFT(ref)).
        for (i in 0 until fftSize) {
            val a = xRe[i]; val b = xIm[i]
            val c = kernelCosRe[i]; val d = kernelCosIm[i]
            ycRe[i] = a * c - b * d
            ycIm[i] = a * d + b * c
        }
        // Q channel: X * conj(Ks)
        for (i in 0 until fftSize) {
            val a = xRe[i]; val b = xIm[i]
            val c = kernelSinRe[i]; val d = kernelSinIm[i]
            ysRe[i] = a * c - b * d
            ysIm[i] = a * d + b * c
        }

        fft.inverse(ycRe, ycIm)
        fft.inverse(ysRe, ysIm)

        val normalisation = 1f / max(refLength, 1)
        for (lag in 0 until lagCount) {
            envelope[lag] = hypot(ycRe[lag], ysRe[lag]) * normalisation
        }
        return envelope
    }

    /**
     * Least-squares fit of the emitted chirp (I/Q basis) at [lag] and subtract it from [buffer].
     * Handles an arbitrary gain and phase, i.e. whatever the speaker/microphone path produced.
     */
    private fun cancelDirectPath(buffer: FloatArray, lag: Int, count: Int) {
        val length = min(refLength, count - lag)
        if (length <= 8) {
            cancelledAmp = 0f
            return
        }
        var dcc = 0.0
        var dss = 0.0
        var dcs = 0.0
        var dotC = 0.0
        var dotS = 0.0
        for (m in 0 until length) {
            val c = chirp.inPhase[m]
            val s = chirp.quadrature[m]
            val v = buffer[lag + m]
            dcc += c.toDouble() * c
            dss += s.toDouble() * s
            dcs += c.toDouble() * s
            dotC += v.toDouble() * c
            dotS += v.toDouble() * s
        }
        val det = dcc * dss - dcs * dcs
        if (abs(det) < 1e-9) {
            cancelledAmp = 0f
            return
        }
        val gc = (dotC * dss - dotS * dcs) / det
        val gs = (dotS * dcc - dotC * dcs) / det
        cancelledAmp = hypot(gc, gs).toFloat()
        for (m in 0 until length) {
            buffer[lag + m] = (buffer[lag + m] - (gc * chirp.inPhase[m] + gs * chirp.quadrature[m])).toFloat()
        }
    }
}