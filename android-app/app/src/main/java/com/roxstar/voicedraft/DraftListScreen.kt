package com.roxstar.voicedraft

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/** Entry point for the Draft list screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftListScreen(
    viewModel: DraftViewModel = viewModel(),
    onNavigateBack: () -> Unit = {},
    onNavigateToRoom: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val playbackUiState by viewModel.playbackUiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var draftToShare by remember { mutableStateOf<Draft?>(null) }
    var roomIdInput by remember { mutableStateOf("") }
    var userIdInput by remember { mutableStateOf("") }

    // Reload whenever the screen becomes visible (handles new recordings added by RecordingScreen).
    LaunchedEffect(Unit) { viewModel.loadDrafts() }

    // Show feedback messages when share attempts complete.
    LaunchedEffect(uiState.shareMessage) {
        val msg = uiState.shareMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearShareMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Drafts", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text(
                            text = "‹",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onNavigateToRoom) {
                        Text("Rooms ›", color = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                uiState.drafts.isEmpty() -> {
                    EmptyDraftList(modifier = Modifier.align(Alignment.Center))
                }

                else -> {
                    DraftList(
                        drafts = uiState.drafts,
                        playingDraftId = uiState.playingDraftId,
                        sharingDraftId = uiState.sharingDraftId,
                        playback = playbackUiState,
                        onPlay = { viewModel.play(it) },
                        onStop = { viewModel.stop() },
                        onDelete = { viewModel.deleteDraft(it.id) },
                        onShare = { draftToShare = it },
                    )
                }
            }

            // Share Draft dialog
            draftToShare?.let { draft ->
                AlertDialog(
                    onDismissRequest = { draftToShare = null },
                    title = { Text("Share Draft with Room") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Share \"${draft.name}\" to a backend room via REST API.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedTextField(
                                value = roomIdInput,
                                onValueChange = { roomIdInput = it },
                                label = { Text("Room ID (24-hex ObjectId)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = userIdInput,
                                onValueChange = { userIdInput = it },
                                label = { Text("User ID (24-hex ObjectId)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.shareDraft(draft, roomIdInput, userIdInput)
                                draftToShare = null
                            },
                            enabled = roomIdInput.isNotBlank() && userIdInput.isNotBlank(),
                        ) {
                            Text("Share")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { draftToShare = null }) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyDraftList(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "♪",
                    fontSize = 32.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No drafts yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Record something and it will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun DraftList(
    drafts: List<DraftWithStatus>,
    playingDraftId: String?,
    sharingDraftId: String?,
    playback: PlaybackUiState,
    onPlay: (Draft) -> Unit,
    onStop: () -> Unit,
    onDelete: (Draft) -> Unit,
    onShare: (Draft) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        items(drafts, key = { it.draft.id }) { draftWithStatus ->
            DraftRow(
                draftWithStatus = draftWithStatus,
                isPlaying = draftWithStatus.draft.id == playingDraftId,
                isSharing = draftWithStatus.draft.id == sharingDraftId,
                playback = playback,
                onPlay = { onPlay(draftWithStatus.draft) },
                onStop = onStop,
                onDelete = { onDelete(draftWithStatus.draft) },
                onShare = { onShare(draftWithStatus.draft) },
            )
        }
    }
}

@Composable
private fun DraftRow(
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

    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (wavMissing) 0.55f else 1f),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPlaying) 4.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Play/Stop button — disabled if the WAV is missing.
            IconButton(
                onClick = if (isPlaying) onStop else onPlay,
                enabled = !wavMissing,
                modifier = Modifier.size(40.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = when {
                        wavMissing -> MaterialTheme.colorScheme.surfaceVariant
                        isPlaying -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isPlaying) "■" else "▶",
                            fontSize = if (isPlaying) 14.sp else 12.sp,
                            color = when {
                                wavMissing -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                isPlaying -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSecondaryContainer
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // Draft name + metadata
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = draft.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
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
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        Text(
                            text = formatDurationMs(
                                if (isPlaying) playback.positionMs else 0L,
                                draft.durationMs,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (draft.effect != Effect.NONE) {
                            EffectBadge(draft.effect)
                        }
                    }
                }
            }

            // Share button — disabled if WAV is missing or share in-flight
            IconButton(
                onClick = onShare,
                enabled = !wavMissing && !isSharing,
                modifier = Modifier.size(36.dp),
            ) {
                if (isSharing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = "↗",
                        fontSize = 18.sp,
                        color = if (wavMissing) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp),
            ) {
                Text(
                    text = "✕",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun EffectBadge(effect: Effect) {
    val label = when (effect) {
        Effect.ECHO -> "Echo"
        Effect.REVERB -> "Reverb"
        Effect.PITCH_SHIFT -> "Pitch"
        Effect.NONE -> return
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/** Formats `position/total` or just `total` as `m:ss / m:ss`. */
private fun formatDurationMs(positionMs: Long, totalMs: Long): String {
    fun Long.toMmSs(): String {
        val s = this / 1000L
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
    return if (positionMs > 0) "${positionMs.toMmSs()} / ${totalMs.toMmSs()}"
    else totalMs.toMmSs()
}

