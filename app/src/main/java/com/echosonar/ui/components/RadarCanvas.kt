package com.echosonar.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.echosonar.sensors.OrientationTracker
import com.echosonar.sensors.PoseMath
import com.echosonar.ui.ScanStatus
import com.echosonar.ui.SonarUiState
import com.echosonar.ui.theme.EchoColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * The retro-green wireframe radar.
 *
 * Echo cells come straight from the accumulated polar map; the bright polyline is the smoothed
 * strongest return per bearing (the "wall" contour) and the dim dashed one is the nearest return.
 * The sweep is animated towards the incoming heading so the phosphor trail stays smooth even though
 * pings only arrive a few times per second.
 */
@Composable
fun RadarView(state: SonarUiState, modifier: Modifier = Modifier) {
    val targetAngle by rememberUpdatedState(state.sweepAngleRad)
    var sweep by remember { mutableFloatStateOf(state.sweepAngleRad) }
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                    val delta = OrientationTracker.shortestArc(sweep, targetAngle)
                    sweep = OrientationTracker.wrap(sweep + delta.coerceIn(-5f * dt, 5f * dt))
                    frame++
                }
                last = now
            }
        }
    }

    val density = LocalDensity.current
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            // Reading the frame counter keeps the draw phase invalidated while the sweep animates.
            @Suppress("UNUSED_EXPRESSION")
            frame
            drawRadar(state, sweep, labelPaint, density.density)
        }

        CornerBrackets(color = EchoColors.PhosphorDim.copy(alpha = 0.7f))

        HudLabel(
            text = "RANGE ${"%.1f".format(state.config.maxRangeM)} m",
            alignment = Alignment.TopStart,
            color = EchoColors.TextSecondary,
        )
        HudLabel(
            text = if (state.demo) {
                "DEMO · SYNTHETIC ROOM"
            } else if (state.prefs.sweepMode == com.echosonar.sonar.SweepMode.ROTATE) {
                "ROTATE · BEARING"
            } else {
                "AUTO SWEEP"
            },
            alignment = Alignment.TopEnd,
            color = when {
                state.demo -> EchoColors.Amber
                state.prefs.sweepMode == com.echosonar.sonar.SweepMode.AUTO -> EchoColors.Amber
                else -> EchoColors.TextSecondary
            },
        )
        HudLabel(
            text = "PINGS ${state.stats.pingCount}",
            alignment = Alignment.BottomStart,
            color = EchoColors.TextSecondary,
        )
        HudLabel(
            text = "COVER ${((state.map?.coverage ?: 0f) * 100).toInt()} %",
            alignment = Alignment.BottomEnd,
            color = EchoColors.TextSecondary,
        )

        when {
            state.status == ScanStatus.ERROR -> HudLabel(
                text = "FAULT",
                alignment = Alignment.Center,
                color = EchoColors.Critical,
                modifier = Modifier.padding(top = 120.dp),
            )

            state.status == ScanStatus.PREPARING || state.status == ScanStatus.CALIBRATING -> HudLabel(
                text = state.statusMessage.uppercase(),
                alignment = Alignment.Center,
                color = EchoColors.Amber,
                modifier = Modifier.padding(top = 120.dp),
            )

            state.status == ScanStatus.IDLE -> HudLabel(
                text = "STANDBY — PRESS START",
                alignment = Alignment.Center,
                color = EchoColors.PhosphorDim,
                modifier = Modifier.padding(top = 120.dp),
            )

            state.map?.hasData != true -> HudLabel(
                text = "SEARCHING FOR RETURNS…",
                alignment = Alignment.Center,
                color = EchoColors.PhosphorDim,
                modifier = Modifier.padding(top = 120.dp),
            )
        }

        if (state.isRunning && !state.demo && state.prefs.sweepMode == com.echosonar.sonar.SweepMode.AUTO) {
            HudLabel(
                text = "SYNTHETIC SWEEP · bearing interpolated, not measured",
                alignment = Alignment.BottomCenter,
                color = EchoColors.Amber.copy(alpha = 0.85f),
            )
        } else if (state.isRunning && !state.demo && state.stats.poseKnown) {
            // "Upright" made measurable: the bearing reference degenerates as the phone tilts.
            when {
                state.stats.tiltDeg > PoseMath.TILT_WARN_DEG -> HudLabel(
                    text = "TILT ${state.stats.tiltDeg.toInt()}° · STAND IT UPRIGHT",
                    alignment = Alignment.BottomCenter,
                    color = EchoColors.Warning,
                )

                state.stats.upsideDown -> HudLabel(
                    text = "UPSIDE DOWN · TOP EDGE SHOULD POINT UP",
                    alignment = Alignment.BottomCenter,
                    color = EchoColors.Warning,
                )
            }
        }
    }
}

private fun DrawScope.drawRadar(
    state: SonarUiState,
    sweepAngle: Float,
    paint: Paint,
    density: Float,
) {
    val maxRange = state.config.maxRangeM.coerceAtLeast(1f)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = min(size.width, size.height) * 0.5f - 20.dp.toPx()
    if (radius <= 8f) return
    val center = Offset(cx, cy)
    val snapshot = state.map
    val dash = PathEffect.dashPathEffect(floatArrayOf(5f * density, 5f * density))

    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF072A1B), Color(0xFF020805)),
            center = center,
            radius = radius * 1.6f,
        ),
    )
    drawCircle(EchoColors.PhosphorDeep, radius, center, style = Stroke(1.5.dp.toPx()))

    // Range rings with metre labels along the upper-right diagonal.
    val ringCount = floor(maxRange).toInt().coerceAtLeast(1)
    for (i in 1..ringCount) {
        val r = radius * i / maxRange
        drawCircle(
            color = EchoColors.GridLine.copy(alpha = 0.6f),
            radius = r,
            center = center,
            style = Stroke(1f, pathEffect = dash),
        )
        drawText(
            paint,
            "${i}m",
            cx + r * 0.7071f + 3f,
            cy - r * 0.7071f - 2f,
            EchoColors.TextSecondary.copy(alpha = 0.65f).toArgb(),
            9f * density,
        )
    }

    for (deg in 0 until 360 step 30) {
        val rad = Math.toRadians(deg.toDouble())
        drawLine(
            color = EchoColors.GridLine.copy(alpha = 0.32f),
            start = center,
            end = Offset(
                cx + (radius * cos(rad)).toFloat(),
                cy + (radius * sin(rad)).toFloat(),
            ),
            strokeWidth = 1f,
            pathEffect = dash,
        )
    }

    // Blind zone: closer than this the transmit burst still rings in the microphone.
    val blindRadius = radius * (state.config.minRangeM / maxRange)
    if (blindRadius > 1f) {
        drawCircle(
            color = EchoColors.PhosphorDeep.copy(alpha = 0.9f),
            radius = blindRadius,
            center = center,
            style = Stroke(1.dp.toPx(), pathEffect = dash),
        )
    }

    val sweepDeg = Math.toDegrees(sweepAngle.toDouble()).toFloat()
    for (i in 0 until 18) {
        val alpha = 0.11f * (1f - i / 18f)
        drawArc(
            color = EchoColors.Sweep.copy(alpha = alpha),
            startAngle = sweepDeg - i * 2.2f - 2.4f,
            sweepAngle = 2.8f,
            useCenter = true,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
        )
    }
    val tip = Offset(cx + radius * cos(sweepAngle), cy + radius * sin(sweepAngle))
    drawLine(EchoColors.PhosphorBright.copy(alpha = 0.8f), center, tip, 1.6.dp.toPx())
    drawCircle(EchoColors.PhosphorBright.copy(alpha = 0.35f), 7.dp.toPx(), tip)
    drawCircle(EchoColors.PhosphorBright, 2.4.dp.toPx(), tip)

    if (snapshot != null) {
        val angleStep = (2f * PI.toFloat()) / snapshot.angleBins
        for (a in 0 until snapshot.angleBins) {
            val angle = a * angleStep
            val ca = cos(angle)
            val sa = sin(angle)
            val base = a * snapshot.rangeBins
            for (b in 0 until snapshot.rangeBins) {
                val value = snapshot.cells[base + b]
                if (value < 0.12f) continue
                val range = snapshot.startRangeM + (b + 0.5f) * snapshot.binSizeM
                if (range > maxRange) continue
                val r = radius * (range / maxRange)
                val point = Offset(cx + r * ca, cy + r * sa)
                val brightness = (value * value).coerceIn(0f, 1f)
                val dot = (1.1f + 2.1f * value) * density
                drawCircle(EchoColors.Phosphor.copy(alpha = 0.28f * brightness), dot * 2f, point)
                drawCircle(EchoColors.Phosphor.copy(alpha = 0.95f * brightness), dot, point)
            }
        }

        drawContour(
            ranges = snapshot.smoothedStrongest(),
            angleCount = snapshot.angleBins,
            maxRange = maxRange,
            radius = radius,
            cx = cx,
            cy = cy,
            color = EchoColors.Sweep.copy(alpha = 0.9f),
            width = 1.8.dp.toPx(),
        )
        drawContour(
            ranges = snapshot.nearestRangeM,
            angleCount = snapshot.angleBins,
            maxRange = maxRange,
            radius = radius,
            cx = cx,
            cy = cy,
            color = EchoColors.PhosphorMid.copy(alpha = 0.45f),
            width = 1.dp.toPx(),
        )
    }

    // Centre marker: the phone itself.
    drawCircle(EchoColors.PhosphorBright, 2.2.dp.toPx(), center)
    drawLine(
        EchoColors.PhosphorBright.copy(alpha = 0.5f),
        Offset(cx - 5.dp.toPx(), cy),
        Offset(cx + 5.dp.toPx(), cy),
        1f,
    )
    drawLine(
        EchoColors.PhosphorBright.copy(alpha = 0.5f),
        Offset(cx, cy - 5.dp.toPx()),
        Offset(cx, cy + 5.dp.toPx()),
        1f,
    )

    if (state.prefs.showHardwareHints) {
        drawText(
            paint,
            "TOP EDGE · MIC",
            cx,
            cy - radius - 6.dp.toPx(),
            EchoColors.PhosphorDim.toArgb(),
            9f * density,
            centered = true,
        )
        drawText(
            paint,
            "BOTTOM EDGE · SPEAKER",
            cx,
            cy + radius + 14.dp.toPx(),
            EchoColors.PhosphorDim.toArgb(),
            9f * density,
            centered = true,
        )
    }
}

private fun DrawScope.drawContour(
    ranges: FloatArray,
    angleCount: Int,
    maxRange: Float,
    radius: Float,
    cx: Float,
    cy: Float,
    color: Color,
    width: Float,
) {
    val path = Path()
    var drawing = false
    for (a in 0 until angleCount) {
        val range = ranges[a]
        if (range.isNaN() || range <= 0f || range > maxRange) {
            drawing = false
            continue
        }
        val angle = a * (2f * PI.toFloat() / angleCount)
        val r = radius * (range / maxRange)
        val x = cx + r * cos(angle)
        val y = cy + r * sin(angle)
        if (!drawing) {
            path.moveTo(x, y)
            drawing = true
        } else {
            path.lineTo(x, y)
        }
    }
    drawPath(
        path = path,
        color = color.copy(alpha = color.alpha * 0.25f),
        style = Stroke(width = width * 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawText(
    paint: Paint,
    text: String,
    x: Float,
    y: Float,
    color: Int,
    textSizePx: Float,
    centered: Boolean = false,
) {
    paint.color = color
    paint.textSize = textSizePx
    paint.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}
