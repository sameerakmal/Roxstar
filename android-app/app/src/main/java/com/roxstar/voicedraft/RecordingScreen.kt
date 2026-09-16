package com.roxstar.voicedraft

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

private const val RECORD_AUDIO_PERMISSION = Manifest.permission.RECORD_AUDIO

@Composable
fun RecordingScreen(viewModel: RecordingViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }

    fun refreshPermission(granted: Boolean) {
        viewModel.onPermissionResult(
            granted = granted,
            canShowRationale = activity.shouldShowRequestPermissionRationale(RECORD_AUDIO_PERMISSION),
            hasAskedBefore = hasRequestedPermission,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshPermission(granted)
        if (granted) viewModel.startRecording()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> refreshPermission(
                    ContextCompat.checkSelfPermission(activity, RECORD_AUDIO_PERMISSION) ==
                        PackageManager.PERMISSION_GRANTED
                )
                Lifecycle.Event.ON_STOP -> viewModel.stopIfRecording()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    RecordingScreenContent(
        uiState = uiState,
        onRecordTap = {
            when (uiState.permission) {
                PermissionState.GRANTED -> viewModel.startRecording()
                PermissionState.PERMANENTLY_DENIED -> Unit
                else -> {
                    hasRequestedPermission = true
                    permissionLauncher.launch(RECORD_AUDIO_PERMISSION)
                }
            }
        },
        onStopTap = viewModel::stopRecording,
        onOpenSettings = {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", activity.packageName, null))
            )
        },
    )
}

@Composable
private fun RecordingScreenContent(
    uiState: RecordingUiState,
    onRecordTap: () -> Unit,
    onStopTap: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(modifier = Modifier.padding(top = 32.dp)) {
            Text("Voice Draft", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Record a short voice clip.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(statusLabel(uiState.phase), style = MaterialTheme.typography.titleMedium)
            Text(
                formatDuration(uiState.elapsedSeconds),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )

            LinearProgressIndicator(
                progress = {
                    if (uiState.phase == RecordingPhase.RECORDING) uiState.peakLevel.coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier.fillMaxWidth(0.6f).height(4.dp),
            )

            RecordButton(
                phase = uiState.phase,
                enabled = uiState.permission != PermissionState.PERMANENTLY_DENIED,
                onClick = if (uiState.phase == RecordingPhase.RECORDING) onStopTap else onRecordTap,
                modifier = Modifier.padding(top = 32.dp, bottom = 20.dp),
            )

            StatusMessage(uiState, onOpenSettings)
        }
    }
}

@Composable
private fun RecordButton(
    phase: RecordingPhase,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRecording = phase == RecordingPhase.RECORDING
    val isSaving = phase == RecordingPhase.SAVING
    val description = when {
        isRecording -> "Stop recording"
        isSaving -> "Saving recording"
        else -> "Start recording"
    }

    Surface(
        onClick = onClick,
        enabled = enabled && !isSaving,
        shape = CircleShape,
        color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = modifier.size(88.dp).semantics { contentDescription = description },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                isSaving -> CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp),
                )
                isRecording -> Surface(
                    color = MaterialTheme.colorScheme.onError,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.size(24.dp),
                ) {}
                else -> Surface(
                    color = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier.size(28.dp),
                ) {}
            }
        }
    }
}

@Composable
private fun StatusMessage(uiState: RecordingUiState, onOpenSettings: () -> Unit) {
    when {
        uiState.permission == PermissionState.PERMANENTLY_DENIED -> {
            Text(
                "Microphone access was turned off. Enable it in Settings to record.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenSettings) { Text("Open Settings") }
        }
        uiState.permission == PermissionState.DENIED && uiState.phase == RecordingPhase.IDLE -> {
            Text(
                "Microphone access is needed to record. Tap the button to try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        uiState.phase == RecordingPhase.SAVED -> {
            Text(
                "Saved as ${uiState.savedFileName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        uiState.phase == RecordingPhase.ERROR -> {
            Text(
                "Error: ${uiState.errorMessage}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun statusLabel(phase: RecordingPhase) = when (phase) {
    RecordingPhase.IDLE -> "Ready to record"
    RecordingPhase.RECORDING -> "Recording"
    RecordingPhase.SAVING -> "Saving…"
    RecordingPhase.SAVED -> "Saved"
    RecordingPhase.ERROR -> "Couldn't complete recording"
}

private fun formatDuration(totalSeconds: Int): String =
    String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
