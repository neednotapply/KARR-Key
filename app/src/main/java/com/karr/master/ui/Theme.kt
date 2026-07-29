package com.karr.master.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KarrDarkColors = darkColorScheme(
    primary = Color(0xFFFF5A4C),
    onPrimary = Color(0xFF210100),
    primaryContainer = Color(0xFF7D201A),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFB8C7DC),
    onSecondary = Color(0xFF233143),
    background = Color(0xFF0A0D12),
    onBackground = Color(0xFFE4E8EF),
    surface = Color(0xFF10151D),
    onSurface = Color(0xFFE4E8EF),
    surfaceVariant = Color(0xFF1B222D),
    onSurfaceVariant = Color(0xFFBBC3CF),
    error = Color(0xFFFFB4AB),
)

@Composable
fun KarrMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KarrDarkColors,
        content = content,
    )
}
