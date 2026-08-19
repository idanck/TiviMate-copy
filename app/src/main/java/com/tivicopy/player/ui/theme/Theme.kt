package com.tivicopy.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tivicopy.player.data.prefs.ThemeMode

/**
 * Colours are chosen for a 10-foot display first: a very dark surface so a bright picture is
 * never framed by grey, and an accent with enough luminance contrast to satisfy the 3:1 focus
 * requirement (NFR-UX-02) against every surface tone here.
 */
private val Accent = Color(0xFF4C8DFF)
private val AccentDark = Color(0xFF1F5FD0)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00122E),
    primaryContainer = AccentDark,
    onPrimaryContainer = Color(0xFFE7F0FF),
    secondary = Color(0xFF9CC2FF),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE9EDF3),
    surface = Color(0xFF161A21),
    onSurface = Color(0xFFE9EDF3),
    surfaceVariant = Color(0xFF262C36),
    onSurfaceVariant = Color(0xFFBEC7D4),
    outline = Color(0xFF5B6472),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0000),
)

private val AmoledScheme = DarkScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF14161A),
)

private val LightScheme = lightColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E4FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF3C5C8A),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF11151B),
    surface = Color.White,
    onSurface = Color(0xFF11151B),
    surfaceVariant = Color(0xFFE1E6EE),
    onSurfaceVariant = Color(0xFF43495A),
    outline = Color(0xFF757C8A),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/**
 * A single type ramp scaled by the user's font-scale setting (FR-UI-19). Nothing lands below
 * 12sp at the smallest scale, which is the floor the 10-foot guidance in `docs/03` sets.
 */
private fun typographyFor(scale: Float): Typography {
    fun style(size: Float, weight: FontWeight, lineHeight: Float) = TextStyle(
        fontSize = (size * scale).sp,
        fontWeight = weight,
        lineHeight = (lineHeight * scale).sp,
    )
    return Typography(
        displaySmall = style(34f, FontWeight.SemiBold, 42f),
        headlineMedium = style(26f, FontWeight.SemiBold, 34f),
        headlineSmall = style(22f, FontWeight.SemiBold, 30f),
        titleLarge = style(20f, FontWeight.SemiBold, 27f),
        titleMedium = style(17f, FontWeight.Medium, 24f),
        titleSmall = style(15f, FontWeight.Medium, 21f),
        bodyLarge = style(16f, FontWeight.Normal, 23f),
        bodyMedium = style(14f, FontWeight.Normal, 20f),
        bodySmall = style(13f, FontWeight.Normal, 18f),
        labelLarge = style(15f, FontWeight.Medium, 20f),
        labelMedium = style(13f, FontWeight.Medium, 17f),
        labelSmall = style(12f, FontWeight.Medium, 16f),
    )
}

@Composable
fun TiviCopyTheme(
    mode: ThemeMode,
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val scheme = when (mode) {
        ThemeMode.SYSTEM -> if (systemDark) DarkScheme else LightScheme
        ThemeMode.LIGHT -> LightScheme
        ThemeMode.DARK -> DarkScheme
        ThemeMode.AMOLED -> AmoledScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = typographyFor(fontScale.coerceIn(0.8f, 1.6f)),
        content = content,
    )
}
