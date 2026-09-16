package com.roxstar.voicedraft

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val engine = AudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AudioEngineScreen(engine)
                }
            }
        }
    }

    override fun onDestroy() {
        engine.close()
        engine.release()
        super.onDestroy()
    }
}

/**
 * Phase 2 verification screen — not the final design. It exists only to drive
 * Open -> Start -> Stop -> Close and show the configuration Oboe actually granted.
 */
@Composable
fun AudioEngineScreen(engine: AudioEngine) {
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    var config by remember { mutableStateOf(engine.config()) }
    var lastAction by remember { mutableStateOf("—") }

    // Constant for the process lifetime — hoisted so the 200 ms poll below does
    // not re-enter JNI for them on every recomposition.
    val banner = remember { "Oboe ${engine.oboeVersion} · ${NativeAudioBridge.nativeHello()}" }

    LaunchedEffect(Unit) {
        while (true) {
            config = engine.config()
            delay(200)
        }
    }

    val state = config.state

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ROXSTAR Voice Draft", style = MaterialTheme.typography.titleLarge)
        Text(banner)

        if (!hasPermission) {
            Text("Microphone permission is required to open an input stream.")
            Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Grant microphone permission")
            }
        }

        Text("State: $state", style = MaterialTheme.typography.titleMedium)
        Text("Last action: $lastAction")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { lastAction = "open -> ${engine.open()}" },
                enabled = hasPermission && state in OPENABLE,
            ) { Text("Open") }

            Button(
                onClick = { lastAction = "start -> ${engine.start()}" },
                enabled = state in STARTABLE,
            ) { Text("Start") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { lastAction = "stop -> ${engine.stop()}" },
                enabled = state == EngineState.STARTED,
            ) { Text("Stop") }

            Button(
                onClick = { lastAction = "close -> ${engine.close()}" },
                enabled = state in CLOSEABLE,
            ) { Text("Close") }
        }

        Text("Actual stream configuration", style = MaterialTheme.typography.titleMedium)
        ConfigRow("Sample rate", "${config.sampleRate} Hz")
        ConfigRow("Channels", "${config.channelCount}")
        ConfigRow("Format", config.format)
        ConfigRow("Sharing mode", config.sharingMode)
        ConfigRow("Performance mode", config.performanceMode)
        ConfigRow("Input preset", config.inputPreset)
        ConfigRow("Audio API", config.audioApi)
        ConfigRow("Frames per burst", "${config.framesPerBurst}")
        ConfigRow("Buffer size", "${config.bufferSizeInFrames} / ${config.bufferCapacityInFrames}")
        ConfigRow("Device id", "${config.deviceId}")
        ConfigRow("Frames read", "${config.framesRead}")
        ConfigRow("Peak level", String.format(Locale.US, "%.4f", config.peakLevel))
        ConfigRow("Last Oboe result", config.lastResult)
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private val OPENABLE = setOf(
    EngineState.UNINITIALIZED,
    EngineState.CLOSED,
    EngineState.DISCONNECTED,
)
private val STARTABLE = setOf(EngineState.OPEN, EngineState.STOPPED)
private val CLOSEABLE = setOf(
    EngineState.OPEN,
    EngineState.STARTED,
    EngineState.STOPPED,
    EngineState.DISCONNECTED,
)
