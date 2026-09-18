package com.roxstar.voicedraft.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// RoxStar Official Palette
val RoxStarPrimary = Color(0xFFF43F7F)
val RoxStarPrimaryPressed = Color(0xFFD92F6A)
val RoxStarBackground = Color(0xFFFFF8FB)
val RoxStarCardSurface = Color(0xFFFFFFFF)
val RoxStarCard = RoxStarCardSurface
val RoxStarTextPrimary = Color(0xFF171717)
val RoxStarTextSecondary = Color(0xFF737373)
val RoxStarAudioAccent = Color(0xFF7C4DFF)

// Derived tints, semantic and container colors
val RoxStarPrimaryContainer = Color(0xFFFFE4EC)
val RoxStarOnPrimaryContainer = Color(0xFF8C1D44)
val RoxStarAudioContainer = Color(0xFFF1EAFF)
val RoxStarOnAudioContainer = Color(0xFF4A148C)
val RoxStarSurfaceVariant = Color(0xFFF9EEF3)
val RoxStarBorder = Color(0xFFF0DDE5)

val RoxStarSuccess = Color(0xFF10B981)
val RoxStarSuccessContainer = Color(0xFFD1FAE5)
val RoxStarWarning = Color(0xFFF59E0B)
val RoxStarWarningContainer = Color(0xFFFEF3C7)
val RoxStarError = Color(0xFFEF4444)
val RoxStarErrorContainer = Color(0xFFFEE2E2)

// Wheel palette for multiplayer slices
val RoxStarWheelSliceColors = listOf(
    Color(0xFFF43F7F),
    Color(0xFF7C4DFF),
    Color(0xFFEC4899),
    Color(0xFF8B5CF6),
    Color(0xFFFB7185),
    Color(0xFF6366F1),
    Color(0xFFF472B6),
    Color(0xFFA855F7),
    Color(0xFFE11D48),
    Color(0xFF9333EA),
    Color(0xFFDB2777),
    Color(0xFF4F46E5),
)

object RoxStarGradients {
    val Primary = Brush.linearGradient(
        colors = listOf(RoxStarPrimary, RoxStarPrimaryPressed)
    )
    val Audio = Brush.linearGradient(
        colors = listOf(RoxStarAudioAccent, RoxStarPrimary)
    )
    val CardHeader = Brush.linearGradient(
        colors = listOf(Color(0xFFFFF0F5), RoxStarCard)
    )
    val Hero = Brush.linearGradient(
        colors = listOf(Color(0xFFFFEEF5), Color(0xFFF5E8FF), RoxStarBackground)
    )
    val GoldWinner = Brush.linearGradient(
        colors = listOf(Color(0xFFFFD700), Color(0xFFFFA000))
    )
}

internal val LightColors = lightColorScheme(
    primary = RoxStarPrimary,
    onPrimary = Color.White,
    primaryContainer = RoxStarPrimaryContainer,
    onPrimaryContainer = RoxStarOnPrimaryContainer,
    secondary = RoxStarAudioAccent,
    onSecondary = Color.White,
    secondaryContainer = RoxStarAudioContainer,
    onSecondaryContainer = RoxStarOnAudioContainer,
    background = RoxStarBackground,
    onBackground = RoxStarTextPrimary,
    surface = RoxStarCard,
    onSurface = RoxStarTextPrimary,
    surfaceVariant = RoxStarSurfaceVariant,
    onSurfaceVariant = RoxStarTextSecondary,
    outline = RoxStarBorder,
    outlineVariant = RoxStarBorder.copy(alpha = 0.5f),
    error = RoxStarError,
    onError = Color.White,
    errorContainer = RoxStarErrorContainer,
    onErrorContainer = Color(0xFF991B1B),
)

internal val DarkColors = darkColorScheme(
    primary = RoxStarPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4E1026),
    onPrimaryContainer = RoxStarPrimaryContainer,
    secondary = Color(0xFF9E77FF),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF321B66),
    onSecondaryContainer = RoxStarAudioContainer,
    background = Color(0xFF140D12),
    onBackground = Color(0xFFF9EEF3),
    surface = Color(0xFF1E141B),
    onSurface = Color(0xFFF9EEF3),
    surfaceVariant = Color(0xFF2C1E27),
    onSurfaceVariant = Color(0xFFD4B8C7),
    outline = Color(0xFF442D3D),
    outlineVariant = Color(0xFF33202E),
    error = Color(0xFFF87171),
    onError = Color.Black,
    errorContainer = Color(0xFF450A0A),
    onErrorContainer = Color(0xFFFCA5A5),
)
