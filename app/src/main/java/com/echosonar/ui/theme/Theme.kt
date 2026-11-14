package com.echosonar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * CRT phosphor palette: near-black glass, green phosphor, amber for cautions, red for failures.
 */
object EchoColors {
    val Glass = Color(0xFF030A06)
    val Panel = Color(0xFF061710)
    val PanelHigh = Color(0xFF0A2418)
    val GridLine = Color(0xFF0E3B27)
    val Phosphor = Color(0xFF35FFA5)
    val PhosphorBright = Color(0xFFC4FFE2)
    val PhosphorMid = Color(0xFF16C077)
    val PhosphorDim = Color(0xFF0C6B45)
    val PhosphorDeep = Color(0xFF08331F)
    val TextPrimary = Color(0xFFA9FFD2)
    val TextSecondary = Color(0xFF5FBF95)
    val Amber = Color(0xFFFFC04D)
    val Warning = Color(0xFFFF8A4C)
    val Critical = Color(0xFFFF5C72)
    val Sweep = Color(0xFF7CFFC8)
}

private val scheme = darkColorScheme(
    primary = EchoColors.Phosphor,
    onPrimary = Color(0xFF00281A),
    primaryContainer = EchoColors.PhosphorDeep,
    onPrimaryContainer = EchoColors.PhosphorBright,
    secondary = EchoColors.PhosphorMid,
    onSecondary = Color(0xFF00281A),
    tertiary = EchoColors.Amber,
    background = EchoColors.Glass,
    onBackground = EchoColors.TextPrimary,
    surface = EchoColors.Panel,
    onSurface = EchoColors.TextPrimary,
    surfaceVariant = EchoColors.PanelHigh,
    onSurfaceVariant = EchoColors.TextSecondary,
    error = EchoColors.Critical,
    onError = Color(0xFF2A0008),
    outline = EchoColors.PhosphorDim,
    outlineVariant = EchoColors.PhosphorDeep,
)

private val typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        letterSpacing = 2.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 1.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.8.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 1.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun EchoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        content = content,
    )
}
