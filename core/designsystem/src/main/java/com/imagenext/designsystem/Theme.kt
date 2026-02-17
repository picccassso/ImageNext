package com.imagenext.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = ImageNextAccent,
    onPrimary = ImageNextWhite,
    primaryContainer = ImageNextAccentVariant,
    onPrimaryContainer = ImageNextWhite,
    secondary = ImageNextAccentSecondary,
    onSecondary = ImageNextWhite,
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
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
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
