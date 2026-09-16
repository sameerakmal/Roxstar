package com.roxstar.voicedraft.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1D4ED8)
private val BlueLight = Color(0xFF7C9CFF)
private val Red = Color(0xFFDC2626)
private val RedLight = Color(0xFFEF5350)

internal val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    background = Color(0xFFFDFDFD),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFDFDFD),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE4E4E7),
    onSurfaceVariant = Color(0xFF5B5B63),
    error = Red,
    onError = Color.White,
)

internal val DarkColors = darkColorScheme(
    primary = BlueLight,
    onPrimary = Color(0xFF0B1B4D),
    background = Color(0xFF121214),
    onBackground = Color(0xFFECECEE),
    surface = Color(0xFF121214),
    onSurface = Color(0xFFECECEE),
    surfaceVariant = Color(0xFF2A2A2E),
    onSurfaceVariant = Color(0xFFA8A8B0),
    error = RedLight,
    onError = Color(0xFF3B0A0A),
)
