package com.echosonar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.echosonar.ui.theme.EchoColors

/**
 * First-run briefing. Four short, honest steps instead of a wall of text: what it does, what you
 * must do, what it cannot do, and the safety note.
 */
@Composable
fun OnboardingOverlay(onDismiss: () -> Unit, onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(18.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(EchoColors.Panel)
                .border(1.dp, EchoColors.PhosphorDim, RoundedCornerShape(14.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "ECHOSONAR",
                style = MaterialTheme.typography.displaySmall,
                color = EchoColors.PhosphorBright,
            )
            Text(
                text = "Ultrasonic room sonar",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextSecondary,
            )
            Spacer(Modifier.height(14.dp))

            OnboardingStep(
                icon = Icons.Filled.GraphicEq,
                title = "1 · What happens",
                body = "The bottom speaker fires a 19 kHz chirp — inaudible to most adults. The top " +
                    "microphone records the echoes, a matched filter compresses them into distances, " +
                    "and the polar map is painted in phosphor green.",
            )
            OnboardingStep(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "2 · Before you start",
                body = "Set media volume to 80–100 %, remove the case, and keep both ends of the " +
                    "phone clear. Hard surfaces (walls, doors, glass) return strong echoes; " +
                    "curtains, sofas and carpets swallow ultrasound.",
            )
            OnboardingStep(
                icon = Icons.Filled.ScreenRotation,
                title = "3 · How to sweep",
                body = "Hold the phone upright at chest height and turn slowly — about one full " +
                    "circle every 15–20 seconds. The map fills in whichever way you point. " +
                    "AUTO mode draws a synthetic sweep instead.",
            )
            OnboardingStep(
                icon = Icons.Filled.Warning,
                title = "4 · What it cannot do",
                body = "One speaker and one microphone cannot measure true bearing: the bearing comes " +
                    "from the phone's own rotation, and echoes smear over roughly a fist-width of " +
                    "range. Treat the map as a sketch, not a survey.",
            )

            Text(
                text = "19 kHz is audible to children, some adults and many pets. Keep the volume " +
                    "moderate and stop if anyone is uncomfortable.",
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = EchoColors.Amber,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("LATER", color = EchoColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = onStart,
                    modifier = Modifier.height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.PhosphorDeep,
                        contentColor = EchoColors.PhosphorBright,
                    ),
                ) {
                    Text("START SCANNING", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun OnboardingStep(icon: ImageVector, title: String, body: String) {
    Row(modifier = Modifier.padding(bottom = 12.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = EchoColors.Phosphor,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = EchoColors.Phosphor,
            )
            Text(
                text = body,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = EchoColors.TextPrimary,
            )
        }
    }
}
