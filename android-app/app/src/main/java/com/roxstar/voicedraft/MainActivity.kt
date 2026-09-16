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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val engine = AudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Startup cleanup (requirement 6): remove any WAV left behind by a
        // process death between open() and finalize() on a previous run.
        val draftsDir = draftsDir()
        lifecycleScope.launch(Dispatchers.IO) {
            RecordingCleanup.removeOrphaned(draftsDir)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AudioEngineScreen(engine, draftsDir)
                }
            }
        }
    }

    override fun onDestroy() {
        engine.close()
        engine.release()
        super.onDestroy()
    }

    private fun draftsDir(): File = File(filesDir, "drafts").apply { mkdirs() }
}

/**
 * Verification screen — not the final design. Phase 2 drives Open/Start/Stop/
 * Close and shows the stream configuration Oboe granted; Phase 3 adds Start
 * Recording/Stop Recording and shows the resulting WAV file.
 */
@Composable
fun AudioEngineScreen(engine: AudioEngine, draftsDir: File) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

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
    var recordingBusy by remember { mutableStateOf(false) }
    var lastRecordingPath by remember { mutableStateOf("") }

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

        Text("Recording", style = MaterialTheme.typography.titleMedium)
        Text("Recording state: ${config.recordingState}")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    recordingBusy = true
                    val path = File(draftsDir, "${UUID.randomUUID()}.wav").absolutePath
                    coroutineScope.launch {
                        // open()/start()/file-create can all block briefly — off the main thread.
                        val status = withContext(Dispatchers.IO) { engine.startRecording(path) }
                        lastAction = "startRecording -> $status"
                        config = engine.config()
                        recordingBusy = false
                    }
                },
                enabled = hasPermission &&
                    !recordingBusy &&
                    config.recordingState == RecordingState.IDLE,
            ) { Text("Start Recording") }

            Button(
                onClick = {
                    recordingBusy = true
                    coroutineScope.launch {
                        // Blocking (drains + joins the writer thread) — off the main thread.
                        val status = withContext(Dispatchers.IO) { engine.stopRecording() }
                        lastAction = "stopRecording -> $status"
                        lastRecordingPath = engine.lastRecordingPath()
                        config = engine.config()
                        recordingBusy = false
                    }
                },
                enabled = !recordingBusy && config.recordingState == RecordingState.RECORDING,
            ) { Text(if (recordingBusy) "Stopping…" else "Stop Recording") }
        }

        val capturedFrames = config.recordingFramesCaptured
        val elapsedSeconds = if (config.sampleRate > 0) {
            capturedFrames.toFloat() / config.sampleRate
        } else {
            0f
        }
        ConfigRow("Frames captured", "$capturedFrames")
        ConfigRow("Elapsed", String.format(Locale.US, "%.2f s", elapsedSeconds))
        ConfigRow("Overrun frames", "${config.recordingOverrunFrames}")
        ConfigRow("Peak level (recording)", String.format(Locale.US, "%.4f", config.peakLevel))
        if (lastRecordingPath.isNotEmpty()) {
            Text("Last recording: $lastRecordingPath", style = MaterialTheme.typography.bodySmall)
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
