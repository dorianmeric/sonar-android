package com.echosonar

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echosonar.ui.SonarViewModel
import com.echosonar.ui.screens.SonarScreen
import com.echosonar.ui.theme.EchoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SonarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            // A scan is useless with the screen off, and the sweep is hand-driven.
            LaunchedEffect(state.isRunning) {
                if (state.isRunning) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            // Releasing the audio devices when the app leaves the foreground keeps the mic free for
            // other apps and avoids a phantom recording indicator.
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                viewModel.stopScan("Paused (app in background)")
            }

            // Automation/QA hook: `adb shell am start -n com.echosonar/.MainActivity --ez demo true`
            // opens the synthetic room so the map can be inspected without acoustics.
            LaunchedEffect(Unit) {
                if (intent?.getBooleanExtra("demo", false) == true) {
                    viewModel.startDemo()
                }
            }

            EchoTheme {
                SonarScreen(viewModel = viewModel, modifier = Modifier)
            }
        }
    }
}