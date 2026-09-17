package com.roxstar.voicedraft.spin

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roxstar.voicedraft.network.SpinPlayerDto

/**
 * Live Spin elimination game view rendered inside the active room.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpinView(
    uiState: SpinUiState,
    isRoomOwner: Boolean,
    currentUserId: String?,
    participantCount: Int,
    onStartSpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SpinWheelIcon(isSpinning = uiState.isSpinActive)
                    Text(
                        text = "Spin Elimination Game",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                SpinStatusBadge(status = uiState.status)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            when (uiState.status) {
                SpinGameStatus.IDLE -> {
                    IdleSpinSection(
                        isRoomOwner = isRoomOwner,
                        isStarting = uiState.isStarting,
                        participantCount = participantCount,
                        onStartSpin = onStartSpin,
                    )
                }

                SpinGameStatus.RUNNING -> {
                    RunningSpinSection(
                        uiState = uiState,
                        currentUserId = currentUserId,
                    )
                }

                SpinGameStatus.COMPLETED -> {
                    CompletedSpinSection(
                        uiState = uiState,
                        isRoomOwner = isRoomOwner,
                        isStarting = uiState.isStarting,
                        participantCount = participantCount,
                        onStartSpin = onStartSpin,
                    )
                }

                SpinGameStatus.ABORTED -> {
                    AbortedSpinSection(
                        isRoomOwner = isRoomOwner,
                        onStartSpin = onStartSpin,
                    )
                }
            }

            // Error banner if any
            if (uiState.errorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = uiState.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpinWheelIcon(isSpinning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "spin_wheel")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
        ),
        label = "spin_angle",
    )

    Surface(
        shape = CircleShape,
        color = if (isSpinning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .size(32.dp)
            .rotate(if (isSpinning) angle else 0f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "✪",
                fontSize = 18.sp,
                color = if (isSpinning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SpinStatusBadge(status: SpinGameStatus) {
    val (bgColor, textColor, label) = when (status) {
        SpinGameStatus.IDLE -> Triple(Color.Gray.copy(alpha = 0.2f), Color.LightGray, "READY")
        SpinGameStatus.RUNNING -> Triple(Color(0xFF4CAF50).copy(alpha = 0.2f), Color(0xFF4CAF50), "LIVE")
        SpinGameStatus.COMPLETED -> Triple(Color(0xFFFFC107).copy(alpha = 0.2f), Color(0xFFFFC107), "FINISHED")
        SpinGameStatus.ABORTED -> Triple(Color(0xFFE91E63).copy(alpha = 0.2f), Color(0xFFE91E63), "ABORTED")
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun IdleSpinSection(
    isRoomOwner: Boolean,
    isStarting: Boolean,
    participantCount: Int,
    onStartSpin: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Elimination spin rules: All room members participate. One player is eliminated every 5 seconds until 1 winner remains.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isRoomOwner) {
            val canStart = participantCount >= 3 && !isStarting

            Button(
                onClick = onStartSpin,
                enabled = canStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Starting Spin...")
                } else {
                    Text("Start Spin Game")
                }
            }

            if (participantCount < 3) {
                Text(
                    text = "Requires at least 3 participants to start (current: $participantCount)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Text(
                text = "Waiting for room owner to start the spin game...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunningSpinSection(
    uiState: SpinUiState,
    currentUserId: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Live Announcement Banner
        if (uiState.recentlyEliminatedUser != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⚡", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${uiState.recentlyEliminatedUser.displayName} was eliminated! (#${uiState.recentlyEliminatedUser.eliminationOrder})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // Remaining Players
        Text(
            text = "Remaining Contenders (${uiState.remainingPlayers.size}):",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            uiState.remainingPlayers.forEach { player ->
                PlayerChip(
                    player = player,
                    isSelf = player.userId == currentUserId,
                    isEliminated = false,
                )
            }
        }

        // Eliminated Players
        if (uiState.eliminatedPlayers.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Eliminated (${uiState.eliminatedPlayers.size}):",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                uiState.eliminatedPlayers.forEach { player ->
                    PlayerChip(
                        player = player,
                        isSelf = player.userId == currentUserId,
                        isEliminated = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletedSpinSection(
    uiState: SpinUiState,
    isRoomOwner: Boolean,
    isStarting: Boolean,
    participantCount: Int,
    onStartSpin: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFFFD54F).copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "🏆 WINNER ANNOUNCED! 🏆",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFFFB300),
                )
                Text(
                    text = uiState.winner?.displayName ?: "Unknown Champion",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Survived all elimination rounds!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isRoomOwner) {
            OutlinedButton(
                onClick = onStartSpin,
                enabled = !isStarting && participantCount >= 3,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Play Again")
            }
        }
    }
}

@Composable
private fun AbortedSpinSection(
    isRoomOwner: Boolean,
    onStartSpin: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "The previous spin was aborted (not enough active players remained).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        if (isRoomOwner) {
            Button(
                onClick = onStartSpin,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restart Spin")
            }
        }
    }
}

@Composable
private fun PlayerChip(
    player: SpinPlayerDto,
    isSelf: Boolean,
    isEliminated: Boolean,
) {
    val bgColor = when {
        isEliminated -> Color.Gray.copy(alpha = 0.15f)
        isSelf -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    val textColor = when {
        isEliminated -> Color.Gray
        isSelf -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        modifier = if (isSelf && !isEliminated) {
            Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
        } else {
            Modifier
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = "${player.displayName}${if (isSelf) " (You)" else ""}${if (player.eliminationOrder != null) " #${player.eliminationOrder}" else ""}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (isEliminated) TextDecoration.LineThrough else TextDecoration.None,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
