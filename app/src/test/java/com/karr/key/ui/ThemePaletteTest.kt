package com.karr.key.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePaletteTest {
    @Test
    fun lightAndDarkSchemesContainOnlyGrayscaleColors() {
        listOf(KarrLightColors, KarrDarkColors).forEach { scheme ->
            scheme.colors().forEach(::assertGrayscale)
        }
    }

    private fun assertGrayscale(color: Color) {
        assertEquals(color.red, color.green, COLOR_TOLERANCE)
        assertEquals(color.green, color.blue, COLOR_TOLERANCE)
    }

    private fun ColorScheme.colors(): List<Color> = listOf(
        primary,
        onPrimary,
        primaryContainer,
        onPrimaryContainer,
        inversePrimary,
        secondary,
        onSecondary,
        secondaryContainer,
        onSecondaryContainer,
        tertiary,
        onTertiary,
        tertiaryContainer,
        onTertiaryContainer,
        background,
        onBackground,
        surface,
        onSurface,
        surfaceVariant,
        onSurfaceVariant,
        surfaceTint,
        inverseSurface,
        inverseOnSurface,
        error,
        onError,
        errorContainer,
        onErrorContainer,
        outline,
        outlineVariant,
        scrim,
        surfaceBright,
        surfaceDim,
        surfaceContainer,
        surfaceContainerHigh,
        surfaceContainerHighest,
        surfaceContainerLow,
        surfaceContainerLowest,
        primaryFixed,
        primaryFixedDim,
        onPrimaryFixed,
        onPrimaryFixedVariant,
        secondaryFixed,
        secondaryFixedDim,
        onSecondaryFixed,
        onSecondaryFixedVariant,
        tertiaryFixed,
        tertiaryFixedDim,
        onTertiaryFixed,
        onTertiaryFixedVariant,
    )

    private companion object {
        const val COLOR_TOLERANCE = 0.0001f
    }
}
