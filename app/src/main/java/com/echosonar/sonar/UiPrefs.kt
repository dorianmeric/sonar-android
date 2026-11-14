package com.echosonar.sonar

enum class SweepMode { ROTATE, AUTO }

enum class MicMode { SYSTEM_TUNED, UNPROCESSED }

/** Rendering / behaviour preferences that do not affect the acoustic pipeline. */
data class UiPrefs(
    val sweepMode: SweepMode = SweepMode.ROTATE,
    val haptics: Boolean = false,
    val persistenceSec: Float = 6f,
    val onboardingSeen: Boolean = false,
    val micMode: MicMode = MicMode.SYSTEM_TUNED,
    val showHardwareHints: Boolean = true,
)