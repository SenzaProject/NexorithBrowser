package com.nexorith.browser.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NexorithColorScheme = darkColorScheme(
    primary = NexorithPrimary,
    secondary = NexorithNeonPurple,
    tertiary = NexorithAccentBlue,
    background = NexorithBackground,
    surface = NexorithSurface,
    surfaceVariant = NexorithSurface,
    onPrimary = NexorithText,
    onSecondary = NexorithText,
    onTertiary = NexorithText,
    onBackground = NexorithText,
    onSurface = NexorithText,
    onSurfaceVariant = NexorithTextSecondary,
    outline = NexorithTextSecondary,
    error = NexorithError,
)

/** Nexorith is dark-only by design. */
@Composable
fun NexorithTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NexorithColorScheme,
        typography = NexorithTypography,
        shapes = NexorithShapes,
        content = content,
    )
}
