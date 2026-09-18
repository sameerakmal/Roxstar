package com.roxstar.voicedraft

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roxstar.voicedraft.spin.SpinGameStatus
import com.roxstar.voicedraft.ui.components.AudioWaveformVisualizer
import com.roxstar.voicedraft.ui.components.RoxStarCard
import com.roxstar.voicedraft.ui.components.RoxStarLogoBadge
import com.roxstar.voicedraft.ui.components.RoxStarMicIcon
import com.roxstar.voicedraft.ui.components.RoxStarOutlineButton
import com.roxstar.voicedraft.ui.components.RoxStarPrimaryButton
import com.roxstar.voicedraft.ui.components.RoxStarTopBar
import com.roxstar.voicedraft.ui.components.SpinBadge
import com.roxstar.voicedraft.ui.components.VoiceEffectBadge
import com.roxstar.voicedraft.ui.theme.RoxStarAudioAccent
import com.roxstar.voicedraft.ui.theme.RoxStarAudioContainer
import com.roxstar.voicedraft.ui.theme.RoxStarBackground
import com.roxstar.voicedraft.ui.theme.RoxStarBorder
import com.roxstar.voicedraft.ui.theme.RoxStarCardSurface
import com.roxstar.voicedraft.ui.theme.RoxStarGradients
import com.roxstar.voicedraft.ui.theme.RoxStarPrimary
import com.roxstar.voicedraft.ui.theme.RoxStarPrimaryContainer
import com.roxstar.voicedraft.ui.theme.RoxStarSuccess
import com.roxstar.voicedraft.ui.theme.RoxStarTextPrimary
import com.roxstar.voicedraft.ui.theme.RoxStarTextSecondary

@Composable
fun DashboardScreen(
    onNavigateToStudio: () -> Unit,
    onNavigateToDrafts: () -> Unit,
    onNavigateToRoom: () -> Unit,
    onNavigateToSpin: () -> Unit,
    activeRoomId: String? = null,
    activeUserName: String? = null,
    recentRooms: List<String> = emptyList(),
    recentDrafts: List<Draft> = emptyList(),
    playingDraftId: String? = null,
    onPlayDraft: (Draft) -> Unit = {},
    onStopDraft: () -> Unit = {},
    spinStatus: SpinGameStatus = SpinGameStatus.IDLE,
    participantCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RoxStarBackground),
    ) {
        RoxStarTopBar(
            title = "RoxStar Studio",
            subtitle = "Voice Drafts • Rooms • Spin Wheel",
            actions = {
                if (activeRoomId != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = RoxStarPrimaryContainer,
                        modifier = Modifier.clickable(onClick = onNavigateToRoom),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(RoxStarSuccess),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "In Room",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = RoxStarPrimary,
                            )
                        }
                    }
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Hero Banner
            item {
                HeroBanner(
                    activeRoomId = activeRoomId,
                    activeUserName = activeUserName,
                    participantCount = participantCount,
                    onNavigateToStudio = onNavigateToStudio,
                    onNavigateToRoom = onNavigateToRoom,
                )
            }

            // 2. Active Session Card (if user is in a room)
            if (activeRoomId != null) {
                item {
                    ActiveSessionCard(
                        roomId = activeRoomId,
                        userName = activeUserName,
                        participantCount = participantCount,
                        spinStatus = spinStatus,
                        onEnterRoom = onNavigateToRoom,
                        onOpenSpin = onNavigateToSpin,
                    )
                }
            }

            // 3. Three Core Capabilities Section
            item {
                Text(
                    text = "Core Capabilities",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarTextPrimary,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }

            item {
                PillarFeatureCard(
                    title = "Voice Draft Studio",
                    subtitle = "Oboe C++ Audio Engine • Echo, Reverb, Pitch",
                    description = "Capture high-fidelity microphone input, apply real-time DSP effects, and store local drafts with duration metadata.",
                    customIcon = {
                        RoxStarMicIcon(
                            color = RoxStarAudioAccent,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    badgeText = "NATIVE DSP",
                    badgeColor = RoxStarAudioAccent,
                    badgeBg = RoxStarAudioContainer,
                    actionText = "Open Audio Studio ›",
                    onClick = onNavigateToStudio,
                )
            }

            item {
                PillarFeatureCard(
                    title = "Real-Time Voice Rooms",
                    subtitle = "Socket.IO • Instant Presence • Draft Sharing",
                    description = "Create or join multiplayer audio rooms with synchronized presence, live event timelines, and 1-tap draft sharing.",
                    icon = "👥",
                    badgeText = "AUTHORITATIVE",
                    badgeColor = RoxStarPrimary,
                    badgeBg = RoxStarPrimaryContainer,
                    actionText = "Enter Voice Room ›",
                    onClick = onNavigateToRoom,
                )
            }

            item {
                PillarFeatureCard(
                    title = "Spin Elimination Arena",
                    subtitle = "5-Second Elimination • Dynamic Wheel • Single Winner",
                    description = "Server-authoritative battle royale! Minimum 3 players, one eliminated every 5 seconds until the champion remains.",
                    icon = "🎡",
                    badgeText = "GAME ARENA",
                    badgeColor = Color(0xFFD97706),
                    badgeBg = Color(0xFFFEF3C7),
                    actionText = "Launch Spin Arena ›",
                    onClick = onNavigateToSpin,
                )
            }

            // 4. Recent Voice Drafts Preview
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Recent Drafts (${recentDrafts.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarTextPrimary,
                    )
                    TextButton(onClick = onNavigateToDrafts) {
                        Text(
                            text = "View All ›",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = RoxStarPrimary,
                        )
                    }
                }
            }

            if (recentDrafts.isEmpty()) {
                item {
                    RoxStarCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(text = "🎵", fontSize = 28.sp)
                            Text(
                                text = "No voice drafts recorded yet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = RoxStarTextPrimary,
                            )
                            Text(
                                text = "Record your first voice clip with echo, reverb, or pitch shift.",
                                style = MaterialTheme.typography.bodySmall,
                                color = RoxStarTextSecondary,
                            )
                            Spacer(Modifier.height(4.dp))
                            RoxStarOutlineButton(
                                text = "Record Now",
                                onClick = onNavigateToStudio,
                            )
                        }
                    }
                }
            } else {
                items(recentDrafts.take(3), key = { it.id }) { draft ->
                    RecentDraftItem(
                        draft = draft,
                        isPlaying = draft.id == playingDraftId,
                        onPlay = { onPlayDraft(draft) },
                        onStop = onStopDraft,
                        onOpenDrafts = onNavigateToDrafts,
                    )
                }
            }

            // 5. Recent Rooms Quick Join
            if (recentRooms.isNotEmpty()) {
                item {
                    Text(
                        text = "Recent Rooms",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarTextPrimary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(recentRooms) { roomId ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = RoxStarCardSurface,
                                border = CardDefaults.outlinedCardBorder().copy(
                                    brush = Brush.linearGradient(listOf(RoxStarBorder, RoxStarBorder))
                                ),
                                modifier = Modifier.clickable(onClick = onNavigateToRoom),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Text("🚪", fontSize = 14.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = roomId.take(8) + "...",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = RoxStarTextPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Footer padding
            item {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HeroBanner(
    activeRoomId: String?,
    activeUserName: String?,
    participantCount: Int,
    onNavigateToStudio: () -> Unit,
    onNavigateToRoom: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .background(RoxStarGradients.Hero)
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = RoxStarPrimaryContainer,
                        ) {
                            Text(
                                text = "ROXSTAR PLATFORM",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = RoxStarPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Next-Gen Voice & Real-Time Arena",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = RoxStarTextPrimary,
                            lineHeight = 26.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    RoxStarLogoBadge(size = 52.dp)
                }

                Text(
                    text = "Seamless native audio recording, live collaborative rooms, and an authoritative 5-second multiplayer spin elimination game.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RoxStarTextSecondary,
                )

                // Decorative waveform
                AudioWaveformVisualizer(
                    isRecording = false,
                    peakLevel = 0.3f,
                    barCount = 24,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RoxStarPrimaryButton(
                        text = "Start Recording",
                        onClick = onNavigateToStudio,
                        modifier = Modifier.weight(1f),
                        icon = {
                            RoxStarMicIcon(
                                color = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                    RoxStarOutlineButton(
                        text = "Join Room",
                        onClick = onNavigateToRoom,
                        modifier = Modifier.weight(1f),
                        icon = { Text("👥", fontSize = 14.sp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveSessionCard(
    roomId: String,
    userName: String?,
    participantCount: Int,
    spinStatus: SpinGameStatus,
    onEnterRoom: () -> Unit,
    onOpenSpin: () -> Unit,
) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(RoxStarSuccess),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Live Room Session",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarTextPrimary,
                    )
                }

                SpinBadge(status = spinStatus)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Room ID: ${roomId.take(8)}...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = RoxStarTextPrimary,
                    )
                    Text(
                        text = "$participantCount participants online • User: ${userName ?: "Guest"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = RoxStarTextSecondary,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                RoxStarPrimaryButton(
                    text = "Enter Room",
                    onClick = onEnterRoom,
                    modifier = Modifier.weight(1f),
                )
                if (spinStatus == SpinGameStatus.RUNNING || spinStatus == SpinGameStatus.COMPLETED) {
                    RoxStarOutlineButton(
                        text = "View Spin Arena",
                        onClick = onOpenSpin,
                        color = RoxStarAudioAccent,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PillarFeatureCard(
    title: String,
    subtitle: String,
    description: String,
    icon: String = "",
    customIcon: @Composable (() -> Unit)? = null,
    badgeText: String,
    badgeColor: Color,
    badgeBg: Color,
    actionText: String,
    onClick: () -> Unit,
) {
    RoxStarCard(onClick = onClick) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = badgeBg,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (customIcon != null) {
                                customIcon()
                            } else {
                                Text(text = icon, fontSize = 20.sp)
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = RoxStarTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = RoxStarTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeBg,
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = RoxStarTextSecondary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarPrimary,
                )
            }
        }
    }
}

@Composable
private fun RecentDraftItem(
    draft: Draft,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onOpenDrafts: () -> Unit,
) {
    RoxStarCard(onClick = onOpenDrafts) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (isPlaying) RoxStarPrimary else RoxStarAudioContainer,
                modifier = Modifier
                    .size(36.dp)
                    .clickable(onClick = if (isPlaying) onStop else onPlay),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isPlaying) "■" else "▶",
                        color = if (isPlaying) Color.White else RoxStarAudioAccent,
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = draft.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
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
                        style = MaterialTheme.typography.labelSmall,
                        color = RoxStarTextSecondary,
                    )
                    VoiceEffectBadge(draft.effect)
                }
            }

            Text("›", fontSize = 20.sp, color = RoxStarTextSecondary)
        }
    }
}
