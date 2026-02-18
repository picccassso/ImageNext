package com.imagenext.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = ImageNextAccent,
    onPrimary = ImageNextBlack,
    primaryContainer = ImageNextAccentVariant,
    onPrimaryContainer = ImageNextBlack,
    secondary = ImageNextAccentSecondary,
    onSecondary = ImageNextBlack,
    background = ImageNextBlack,
    onBackground = ImageNextOnSurface,
    surface = ImageNextSurface,
    onSurface = ImageNextOnSurface,
    surfaceVariant = ImageNextSurfaceVariant,
    onSurfaceVariant = ImageNextOnSurfaceVariant,
    error = ImageNextError,
    onError = ImageNextOnError,
)

private val ImageNextShapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

/**
 * ImageNext application theme.
 * Premium dark-first design optimized for modern photo viewing.
 */
@Composable
fun ImageNextTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = ImageNextTypography,
        shapes = ImageNextShapes,
        content = content,
    )
}
