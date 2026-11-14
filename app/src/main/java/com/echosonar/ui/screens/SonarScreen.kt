package com.echosonar.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echosonar.ui.SonarViewModel
import com.echosonar.ui.components.AdviceCard
import com.echosonar.ui.components.ConsoleLog
import com.echosonar.ui.components.ControlBar
import com.echosonar.ui.components.CrtOverlay
import com.echosonar.ui.components.PermissionCard
import com.echosonar.ui.components.PhosphorPanel
import com.echosonar.ui.components.ProfileStrip
import com.echosonar.ui.components.RadarView
import com.echosonar.ui.components.SensitivityBar
import com.echosonar.ui.components.SonarHeader
import com.echosonar.ui.components.StatusBanner
import com.echosonar.ui.components.TelemetryGrid
import com.echosonar.ui.theme.EchoColors

@Composable
fun SonarScreen(viewModel: SonarViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onPermissionGranted()
            viewModel.startScan()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    fun beginScan() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.onPermissionGranted()
            viewModel.startScan()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(EchoColors.Glass),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            SonarHeader(
                state = state,
                onSettings = { showSettings = true },
                onHelp = { showHelp = true },
            )
            Spacer(Modifier.height(6.dp))
            StatusBanner(state)
            Spacer(Modifier.height(6.dp))

            PhosphorPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.92f),
                contentPadding = 4.dp,
            ) {
                RadarView(state, Modifier.fillMaxSize())
            }

            Spacer(Modifier.height(6.dp))
            AdviceCard(state)
            Spacer(Modifier.height(6.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.0f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.permissionDenied) {
                    PermissionCard(onGrant = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) })
                }
                ProfileStrip(state)
                TelemetryGrid(state)
                ConsoleLog(state.log)
                Spacer(Modifier.height(2.dp))
            }

            Spacer(Modifier.height(4.dp))
            SensitivityBar(
                thresholdDb = state.config.thresholdDb,
                onChange = viewModel::setSensitivity,
            )
            ControlBar(
                state = state,
                onStartStop = {
                    if (state.isRunning) viewModel.stopScan() else beginScan()
                },
                onSweepMode = viewModel::setSweepMode,
                onClear = viewModel::clearMap,
            )
        }

        CrtOverlay(intensity = 0.75f)

        if (!state.prefs.onboardingSeen) {
            OnboardingOverlay(
                onDismiss = viewModel::markOnboardingSeen,
                onStart = {
                    viewModel.markOnboardingSeen()
                    beginScan()
                },
            )
        }
    }

    if (showSettings) {
        SettingsSheet(
            state = state,
            onDismiss = { showSettings = false },
            onApply = { config ->
                viewModel.applyConfig(config)
                showSettings = false
            },
            onPersistence = viewModel::setPersistence,
            onHaptics = viewModel::setHaptics,
            onHardwareHints = { enabled ->
                viewModel.setPrefs(state.prefs.copy(showHardwareHints = enabled))
            },
            onMicMode = viewModel::setMicMode,
            onProbe = viewModel::probeFrequencies,
            onUseFrequency = { frequency ->
                viewModel.applyCalibratedFrequency(frequency)
                showSettings = false
            },
            onResetDefaults = viewModel::resetPreferences,
        )
    }

    if (showHelp) {
        HelpSheet(
            state = state,
            onDismiss = { showHelp = false },
            onStartDemo = {
                showHelp = false
                viewModel.startDemo()
            },
        )
    }
}
