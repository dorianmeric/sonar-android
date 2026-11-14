package com.echosonar.ui

import com.echosonar.dsp.PingMetrics
import com.echosonar.dsp.RangeProfile
import com.echosonar.dsp.SonarConfig
import com.echosonar.sonar.Advice
import com.echosonar.sonar.AdviceSeverity
import com.echosonar.sonar.MapSnapshot
import com.echosonar.sonar.SessionStats
import com.echosonar.sonar.UiPrefs

enum class ScanStatus { IDLE, PREPARING, SCANNING, CALIBRATING, ERROR }

data class LogEntry(
    val message: String,
    val severity: AdviceSeverity,
    val timeMs: Long,
)

data class FrequencyResponse(
    val centerHz: Float,
    val levelDb: Float,
)

data class CalibrationResult(
    val responses: List<FrequencyResponse>,
    val bestCenterHz: Float,
    /** False when the device showed no usable coupling at any candidate frequency. */
    val usable: Boolean,
)

/** The single immutable state object the whole UI renders from. */
data class SonarUiState(
    val status: ScanStatus = ScanStatus.IDLE,
    val statusMessage: String = "Ready to ping",
    val config: SonarConfig = SonarConfig(),
    val prefs: UiPrefs = UiPrefs(),
    val map: MapSnapshot? = null,
    val profile: RangeProfile? = null,
    val metrics: PingMetrics? = null,
    val stats: SessionStats = SessionStats(),
    val advice: Advice = Advice(
        id = "welcome",
        severity = AdviceSeverity.INFO,
        title = "EchoSonar is standing by",
        detail = "Press START to emit a 19 kHz chirp and listen for its echoes.",
        action = "Hold the phone upright with the bottom speaker and top mic unobstructed.",
    ),
    val log: List<LogEntry> = emptyList(),
    val sweepAngleRad: Float = 0f,
    val diagnostics: List<Pair<String, String>> = emptyList(),
    val calibration: CalibrationResult? = null,
    val calibrationProgress: String = "",
    val permissionDenied: Boolean = false,
    val audioRateHz: Int = 0,
    val micLabel: String = "",
    val demo: Boolean = false,
) {
    val isRunning: Boolean get() = status == ScanStatus.SCANNING || status == ScanStatus.PREPARING
    val rotateMode: Boolean get() = prefs.sweepMode == com.echosonar.sonar.SweepMode.ROTATE
    val hasMap: Boolean get() = map?.hasData == true
}