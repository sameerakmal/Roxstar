package com.roxstar.voicedraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.roxstar.voicedraft.ui.theme.VoiceDraftTheme

/**
 * Single-activity host.
 *
 * Navigation is a simple enum-based screen switch — no NavGraph required
 * for two screens. If more screens are added, migrate to NavHost.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceDraftTheme {
                var currentScreen by remember { mutableStateOf(Screen.RECORDING) }

                when (currentScreen) {
                    Screen.RECORDING -> RecordingScreen(
                        onNavigateToDrafts = { currentScreen = Screen.DRAFTS },
                    )
                    Screen.DRAFTS -> DraftListScreen(
                        onNavigateBack = { currentScreen = Screen.RECORDING },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the shared native engine when the process is being torn down.
        AudioEngineHolder.release()
    }
}

private enum class Screen { RECORDING, DRAFTS }
