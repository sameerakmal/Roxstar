package com.roxstar.voicedraft.room

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roxstar.voicedraft.Draft
import com.roxstar.voicedraft.network.ParticipantDto
import com.roxstar.voicedraft.network.SharedDraftDto
import com.roxstar.voicedraft.spin.SpinUiState
import com.roxstar.voicedraft.spin.SpinView
import com.roxstar.voicedraft.ui.components.ConnectionBadge
import com.roxstar.voicedraft.ui.components.ParticipantRoleBadge
import com.roxstar.voicedraft.ui.components.RoxStarCard
import com.roxstar.voicedraft.ui.components.RoxStarOutlineButton
import com.roxstar.voicedraft.ui.components.RoxStarPrimaryButton
import com.roxstar.voicedraft.ui.components.RoxStarTopBar
import com.roxstar.voicedraft.ui.components.UserAvatar
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
import com.roxstar.voicedraft.ui.theme.RoxStarSuccess
import com.roxstar.voicedraft.ui.theme.RoxStarTextPrimary
import com.roxstar.voicedraft.ui.theme.RoxStarTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RoomScreen(
    viewModel: RoomViewModel = viewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val spinUiState by viewModel.spinViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        val err = uiState.errorMessage
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearErrorMessage()
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    LaunchedEffect(spinUiState.errorMessage) {
        val err = spinUiState.errorMessage
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            viewModel.spinViewModel.clearErrorMessage()
        }
    }

    LaunchedEffect(spinUiState.statusMessage) {
        val msg = spinUiState.statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.spinViewModel.clearStatusMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RoxStarBackground),
    ) {
        RoxStarTopBar(
            title = if (uiState.isInRoom) "Room: ${uiState.currentRoomId?.take(8)}..." else "Voice Rooms",
            subtitle = if (uiState.isInRoom) "${uiState.participants.size} participants online" else "Host or Join Collaborative Rooms",
            onBack = onNavigateBack,
            actions = {
                if (uiState.isInRoom) {
                    TextButton(
                        onClick = { viewModel.leaveRoom() },
                        enabled = !uiState.isJoiningOrLeaving,
                    ) {
                        Text(
                            text = "Leave",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = RoxStarError,
                        )
                    }
                }
            },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (!uiState.isInRoom) {
                RoomLobby(
                    isBusy = uiState.isJoiningOrLeaving,
                    onJoin = { roomId, userName -> viewModel.joinExistingRoom(roomId, userName) },
                    onCreate = { userName -> viewModel.createAndJoinRoom(userName) },
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                ActiveRoomView(
                    uiState = uiState,
                    spinUiState = spinUiState,
                    viewModel = viewModel,
                    onStartSpin = { viewModel.startSpin() },
                    onRefresh = { viewModel.refreshRoomState() },
                    onRetryConnection = { viewModel.retryConnection() },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (uiState.isJoiningOrLeaving) {
                Surface(
                    color = Color.Black.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RoxStarPrimary)
                    }
                }
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
private fun RoomLobby(
    isBusy: Boolean,
    onJoin: (roomId: String, userName: String) -> Unit,
    onCreate: (userName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val recentRooms by RoomSession.recentRooms.collectAsState()
    var userNameInput by remember { mutableStateOf(RoomSession.savedUserName.value) }
    var roomIdInput by remember { mutableStateOf("") }

    RoxStarCard(
        elevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = RoxStarPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("👥", fontSize = 22.sp)
                    }
                }
                Column {
                    Text(
                        text = "Voice Room Lobby",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarTextPrimary,
                    )
                    Text(
                        text = "Real-time presence and draft sharing",
                        style = MaterialTheme.typography.bodySmall,
                        color = RoxStarTextSecondary,
                    )
                }
            }

            // Step 1: Username
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Your Display Name",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarTextPrimary,
                )
                OutlinedTextField(
                    value = userNameInput,
                    onValueChange = {
                        userNameInput = it
                        RoomSession.saveUserName(it)
                    },
                    placeholder = { Text("e.g. Maya, Alex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Primary CTA: Create Room
            RoxStarPrimaryButton(
                text = "Create New Room",
                onClick = { onCreate(userNameInput) },
                enabled = !isBusy && userNameInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                icon = { Text("✨", fontSize = 16.sp) },
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = RoxStarBorder)
                Text(
                    text = "  OR JOIN WITH CODE  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = RoxStarTextSecondary,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = RoxStarBorder)
            }

            // Room ID Input with Paste
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Room ID",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarTextPrimary,
                    )
                    TextButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text?.trim().orEmpty()
                            if (clipText.isNotBlank()) {
                                roomIdInput = clipText
                                Toast.makeText(context, "Pasted Room ID", Toast.LENGTH_SHORT).show()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("📋 Paste", style = MaterialTheme.typography.labelSmall, color = RoxStarPrimary)
                    }
                }

                OutlinedTextField(
                    value = roomIdInput,
                    onValueChange = { roomIdInput = it.trim() },
                    placeholder = { Text("Enter 24-character Room ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Recent Rooms Chips
                if (recentRooms.isNotEmpty()) {
                    Text(
                        text = "Recent Rooms:",
                        style = MaterialTheme.typography.labelSmall,
                        color = RoxStarTextSecondary,
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(recentRooms) { recentId ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = RoxStarBackground,
                                border = CardDefaults.outlinedCardBorder().copy(
                                    brush = Brush.linearGradient(listOf(RoxStarBorder, RoxStarBorder))
                                ),
                                modifier = Modifier.clickable { roomIdInput = recentId },
                            ) {
                                Text(
                                    text = recentId.take(8) + "...",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    color = RoxStarTextPrimary,
                                )
                            }
                        }
                    }
                }
            }

            RoxStarOutlineButton(
                text = "Join Existing Room",
                onClick = { onJoin(roomIdInput, userNameInput) },
                enabled = !isBusy && roomIdInput.isNotBlank() && userNameInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ActiveRoomView(
    uiState: RoomUiState,
    spinUiState: SpinUiState,
    viewModel: RoomViewModel,
    onStartSpin: () -> Unit,
    onRefresh: () -> Unit,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOwner = uiState.roomInfo?.ownerUserId == uiState.currentUserId
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showShareDraftDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Sticky Room Header Card
        item {
            RoxStarCard(elevation = 3.dp) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ConnectionBadge(
                            status = uiState.connectionStatus,
                            onRetry = onRetryConnection,
                        )

                        ParticipantRoleBadge(isOwner = isOwner)
                    }

                    // Room Code full-width display
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = RoxStarBackground,
                        border = BorderStroke(1.dp, RoxStarBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = "ROOM CODE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = RoxStarTextSecondary,
                                letterSpacing = 1.sp,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = uiState.currentRoomId ?: "Unknown",
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = RoxStarTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Action buttons row: Equal width and height
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RoxStarOutlineButton(
                            text = "Copy Code",
                            onClick = {
                                val id = uiState.currentRoomId ?: return@RoxStarOutlineButton
                                clipboardManager.setText(AnnotatedString(id))
                                Toast.makeText(context, "Room code copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            height = 40.dp,
                        )

                        RoxStarOutlineButton(
                            text = "Share Room",
                            onClick = {
                                val id = uiState.currentRoomId ?: return@RoxStarOutlineButton
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Join my RoxStar Voice Room!\nRoom Code: $id")
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Room Code"))
                            },
                            color = RoxStarAudioAccent,
                            modifier = Modifier.weight(1f),
                            height = 40.dp,
                        )
                    }
                }
            }
        }

        // 2. Participants Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Participants (${uiState.participants.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarTextPrimary,
                )
                TextButton(onClick = onRefresh) {
                    Text(
                        text = "Sync Now",
                        style = MaterialTheme.typography.titleSmall,
                        color = RoxStarPrimary,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            if (uiState.participants.isEmpty()) {
                Text(
                    text = "No participants connected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RoxStarTextSecondary,
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(uiState.participants, key = { it.userId }) { participant ->
                        ParticipantCard(
                            participant = participant,
                            isSelf = participant.userId == uiState.currentUserId,
                            isOwner = participant.userId == uiState.roomInfo?.ownerUserId,
                        )
                    }
                }
            }
        }

        // 3. Circular Spin Wheel Arena
        item {
            SpinView(
                uiState = spinUiState,
                isRoomOwner = isOwner,
                currentUserId = uiState.currentUserId,
                participantCount = uiState.participants.size,
                roomParticipants = uiState.participants,
                onStartSpin = onStartSpin,
            )
        }

        // 4. Real-time Activity Timeline Section
        item {
            Text(
                text = "Room Activity Timeline",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = RoxStarTextPrimary,
            )
        }

        if (uiState.activityEvents.isEmpty()) {
            item {
                RoxStarCard {
                    Text(
                        text = "Real-time room events (joins, departures, shared drafts, spins) will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RoxStarTextSecondary,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        } else {
            items(uiState.activityEvents.take(8), key = { it.id }) { event ->
                ActivityTimelineItem(event = event)
            }
        }

        // 5. Shared Voice Drafts Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Shared Drafts (${uiState.sharedDrafts.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarTextPrimary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                RoxStarPrimaryButton(
                    text = "+ Share Draft",
                    onClick = { showShareDraftDialog = true },
                    isAudioTheme = true,
                    height = 36.dp,
                )
            }
        }

        if (uiState.sharedDrafts.isEmpty()) {
            item {
                RoxStarCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("🎵", fontSize = 28.sp)
                        Text(
                            text = "No drafts shared in this room yet",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = RoxStarTextPrimary,
                        )
                        Text(
                            text = "Record a voice draft and share it with room members in real-time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = RoxStarTextSecondary,
                        )
                        RoxStarOutlineButton(
                            text = "+ Add from My Drafts",
                            onClick = { showShareDraftDialog = true },
                        )
                    }
                }
            }
        } else {
            items(uiState.sharedDrafts, key = { it.draftId }) { draft ->
                SharedDraftCard(draft = draft)
            }
        }
    }

    if (showShareDraftDialog) {
        SelectDraftToShareDialog(
            viewModel = viewModel,
            onDismiss = { showShareDraftDialog = false },
        )
    }
}

@Composable
private fun ParticipantCard(
    participant: ParticipantDto,
    isSelf: Boolean,
    isOwner: Boolean,
) {
    val isOnline = participant.connectionState == "CONNECTED"

    RoxStarCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            UserAvatar(
                name = participant.displayName,
                size = 32.dp,
                fontSize = 13,
                isOnline = isOnline,
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = participant.displayName + if (isSelf) " (You)" else "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Medium,
                        color = RoxStarTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isOwner) {
                        Spacer(Modifier.width(4.dp))
                        Text("👑", fontSize = 11.sp)
                    }
                }
                Text(
                    text = if (isOnline) "Connected" else "Disconnected",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = if (isOnline) RoxStarSuccess else RoxStarTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ActivityTimelineItem(event: RoomActivityEvent) {
    RoxStarCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = event.icon, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = event.text,
                style = MaterialTheme.typography.bodySmall,
                color = RoxStarTextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTimeAgo(event.timestampMs),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = RoxStarTextSecondary,
            )
        }
    }
}

@Composable
private fun SharedDraftCard(draft: SharedDraftDto) {
    RoxStarCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = RoxStarAudioContainer,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("♪", fontSize = 18.sp, color = RoxStarAudioAccent)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = draft.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${draft.durationMs / 1000}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = RoxStarTextSecondary,
                    )
                    if (draft.effect != "NONE") {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = RoxStarAudioContainer,
                        ) {
                            Text(
                                text = draft.effect,
                                style = MaterialTheme.typography.labelSmall,
                                color = RoxStarAudioAccent,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = "• by ${draft.sharedByUserId.take(6)}...",
                        style = MaterialTheme.typography.labelSmall,
                        color = RoxStarTextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectDraftToShareDialog(
    viewModel: RoomViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var isSharing by remember { mutableStateOf(false) }
    var sharingDraftId by remember { mutableStateOf<String?>(null) }
    val drafts = remember { viewModel.loadLocalDrafts(context) }

    AlertDialog(
        onDismissRequest = { if (!isSharing) onDismiss() },
        shape = RoundedCornerShape(18.dp),
        containerColor = RoxStarCardSurface,
        title = {
            Text(
                text = "Share from My Drafts",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = RoxStarTextPrimary,
            )
        },
        text = {
            if (drafts.isEmpty()) {
                Text(
                    text = "No local voice drafts found. Record a draft in 'Studio' first!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RoxStarTextSecondary,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp),
                ) {
                    items(drafts, key = { it.id }) { draft ->
                        val isThisSharing = isSharing && sharingDraftId == draft.id
                        RoxStarCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = draft.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = RoxStarTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${draft.durationMs / 1000}s • ${draft.effect}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = RoxStarTextSecondary,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                RoxStarPrimaryButton(
                                    text = if (isThisSharing) "Sharing..." else "Share",
                                    onClick = {
                                        isSharing = true
                                        sharingDraftId = draft.id
                                        viewModel.shareLocalDraft(draft) { success, msg ->
                                            isSharing = false
                                            sharingDraftId = null
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            if (success) onDismiss()
                                        }
                                    },
                                    enabled = !isSharing,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSharing) {
                Text("Cancel", color = RoxStarTextSecondary)
            }
        },
    )
}

private fun formatTimeAgo(epochMs: Long): String {
    val diff = (System.currentTimeMillis() - epochMs) / 1000L
    return when {
        diff < 5 -> "just now"
        diff < 60 -> "${diff}s ago"
        diff < 3600 -> "${diff / 60}m ago"
        else -> "${diff / 3600}h ago"
    }
}
