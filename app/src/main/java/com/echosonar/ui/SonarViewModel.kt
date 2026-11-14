package com.echosonar.ui

import android.app.Application
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.echosonar.audio.AudioEngine
import com.echosonar.audio.AudioSupport
import com.echosonar.dsp.ChirpWaveform
import com.echosonar.dsp.PingResult
import com.echosonar.dsp.RangeProcessor
import com.echosonar.dsp.SonarConfig
import com.echosonar.dsp.SyntheticRoom
import com.echosonar.sensors.OrientationTracker
import com.echosonar.sonar.Advice
import com.echosonar.sonar.AdviceEngine
import com.echosonar.sonar.AdviceSeverity
import com.echosonar.sonar.MapAccumulator
import com.echosonar.sonar.MicMode
import com.echosonar.sonar.SessionStats
import com.echosonar.sonar.SessionTracker
import com.echosonar.sonar.SettingsStore
import com.echosonar.sonar.SweepMode
import com.echosonar.sonar.UiPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max

class SonarViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)
    private val storedConfig = store.loadConfig()
    private val storedPrefs = store.loadPrefs()

    private val _state = MutableStateFlow(
        SonarUiState(
            config = storedConfig,
            prefs = storedPrefs,
            diagnostics = AudioSupport.diagnostics(application),
            audioRateHz = AudioSupport.resolveRate(storedConfig.sampleRateHz),
        ),
    )
    val state: StateFlow<SonarUiState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var calibrationJob: Job? = null
    private var engine: AudioEngine? = null
    private var activeProcessor: RangeProcessor? = null
    private var orientation: OrientationTracker? = null
    private val adviceEngine = AdviceEngine()
    private val sessionTracker = SessionTracker()
    private var lastVibrationMs = 0L
    private val vibrator: Vibrator? = resolveVibrator(application)

    // ---------------------------------------------------------------- lifecycle

    fun startScan() {
        if (sessionJob?.isActive == true) return
        calibrationJob?.cancel()
        sessionJob = viewModelScope.launch(Dispatchers.Default) {
            runCatching { scanLoop() }.onFailure { error ->
                if (error is CancellationException) throw error
                fail(error.message ?: error::class.java.simpleName)
            }
        }
    }

    fun stopScan(message: String = "Stopped") {
        sessionJob?.cancel()
        sessionJob = null
        calibrationJob?.cancel()
        calibrationJob = null
        releaseAudio()
        _state.update {
            it.copy(
                status = ScanStatus.IDLE,
                statusMessage = message,
                demo = false,
                advice = if (it.metrics != null) {
                    Advice(
                        id = "stopped",
                        severity = AdviceSeverity.INFO,
                        title = "Scan stopped",
                        detail = "The map keeps the geometry it already measured.",
                        action = "Press START to ping again, or CLEAR to wipe the phosphor.",
                    )
                } else {
                    it.advice
                },
            )
        }
    }

    override fun onCleared() {
        sessionJob?.cancel()
        calibrationJob?.cancel()
        releaseAudio()
        super.onCleared()
    }

    // ---------------------------------------------------------------- demo

    /**
     * Runs the real DSP pipeline against a synthetic room: no audio devices, no permission.
     *
     * This exists so the map can be demonstrated (and verified) on hardware that cannot do
     * ultrasound at all — an emulator, or a phone whose speaker is dead. Everything downstream of
     * the capture window is the production code path.
     */
    fun startDemo() {
        if (sessionJob?.isActive == true) return
        calibrationJob?.cancel()
        sessionJob = viewModelScope.launch(Dispatchers.Default) {
            runCatching { demoLoop() }.onFailure { error ->
                if (error is CancellationException) throw error
                fail(error.message ?: "Demo failed")
            }
        }
    }

    private suspend fun demoLoop() {
        val config = _state.value.config.copy(sampleRateHz = 48_000)
        val processor = RangeProcessor(config)
        val capture = FloatArray(processor.windowSamples)
        val map = MapAccumulator(config.rangeBins, 72, config.minRangeM, config.rangeBinM)
        sessionTracker.reset(SystemClock.elapsedRealtime())
        adviceEngine.reset()
        addLog("Demo mode: synthetic room, microphone is not used", AdviceSeverity.INFO)
        var angle = 0f
        var index = 0L
        var lastMs = SystemClock.elapsedRealtime()
        val step = (2.0 * PI / 48.0).toFloat()
        _state.update {
            it.copy(
                status = ScanStatus.SCANNING,
                demo = true,
                statusMessage = "Synthetic demo · audio path is idle",
            )
        }
        try {
            while (coroutineContext.isActive) {
                SyntheticRoom.capture(
                    config = config,
                    windowSamples = processor.windowSamples,
                    wallRangeM = SyntheticRoom.demoWallRange(angle),
                    secondaryRangeM = if (index % 6L == 0L) 3.15f else null,
                    seed = index * 7919L + 13L,
                ).copyInto(capture)
                val now = SystemClock.elapsedRealtime()
                val result = processor.process(capture, angle, now, index)
                index++
                val dtSeconds = ((now - lastMs).coerceAtLeast(1L)) / 1000f
                lastMs = now
                map.ingest(result)
                map.decay(dtSeconds, _state.value.prefs.persistenceSec)
                sessionTracker.record(result, 24f, now, tiltDeg = 0f, poseKnown = false)
                val stats = sessionTracker.stats(now)
                val advice = evaluateAdvice(now, stats, config, map, rotate = false)
                val snapshot = map.snapshot()
                _state.update { current ->
                    current.copy(
                        status = ScanStatus.SCANNING,
                        demo = true,
                        statusMessage = "DEMO · " + describe(result),
                        map = snapshot,
                        profile = result.profile,
                        metrics = result.metrics,
                        stats = stats,
                        advice = advice ?: current.advice,
                        sweepAngleRad = angle,
                    )
                }
                angle = OrientationTracker.wrap(angle + step)
                delay(config.pingIntervalMs.toLong().coerceAtLeast(120L))
            }
        } finally {
            if (_state.value.status != ScanStatus.ERROR) {
                _state.update { it.copy(status = ScanStatus.IDLE, demo = false, statusMessage = "Demo stopped") }
            }
        }
    }

    // ---------------------------------------------------------------- scan loop

    private suspend fun scanLoop() {
        val app = getApplication<Application>()
        val prefs = _state.value.prefs
        val requested = _state.value.config
        val rate = AudioSupport.resolveRate(requested.sampleRateHz)
        val config = requested.copy(sampleRateHz = rate)
        val micSource = resolveMicSource(prefs)
        val micLabel = AudioSupport.micSourceLabel(micSource)

        _state.update {
            it.copy(
                status = ScanStatus.PREPARING,
                statusMessage = "Opening ${rate / 1000} kHz duplex audio path…",
                audioRateHz = rate,
                micLabel = micLabel,
            )
        }
        addLog("Audio path: ${rate / 1000} kHz mono, ${micLabel.lowercase()}", AdviceSeverity.INFO)

        val audio = AudioEngine(app, rate, micSource)
        engine = audio
        val openError = withContext(Dispatchers.IO) { audio.open() }
        if (openError != null) {
            fail(openError)
            return
        }
        val startError = withContext(Dispatchers.IO) { audio.start() }
        if (startError != null) {
            fail(startError)
            return
        }

        val processor = RangeProcessor(config)
        activeProcessor = processor
        val chirp = ChirpWaveform(config.chirpSpec)
        val capture = FloatArray(processor.windowSamples)
        val map = MapAccumulator(config.rangeBins, 72, config.minRangeM, config.rangeBinM)
        val tracker = OrientationTracker(app)
        orientation = tracker
        val sensorOk = tracker.start()
        addLog(
            if (sensorOk) {
                if (tracker.hasAbsoluteHeading) "Compass available — bearing sweep enabled"
                else "No compass; gyro sweep enabled (drifts slowly)"
            } else {
                "No rotation sensor — falling back to synthetic sweep"
            },
            if (sensorOk) AdviceSeverity.INFO else AdviceSeverity.HINT,
        )

        sessionTracker.reset(SystemClock.elapsedRealtime())
        adviceEngine.reset()
        val windowMs = processor.windowSamples * 1000L / rate
        val interval = max(config.pingIntervalMs.toLong(), windowMs + 25L)
        val windowWait = windowMs + 30L
        var pingIndex = 0L
        var autoAngle = 0f
        val autoStep = (2.0 * PI / 48.0).toFloat()
        var lastPingMs = SystemClock.elapsedRealtime()
        var failureStreak = 0

        addLog(
            "Pinging ${config.centerHz.toInt()} Hz, ${config.chirpMs} ms chirp, " +
                "${"%.0f".format(config.rangeResolutionM * 100)} cm resolution",
            AdviceSeverity.INFO,
        )
        _state.update {
            it.copy(status = ScanStatus.SCANNING, statusMessage = "Listening for echoes…")
        }

        try {
            while (coroutineContext.isActive && audio.isRunning) {
                val loopStart = SystemClock.elapsedRealtime()
                val startFrame = audio.framesWritten
                val played = withContext(Dispatchers.IO) { audio.play(chirp.pcm) }
                delay(windowWait)
                val captured = withContext(Dispatchers.IO) {
                    audio.capture(startFrame, capture.size, capture)
                }
                val now = SystemClock.elapsedRealtime()

                if (!played || !captured) {
                    failureStreak++
                    if (failureStreak == 4) {
                        addLog(
                            "Capture window missed twice in a row — check volume and that no other app is recording.",
                            AdviceSeverity.WARN,
                        )
                    }
                    delay(40)
                    continue
                }
                failureStreak = 0

                val rotate = prefs.sweepMode == SweepMode.ROTATE && tracker.listening
                val angle = if (rotate) tracker.yawRad else autoAngle
                val result = processor.process(capture, angle, now, pingIndex)
                pingIndex++

                val dtSeconds = ((now - lastPingMs).coerceAtLeast(1L)) / 1000f
                lastPingMs = now
                map.ingest(result)
                map.decay(dtSeconds, prefs.persistenceSec)
                sessionTracker.record(result, tracker.rotationSpeedDegPerSec, now, tracker.tiltDeg, tracker.poseKnown, tracker.upsideDown)

                val stats = sessionTracker.stats(now)
                val newAdvice = evaluateAdvice(now, stats, config, map, rotate)
                maybeVibrate(result, prefs)

                val snapshot = map.snapshot()
                _state.update { current ->
                    current.copy(
                        status = ScanStatus.SCANNING,
                        statusMessage = describe(result),
                        map = snapshot,
                        profile = result.profile,
                        metrics = result.metrics,
                        stats = stats,
                        advice = newAdvice ?: current.advice,
                        sweepAngleRad = angle,
                        audioRateHz = rate,
                        micLabel = micLabel,
                    )
                }

                if (!rotate) autoAngle = OrientationTracker.wrap(autoAngle + autoStep)

                val spent = SystemClock.elapsedRealtime() - loopStart
                delay(max(0L, interval - spent))
            }
        } finally {
            tracker.stop()
            orientation = null
            activeProcessor = null
            releaseAudio()
            if (_state.value.status != ScanStatus.ERROR) {
                _state.update { it.copy(status = ScanStatus.IDLE, statusMessage = "Stopped") }
            }
        }
    }

    private fun describe(result: PingResult): String {
        val m = result.metrics
        return if (m.detectedBins > 0) {
            "Ping ${result.index + 1} · nearest ${"%.2f".format(m.nearestRangeM)} m · " +
                "${"%.0f".format(m.peakSnrDb)} dB SNR"
        } else {
            "Ping ${result.index + 1} · no return · aim at a hard surface"
        }
    }

    private fun evaluateAdvice(
        now: Long,
        stats: SessionStats,
        config: SonarConfig,
        map: MapAccumulator,
        rotate: Boolean,
    ): Advice? {
        val current = _state.value
        if (current.status == ScanStatus.ERROR) return null
        if (stats.pingCount % 3 != 0 && stats.pingCount > 3) return null
        val advice = adviceEngine.evaluate(
            nowMs = now,
            stats = stats,
            config = config,
            map = map.snapshot(),
            rotateMode = rotate,
            audioError = null,
        )
        if (advice.isEmpty()) return null
        val primary = advice.first()
        advice.take(3).forEach { item ->
            addLog("${item.title} — ${item.detail}", item.severity)
        }
        return primary
    }

    private fun maybeVibrate(result: PingResult, prefs: UiPrefs) {
        val haptics = prefs.haptics && result.metrics.peakSnrDb > 18f && result.metrics.detectedBins > 0
        if (!haptics) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastVibrationMs < 180L) return
        lastVibrationMs = now
        val device = vibrator ?: return
        runCatching {
            device.vibrate(VibrationEffect.createOneShot(8L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---------------------------------------------------------------- settings

    fun applyConfig(config: SonarConfig) {
        val sanitized = config.sanitized()
        store.saveConfig(sanitized)
        _state.update { it.copy(config = sanitized) }
        val running = _state.value.isRunning
        if (running) {
            stopScan("Applying new configuration…")
            startScan()
        }
    }

    fun setSensitivity(thresholdDb: Float) {
        val clamped = thresholdDb.coerceIn(2f, 26f)
        val updated = _state.value.config.copy(thresholdDb = clamped)
        store.saveConfig(updated)
        activeProcessor?.thresholdDb = clamped
        _state.update { it.copy(config = updated) }
    }

    fun setPrefs(prefs: UiPrefs) {
        store.savePrefs(prefs)
        _state.update { it.copy(prefs = prefs) }
    }

    fun setSweepMode(mode: SweepMode) = setPrefs(_state.value.prefs.copy(sweepMode = mode))

    fun setPersistence(seconds: Float) {
        setPrefs(_state.value.prefs.copy(persistenceSec = seconds.coerceIn(0f, 25f)))
    }

    fun setHaptics(enabled: Boolean) = setPrefs(_state.value.prefs.copy(haptics = enabled))

    fun setMicMode(mode: MicMode) = setPrefs(_state.value.prefs.copy(micMode = mode))

    fun markOnboardingSeen() = setPrefs(_state.value.prefs.copy(onboardingSeen = true))

    fun resetPreferences() {
        store.saveConfig(SonarConfig())
        store.savePrefs(UiPrefs(onboardingSeen = _state.value.prefs.onboardingSeen))
        val running = _state.value.isRunning
        _state.update { it.copy(config = SonarConfig(), prefs = store.loadPrefs()) }
        if (running) {
            stopScan("Configuration reset…")
            startScan()
        }
        addLog("Configuration reset to defaults", AdviceSeverity.INFO)
    }

    fun clearMap() {
        // The next ping republishes a fresh snapshot built from a new accumulator.
        sessionTracker.reset(SystemClock.elapsedRealtime())
        _state.update { it.copy(map = null, profile = null, metrics = null, stats = SessionStats()) }
        val running = _state.value.isRunning
        if (running) {
            stopScan("Clearing map…")
            startScan()
        } else {
            addLog("Map cleared", AdviceSeverity.INFO)
        }
    }

    fun refreshDiagnostics() {
        _state.update { it.copy(diagnostics = AudioSupport.diagnostics(getApplication())) }
    }

    fun onPermissionDenied() {
        _state.update { it.copy(permissionDenied = true, status = ScanStatus.IDLE, statusMessage = "Microphone permission denied") }
        addLog("Microphone permission denied — nothing can be recorded", AdviceSeverity.CRITICAL)
    }

    fun onPermissionGranted() {
        _state.update { it.copy(permissionDenied = false) }
    }

    // ---------------------------------------------------------------- calibration

    /**
     * Measures the device's speaker + microphone response at candidate ultrasonic frequencies by
     * pinging each one and reading the direct coupling level. Whatever comes back strongest is the
     * frequency the phone can actually produce and hear.
     */
    fun probeFrequencies() {
        if (calibrationJob?.isActive == true) return
        calibrationJob = viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<Application>()
            // Let the previous session finish releasing the audio devices before reopening them.
            val previous = sessionJob
            previous?.cancelAndJoin()
            sessionJob = null
            releaseAudio()
            val base = _state.value.config
            val rate = AudioSupport.resolveRate(base.sampleRateHz)
            val micSource = resolveMicSource(_state.value.prefs)
            val audio = AudioEngine(app, rate, micSource)
            engine = audio
            _state.update {
                it.copy(
                    status = ScanStatus.CALIBRATING,
                    statusMessage = "Measuring speaker and mic response…",
                    calibrationProgress = "Opening audio path",
                )
            }
            val openError = withContext(Dispatchers.IO) { audio.open() }
            if (openError != null) {
                fail(openError)
                return@launch
            }
            val startError = withContext(Dispatchers.IO) { audio.start() }
            if (startError != null) {
                fail(startError)
                return@launch
            }
            val candidates = listOf(17_500f, 18_250f, 19_000f, 19_750f, 20_500f)
            val responses = ArrayList<FrequencyResponse>(candidates.size)
            try {
                candidates.forEachIndexed { index, frequency ->
                    _state.update {
                        it.copy(calibrationProgress = "Probing ${frequency.toInt()} Hz (${index + 1}/${candidates.size})")
                    }
                    val probeConfig = base.copy(
                        sampleRateHz = rate,
                        centerHz = frequency,
                        bandwidthHz = 700f,
                        chirpMs = 20,
                    )
                    val processor = RangeProcessor(probeConfig)
                    val chirp = ChirpWaveform(probeConfig.chirpSpec)
                    val buffer = FloatArray(processor.windowSamples)
                    var bestAmp = 0f
                    repeat(3) {
                        val startFrame = audio.framesWritten
                        withContext(Dispatchers.IO) { audio.play(chirp.pcm) }
                        delay(processor.windowSamples * 1000L / rate + 40L)
                        val got = withContext(Dispatchers.IO) {
                            audio.capture(startFrame, buffer.size, buffer)
                        }
                        if (got) {
                            val metrics = processor.process(buffer, 0f, SystemClock.elapsedRealtime(), 0L).metrics
                            if (metrics.directAmp > bestAmp) bestAmp = metrics.directAmp
                        }
                    }
                    responses += FrequencyResponse(frequency, 20f * log10(max(bestAmp, 1e-6f)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Frequency probe failed: ${e.message}")
                return@launch
            } finally {
                releaseAudio()
            }

            val best = responses.maxByOrNull { it.levelDb }
            val usable = (best?.levelDb ?: -200f) > -85f
            val recommended = if (usable) best?.centerHz ?: base.centerHz else base.centerHz
            val result = CalibrationResult(responses, recommended, usable)
            _state.update {
                it.copy(
                    status = ScanStatus.IDLE,
                    statusMessage = if (usable) "Frequency probe complete" else "Probe found no coupling",
                    calibration = result,
                    calibrationProgress = "",
                )
            }
            if (best != null && usable) {
                addLog(
                    "Frequency probe: best response at ${best.centerHz.toInt()} Hz " +
                        "(${"%.1f".format(best.levelDb)} dB coupling); weakest " +
                        "${responses.minByOrNull { it.levelDb }?.centerHz?.toInt()} Hz",
                    AdviceSeverity.GOOD,
                )
            } else {
                addLog(
                    "Frequency probe heard nothing at any frequency. Raise media volume, remove the case, " +
                        "and make sure the speaker is not blocked.",
                    AdviceSeverity.WARN,
                )
            }
        }
    }

    fun applyCalibratedFrequency(frequency: Float) {
        applyConfig(_state.value.config.copy(centerHz = frequency))
    }

    // ---------------------------------------------------------------- helpers

    private fun fail(message: String) {
        releaseAudio()
        _state.update { it.copy(status = ScanStatus.ERROR, statusMessage = message) }
        addLog(message, AdviceSeverity.CRITICAL)
    }

    private fun releaseAudio() {
        val audio = engine
        engine = null
        if (audio != null) {
            runCatching { audio.release() }
        }
        orientation?.stop()
    }

    private fun addLog(message: String, severity: AdviceSeverity) {
        val entry = LogEntry(message, severity, System.currentTimeMillis())
        _state.update { current ->
            val merged = if (current.log.firstOrNull()?.message == message) current.log
            else (listOf(entry) + current.log).take(40)
            current.copy(log = merged)
        }
    }

    private fun resolveMicSource(prefs: UiPrefs): Int =
        if (prefs.micMode == MicMode.UNPROCESSED) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC

    private fun resolveVibrator(app: Application): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = app.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()
}