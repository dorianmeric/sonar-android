package com.echosonar.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.echosonar.ui.theme.EchoColors
import kotlin.math.round

/**
 * Full-screen CRT treatment: horizontal scanlines, a soft vignette and a very slow brightness
 * flicker. Kept subtle so text stays readable.
 */
@Composable
fun CrtOverlay(modifier: Modifier = Modifier, intensity: Float = 1f) {
    val transition = rememberInfiniteTransition(label = "crt")
    val flicker by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flicker",
    )

    Canvas(modifier.fillMaxSize()) {
        val lineHeight = 3.dp.toPx()
        val line = 1.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawRect(
                color = Color.Black.copy(alpha = 0.14f * intensity * flicker),
                topLeft = Offset(0f, y),
                size = Size(size.width, line),
            )
            y += lineHeight
        }
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.34f * intensity)),
                center = Offset(size.width / 2f, size.height * 0.45f),
                radius = maxOf(size.width, size.height) * 0.72f,
            ),
        )
    }
}

/** Rounded phosphor panel used for every content block. */
@Composable
fun PhosphorPanel(
    modifier: Modifier = Modifier,
    borderColor: Color = EchoColors.PhosphorDeep,
    background: Color = EchoColors.Panel.copy(alpha = 0.82f),
    contentPadding: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(contentPadding),
        content = content,
    )
}

/** Small label + value pair used across the HUD and telemetry rows. */
@Composable
fun MetricChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = EchoColors.Phosphor,
    accent: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = EchoColors.TextSecondary.copy(alpha = 0.85f),
        )
        Text(
            text = value,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = if (accent) EchoColors.PhosphorBright else valueColor,
            maxLines = 1,
        )
    }
}

/** Section heading with a leading block cursor, terminal style. */
@Composable
fun SectionHeader(title: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "\u258C",
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            color = EchoColors.Phosphor,
        )
        Text(
            text = title,
            modifier = Modifier.padding(start = 4.dp),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            color = EchoColors.PhosphorBright,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                modifier = Modifier
                    .padding(start = 8.dp),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = EchoColors.TextSecondary,
            )
        }
    }
}

/** Status lamp: bright when active, with a slow pulse so "it is running" is obvious. */
@Composable
fun StatusLamp(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "lamp")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        drawCircle(color = color.copy(alpha = 0.25f), radius = radius)
        drawCircle(
            color = color.copy(alpha = if (active) pulse else 0.5f),
            radius = radius * 0.55f,
        )
        drawCircle(
            color = color.copy(alpha = 0.9f),
            radius = radius * 0.9f,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** Corner brackets for the "targeting system" frame. */
@Composable
fun CornerBrackets(modifier: Modifier = Modifier, color: Color = EchoColors.PhosphorDim) {
    Canvas(modifier.fillMaxSize()) {
        val length = 16.dp.toPx()
        val inset = 6.dp.toPx()
        val stroke = 1.5.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(color, Offset(inset, inset), Offset(inset + length, inset), stroke)
        drawLine(color, Offset(inset, inset), Offset(inset, inset + length), stroke)
        drawLine(color, Offset(w - inset, inset), Offset(w - inset - length, inset), stroke)
        drawLine(color, Offset(w - inset, inset), Offset(w - inset, inset + length), stroke)
        drawLine(color, Offset(inset, h - inset), Offset(inset + length, h - inset), stroke)
        drawLine(color, Offset(inset, h - inset), Offset(inset, h - inset - length), stroke)
        drawLine(color, Offset(w - inset, h - inset), Offset(w - inset - length, h - inset), stroke)
        drawLine(color, Offset(w - inset, h - inset), Offset(w - inset, h - inset - length), stroke)
    }
}

/** Thin horizontal divider with phosphor glow. */
@Composable
fun PhosphorDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        EchoColors.PhosphorDim,
                        EchoColors.Phosphor.copy(alpha = 0.6f),
                        EchoColors.PhosphorDim,
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

@Composable
fun BoxScope.HudLabel(
    text: String,
    alignment: Alignment,
    color: Color = EchoColors.TextSecondary,
    modifier: Modifier = Modifier,
    padding: Dp = 12.dp,
) {
    Text(
        text = text,
        modifier = modifier
            .align(alignment)
            .padding(padding),
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = color,
    )
}

/**
 * Compact phosphor slider used for every numeric control.
 *
 * Material's Slider brings its own visual language (rounded caps, stop indicators, oversized thumb)
 * which fights the CRT look, so this draws a track, tick marks and a square phosphor handle instead.
 */
@Composable
fun PhosphorSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    height: Dp = 34.dp,
) {
    val density = LocalDensity.current
    val handleWidth = 12.dp
    val handlePx = with(density) { handleWidth.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

    fun valueAt(x: Float): Float {
        val usable = (widthPx - handlePx).coerceAtLeast(1f)
        val raw = ((x - handlePx / 2f) / usable).coerceIn(0f, 1f)
        val quantised = if (steps > 0) {
            val divisions = steps + 1
            (round(raw * divisions) / divisions)
        } else {
            raw
        }
        return range.start + quantised * (range.endInclusive - range.start)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(range, steps, widthPx) {
                detectTapGestures { offset -> onValueChange(valueAt(offset.x)) }
            }
            .pointerInput(range, steps, widthPx) {
                detectHorizontalDragGestures { change, _ ->
                    onValueChange(valueAt(change.position.x))
                    change.consume()
                }
            },
    ) {
        val centreY = size.height / 2f
        val startX = handlePx / 2f
        val endX = size.width - handlePx / 2f
        val trackHeight = 3.dp.toPx()
        val radius = CornerRadius(trackHeight / 2f)

        drawRoundRect(
            color = EchoColors.PhosphorDeep,
            topLeft = Offset(startX, centreY - trackHeight / 2f),
            size = Size(endX - startX, trackHeight),
            cornerRadius = radius,
        )

        if (steps in 1..40) {
            val tick = 5.dp.toPx()
            for (i in 0..(steps + 1)) {
                val t = i.toFloat() / (steps + 1)
                val x = startX + t * (endX - startX)
                drawLine(
                    color = EchoColors.PhosphorDim.copy(alpha = 0.55f),
                    start = Offset(x, centreY - tick),
                    end = Offset(x, centreY + tick),
                    strokeWidth = 1f,
                )
            }
        }

        val handleX = startX + fraction * (endX - startX)
        if (handleX > startX + 0.5f) {
            drawRoundRect(
                color = EchoColors.PhosphorMid.copy(alpha = 0.9f),
                topLeft = Offset(startX, centreY - trackHeight / 2f),
                size = Size(handleX - startX, trackHeight),
                cornerRadius = radius,
            )
        }
        drawCircle(EchoColors.Phosphor.copy(alpha = 0.20f), 11.dp.toPx(), Offset(handleX, centreY))
        drawRoundRect(
            color = EchoColors.PhosphorBright,
            topLeft = Offset(handleX - 1.5.dp.toPx(), centreY - 9.dp.toPx()),
            size = Size(3.dp.toPx(), 18.dp.toPx()),
            cornerRadius = CornerRadius(1.5.dp.toPx()),
        )
    }
}
