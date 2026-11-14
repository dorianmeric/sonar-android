package com.echosonar.sonar

import android.content.Context
import com.echosonar.dsp.SonarConfig

/**
 * SharedPreferences persistence for the acoustic configuration and UI preferences.
 * Everything is stored per key (no serialisation library) so a corrupt value can be sanitised
 * without losing the rest of the settings.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("echosonar_prefs", Context.MODE_PRIVATE)

    fun loadConfig(): SonarConfig {
        val defaults = SonarConfig()
        return SonarConfig(
            sampleRateHz = prefs.getInt(KEY_SAMPLE_RATE, defaults.sampleRateHz),
            centerHz = prefs.getFloat(KEY_CENTER, defaults.centerHz),
            bandwidthHz = prefs.getFloat(KEY_BANDWIDTH, defaults.bandwidthHz),
            chirpMs = prefs.getInt(KEY_CHIRP_MS, defaults.chirpMs),
            amplitude = prefs.getFloat(KEY_AMPLITUDE, defaults.amplitude),
            maxRangeM = prefs.getFloat(KEY_MAX_RANGE, defaults.maxRangeM),
            minRangeM = prefs.getFloat(KEY_MIN_RANGE, defaults.minRangeM),
            latencyMarginMs = prefs.getInt(KEY_LATENCY, defaults.latencyMarginMs),
            temperatureC = prefs.getFloat(KEY_TEMPERATURE, defaults.temperatureC),
            gainDb = prefs.getFloat(KEY_GAIN, defaults.gainDb),
            thresholdDb = prefs.getFloat(KEY_THRESHOLD, defaults.thresholdDb),
            pingIntervalMs = prefs.getInt(KEY_PING_INTERVAL, defaults.pingIntervalMs),
            rangeBinM = prefs.getFloat(KEY_RANGE_BIN, defaults.rangeBinM),
        ).sanitized()
    }

    fun saveConfig(config: SonarConfig) {
        prefs.edit()
            .putInt(KEY_SAMPLE_RATE, config.sampleRateHz)
            .putFloat(KEY_CENTER, config.centerHz)
            .putFloat(KEY_BANDWIDTH, config.bandwidthHz)
            .putInt(KEY_CHIRP_MS, config.chirpMs)
            .putFloat(KEY_AMPLITUDE, config.amplitude)
            .putFloat(KEY_MAX_RANGE, config.maxRangeM)
            .putFloat(KEY_MIN_RANGE, config.minRangeM)
            .putInt(KEY_LATENCY, config.latencyMarginMs)
            .putFloat(KEY_TEMPERATURE, config.temperatureC)
            .putFloat(KEY_GAIN, config.gainDb)
            .putFloat(KEY_THRESHOLD, config.thresholdDb)
            .putInt(KEY_PING_INTERVAL, config.pingIntervalMs)
            .putFloat(KEY_RANGE_BIN, config.rangeBinM)
            .apply()
    }

    fun loadPrefs(): UiPrefs {
        val defaults = UiPrefs()
        return UiPrefs(
            sweepMode = runCatching {
                SweepMode.valueOf(prefs.getString(KEY_SWEEP_MODE, defaults.sweepMode.name)!!)
            }.getOrDefault(defaults.sweepMode),
            haptics = prefs.getBoolean(KEY_HAPTICS, defaults.haptics),
            persistenceSec = prefs.getFloat(KEY_PERSISTENCE, defaults.persistenceSec),
            onboardingSeen = prefs.getBoolean(KEY_ONBOARDING, defaults.onboardingSeen),
            micMode = runCatching {
                MicMode.valueOf(prefs.getString(KEY_MIC_MODE, defaults.micMode.name)!!)
            }.getOrDefault(defaults.micMode),
            showHardwareHints = prefs.getBoolean(KEY_HARDWARE_HINTS, defaults.showHardwareHints),
        )
    }

    fun savePrefs(prefsValue: UiPrefs) {
        prefs.edit()
            .putString(KEY_SWEEP_MODE, prefsValue.sweepMode.name)
            .putBoolean(KEY_HAPTICS, prefsValue.haptics)
            .putFloat(KEY_PERSISTENCE, prefsValue.persistenceSec)
            .putBoolean(KEY_ONBOARDING, prefsValue.onboardingSeen)
            .putString(KEY_MIC_MODE, prefsValue.micMode.name)
            .putBoolean(KEY_HARDWARE_HINTS, prefsValue.showHardwareHints)
            .apply()
    }

    private companion object {
        const val KEY_SAMPLE_RATE = "sample_rate"
        const val KEY_CENTER = "center_hz"
        const val KEY_BANDWIDTH = "bandwidth_hz"
        const val KEY_CHIRP_MS = "chirp_ms"
        const val KEY_AMPLITUDE = "amplitude"
        const val KEY_MAX_RANGE = "max_range_m"
        const val KEY_MIN_RANGE = "min_range_m"
        const val KEY_LATENCY = "latency_margin_ms"
        const val KEY_TEMPERATURE = "temperature_c"
        const val KEY_GAIN = "gain_db"
        const val KEY_THRESHOLD = "threshold_db"
        const val KEY_PING_INTERVAL = "ping_interval_ms"
        const val KEY_RANGE_BIN = "range_bin_m"
        const val KEY_SWEEP_MODE = "sweep_mode"
        const val KEY_HAPTICS = "haptics"
        const val KEY_PERSISTENCE = "persistence_s"
        const val KEY_ONBOARDING = "onboarding_seen"
        const val KEY_MIC_MODE = "mic_mode"
        const val KEY_HARDWARE_HINTS = "hardware_hints"
    }
}