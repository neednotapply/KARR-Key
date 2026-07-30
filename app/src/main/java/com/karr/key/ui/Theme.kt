package com.karr.key.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val KarrDarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE7BDB8),
    onSecondary = Color(0xFF442926),
    secondaryContainer = Color(0xFF5D3F3C),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFDEC48C),
    onTertiary = Color(0xFF3E2E04),
    tertiaryContainer = Color(0xFF574419),
    onTertiaryContainer = Color(0xFFFBE0A5),
    background = Color(0xFF120E0D),
    onBackground = Color(0xFFF2DFDC),
    surface = Color(0xFF120E0D),
    onSurface = Color(0xFFF2DFDC),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BF),
    surfaceTint = Color(0xFFFFB4AB),
    outline = Color(0xFFA08C89),
    outlineVariant = Color(0xFF534341),
    inverseSurface = Color(0xFFF2DFDC),
    inverseOnSurface = Color(0xFF382E2C),
    inversePrimary = Color(0xFFBA1A1A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

private val KarrLightColors = lightColorScheme(
    primary = Color(0xFFBA1A1A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775652),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF705D24),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBE0A5),
    onTertiaryContainer = Color(0xFF251A00),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF201A19),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF201A19),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    surfaceTint = Color(0xFFBA1A1A),
    outline = Color(0xFF857370),
    outlineVariant = Color(0xFFD8C2BF),
    inverseSurface = Color(0xFF362F2E),
    inverseOnSurface = Color(0xFFFBEEEC),
    inversePrimary = Color(0xFFFFB4AB),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

@Composable
fun KarrKeyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) KarrDarkColors else KarrLightColors,
        content = content,
    )
}
