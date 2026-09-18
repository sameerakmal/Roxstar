package com.roxstar.voicedraft

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roxstar.voicedraft.ui.components.AudioWaveformVisualizer
import com.roxstar.voicedraft.ui.components.RoxStarCard
import com.roxstar.voicedraft.ui.components.RoxStarMicIcon
import com.roxstar.voicedraft.ui.components.RoxStarOutlineButton
import com.roxstar.voicedraft.ui.components.RoxStarPrimaryButton
import com.roxstar.voicedraft.ui.components.RoxStarTopBar
import com.roxstar.voicedraft.ui.components.VoiceEffectBadge
import com.roxstar.voicedraft.ui.theme.RoxStarAudioAccent
import com.roxstar.voicedraft.ui.theme.RoxStarAudioContainer
import com.roxstar.voicedraft.ui.theme.RoxStarBackground
import com.roxstar.voicedraft.ui.theme.RoxStarBorder
import com.roxstar.voicedraft.ui.theme.RoxStarError
import com.roxstar.voicedraft.ui.theme.RoxStarErrorContainer
import com.roxstar.voicedraft.ui.theme.RoxStarGradients
import com.roxstar.voicedraft.ui.theme.RoxStarPrimary
import com.roxstar.voicedraft.ui.theme.RoxStarPrimaryContainer
import com.roxstar.voicedraft.ui.theme.RoxStarPrimaryPressed
import com.roxstar.voicedraft.ui.theme.RoxStarSuccess
import com.roxstar.voicedraft.ui.theme.RoxStarSuccessContainer
import com.roxstar.voicedraft.ui.theme.RoxStarTextPrimary
import com.roxstar.voicedraft.ui.theme.RoxStarTextSecondary
import java.util.Locale

private const val RECORD_AUDIO_PERMISSION = Manifest.permission.RECORD_AUDIO

@Composable
fun RecordingScreen(
    viewModel: RecordingViewModel = viewModel(),
    onNavigateToDrafts: () -> Unit = {},
    onNavigateToRoom: () -> Unit = {},
) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RoxStarBackground),
    ) {
        RoxStarTopBar(
            title = "Audio Studio",
            subtitle = "Native Oboe 48kHz PCM Pipeline",
            actions = {
                TextButton(onClick = onNavigateToDrafts) {
                    Text(
                        text = "My Drafts ›",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarPrimary,
                    )
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Effect Preset Selector
            EffectSelectorConsole(
                selected = uiState.selectedEffect,
                enabled = uiState.phase != RecordingPhase.RECORDING && uiState.phase != RecordingPhase.SAVING,
                onSelect = viewModel::selectEffect,
            )

            // Main Recording Studio Console Stage
            StudioConsoleStage(
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
                onCancelTap = viewModel::cancelRecording,
                onOpenSettings = {
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", activity.packageName, null))
                    )
                },
                onNavigateToDrafts = onNavigateToDrafts,
                onNavigateToRoom = onNavigateToRoom,
            )
        }
    }
}

@Composable
private fun EffectSelectorConsole(
    selected: Effect,
    enabled: Boolean,
    onSelect: (Effect) -> Unit,
) {
    RoxStarCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎛️", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Voice Effect Preset",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarTextPrimary,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = RoxStarAudioContainer,
                ) {
                    Text(
                        text = "DSP Pipeline",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarAudioAccent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EffectChipItem(
                    label = "None",
                    sub = "Dry",
                    isSelected = selected == Effect.NONE,
                    enabled = enabled,
                    onClick = { onSelect(Effect.NONE) },
                    modifier = Modifier.weight(1f),
                )
                EffectChipItem(
                    label = "Echo",
                    sub = "Delay",
                    isSelected = selected == Effect.ECHO,
                    enabled = enabled,
                    onClick = { onSelect(Effect.ECHO) },
                    modifier = Modifier.weight(1f),
                )
                EffectChipItem(
                    label = "Reverb",
                    sub = "Space",
                    isSelected = selected == Effect.REVERB,
                    enabled = enabled,
                    onClick = { onSelect(Effect.REVERB) },
                    modifier = Modifier.weight(1f),
                )
                EffectChipItem(
                    label = "Pitch",
                    sub = "+4 st",
                    isSelected = selected == Effect.PITCH_SHIFT,
                    enabled = enabled,
                    onClick = { onSelect(Effect.PITCH_SHIFT) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EffectChipItem(
    label: String,
    sub: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (isSelected) RoxStarAudioAccent else RoxStarBackground,
        label = "effect_bg",
    )
    val textColor = if (isSelected) Color.White else RoxStarTextPrimary
    val subColor = if (isSelected) Color.White.copy(alpha = 0.8f) else RoxStarTextSecondary

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = if (isSelected) null else CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(listOf(RoxStarBorder, RoxStarBorder))
        ),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = subColor,
            )
        }
    }
}

@Composable
private fun StudioConsoleStage(
    uiState: RecordingUiState,
    onRecordTap: () -> Unit,
    onStopTap: () -> Unit,
    onCancelTap: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToDrafts: () -> Unit,
    onNavigateToRoom: () -> Unit,
) {
    val isRecording = uiState.phase == RecordingPhase.RECORDING
    val isSaving = uiState.phase == RecordingPhase.SAVING

    RoxStarCard(elevation = 4.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Phase indicator badge
            PhaseBadge(phase = uiState.phase)

            // Large elapsed time counter
            Text(
                text = formatDuration(uiState.elapsedSeconds),
                style = MaterialTheme.typography.displayLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (isRecording) RoxStarPrimary else RoxStarTextPrimary,
            )

            // Animated Waveform Visualizer
            AudioWaveformVisualizer(
                isRecording = isRecording,
                peakLevel = uiState.peakLevel,
                barCount = 22,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                barColor = RoxStarAudioAccent,
            )

            // Primary Record / Stop Button with Pulse Rings
            StudioRecordButton(
                phase = uiState.phase,
                enabled = uiState.permission != PermissionState.PERMANENTLY_DENIED,
                onClick = if (isRecording) onStopTap else onRecordTap,
            )

            // Cancel action while recording
            if (isRecording) {
                TextButton(
                    onClick = onCancelTap,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        text = "Cancel & Discard",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = RoxStarError,
                    )
                }
            } else {
                Spacer(Modifier.height(16.dp))
            }

            // Status message / Feedback card
            StatusFeedbackCard(
                uiState = uiState,
                onOpenSettings = onOpenSettings,
                onNavigateToDrafts = onNavigateToDrafts,
                onNavigateToRoom = onNavigateToRoom,
            )
        }
    }
}

@Composable
private fun StudioRecordButton(
    phase: RecordingPhase,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRecording = phase == RecordingPhase.RECORDING
    val isSaving = phase == RecordingPhase.SAVING

    val transition = rememberInfiniteTransition(label = "pulse_ring")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(110.dp),
    ) {
        // Outer pulsing wave when recording
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(RoxStarPrimary.copy(alpha = pulseAlpha)),
            )
        }

        // Central Action Button
        Surface(
            onClick = onClick,
            enabled = enabled && !isSaving,
            shape = CircleShape,
            color = if (isRecording) RoxStarPrimaryPressed else RoxStarPrimary,
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(80.dp)
                .semantics {
                    contentDescription = if (isRecording) "Stop Recording" else "Start Recording"
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isRecording) {
                            Brush.linearGradient(listOf(RoxStarPrimaryPressed, Color(0xFFB91C1C)))
                        } else {
                            RoxStarGradients.Primary
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isSaving -> CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(32.dp),
                    )
                    isRecording -> Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.size(24.dp),
                    ) {}
                    else -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        RoxStarMicIcon(
                            color = Color.White,
                            accentColor = Color(0xFFFFD1E3),
                            showSoundWaves = true,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseBadge(phase: RecordingPhase) {
    val (label, bg, fg) = when (phase) {
        RecordingPhase.IDLE -> Triple("READY TO RECORD", RoxStarPrimaryContainer, RoxStarPrimary)
        RecordingPhase.RECORDING -> Triple("RECORDING LIVE", RoxStarErrorContainer, RoxStarError)
        RecordingPhase.SAVING -> Triple("PROCESSING & SAVING...", RoxStarAudioContainer, RoxStarAudioAccent)
        RecordingPhase.SAVED -> Triple("RECORDING SAVED", RoxStarSuccessContainer, Color(0xFF065F46))
        RecordingPhase.CANCELLING -> Triple("CANCELLING...", Color(0xFFF3F4F6), RoxStarTextSecondary)
        RecordingPhase.ERROR -> Triple("ERROR", RoxStarErrorContainer, RoxStarError)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StatusFeedbackCard(
    uiState: RecordingUiState,
    onOpenSettings: () -> Unit,
    onNavigateToDrafts: () -> Unit,
    onNavigateToRoom: () -> Unit,
) {
    when {
        uiState.permission == PermissionState.PERMANENTLY_DENIED -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = RoxStarErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Microphone Access Required",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarError,
                    )
                    Text(
                        text = "Audio permission was permanently denied. Please grant permission in Android Settings to enable recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7F1D1D),
                    )
                    RoxStarOutlineButton(
                        text = "Open App Settings",
                        onClick = onOpenSettings,
                        color = RoxStarError,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        uiState.phase == RecordingPhase.SAVED -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = RoxStarSuccessContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("✅", fontSize = 18.sp)
                        Text(
                            text = "Draft Saved Successfully!",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF065F46),
                        )
                    }
                    Text(
                        text = "Saved as: ${uiState.savedFileName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF065F46),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RoxStarPrimaryButton(
                            text = "View in Drafts",
                            onClick = onNavigateToDrafts,
                            modifier = Modifier.weight(1f),
                        )
                        RoxStarOutlineButton(
                            text = "Share to Room",
                            onClick = onNavigateToRoom,
                            color = RoxStarAudioAccent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        uiState.phase == RecordingPhase.ERROR -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = RoxStarErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Recording Error: ${uiState.errorMessage ?: "Unknown error"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RoxStarError,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        else -> {
            Text(
                text = "Tap the microphone button to start recording. Tap again to finalize and save.",
                style = MaterialTheme.typography.bodySmall,
                color = RoxStarTextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun formatDuration(totalSeconds: Int): String =
    String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
