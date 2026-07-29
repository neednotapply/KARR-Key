package com.karr.master.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KarrDarkColors = darkColorScheme(
    primary = Color(0xFFFF3B30),
    onPrimary = Color(0xFF230000),
    primaryContainer = Color(0xFF7A0B0B),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFFFB4AB),
    onSecondary = Color(0xFF690005),
    secondaryContainer = Color(0xFF5C0006),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFFF897D),
    onTertiary = Color(0xFF4A0703),
    tertiaryContainer = Color(0xFF6A110B),
    onTertiaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF050505),
    onBackground = Color(0xFFF8EEEE),
    surface = Color(0xFF0C0A0A),
    onSurface = Color(0xFFF8EEEE),
    surfaceVariant = Color(0xFF251717),
    onSurfaceVariant = Color(0xFFD9C1BF),
    surfaceTint = Color(0xFFFF3B30),
    outline = Color(0xFFA88A87),
    outlineVariant = Color(0xFF533B39),
    inverseSurface = Color(0xFFF8EEEE),
    inverseOnSurface = Color(0xFF251818),
    inversePrimary = Color(0xFFBA1A1A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

@Composable
fun KarrMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KarrDarkColors,
        content = content,
    )
}
