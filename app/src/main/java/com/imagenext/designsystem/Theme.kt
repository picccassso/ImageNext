package com.imagenext.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = ImageNextAccent,
    onPrimary = ImageNextWhite,
    primaryContainer = ImageNextAccentVariant,
    onPrimaryContainer = ImageNextWhite,
    secondary = ImageNextMediumGray,
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

/**
 * ImageNext application theme.
 * Dark-first design optimized for photo viewing.
 *
 * Accessibility baseline:
 * - Text contrast ratio meets WCAG AA (4.5:1 for normal text, 3:1 for large text).
 * - Touch targets are minimum 48dp as enforced by Material 3 defaults.
 */
@Composable
fun ImageNextTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = ImageNextTypography,
        content = content,
    )
}
