package com.echosonar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.echosonar.dsp.SonarConfig
import com.echosonar.sonar.MicMode
import com.echosonar.ui.SonarUiState
import com.echosonar.ui.components.PhosphorDivider
import com.echosonar.ui.components.PhosphorSlider
import com.echosonar.ui.components.SectionHeader
import com.echosonar.ui.theme.EchoColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: SonarUiState,
    onDismiss: () -> Unit,
    onApply: (SonarConfig) -> Unit,
    onPersistence: (Float) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onHardwareHints: (Boolean) -> Unit,
    onMicMode: (MicMode) -> Unit,
    onProbe: () -> Unit,
    onUseFrequency: (Float) -> Unit,
    onResetDefaults: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(state.config) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = EchoColors.Panel,
        contentColor = EchoColors.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "TUNE",
                style = MaterialTheme.typography.titleLarge,
                color = EchoColors.PhosphorBright,
            )
            Text(
                text = "Acoustic parameters · changes restart the audio path",
                style = MaterialTheme.typography.labelSmall,
                color = EchoColors.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))

            SectionHeader("CHIRP")
            FrequencyRow(draft) { draft = draft.copy(centerHz = it) }
            SliderRow(
                label = "BANDWIDTH",
                valueText = "${"%.2f".format(draft.bandwidthHz / 1000f)} kHz · ${"%.0f".format(draft.rangeResolutionM * 100f)} cm res",
                value = draft.bandwidthHz,
                range = 500f..5000f,
                steps = 17,
                hint = "Wider sweep = finer range resolution, but the speaker may not cover the edges.",
            ) { draft = draft.copy(bandwidthHz = it) }
            SliderRow(
                label = "CHIRP LENGTH",
                valueText = "${draft.chirpMs} ms",
                value = draft.chirpMs.toFloat(),
                range = 4f..30f,
                steps = 25,
                hint = "Longer bursts carry more energy into the room (better SNR) but blur moving targets.",
            ) { draft = draft.copy(chirpMs = it.toInt()) }
            SliderRow(
                label = "PING INTERVAL",
                valueText = "${draft.pingIntervalMs} ms · ${"%.1f".format(1000f / draft.pingIntervalMs)} pings/s",
                value = draft.pingIntervalMs.toFloat(),
                range = 180f..900f,
                steps = 35,
                hint = "Must exceed one capture window (${draft.windowMs} ms) so pings do not overlap.",
            ) { draft = draft.copy(pingIntervalMs = it.toInt()) }

            Spacer(Modifier.height(10.dp))
            SectionHeader("RANGE")
            SliderRow(
                label = "MAX RANGE",
                valueText = "${"%.1f".format(draft.maxRangeM)} m · ${draft.rangeBins} bins",
                value = draft.maxRangeM,
                range = 1.5f..8f,
                steps = 12,
                hint = "Air absorbs 19 kHz quickly; beyond ~6 m most rooms return almost nothing.",
            ) { draft = draft.copy(maxRangeM = it) }
            SliderRow(
                label = "BLIND ZONE",
                valueText = "${"%.2f".format(draft.minRangeM)} m",
                value = draft.minRangeM,
                range = 0.1f..1.2f,
                steps = 21,
                hint = "Closer than this the transmit burst is still ringing in the microphone.",
            ) { draft = draft.copy(minRangeM = it) }
            SliderRow(
                label = "TEMPERATURE",
                valueText = "${"%.0f".format(draft.temperatureC)} °C · sound ${"%.0f".format(draft.speedOfSound)} m/s",
                value = draft.temperatureC,
                range = -5f..45f,
                steps = 49,
                hint = "Humidity is ignored; temperature changes the speed of sound by ~0.6 m/s per °C.",
            ) { draft = draft.copy(temperatureC = it) }

            Spacer(Modifier.height(10.dp))
            SectionHeader("DETECTION")
            SliderRow(
                label = "GAIN",
                valueText = "${"%.0f".format(draft.gainDb)} dB",
                value = draft.gainDb,
                range = -6f..18f,
                steps = 23,
                hint = "Applied after spreading-loss compensation; it cannot create signal that is not there.",
            ) { draft = draft.copy(gainDb = it) }
            SliderRow(
                label = "THRESHOLD",
                valueText = "${"%.0f".format(draft.thresholdDb)} dB above local floor",
                value = draft.thresholdDb,
                range = 2f..26f,
                steps = 23,
                hint = "Peaks must stand this far above the running median of nearby range cells.",
            ) { draft = draft.copy(thresholdDb = it) }
            SliderRow(
                label = "LATENCY MARGIN",
                valueText = "${draft.latencyMarginMs} ms",
                value = draft.latencyMarginMs.toFloat(),
                range = 80f..600f,
                steps = 25,
                hint = "Search window for the speaker→mic reference pulse. Raise it if coupling " +
                    "never locks on Bluetooth speakers or docks.",
            ) { draft = draft.copy(latencyMarginMs = it.toInt()) }
            SliderRow(
                label = "CAPTURE RATE",
                valueText = "${draft.sampleRateHz / 1000} kHz",
                value = draft.sampleRateHz.toFloat(),
                range = 44_100f..48_000f,
                steps = 0,
                hint = "48 kHz is required to reproduce 19 kHz cleanly; 44.1 kHz is a fallback.",
            ) { draft = draft.copy(sampleRateHz = if (it > 46_000f) 48_000 else 44_100) }

            Spacer(Modifier.height(10.dp))
            SectionHeader("DISPLAY & INPUT")
            SliderRow(
                label = "PHOSPHOR PERSISTENCE",
                valueText = if (state.prefs.persistenceSec <= 0.1f) "OFF (keep forever)" else "${"%.1f".format(state.prefs.persistenceSec)} s",
                value = state.prefs.persistenceSec,
                range = 0f..25f,
                steps = 24,
                hint = "How fast old echoes fade. Long trails show a full rotation; short trails track motion.",
            ) { onPersistence(it) }
            ToggleRow(
                label = "HAPTIC ECHO TICKS",
                checked = state.prefs.haptics,
                hint = "A short vibration whenever a strong return is found.",
            ) { onHaptics(it) }
            ToggleRow(
                label = "HARDWARE HINTS",
                checked = state.prefs.showHardwareHints,
                hint = "Labels the top microphone and bottom speaker in the radar view.",
            ) { onHardwareHints(it) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MICROPHONE PATH",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.PhosphorBright,
                )
                FilterChip(
                    selected = state.prefs.micMode == MicMode.SYSTEM_TUNED,
                    onClick = { onMicMode(MicMode.SYSTEM_TUNED) },
                    label = { Text("TUNED", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = EchoColors.PhosphorDeep,
                        selectedLabelColor = EchoColors.PhosphorBright,
                        labelColor = EchoColors.TextSecondary,
                        containerColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = state.prefs.micMode == MicMode.UNPROCESSED,
                    onClick = { onMicMode(MicMode.UNPROCESSED) },
                    label = { Text("RAW", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = EchoColors.PhosphorDeep,
                        selectedLabelColor = EchoColors.PhosphorBright,
                        labelColor = EchoColors.TextSecondary,
                        containerColor = Color.Transparent,
                    ),
                )
            }
            Text(
                text = "TUNED uses the system's tuned capture path; RAW bypasses echo cancellation but " +
                    "some devices refuse it.",
                style = MaterialTheme.typography.labelSmall,
                color = EchoColors.TextSecondary,
            )

            Spacer(Modifier.height(14.dp))
            FrequencyProbeSection(state, onProbe, onUseFrequency)

            Spacer(Modifier.height(14.dp))
            PhosphorDivider(Modifier.fillMaxWidth().height(1.dp))
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onApply(draft) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.PhosphorDeep,
                        contentColor = EchoColors.PhosphorBright,
                    ),
                ) {
                    Text("APPLY & RESTART", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onResetDefaults) {
                    Text("DEFAULTS", color = EchoColors.Amber, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun FrequencyRow(config: SonarConfig, onChange: (Float) -> Unit) {
    SliderRow(
        label = "CENTRE FREQUENCY",
        valueText = "${"%.2f".format(config.centerHz / 1000f)} kHz · sweep ${"%.1f".format(config.chirpSpec.startHz / 1000f)}–${"%.1f".format(config.chirpSpec.endHz / 1000f)} kHz",
        value = config.centerHz,
        range = 17_000f..21_500f,
        steps = 17,
        hint = "19 kHz is inaudible to most adults. Lower frequencies travel further but are more audible.",
    ) { onChange(it) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(18_000f, 18_500f, 19_000f, 19_500f, 20_000f).forEach { frequency ->
            FilterChip(
                selected = kotlin.math.abs(config.centerHz - frequency) < 60f,
                onClick = { onChange(frequency) },
                label = {
                    Text(
                        text = "${"%.1f".format(frequency / 1000f)}k",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = EchoColors.PhosphorDeep,
                    selectedLabelColor = EchoColors.PhosphorBright,
                    labelColor = EchoColors.TextSecondary,
                    containerColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun FrequencyProbeSection(
    state: SonarUiState,
    onProbe: () -> Unit,
    onUseFrequency: (Float) -> Unit,
) {
    SectionHeader("FREQUENCY PROBE")
    Text(
        text = "Measures how strongly your speaker and microphone actually couple at each candidate " +
            "frequency. Most phone speakers roll off above 19.5 kHz and cases make it worse — this " +
            "tells you which frequency your device really supports.",
        style = MaterialTheme.typography.bodySmall,
        color = EchoColors.TextSecondary,
    )
    val calibration = state.calibration
    if (calibration != null && !calibration.usable) {
        Spacer(Modifier.height(8.dp))
        SheetNote(
            text = "No coupling was detected at any candidate frequency. The phone could not hear its own " +
                "chirp — raise media volume, remove the case, and probe again. The current frequency was kept.",
            color = EchoColors.Critical,
        )
    }
    if (calibration != null && calibration.usable) {
        Spacer(Modifier.height(8.dp))
        val best = calibration.responses.maxOfOrNull { it.levelDb } ?: 0f
        calibration.responses.forEach { response ->
            val normalised = ((response.levelDb - (best - 24f)) / 24f).coerceIn(0f, 1f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${"%.2f".format(response.centerHz / 1000f)}k",
                    modifier = Modifier.width(46.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = EchoColors.TextSecondary,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(EchoColors.Glass),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(normalised.coerceAtLeast(0.02f))
                            .height(10.dp)
                            .background(if (response.levelDb == best) EchoColors.Phosphor else EchoColors.PhosphorMid),
                    )
                }
                Text(
                    text = "${"%.1f".format(response.levelDb)} dB",
                    modifier = Modifier
                        .width(56.dp)
                        .padding(start = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = EchoColors.PhosphorBright,
                )
                if (response.levelDb == best) {
                    TextButton(onClick = { onUseFrequency(response.centerHz) }) {
                        Text("USE", style = MaterialTheme.typography.labelSmall, color = EchoColors.Phosphor)
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
    }
    if (state.calibrationProgress.isNotEmpty()) {
        Text(
            text = state.calibrationProgress,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = EchoColors.Amber,
        )
    }
    Button(
        onClick = onProbe,
        modifier = Modifier
            .padding(top = 8.dp)
            .height(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = EchoColors.PhosphorDeep,
            contentColor = EchoColors.PhosphorBright,
        ),
    ) {
        Text("PROBE FREQUENCIES", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    hint: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.PhosphorBright,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall,
                color = EchoColors.TextSecondary,
            )
        }
        PhosphorSlider(
            value = value,
            onValueChange = onChange,
            range = range,
            steps = steps,
        )
        if (hint != null) {
            Text(
                text = hint,
                modifier = Modifier.padding(bottom = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = EchoColors.TextSecondary.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, hint: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.PhosphorBright,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = EchoColors.TextSecondary.copy(alpha = 0.8f),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = EchoColors.PhosphorBright,
                checkedTrackColor = EchoColors.PhosphorDim,
                uncheckedThumbColor = EchoColors.TextSecondary,
                uncheckedTrackColor = EchoColors.PanelHigh,
            ),
        )
    }
}

/** Small bordered note used inside sheets. */
@Composable
fun SheetNote(text: String, color: Color = EchoColors.Amber, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}
