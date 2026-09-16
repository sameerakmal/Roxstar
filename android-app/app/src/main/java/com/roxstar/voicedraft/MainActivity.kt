package com.roxstar.voicedraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.roxstar.voicedraft.ui.theme.VoiceDraftTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceDraftTheme {
                RecordingScreen()
            }
        }
    }
}
