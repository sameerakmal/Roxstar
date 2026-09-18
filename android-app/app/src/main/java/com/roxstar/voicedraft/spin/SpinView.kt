package com.roxstar.voicedraft.spin

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roxstar.voicedraft.network.ParticipantDto
import com.roxstar.voicedraft.network.SpinPlayerDto
import com.roxstar.voicedraft.ui.components.ParticipantRoleBadge
import com.roxstar.voicedraft.ui.components.RoxStarCard
import com.roxstar.voicedraft.ui.components.RoxStarOutlineButton
import com.roxstar.voicedraft.ui.components.RoxStarPrimaryButton
import com.roxstar.voicedraft.ui.components.SpinBadge
import com.roxstar.voicedraft.ui.components.UserAvatar
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
import com.roxstar.voicedraft.ui.theme.RoxStarSuccess
import com.roxstar.voicedraft.ui.theme.RoxStarSuccessContainer
import com.roxstar.voicedraft.ui.theme.RoxStarTextPrimary
import com.roxstar.voicedraft.ui.theme.RoxStarTextSecondary
import com.roxstar.voicedraft.ui.theme.RoxStarWarning
import com.roxstar.voicedraft.ui.theme.RoxStarWarningContainer
import com.roxstar.voicedraft.ui.theme.RoxStarWheelSliceColors
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual Spin Wheel Elimination Arena.
 * Contains the canvas-rendered circular wheel, 5-second countdown timer,
 * elimination notifications, contender badges, and winner podium.
 */
@Composable
fun SpinView(
    uiState: SpinUiState,
    isRoomOwner: Boolean,
    currentUserId: String?,
    participantCount: Int,
    onStartSpin: () -> Unit,
    roomParticipants: List<ParticipantDto> = emptyList(),
    modifier: Modifier = Modifier,
) {
    RoxStarCard(
        elevation = 4.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "🎡", fontSize = 22.sp)
                    Column {
                        Text(
                            text = "Spin Elimination Arena",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = RoxStarTextPrimary,
                        )
                        Text(
                            text = "5-Second Battle Royale • 1 Winner",
                            style = MaterialTheme.typography.bodySmall,
                            color = RoxStarTextSecondary,
                        )
                    }
                }

                SpinBadge(status = uiState.status)
            }

            // Central Circular Spin Wheel Canvas
            VisibleSpinWheel(
                uiState = uiState,
                participantCount = participantCount,
                roomParticipants = roomParticipants,
            )

            // Dynamic State Sections
            when (uiState.status) {
                SpinGameStatus.IDLE -> {
                    IdleSection(
                        isRoomOwner = isRoomOwner,
                        isStarting = uiState.isStarting,
                        participantCount = participantCount,
                        onStartSpin = onStartSpin,
                    )
                }

                SpinGameStatus.RUNNING -> {
                    RunningSection(
                        uiState = uiState,
                        currentUserId = currentUserId,
                    )
                }

                SpinGameStatus.COMPLETED -> {
                    CompletedSection(
                        uiState = uiState,
                        isRoomOwner = isRoomOwner,
                        onStartSpin = onStartSpin,
                        participantCount = participantCount,
                    )
                }

                SpinGameStatus.ABORTED -> {
                    AbortedSection(
                        isRoomOwner = isRoomOwner,
                        onStartSpin = onStartSpin,
                    )
                }
            }

            // Error Message Banner
            if (uiState.errorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = RoxStarErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "⚠ ${uiState.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = RoxStarError,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
    }
}

/**
 * Large circular wheel rendered using Compose Canvas.
 */
@Composable
private fun VisibleSpinWheel(
    uiState: SpinUiState,
    participantCount: Int,
    roomParticipants: List<ParticipantDto> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val isRunning = uiState.status == SpinGameStatus.RUNNING
    val displayNames: List<String> = when {
        uiState.remainingPlayers.isNotEmpty() -> uiState.remainingPlayers.map { it.displayName }
        uiState.eligiblePlayers.isNotEmpty() -> uiState.eligiblePlayers.map { it.displayName }
        roomParticipants.isNotEmpty() -> roomParticipants.map { it.displayName }
        else -> emptyList()
    }

    // Infinite smooth rotation animation when running
    val transition = rememberInfiniteTransition(label = "wheel_spin")
    val rotationAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation_angle",
    )

    val currentRotation = if (isRunning) rotationAngle else 0f

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(260.dp)
            .padding(8.dp),
    ) {
        // Outer glow when running
        if (isRunning) {
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .clip(CircleShape)
                    .background(RoxStarPrimary.copy(alpha = 0.12f)),
            )
        }

        // Wheel Canvas
        Canvas(
            modifier = Modifier
                .size(240.dp)
                .rotate(currentRotation),
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val center = Offset(canvasWidth / 2f, canvasHeight / 2f)
            val radius = (size.minDimension / 2f) - 6.dp.toPx()

            val segmentCount = maxOf(1, displayNames.size)
            val sweepAngle = 360f / segmentCount

            // 1. Draw Slices
            for (i in 0 until segmentCount) {
                val startAngle = i * sweepAngle
                val sliceColor = RoxStarWheelSliceColors[i % RoxStarWheelSliceColors.size]

                drawArc(
                    color = sliceColor,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = true,
                    size = Size(radius * 2f, radius * 2f),
                    topLeft = Offset(center.x - radius, center.y - radius),
                )

                // Divider line between slices (only drawn if more than 1 slice)
                if (segmentCount > 1) {
                    val rad = Math.toRadians((startAngle).toDouble())
                    val lineEnd = Offset(
                        x = (center.x + radius * cos(rad)).toFloat(),
                        y = (center.y + radius * sin(rad)).toFloat(),
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.85f),
                        start = center,
                        end = lineEnd,
                        strokeWidth = 2.dp.toPx(),
                    )
                }

                // Draw Participant Name in slice
                val playerName = if (displayNames.isNotEmpty()) displayNames[i] else "Waiting..."
                val midAngle = startAngle + sweepAngle / 2f
                val midRad = Math.toRadians(midAngle.toDouble())
                val textDistance = radius * 0.60f
                val textX = (center.x + textDistance * cos(midRad)).toFloat()
                val textY = (center.y + textDistance * sin(midRad)).toFloat()

                if (segmentCount == 1) {
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 12.sp.toPx()
                            typeface = Typeface.DEFAULT_BOLD
                            textAlign = Paint.Align.CENTER
                            isAntiAlias = true
                        }
                        drawText(playerName.take(12), center.x, center.y - (radius * 0.50f), paint)
                    }
                } else {
                    drawContext.canvas.nativeCanvas.apply {
                        save()
                        rotate(midAngle + 90f, textX, textY)
                        val paint = Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = if (segmentCount > 6) 8.sp.toPx() else 10.sp.toPx()
                            typeface = Typeface.DEFAULT_BOLD
                            textAlign = Paint.Align.CENTER
                            isAntiAlias = true
                        }
                        drawText(playerName.take(9), textX, textY, paint)
                        restore()
                    }
                }
            }

            // 2. Outer Rim with decorative studs
            drawCircle(
                color = RoxStarPrimaryPressed,
                radius = radius,
                center = center,
                style = Stroke(width = 5.dp.toPx()),
            )

            // Rim studs
            val studCount = 12
            for (s in 0 until studCount) {
                val studAngle = Math.toRadians((s * (360f / studCount)).toDouble())
                val studPos = Offset(
                    x = (center.x + radius * cos(studAngle)).toFloat(),
                    y = (center.y + radius * sin(studAngle)).toFloat(),
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.5.dp.toPx(),
                    center = studPos,
                )
            }

            // 3. Center Hub
            drawCircle(
                color = RoxStarCardSurface,
                radius = radius * 0.25f,
                center = center,
            )
            drawCircle(
                color = RoxStarPrimary,
                radius = radius * 0.18f,
                center = center,
            )
            drawCircle(
                color = Color.White,
                radius = radius * 0.08f,
                center = center,
            )
        }

        // Fixed Top Pointer Needle
        TopPointerNeedle(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 0.dp),
        )

        // Center Icon Star
        Text(
            text = "★",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * Fixed downward-pointing arrow needle at top of wheel.
 */
@Composable
private fun TopPointerNeedle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 24.dp, height = 24.dp)) {
        val path = Path().apply {
            moveTo(size.width / 2f, size.height)
            lineTo(0f, 0f)
            lineTo(size.width, 0f)
            close()
        }
        drawPath(
            path = path,
            color = Color(0xFF171717),
        )
        // Accent border on needle
        drawPath(
            path = path,
            color = RoxStarPrimary,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun IdleSection(
    isRoomOwner: Boolean,
    isStarting: Boolean,
    participantCount: Int,
    onStartSpin: () -> Unit,
) {
    val canStart = participantCount in 3..20 && !isStarting

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Player Eligibility Count Banner
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (participantCount >= 3) RoxStarSuccessContainer else RoxStarPrimaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = if (participantCount >= 3) "✅" else "⏳", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (participantCount >= 3) "Eligible Contenders Ready" else "Need More Players",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (participantCount >= 3) Color(0xFF065F46) else RoxStarPrimary,
                    )
                }

                Text(
                    text = "$participantCount / 3 min (max 20)",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (participantCount >= 3) Color(0xFF065F46) else RoxStarPrimary,
                )
            }
        }

        if (isRoomOwner) {
            RoxStarPrimaryButton(
                text = if (isStarting) "Starting Spin Arena..." else "Start Spin Game",
                onClick = onStartSpin,
                enabled = canStart,
                modifier = Modifier.fillMaxWidth(),
                icon = {
                    if (isStarting) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Text("⚡", fontSize = 16.sp)
                    }
                },
            )

            if (participantCount < 3) {
                Text(
                    text = "At least 3 eligible participants are required to launch an elimination spin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RoxStarError,
                    textAlign = TextAlign.Center,
                )
            } else if (participantCount > 20) {
                Text(
                    text = "Room exceeds maximum of 20 players for spin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RoxStarError,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = RoxStarBackground,
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(listOf(RoxStarBorder, RoxStarBorder))
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (participantCount >= 3) "Waiting for Room Owner to launch spin" else "Waiting for at least 3 players to join...",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = RoxStarTextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Once started, 1 participant will be eliminated every 5 seconds until 1 champion remains.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RoxStarTextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunningSection(
    uiState: SpinUiState,
    currentUserId: String?,
) {
    // 5-Second Elimination Countdown Timer
    var secondsLeft by remember { mutableIntStateOf(5) }

    LaunchedEffect(uiState.lastSequenceNumber, uiState.status) {
        secondsLeft = 5
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Countdown banner: Next elimination in 04s.. 03s..
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = RoxStarPrimaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        progress = { (secondsLeft.toFloat() / 5f).coerceIn(0f, 1f) },
                        modifier = Modifier.size(24.dp),
                        color = RoxStarPrimary,
                        trackColor = RoxStarBorder,
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "NEXT ELIMINATION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarPrimary,
                    )
                }

                Text(
                    text = "0${secondsLeft}s",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarPrimary,
                )
            }
        }

        // Live elimination announcement banner if someone was recently eliminated
        AnimatedVisibility(
            visible = uiState.recentlyEliminatedUser != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            uiState.recentlyEliminatedUser?.let { eliminated ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = RoxStarErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("⚡", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "ELIMINATED: ${eliminated.displayName}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = RoxStarError,
                            )
                            Text(
                                text = "Elimination #${eliminated.eliminationOrder ?: "?"} • ${eliminated.eliminationReason ?: "TIMER"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF7F1D1D),
                            )
                        }
                    }
                }
            }
        }

        // Remaining Contenders List
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Active Contenders",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarTextPrimary,
                )
                Text(
                    text = "${uiState.remainingPlayers.size} left",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarPrimary,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                uiState.remainingPlayers.forEach { player ->
                    PlayerContenderChip(
                        player = player,
                        isSelf = player.userId == currentUserId,
                        isEliminated = false,
                    )
                }
            }
        }

        // Eliminated Contenders
        if (uiState.eliminatedPlayers.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Eliminated (${uiState.eliminatedPlayers.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarTextSecondary,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    uiState.eliminatedPlayers.forEach { player ->
                        PlayerContenderChip(
                            player = player,
                            isSelf = player.userId == currentUserId,
                            isEliminated = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletedSection(
    uiState: SpinUiState,
    isRoomOwner: Boolean,
    onStartSpin: () -> Unit,
    participantCount: Int,
) {
    val winner = uiState.winner

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Winner Podium Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = RoxStarWarningContainer,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "👑", fontSize = 38.sp)
                Text(
                    text = "CHAMPION ANNOUNCED!",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB45309),
                    letterSpacing = 1.sp,
                )
                Text(
                    text = winner?.displayName ?: "Unknown Winner",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarTextPrimary,
                )
                Text(
                    text = "Outlasted ${uiState.eliminatedPlayers.size} contenders in the 5s elimination arena!",
                    style = MaterialTheme.typography.bodySmall,
                    color = RoxStarTextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (isRoomOwner) {
            RoxStarPrimaryButton(
                text = "Play Spin Again",
                onClick = onStartSpin,
                enabled = participantCount in 3..20,
                modifier = Modifier.fillMaxWidth(),
                icon = { Text("🔄", fontSize = 16.sp) },
            )
        }
    }
}

@Composable
private fun AbortedSection(
    isRoomOwner: Boolean,
    onStartSpin: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF3F4F6),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "🛑", fontSize = 24.sp)
            Text(
                text = "Spin Game Aborted",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = RoxStarTextPrimary,
            )
            Text(
                text = "The spin game was stopped (player count dropped below minimum or connection ended).",
                style = MaterialTheme.typography.bodySmall,
                color = RoxStarTextSecondary,
                textAlign = TextAlign.Center,
            )
            if (isRoomOwner) {
                RoxStarOutlineButton(
                    text = "Reset & Try Again",
                    onClick = onStartSpin,
                )
            }
        }
    }
}

@Composable
private fun PlayerContenderChip(
    player: SpinPlayerDto,
    isSelf: Boolean,
    isEliminated: Boolean,
) {
    val bg = if (isEliminated) Color(0xFFF3F4F6) else RoxStarCardSurface
    val textColor = if (isEliminated) RoxStarTextSecondary else RoxStarTextPrimary

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(listOf(RoxStarBorder, RoxStarBorder))
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            UserAvatar(
                name = player.displayName,
                size = 20.dp,
                fontSize = 10,
                isOnline = !isEliminated,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = player.displayName + if (isSelf) " (You)" else "",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Medium,
                color = textColor,
                textDecoration = if (isEliminated) TextDecoration.LineThrough else TextDecoration.None,
            )
            if (isEliminated && player.eliminationOrder != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "#${player.eliminationOrder}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = RoxStarError,
                )
            }
        }
    }
}
