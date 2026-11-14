package com.echosonar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.echosonar.ui.SonarUiState
import com.echosonar.ui.components.PhosphorDivider
import com.echosonar.ui.components.SectionHeader
import com.echosonar.ui.theme.EchoColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(state: SonarUiState, onDismiss: () -> Unit, onStartDemo: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
            Text("HOW IT WORKS", style = MaterialTheme.typography.titleLarge, color = EchoColors.PhosphorBright)
            Text(
                text = "A bat-style pulse-echo sonar running entirely on your phone.",
                style = MaterialTheme.typography.labelSmall,
                color = EchoColors.TextSecondary,
            )

            Spacer(Modifier.height(12.dp))
            SectionHeader("THE PIPELINE")
            Paragraph(
                "1 · The bottom speaker emits a ${state.config.chirpMs} ms linear chirp sweeping " +
                    "${"%.1f".format(state.config.chirpSpec.startHz / 1000f)}–" +
                    "${"%.1f".format(state.config.chirpSpec.endHz / 1000f)} kHz. A Hann-shaped envelope keeps " +
                    "the correlation sidelobes 40–50 dB down, which is what stops the loud speaker→microphone " +
                    "coupling from spraying phantom walls everywhere.",
            )
            Paragraph(
                "2 · The top microphone records continuously at ${state.config.sampleRateHz / 1000} kHz. " +
                    "Each ping grabs a ${state.config.windowMs} ms window that starts before the chirp is " +
                    "played, so the direct coupling pulse is always inside it.",
            )
            Paragraph(
                "3 · The window is correlated with the chirp's cosine/sine references via FFT — a matched " +
                    "filter. That is both a band-pass (noise outside 17–21 kHz is rejected) and a compressor: " +
                    "range resolution is c / 2B = ${"%.0f".format(state.config.rangeResolutionM * 100)} cm for the " +
                    "current bandwidth.",
            )
            Paragraph(
                "4 · The strongest early peak is the speaker→microphone coupling. Its position removes all " +
                    "unknown audio latency, its amplitude becomes the reference level, and it is subtracted " +
                    "before the echoes are measured.",
            )
            Paragraph(
                "5 · Remaining peaks are binned into ${state.config.rangeBins} range cells of " +
                    "${"%.0f".format(state.config.rangeBinM * 100)} cm, corrected for spreading loss " +
                    "(+20 dB per decade) and compared against the running median of nearby cells — so a wall " +
                    "is a local peak, not just a loud cell.",
            )
            Paragraph(
                "6 · Each detection is painted into a polar map at the phone's current bearing. Max-blend " +
                    "keeps walls crisp, exponential decay makes the phosphor fade.",
            )

            Spacer(Modifier.height(10.dp))
            SectionHeader("SYNTHETIC DEMO")
            Paragraph(
                "No hard wall nearby, or reviewing this on a device with a dead speaker? The demo runs the " +
                    "identical range processor and map accumulator against a synthetic room — the only thing " +
                    "missing is the microphone.",
            )
            Button(
                onClick = onStartDemo,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EchoColors.PhosphorDeep,
                    contentColor = EchoColors.PhosphorBright,
                ),
            ) {
                Text("PLAY SYNTHETIC DEMO", style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(10.dp))
            SectionHeader("GETTING A GOOD SCAN")
            Bullet("Media volume 80–100 %. Below ~50 % the chirp is too quiet to hear its own echo.")
            Bullet("Take the case off. A few millimetres of plastic or leather can cost you 10 dB at 19 kHz.")
            Bullet("Aim the top edge at hard, flat things: walls, doors, glass, tiles, tables. Sofas, curtains and carpets absorb ultrasound.")
            Bullet("Stay 1–3 m from the nearest surface. Closer than the blind zone (${"%.2f".format(state.config.minRangeM)} m) the echo hides inside the transmit burst.")
            Bullet("Turn the phone slowly — around one circle per 15–20 s. Fast rotation smears each ping across many degrees.")
            Bullet("Hold it upright and steady; handling noise is loud in the ultrasonic band.")
            Bullet("Quiet the room: fans, air-conditioners, aquarium pumps and other phones emit ultrasound.")
            Bullet("Run the frequency probe in TUNE once. It finds the frequency your particular speaker and microphone actually handle.")

            Spacer(Modifier.height(10.dp))
            SectionHeader("HONEST LIMITATIONS")
            Bullet("Bearing is not measured. A single microphone is omnidirectional, so the angle comes from the phone's rotation (or the synthetic AUTO sweep). The map is a sketch of 'what was in front when this ping happened'.")
            Bullet("Echoes smear over roughly a fist-width of range because of the correlation envelope and the speaker's uneven response.")
            Bullet("Specular walls can miss the microphone entirely if they are angled away — that is why some directions come back empty.")
            Bullet("Reverberation raises the local noise floor in bare rooms, which hides weak returns behind the walls you already found.")
            Bullet("The system's own audio processing (automatic gain, noise suppression, echo cancellation) can notch the ultrasonic band. RAW microphone mode bypasses some of it, but not on every device.")
            Bullet("Reported ranges assume the speed of sound at ${"%.0f".format(state.config.temperatureC)} °C " +
                "(${"%.0f".format(state.config.speedOfSound)} m/s); humidity and altitude are ignored.")

            Spacer(Modifier.height(10.dp))
            SectionHeader("SAFETY")
            Paragraph(
                "19 kHz is above the usual adult hearing limit but is audible to young children and pets, and " +
                    "some adults do hear it — especially as a faint hiss or a headache after long exposure. " +
                    "Keep the volume moderate, take breaks, and stop if anyone finds it uncomfortable. " +
                    "This is a toy-grade sonar: it is not a measuring instrument, a medical device, or a " +
                    "navigation aid.",
                color = EchoColors.Amber,
            )

            Spacer(Modifier.height(10.dp))
            SectionHeader("DEVICE DIAGNOSTICS")
            if (state.diagnostics.isEmpty()) {
                Paragraph("Diagnostics are collected when the app starts.")
            } else {
                state.diagnostics.forEach { (key, value) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(
                            text = key,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = EchoColors.TextSecondary,
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.labelSmall,
                            color = EchoColors.PhosphorBright,
                        )
                    }
                }
            }

            state.metrics?.let { metrics ->
                Spacer(Modifier.height(8.dp))
                SectionHeader("LAST PING")
                DiagnosticRow("Detected cells", "${metrics.detectedBins}")
                DiagnosticRow("Peak prominence", "${"%.1f".format(metrics.peakSnrDb)} dB above local floor")
                DiagnosticRow("Direct coupling lag", "${"%.1f".format(metrics.directLagMs)} ms")
                DiagnosticRow("Raw window peak", "${"%.3f".format(metrics.inputPeak)} of full scale")
                DiagnosticRow("Captured RMS", "${"%.4f".format(metrics.inputRms)}")
                DiagnosticRow("Clipped", if (metrics.clipped) "yes — reduce volume" else "no")
                DiagnosticRow(
                    "Timing lock",
                    if (metrics.directLocked) "locked" else "unlocked — raise volume / uncouple the mic",
                )
            }

            Spacer(Modifier.height(14.dp))
            PhosphorDivider(Modifier.fillMaxWidth().height(1.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Paragraph(text: String, color: androidx.compose.ui.graphics.Color = EchoColors.TextPrimary) {
    Text(
        text = text,
        modifier = Modifier.padding(bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
        Text("·", style = MaterialTheme.typography.bodySmall, color = EchoColors.Phosphor)
        Text(
            text = text,
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = EchoColors.TextPrimary,
        )
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = EchoColors.TextSecondary)
        Text(value, style = MaterialTheme.typography.labelSmall, color = EchoColors.PhosphorBright)
    }
}
