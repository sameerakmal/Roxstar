package com.roxstar.voicedraft

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roxstar.voicedraft.room.RoomSession
import com.roxstar.voicedraft.ui.components.RoxStarCard
import com.roxstar.voicedraft.ui.components.RoxStarOutlineButton
import com.roxstar.voicedraft.ui.components.RoxStarPrimaryButton
import com.roxstar.voicedraft.ui.components.RoxStarTopBar
import com.roxstar.voicedraft.ui.components.VoiceEffectBadge
import com.roxstar.voicedraft.ui.theme.RoxStarAudioAccent
import com.roxstar.voicedraft.ui.theme.RoxStarAudioContainer
import com.roxstar.voicedraft.ui.theme.RoxStarBackground
import com.roxstar.voicedraft.ui.theme.RoxStarBorder
import com.roxstar.voicedraft.ui.theme.RoxStarCardSurface
import com.roxstar.voicedraft.ui.theme.RoxStarError
import com.roxstar.voicedraft.ui.theme.RoxStarErrorContainer
import com.roxstar.voicedraft.ui.theme.RoxStarGradients
import com.roxstar.voicedraft.ui.theme.RoxStarPrimary
import com.roxstar.voicedraft.ui.theme.RoxStarPrimaryContainer
import com.roxstar.voicedraft.ui.theme.RoxStarPrimaryPressed
import com.roxstar.voicedraft.ui.theme.RoxStarTextPrimary
import com.roxstar.voicedraft.ui.theme.RoxStarTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DraftListScreen(
    viewModel: DraftViewModel = viewModel(),
    onNavigateBack: () -> Unit = {},
    onNavigateToRoom: () -> Unit = {},
    onNavigateToStudio: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val playbackUiState by viewModel.playbackUiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var draftToShare by remember { mutableStateOf<Draft?>(null) }
    var draftToDelete by remember { mutableStateOf<Draft?>(null) }
    var roomIdInput by remember { mutableStateOf("") }
    var userIdInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadDrafts() }

    LaunchedEffect(uiState.shareMessage) {
        val msg = uiState.shareMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearShareMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RoxStarBackground),
    ) {
        RoxStarTopBar(
            title = "My Voice Drafts",
            subtitle = "${uiState.drafts.size} saved recordings",
            onBack = onNavigateBack,
            actions = {
                TextButton(onClick = onNavigateToRoom) {
                    Text(
                        text = "Rooms ›",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarPrimary,
                    )
                }
            },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = RoxStarPrimary,
                    )
                }

                uiState.drafts.isEmpty() -> {
                    EmptyDraftList(
                        onRecordNow = onNavigateToStudio,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        items(uiState.drafts, key = { it.draft.id }) { draftWithStatus ->
                            DraftCardItem(
                                draftWithStatus = draftWithStatus,
                                isPlaying = draftWithStatus.draft.id == uiState.playingDraftId,
                                isSharing = draftWithStatus.draft.id == uiState.sharingDraftId,
                                playback = playbackUiState,
                                onPlay = { viewModel.play(draftWithStatus.draft) },
                                onStop = { viewModel.stop() },
                                onDelete = { draftToDelete = draftWithStatus.draft },
                                onShare = { draftToShare = draftWithStatus.draft },
                            )
                        }
                    }
                }
            }

            // Delete Confirmation Dialog
            draftToDelete?.let { draft ->
                AlertDialog(
                    onDismissRequest = { draftToDelete = null },
                    title = {
                        Text(
                            text = "Delete Draft?",
                            fontWeight = FontWeight.Bold,
                            color = RoxStarTextPrimary,
                        )
                    },
                    text = {
                        Text(
                            text = "Are you sure you want to delete \"${draft.name}\"? The local audio file will be permanently removed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = RoxStarTextSecondary,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.deleteDraft(draft.id)
                                draftToDelete = null
                            },
                        ) {
                            Text(
                                text = "Delete",
                                fontWeight = FontWeight.Bold,
                                color = RoxStarError,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { draftToDelete = null }) {
                            Text("Cancel", color = RoxStarTextSecondary)
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = RoxStarCardSurface,
                )
            }

            // Share Draft Dialog
            draftToShare?.let { draft ->
                val activeRoom = RoomSession.activeRoomId
                val activeUser = RoomSession.activeUserId
                val clipboard = LocalClipboardManager.current
                var showManualInputs by remember { mutableStateOf(activeRoom == null) }

                AlertDialog(
                    onDismissRequest = { draftToShare = null },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = RoxStarCardSurface,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("↗️", fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Share to Voice Room",
                                fontWeight = FontWeight.Bold,
                                color = RoxStarTextPrimary,
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Share \"${draft.name}\" (${draft.effect.name}) to room participants in real-time.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = RoxStarTextSecondary,
                            )

                            if (activeRoom != null && activeUser != null) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = RoxStarPrimaryContainer,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = "Active Room Detected",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = RoxStarPrimary,
                                        )
                                        Text(
                                            text = "Room ID: $activeRoom",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                RoxStarPrimaryButton(
                                    text = "Share to Active Room (1-Tap)",
                                    onClick = {
                                        viewModel.shareDraft(draft, activeRoom, activeUser)
                                        draftToShare = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                TextButton(
                                    onClick = { showManualInputs = !showManualInputs },
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                ) {
                                    Text(
                                        text = if (showManualInputs) "Hide custom room input" else "Or enter custom Room ID ›",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = RoxStarPrimary,
                                    )
                                }
                            }

                            if (showManualInputs) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Custom Room Target",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    TextButton(
                                        onClick = {
                                            clipboard.getText()?.text?.let { roomIdInput = it.trim() }
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text("Paste", style = MaterialTheme.typography.labelSmall, color = RoxStarPrimary)
                                    }
                                }

                                OutlinedTextField(
                                    value = roomIdInput,
                                    onValueChange = { roomIdInput = it },
                                    label = { Text("Room ID (24-hex ObjectId)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = userIdInput.ifBlank { RoomSession.activeUserId ?: "" },
                                    onValueChange = { userIdInput = it },
                                    label = { Text("User ID (24-hex ObjectId)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    },
                    confirmButton = {
                        if (showManualInputs) {
                            val resolvedUser = userIdInput.ifBlank { RoomSession.activeUserId ?: "" }
                            RoxStarPrimaryButton(
                                text = "Share to Custom Room",
                                onClick = {
                                    viewModel.shareDraft(draft, roomIdInput, resolvedUser)
                                    draftToShare = null
                                },
                                enabled = roomIdInput.isNotBlank() && resolvedUser.isNotBlank(),
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { draftToShare = null }) {
                            Text("Cancel", color = RoxStarTextSecondary)
                        }
                    },
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun EmptyDraftList(
    onRecordNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = RoxStarPrimaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = "🎙️", fontSize = 34.sp)
            }
        }
        Text(
            text = "No voice drafts yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = RoxStarTextPrimary,
        )
        Text(
            text = "Record your first voice draft with studio effects and manage it here.",
            style = MaterialTheme.typography.bodyMedium,
            color = RoxStarTextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        RoxStarPrimaryButton(
            text = "Record First Draft",
            onClick = onRecordNow,
        )
    }
}

@Composable
private fun DraftCardItem(
    draftWithStatus: DraftWithStatus,
    isPlaying: Boolean,
    isSharing: Boolean,
    playback: PlaybackUiState,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = draftWithStatus.draft
    val wavMissing = draftWithStatus.wavMissing

    val cardBg = if (isPlaying) RoxStarPrimaryContainer.copy(alpha = 0.5f) else RoxStarCardSurface

    RoxStarCard(
        modifier = modifier.alpha(if (wavMissing) 0.55f else 1f),
        elevation = if (isPlaying) 4.dp else 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Play / Stop Icon Button
                Surface(
                    shape = CircleShape,
                    color = when {
                        wavMissing -> Color(0xFFEEEEEE)
                        isPlaying -> RoxStarPrimary
                        else -> RoxStarAudioContainer
                    },
                    modifier = Modifier
                        .size(42.dp)
                        .clickable(enabled = !wavMissing, onClick = if (isPlaying) onStop else onPlay),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isPlaying) "■" else "▶",
                            fontSize = if (isPlaying) 15.sp else 13.sp,
                            color = when {
                                wavMissing -> RoxStarTextSecondary
                                isPlaying -> Color.White
                                else -> RoxStarAudioAccent
                            },
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                // Title & metadata
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = draft.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (wavMissing) {
                            Text(
                                text = "⚠ File missing",
                                style = MaterialTheme.typography.labelSmall,
                                color = RoxStarError,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Text(
                                text = formatDurationMs(
                                    if (isPlaying) playback.positionMs else 0L,
                                    draft.durationMs,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = RoxStarTextSecondary,
                            )
                            VoiceEffectBadge(draft.effect)
                            Text(
                                text = formatCreationTime(draft.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = RoxStarTextSecondary,
                            )
                        }
                    }
                }

                // Action Buttons: Share & Delete
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onShare,
                        enabled = !wavMissing && !isSharing,
                        modifier = Modifier.size(36.dp),
                    ) {
                        if (isSharing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = RoxStarPrimary,
                            )
                        } else {
                            Text(
                                text = "↗️",
                                fontSize = 16.sp,
                            )
                        }
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Text(
                            text = "🗑️",
                            fontSize = 16.sp,
                        )
                    }
                }
            }

            // Audio Progress Indicator bar when playing
            if (isPlaying && draft.durationMs > 0) {
                val progress = (playback.positionMs.toFloat() / draft.durationMs.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape),
                    color = RoxStarPrimary,
                    trackColor = RoxStarBorder,
                )
            }
        }
    }
}

private fun formatDurationMs(positionMs: Long, totalMs: Long): String {
    fun Long.toMmSs(): String {
        val s = this / 1000L
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
    return if (positionMs > 0) "${positionMs.toMmSs()} / ${totalMs.toMmSs()}"
    else totalMs.toMmSs()
}

private fun formatCreationTime(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return "• " + sdf.format(Date(epochMs))
}
