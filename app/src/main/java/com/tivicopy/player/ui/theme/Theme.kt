package com.tivicopy.player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tivicopy.player.data.prefs.ThemeMode

/**
 * The Studio palette (ADR-027), from `docs/design/studio/studio.css`. One name per `--st-*` token.
 *
 * Night blue rather than neutral black: the ground is `#080C14`, and the surfaces above it climb
 * in the same hue (`surface`, `card`, `card-focus`) so a bright picture is framed by something
 * darker than itself without the chrome reading as a hole in the screen.
 *
 * Two lights carry all the meaning, as on a studio floor:
 *
 * - **tally** (red) — what is on screen or playing now;
 * - **cue** (amber) — on a television, where the remote is. It is the *only* focus colour. On a
 *   phone, which has no remote, it marks what is active or selected instead.
 *
 * **next** (pale blue) is what comes after the current programme.
 */
@Immutable
data class StudioColors(
    val ground: Color,
    val surface: Color,
    val card: Color,
    val cardFocus: Color,
    /** A guide cell that is airing now. */
    val airing: Color,
    /** A guide cell still to come. */
    val future: Color,
    /** A guide cell that has already aired. */
    val past: Color,
    /** A button resting over video. */
    val control: Color,
    /** A panel over video. */
    val panel: Color,
    /** The open side menu. */
    val drawer: Color,
    /** The closed side menu's strip. */
    val rail: Color,
    /** What the open side menu lays over the screen behind it. */
    val dimOpen: Color,
    val scrim: Color,
    val hairline: Color,
    val hairlineSoft: Color,
    /** The unfilled part of a progress bar or a switch. */
    val track: Color,
    val halo: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val next: Color,
    val cue: Color,
    val onCue: Color,
    val tally: Color,
    val onTally: Color,
    /** The fill of the card for the channel that is on screen: dark, in the tally's hue. */
    val onScreen: Color,
    val tallyGlow: Color,
    val error: Color,
    val ok: Color,
    /** The four tones the radio's shifting background cycles through (anti burn-in). */
    val ambient: List<Color>,
    val equalizerBar: Color,
)

private val Studio = StudioColors(
    ground = Color(0xFF080C14),
    surface = Color(0xFF0F1624),
    card = Color(0xFF172033),
    cardFocus = Color(0xFF26365A),
    airing = Color(0xFF1C2840),
    future = Color(0xFF131B2B),
    past = Color(0xFF0D131E),
    control = Color(0xE0172033),
    panel = Color(0xE60F1624),
    drawer = Color(0xF70C121E),
    rail = Color(0xDB0C121E),
    dimOpen = Color(0x8C080C14),
    scrim = Color(0xD1080C14),
    hairline = Color(0x24FFFFFF),
    hairlineSoft = Color(0x12FFFFFF),
    track = Color(0x2EFFFFFF),
    halo = Color(0x6B4670C4),
    ink = Color(0xFFF3F5F9),
    ink2 = Color(0xFFAEB8C8),
    ink3 = Color(0xFF8591A5),
    next = Color(0xFF9DC0F7),
    cue = Color(0xFFFFB547),
    onCue = Color(0xFF140E00),
    tally = Color(0xFFFF5A5F),
    onTally = Color(0xFF1A0505),
    onScreen = Color(0xFF2B1B27),
    tallyGlow = Color(0x61FF5A5F),
    error = Color(0xFFFF9C9C),
    ok = Color(0xFF7FD8A6),
    ambient = listOf(Color(0xFF0B1424), Color(0xFF08191C), Color(0xFF160F22), Color(0xFF1A120C)),
    equalizerBar = Color(0xE0F3F5F9),
)

/**
 * Studio on a true-black ground, for OLED panels (ADR-028). Only the ground and the surfaces that
 * sit flat on it change; the cards keep their blue so focus and the tally still read the same.
 */
private val StudioAmoled = Studio.copy(
    ground = Color(0xFF000000),
    surface = Color(0xFF070A10),
    past = Color(0xFF05080D),
    scrim = Color(0xD1000000),
    dimOpen = Color(0x8C000000),
)

/**
 * The Material roles, mapped from Studio as `docs/design/studio/MIGRATION.md` lays out, so every
 * screen that still reads `MaterialTheme.colorScheme` moves to the new palette at once.
 *
 * `primary` is the cue: every focus treatment in `ui/common/Focus.kt` is drawn from it, and cue is
 * the one focus colour. `secondary` is next. The container ramp follows the surface steps.
 * `primaryContainer` and the error container are not Studio tokens; they are the cue and the error
 * colour darkened into the same night-blue range, for the few Material components that ask.
 */
private fun schemeFor(s: StudioColors) = darkColorScheme(
    primary = s.cue,
    onPrimary = s.onCue,
    primaryContainer = Color(0xFF4A3613),
    onPrimaryContainer = Color(0xFFFFE2B8),
    inversePrimary = Color(0xFF7A5200),
    secondary = s.next,
    onSecondary = s.ground,
    secondaryContainer = s.cardFocus,
    onSecondaryContainer = s.ink,
    tertiary = s.next,
    onTertiary = s.ground,
    background = s.ground,
    onBackground = s.ink,
    surface = s.surface,
    onSurface = s.ink,
    surfaceVariant = s.cardFocus,
    onSurfaceVariant = s.ink2,
    // Material tints a raised surface with this. Left at its default it would be `primary` — an
    // amber wash on every elevated sheet — so it is pinned to the surface family instead.
    surfaceTint = s.cardFocus,
    inverseSurface = s.ink,
    inverseOnSurface = s.ground,
    error = s.error,
    onError = s.onTally,
    errorContainer = Color(0xFF3A1519),
    onErrorContainer = Color(0xFFFFD9D9),
    outline = s.ink3,
    outlineVariant = s.hairline,
    scrim = s.ground,
    surfaceBright = s.cardFocus,
    surfaceDim = s.ground,
    surfaceContainerLowest = s.ground,
    surfaceContainerLow = s.surface,
    surfaceContainer = s.card,
    surfaceContainerHigh = s.airing,
    surfaceContainerHighest = s.cardFocus,
)

private val StudioScheme = schemeFor(Studio)
private val StudioAmoledScheme = schemeFor(StudioAmoled)

/**
 * The canvas's corner radii (`--st-r-*`) as Material's ramp, so a stock Material component — a
 * dialog, a menu — lands on a Studio corner without knowing which shell it is in. The television's
 * are the canvas's 1080p pixels halved; the phone's are as drawn. The app's own surfaces read the
 * same numbers by name from `LocalTvMetrics`.
 */
private fun shapesFor(television: Boolean) = if (television) {
    Shapes(
        extraSmall = RoundedCornerShape(5.dp), // badge
        small = RoundedCornerShape(6.dp), // card, chip
        medium = RoundedCornerShape(7.dp), // control
        large = RoundedCornerShape(12.dp), // panel
        extraLarge = RoundedCornerShape(12.dp), // panel, menu
    )
} else {
    Shapes(
        extraSmall = RoundedCornerShape(8.dp), // badge
        small = RoundedCornerShape(10.dp), // card
        medium = RoundedCornerShape(12.dp), // control
        large = RoundedCornerShape(20.dp), // panel
        extraLarge = RoundedCornerShape(24.dp), // sheet, menu
    )
}

private val LocalStudioColors = staticCompositionLocalOf { Studio }
private val LocalStudioType = staticCompositionLocalOf { studioTypeFor(television = true, scale = 1f) }

/**
 * The parts of the Studio language Material has no role for: the two lights, the guide's
 * airing/future/past cells, the overlays, the ambient tones, and the ramp by its canvas names.
 */
object StudioTheme {
    val colors: StudioColors
        @Composable @ReadOnlyComposable
        get() = LocalStudioColors.current

    val type: StudioType
        @Composable @ReadOnlyComposable
        get() = LocalStudioType.current
}

/**
 * @param mode dark or AMOLED; Studio is drawn dark only (ADR-028)
 * @param fontScale the user's font-scale setting (FR-UI-19)
 * @param television which shell is showing. The canvas draws a ten-foot ramp and a touch ramp, and
 * they are not one ramp at two scales — on a television the body is smaller than a navigation
 * label, on a phone it is larger.
 */
@Composable
fun TiviCopyTheme(
    mode: ThemeMode,
    fontScale: Float,
    television: Boolean,
    content: @Composable () -> Unit,
) {
    val (colors, scheme) = when (mode) {
        ThemeMode.DARK -> Studio to StudioScheme
        ThemeMode.AMOLED -> StudioAmoled to StudioAmoledScheme
    }
    val scale = fontScale.coerceIn(0.8f, 1.6f)
    val type = remember(television, scale) { studioTypeFor(television, scale) }
    val typography = remember(type) { typographyFor(type) }
    val shapes = remember(television) { shapesFor(television) }
    CompositionLocalProvider(
        LocalStudioColors provides colors,
        LocalStudioType provides type,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}
