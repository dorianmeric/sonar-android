package com.echosonar.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echosonar.sonar.AdviceSeverity
import com.echosonar.sonar.SweepMode
import com.echosonar.ui.LogEntry
import com.echosonar.ui.ScanStatus
import com.echosonar.ui.SonarUiState
import com.echosonar.ui.theme.EchoColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SonarHeader(
    state: SonarUiState,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "ECHOSONAR",
                style = MaterialTheme.typography.titleLarge,
                color = EchoColors.PhosphorBright,
            )
            Text(
                text = "ULTRASONIC ROOM IMAGING · 19 kHz",
                style = MaterialTheme.typography.labelSmall,
                color = EchoColors.TextSecondary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLamp(
                active = state.isRunning,
                color = statusColor(state.status),
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = statusLabel(state.status),
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor(state.status),
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Tune, contentDescription = "Tune", tint = EchoColors.Phosphor)
            }
            IconButton(onClick = onHelp) {
                Icon(Icons.Filled.Info, contentDescription = "How it works", tint = EchoColors.Phosphor)
            }
        }
    }
}

fun statusColor(status: ScanStatus): Color = when (status) {
    ScanStatus.SCANNING -> EchoColors.Phosphor
    ScanStatus.PREPARING, ScanStatus.CALIBRATING -> EchoColors.Amber
    ScanStatus.ERROR -> EchoColors.Critical
    ScanStatus.IDLE -> EchoColors.PhosphorDim
}

fun statusLabel(status: ScanStatus): String = when (status) {
    ScanStatus.SCANNING -> "PINGING"
    ScanStatus.PREPARING -> "ARMING"
    ScanStatus.CALIBRATING -> "PROBING"
    ScanStatus.ERROR -> "FAULT"
    ScanStatus.IDLE -> "IDLE"
}

@Composable
fun TelemetryGrid(state: SonarUiState, modifier: Modifier = Modifier) {
    val metrics = state.metrics
    val stats = state.stats
    val map = state.map
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricChip(
                label = "PEAK SNR",
                value = metrics?.let { "${"%.0f".format(it.peakSnrDb)} dB" } ?: "--",
                modifier = Modifier.weight(1f),
                accent = (metrics?.peakSnrDb ?: 0f) > 20f,
            )
            MetricChip(
                label = "NEAREST",
                value = metrics?.takeIf { it.nearestRangeM > 0f }?.let { "${"%.2f".format(it.nearestRangeM)} m" } ?: "--",
                modifier = Modifier.weight(1f),
            )
            MetricChip(
                label = "QUALITY",
                value = metrics?.let { "${it.quality}/100" } ?: "--",
                modifier = Modifier.weight(1f),
            )
            MetricChip(
                label = "COVERAGE",
                value = map?.let { "${(it.coverage * 100).toInt()} %" } ?: "0 %",
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricChip(
                label = "NOISE FLOOR",
                value = metrics?.takeIf { it.directLocked }?.let { "${"%.0f".format(it.noiseFloorDb)} dB" } ?: "--",
                modifier = Modifier.weight(1f),
            )
            MetricChip(
                label = "LATENCY",
                value = metrics?.takeIf { it.directLocked }?.let { "${"%.0f".format(it.directLagMs)} ms" } ?: "--",
                modifier = Modifier.weight(1f),
            )
            MetricChip(
                label = "RESOLUTION",
                value = metrics?.let { "${"%.0f".format(it.rangeResolutionM * 100f)} cm" } ?: "--",
                modifier = Modifier.weight(1f),
            )
            MetricChip(
                label = "MEAN RANGE",
                value = map?.takeIf { it.hasData }?.let { "${"%.1f".format(it.meanRangeM)} m" } ?: "--",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "PINGS ${stats.pingCount} · HITS ${stats.echoPingCount} · " +
                when {
                    state.demo -> "SWEEP SYNTHETIC"
                    state.rotateMode -> "ROTATION ${stats.rotationSpeedDeg.toInt()} °/s"
                    else -> "SWEEP SYNTHETIC"
                },
            style = MaterialTheme.typography.labelSmall,
            color = EchoColors.TextSecondary.copy(alpha = 0.85f),
        )
    }
}

@Composable
fun AdviceCard(state: SonarUiState, modifier: Modifier = Modifier) {
    val advice = state.advice
    val accent = severityColor(advice.severity)
    AnimatedContent(
        targetState = advice,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        label = "advice",
        modifier = modifier.fillMaxWidth(),
    ) { current ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.08f))
                .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = severityIcon(current.severity),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = current.title.uppercase(),
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    maxLines = 2,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = severityLabel(current.severity),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.8f),
                )
            }
            Text(
                text = current.detail,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = EchoColors.TextPrimary,
            )
            current.action?.let { action ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        text = "→",
                        style = MaterialTheme.typography.bodySmall,
                        color = accent,
                    )
                    Text(
                        text = action,
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = EchoColors.PhosphorBright,
                    )
                }
            }
        }
    }
}

@Composable
fun ConsoleLog(entries: List<LogEntry>, modifier: Modifier = Modifier) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    PhosphorPanel(modifier = modifier, contentPadding = 8.dp) {
        SectionHeader(title = "CONSOLE", trailing = "${entries.size} events")
        Spacer(Modifier.height(4.dp))
        if (entries.isEmpty()) {
            Text(
                text = "> waiting for the first ping…",
                style = MaterialTheme.typography.bodySmall,
                color = EchoColors.TextSecondary,
            )
        } else {
            // A plain Column, not LazyColumn: this panel lives inside a vertically scrolling parent, so
            // a lazy list would be measured with infinite height constraints and crash.
            Column(Modifier.fillMaxWidth()) {
                entries.forEach { entry ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(
                            text = formatter.format(Date(entry.timeMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = EchoColors.PhosphorDim,
                        )
                        Text(
                            text = ">",
                            modifier = Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = severityColor(entry.severity),
                        )
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = severityColor(entry.severity).copy(alpha = 0.95f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SensitivityBar(
    thresholdDb: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Slider is inverted so dragging right means "more sensitive".
    val sliderValue = (26f - thresholdDb).coerceIn(0f, 24f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "SENS",
            style = MaterialTheme.typography.labelSmall,
            color = EchoColors.TextSecondary,
        )
        Text(
            text = "STRICT",
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = EchoColors.PhosphorDim,
        )
        PhosphorSlider(
            value = sliderValue,
            onValueChange = { onChange(26f - it) },
            range = 0f..24f,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
        )
        Text(
            text = "EAGER",
            style = MaterialTheme.typography.labelSmall,
            color = EchoColors.PhosphorDim,
        )
        Text(
            text = "${"%.0f".format(thresholdDb)}dB",
            modifier = Modifier.padding(start = 8.dp).width(44.dp),
            style = MaterialTheme.typography.labelMedium,
            color = EchoColors.PhosphorBright,
        )
    }
}

@Composable
fun ControlBar(
    state: SonarUiState,
    onStartStop: () -> Unit,
    onSweepMode: (SweepMode) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onStartStop,
            modifier = Modifier.height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isRunning) EchoColors.Critical.copy(alpha = 0.22f) else EchoColors.PhosphorDeep,
                contentColor = if (state.isRunning) EchoColors.Critical else EchoColors.PhosphorBright,
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (state.isRunning) EchoColors.Critical else EchoColors.Phosphor,
            ),
        ) {
            Icon(
                imageVector = if (state.isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (state.isRunning) "STOP" else "START",
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SweepChip(
                    label = "ROTATE",
                    selected = state.prefs.sweepMode == SweepMode.ROTATE,
                    onClick = { onSweepMode(SweepMode.ROTATE) },
                )
                SweepChip(
                    label = "AUTO",
                    selected = state.prefs.sweepMode == SweepMode.AUTO,
                    onClick = { onSweepMode(SweepMode.AUTO) },
                )
            }
            Text(
                text = if (state.prefs.sweepMode == SweepMode.ROTATE) {
                    "turn the phone to paint bearings"
                } else {
                    "synthetic sweep, no real bearing"
                },
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = EchoColors.TextSecondary,
            )
        }

        IconButton(onClick = onClear) {
            Icon(
                Icons.Filled.DeleteSweep,
                contentDescription = "Clear map",
                tint = EchoColors.PhosphorMid,
            )
        }
    }
}

@Composable
private fun SweepChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = EchoColors.PhosphorDeep,
            selectedLabelColor = EchoColors.PhosphorBright,
            labelColor = EchoColors.TextSecondary,
            containerColor = Color.Transparent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = EchoColors.PhosphorDim,
            selectedBorderColor = EchoColors.Phosphor,
        ),
    )
}

@Composable
fun PermissionCard(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(EchoColors.Critical.copy(alpha = 0.10f))
            .border(1.dp, EchoColors.Critical.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = EchoColors.Critical, modifier = Modifier.size(16.dp))
            Text(
                text = "MICROPHONE ACCESS REQUIRED",
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = EchoColors.Critical,
            )
        }
        Text(
            text = "EchoSonar listens for its own chirp. Without the microphone permission there is " +
                "nothing to analyse — no audio is stored or sent anywhere.",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = EchoColors.TextPrimary,
        )
        Button(
            onClick = onGrant,
            modifier = Modifier
                .padding(top = 8.dp)
                .height(40.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = EchoColors.Critical.copy(alpha = 0.25f),
                contentColor = EchoColors.Critical,
            ),
        ) {
            Text("GRANT MICROPHONE", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun StatusBanner(state: SonarUiState, modifier: Modifier = Modifier) {
    val color = when (state.status) {
        ScanStatus.ERROR -> EchoColors.Critical
        ScanStatus.PREPARING, ScanStatus.CALIBRATING -> EchoColors.Amber
        else -> EchoColors.PhosphorMid
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (state.status) {
                ScanStatus.ERROR -> Icons.Filled.Error
                ScanStatus.PREPARING, ScanStatus.CALIBRATING -> Icons.Filled.Bolt
                else -> Icons.Filled.CheckCircle
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = state.statusMessage,
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 2,
        )
    }
}

@Composable
fun IconRowButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = EchoColors.PhosphorMid,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = 0.85f),
        )
    }
}

fun severityColor(severity: AdviceSeverity): Color = when (severity) {
    AdviceSeverity.GOOD -> EchoColors.Phosphor
    AdviceSeverity.INFO -> EchoColors.PhosphorMid
    AdviceSeverity.HINT -> EchoColors.Amber
    AdviceSeverity.WARN -> EchoColors.Warning
    AdviceSeverity.CRITICAL -> EchoColors.Critical
}

fun severityIcon(severity: AdviceSeverity): ImageVector = when (severity) {
    AdviceSeverity.GOOD -> Icons.Filled.CheckCircle
    AdviceSeverity.INFO -> Icons.Filled.Info
    AdviceSeverity.HINT -> Icons.Filled.Lightbulb
    AdviceSeverity.WARN -> Icons.Filled.Warning
    AdviceSeverity.CRITICAL -> Icons.Filled.Error
}

fun severityLabel(severity: AdviceSeverity): String = when (severity) {
    AdviceSeverity.GOOD -> "GOOD"
    AdviceSeverity.INFO -> "INFO"
    AdviceSeverity.HINT -> "HINT"
    AdviceSeverity.WARN -> "WARN"
    AdviceSeverity.CRITICAL -> "CRITICAL"
}

@Composable
fun ProfileStrip(
    state: SonarUiState,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile
    PhosphorPanel(modifier = modifier, contentPadding = 8.dp) {
        SectionHeader(
            title = "A-SCAN",
            trailing = profile?.let { "near → far, ${"%.0f".format(it.binSizeM * 100)} cm bins" } ?: "no data",
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(EchoColors.Glass),
        ) {
            if (profile == null) {
                Text(
                    text = "awaiting returns",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.labelSmall,
                    color = EchoColors.PhosphorDim,
                )
            } else {
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                    val bins = profile.size
                    if (bins == 0) return@Canvas
                    val barWidth = size.width / bins
                    for (i in 0 until bins) {
                        val intensity = profile.intensity[i]
                        if (intensity <= 0f) continue
                        val height = (intensity * size.height).coerceAtLeast(2f)
                        drawRect(
                            color = EchoColors.Phosphor.copy(alpha = 0.35f + 0.6f * intensity),
                            topLeft = androidx.compose.ui.geometry.Offset(i * barWidth, size.height - height),
                            size = androidx.compose.ui.geometry.Size(barWidth * 0.72f, height),
                        )
                    }
                    drawLine(
                        color = EchoColors.PhosphorDeep,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height - 1f),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height - 1f),
                        strokeWidth = 1f,
                    )
                }
            }
        }
    }
}
