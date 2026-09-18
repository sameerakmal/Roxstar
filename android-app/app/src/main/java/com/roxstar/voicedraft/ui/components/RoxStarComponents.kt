package com.roxstar.voicedraft.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roxstar.voicedraft.Effect
import com.roxstar.voicedraft.room.RoomConnectionStatus
import com.roxstar.voicedraft.spin.SpinGameStatus
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

/**
 * Vector-drawn studio microphone icon with acoustic grille slits, shockmount cradle,
 * base stand, and optional audio soundwaves.
 */
@Composable
fun RoxStarMicIcon(
    modifier: Modifier = Modifier,
    color: Color = RoxStarPrimary,
    accentColor: Color = RoxStarAudioAccent,
    showSoundWaves: Boolean = false,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h * 0.44f

        // 1. Microphone Capsule (Rounded Pill)
        val capW = w * 0.32f
        val capH = h * 0.46f
        val capLeft = cx - capW / 2f
        val capTop = cy - capH / 2f
        val cornerRadius = CornerRadius(capW / 2f, capW / 2f)

        drawRoundRect(
            color = color,
            topLeft = Offset(capLeft, capTop),
            size = Size(capW, capH),
            cornerRadius = cornerRadius,
        )

        // 2. Grille details (horizontal acoustic slits)
        val slitStroke = (w * 0.055f).coerceAtLeast(1.5f)
        val slitColor = Color.White.copy(alpha = 0.9f)
        val slitInset = capW * 0.2f

        for (i in 1..3) {
            val slitY = capTop + (capH * 0.18f * i)
            drawLine(
                color = slitColor,
                start = Offset(capLeft + slitInset, slitY),
                end = Offset(capLeft + capW - slitInset, slitY),
                strokeWidth = slitStroke,
                cap = StrokeCap.Round,
            )
        }

        // Center division band
        val bandY = capTop + capH * 0.58f
        drawLine(
            color = Color.White.copy(alpha = 0.6f),
            start = Offset(capLeft + capW * 0.1f, bandY),
            end = Offset(capLeft + capW * 0.9f, bandY),
            strokeWidth = slitStroke * 1.2f,
            cap = StrokeCap.Square,
        )

        // 3. Shockmount U-Cradle
        val cradleStroke = (w * 0.075f).coerceAtLeast(2f)
        val cradleRadius = capW * 0.85f
        val cradleTopY = cy - capH * 0.15f
        val cradleBottomY = cy + capH * 0.42f

        val cradlePath = Path().apply {
            moveTo(cx - cradleRadius, cradleTopY)
            lineTo(cx - cradleRadius, cradleBottomY)
            arcTo(
                rect = Rect(cx - cradleRadius, cradleBottomY - cradleRadius, cx + cradleRadius, cradleBottomY + cradleRadius),
                startAngleDegrees = 180f,
                sweepAngleDegrees = -180f,
                forceMoveTo = false,
            )
            lineTo(cx + cradleRadius, cradleTopY)
        }
        drawPath(
            path = cradlePath,
            color = color,
            style = Stroke(width = cradleStroke, cap = StrokeCap.Round),
        )

        // 4. Vertical Stem
        val stemTop = cradleBottomY + cradleRadius
        val stemBottom = h * 0.90f
        drawLine(
            color = color,
            start = Offset(cx, stemTop),
            end = Offset(cx, stemBottom),
            strokeWidth = cradleStroke * 1.1f,
            cap = StrokeCap.Round,
        )

        // 5. Desk Stand Base
        val baseHalfW = w * 0.30f
        drawLine(
            color = color,
            start = Offset(cx - baseHalfW, stemBottom),
            end = Offset(cx + baseHalfW, stemBottom),
            strokeWidth = cradleStroke * 1.2f,
            cap = StrokeCap.Round,
        )

        // 6. Optional Radiating Soundwaves
        if (showSoundWaves) {
            val waveStroke = (w * 0.05f).coerceAtLeast(1.5f)
            val leftInner = Path().apply {
                arcTo(
                    rect = Rect(cx - w * 0.58f, cy - h * 0.22f, cx - w * 0.30f, cy + h * 0.22f),
                    startAngleDegrees = 120f,
                    sweepAngleDegrees = 120f,
                    forceMoveTo = false,
                )
            }
            drawPath(path = leftInner, color = accentColor, style = Stroke(width = waveStroke, cap = StrokeCap.Round))

            val rightInner = Path().apply {
                arcTo(
                    rect = Rect(cx + w * 0.30f, cy - h * 0.22f, cx + w * 0.58f, cy + h * 0.22f),
                    startAngleDegrees = -60f,
                    sweepAngleDegrees = 120f,
                    forceMoveTo = false,
                )
            }
            drawPath(path = rightInner, color = accentColor, style = Stroke(width = waveStroke, cap = StrokeCap.Round))
        }
    }
}

/**
 * Official RoxStar Studio Logo Badge.
 * Gradient container with the studio microphone and glow.
 */
@Composable
fun RoxStarLogoBadge(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    Surface(
        shape = RoundedCornerShape((size * 0.3f)),
        color = Color.Transparent,
        modifier = modifier.size(size),
        shadowElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(RoxStarGradients.Primary),
            contentAlignment = Alignment.Center,
        ) {
            RoxStarMicIcon(
                color = Color.White,
                accentColor = Color(0xFFFFD1E3),
                showSoundWaves = true,
                modifier = Modifier.size(size * 0.62f),
            )
        }
    }
}

/**
 * Top App Bar with RoxStar branding, optional back navigation, and action buttons.
 */
@Composable
fun RoxStarTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        color = RoxStarCardSurface,
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(RoxStarBackground),
                    ) {
                        Text(
                            text = "‹",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = RoxStarTextPrimary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                } else {
                    // Official RoxStar Studio Brand Logo Badge
                    RoxStarLogoBadge(size = 36.dp)
                    Spacer(Modifier.width(10.dp))
                }

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = RoxStarTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = RoxStarTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
    }
}

/**
 * Standard elevated RoxStar card with crisp border and subtle elevation.
 */
@Composable
fun RoxStarCard(
    modifier: Modifier = Modifier,
    elevation: Dp = 2.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val cardModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }

    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = RoxStarCardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(listOf(RoxStarBorder.copy(alpha = 0.6f), RoxStarBorder.copy(alpha = 0.2f)))
        ),
    ) {
        content()
    }
}

/**
 * Gradient CTA primary button with pressed/active interaction states.
 */
@Composable
fun RoxStarPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 46.dp,
    icon: @Composable (() -> Unit)? = null,
    isAudioTheme: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val gradient = when {
        !enabled -> Brush.linearGradient(listOf(Color(0xFFE0E0E0), Color(0xFFD5D5D5)))
        isPressed -> Brush.linearGradient(listOf(RoxStarPrimaryPressed, RoxStarPrimaryPressed))
        isAudioTheme -> RoxStarGradients.Audio
        else -> RoxStarGradients.Primary
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(gradient)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (icon != null) {
                    icon()
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) Color.White else Color(0xFF888888),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Outline button with RoxStar border and primary text styling.
 */
@Composable
fun RoxStarOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 46.dp,
    color: Color = RoxStarPrimary,
    icon: @Composable (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(color, color.copy(alpha = 0.7f)))),
        color = Color.Transparent,
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (icon != null) {
                    icon()
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) color else RoxStarTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Badges for Participant States: Online, Offline, Room Owner, Spin Contender, Eliminated.
 */
@Composable
fun ParticipantRoleBadge(
    isOwner: Boolean,
    isEliminated: Boolean = false,
    isWinner: Boolean = false,
) {
    when {
        isWinner -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = RoxStarWarningContainer,
            ) {
                Text(
                    text = "🏆 WINNER",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB45309),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        isOwner -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = RoxStarPrimaryContainer,
            ) {
                Text(
                    text = "👑 HOST",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarPrimary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        isEliminated -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = RoxStarErrorContainer,
            ) {
                Text(
                    text = "✕ OUT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarError,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Socket.IO Real-time connection badge with pulse dot.
 */
@Composable
fun ConnectionBadge(
    status: RoomConnectionStatus,
    onRetry: (() -> Unit)? = null,
) {
    val (dotColor, bgColor, textColor, label) = when (status) {
        RoomConnectionStatus.CONNECTED -> Quadruple(RoxStarSuccess, RoxStarSuccessContainer, Color(0xFF065F46), "LIVE CONNECTED")
        RoomConnectionStatus.CONNECTING -> Quadruple(RoxStarWarning, RoxStarWarningContainer, Color(0xFF92400E), "CONNECTING...")
        RoomConnectionStatus.ERROR -> Quadruple(RoxStarError, RoxStarErrorContainer, Color(0xFF991B1B), "SYNC ACTIVE")
        RoomConnectionStatus.DISCONNECTED -> Quadruple(Color.Gray, Color(0xFFE5E5E5), Color(0xFF555555), "OFFLINE")
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bgColor,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
            if (status == RoomConnectionStatus.ERROR && onRetry != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "• Retry",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = RoxStarPrimary,
                    modifier = Modifier.clickable(onClick = onRetry),
                )
            }
        }
    }
}

/**
 * Spin Game status badge: WAITING, LIVE, FINISHED, ABORTED.
 */
@Composable
fun SpinBadge(status: SpinGameStatus) {
    val (bgColor, textColor, label) = when (status) {
        SpinGameStatus.IDLE -> Triple(RoxStarPrimaryContainer, RoxStarPrimary, "WAITING")
        SpinGameStatus.RUNNING -> Triple(RoxStarErrorContainer, RoxStarPrimaryPressed, "⚡ LIVE SPIN")
        SpinGameStatus.COMPLETED -> Triple(RoxStarWarningContainer, Color(0xFFB45309), "🏆 FINISHED")
        SpinGameStatus.ABORTED -> Triple(Color(0xFFEEEEEE), Color(0xFF666666), "ABORTED")
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

/**
 * Voice Effect tag with purple audio accent.
 */
@Composable
fun VoiceEffectBadge(effect: Effect) {
    val (label, bg, fg) = when (effect) {
        Effect.NONE -> Triple("Dry (Raw)", Color(0xFFF3F4F6), RoxStarTextSecondary)
        Effect.ECHO -> Triple("Echo", RoxStarAudioContainer, RoxStarAudioAccent)
        Effect.REVERB -> Triple("Reverb", Color(0xFFEDE9FE), Color(0xFF6D28D9))
        Effect.PITCH_SHIFT -> Triple("Pitch Shift", RoxStarPrimaryContainer, RoxStarPrimary)
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Multi-bar animated waveform visualizer reacting to peak level.
 */
@Composable
fun AudioWaveformVisualizer(
    isRecording: Boolean,
    peakLevel: Float,
    barCount: Int = 18,
    modifier: Modifier = Modifier,
    barColor: Color = RoxStarAudioAccent,
) {
    val transition = rememberInfiniteTransition(label = "waveform_anim")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until barCount) {
            val normalizedIndex = i.toFloat() / barCount
            val sinFactor = kotlin.math.sin(phase + normalizedIndex * 4f).toFloat().coerceAtLeast(0f)
            val dynamicHeight = if (isRecording) {
                val amplitude = (peakLevel * 36f) + (sinFactor * 16f)
                amplitude.coerceIn(4f, 44f)
            } else {
                6f
            }

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(dynamicHeight.dp)
                    .clip(CircleShape)
                    .background(
                        if (isRecording) {
                            Brush.verticalGradient(listOf(barColor, RoxStarPrimary))
                        } else {
                            Brush.verticalGradient(listOf(RoxStarBorder, RoxStarBorder))
                        }
                    ),
            )
        }
    }
}

/**
 * User avatar circle with first initial and deterministically assigned background tone.
 */
@Composable
fun UserAvatar(
    name: String,
    size: Dp = 40.dp,
    fontSize: Int = 16,
    isOnline: Boolean = true,
) {
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    val colorIndex = kotlin.math.abs(name.hashCode()) % 4
    val bg = when (colorIndex) {
        0 -> RoxStarPrimary
        1 -> RoxStarAudioAccent
        2 -> Color(0xFF0284C7)
        else -> Color(0xFFD97706)
    }

    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = bg,
            modifier = Modifier.size(size),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize.sp,
                )
            }
        }

        // Online status dot indicator
        if (isOnline) {
            Box(
                modifier = Modifier
                    .size((size.value * 0.3f).dp.coerceAtLeast(8.dp))
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(RoxStarSuccess)
                    .border(1.5.dp, RoxStarCardSurface, CircleShape),
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
