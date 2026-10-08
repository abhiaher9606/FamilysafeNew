package com.familysafe.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// High-contrast colours for both modes.
// Dark mode uses a true-black background, which saves battery on AMOLED screens.

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B4FB3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF3F5A7A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E6FF),
    onSecondaryContainer = Color(0xFF0B1D33),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111418),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111418),
    surfaceVariant = Color(0xFFE1E6EE),
    onSurfaceVariant = Color(0xFF3A414B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F8FB),
    surfaceContainer = Color(0xFFF0F3F7),
    surfaceContainerHigh = Color(0xFFEAEEF3),
    surfaceContainerHighest = Color(0xFFE4E9F0),
    outline = Color(0xFF6B7380),
    outlineVariant = Color(0xFFC3CAD4),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC2FF),
    onPrimary = Color(0xFF002A66),
    primaryContainer = Color(0xFF0B3F8F),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB7CAE6),
    onSecondary = Color(0xFF1F3247),
    secondaryContainer = Color(0xFF2E4560),
    onSecondaryContainer = Color(0xFFD8E6FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF1F3F6),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF1F3F6),
    surfaceVariant = Color(0xFF2A2F36),
    onSurfaceVariant = Color(0xFFC6CCD5),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0D0F12),
    surfaceContainer = Color(0xFF14171B),
    surfaceContainerHigh = Color(0xFF1B1F24),
    surfaceContainerHighest = Color(0xFF22272D),
    outline = Color(0xFF8E96A2),
    outlineVariant = Color(0xFF3A414B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6)
)

/** Bright red used for the SOS button in both modes (white text on it). */
internal val SosRed = Color(0xFFD32F2F)

@Composable
fun FamilySafeTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
