package com.idanck.shidur.ui.theme

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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.idanck.shidur.data.prefs.ThemeMode
import com.idanck.shidur.ui.common.LocalReducedMotion
import com.idanck.shidur.ui.common.rememberReducedMotion

/**
 * The Merhav palette (ADR-046), from the canvas's `studio.css`. One name per `--st-*` token; the
 * names are Studio's (ADR-027), kept so every screen that reads them moved with the tokens.
 *
 * **The picture is the environment.** The ground is near-black (`#05070C`) and everything above
 * it is glass: *smoky* — dark tints at partial opacity, never milky white, which is what keeps the
 * secondary ink at 4.5:1 over the brightest part of a frame. Three thicknesses:
 *
 * - [card] — thin glass (`st-glass-thin`): rows, tiles, chips and buttons at rest;
 * - [surface] — glass (`st-glass`): panels;
 * - [panel] — thick glass (`st-glass-thick`, `st-panel`): dialogs, sheets, big panels.
 *
 * Where nothing really blurs what is behind (most of the app — see `ui/common/Glass.kt`), a panel
 * over the picture takes [glassFallback] instead, with no change of layout.
 *
 * **Four colours carry meaning**, and everything else is in the three inks:
 *
 * - **cue** — on a television, the *lit surface* (`#F4F6FB`) that focus turns a control into, with
 *   [onCue] text on it. On a phone, which has no focus, the cue is amber and marks what is active.
 * - **current** (amber `#FFB547`) — where you are and what is chosen: the current screen in the
 *   side menu, the selected day, chip or segment, the chosen value. Never a status, a category, a
 *   favourite, loading or a message.
 * - **tally** (red) — only what is on screen, live, or recording now, and always with a word.
 * - **next** (pale blue) — what comes after the current programme.
 */
@Immutable
data class StudioColors(
    val ground: Color,
    /** Glass (`--st-surface`): a panel's material. */
    val surface: Color,
    /** Thin glass (`--st-card`): rows, tiles and chips at rest. */
    val card: Color,
    /** Lit glass (`--st-card-focus`): a content tile under focus; its text stays white. */
    val cardFocus: Color,
    /** A guide cell that is airing now. */
    val airing: Color,
    /** A guide cell still to come. */
    val future: Color,
    /** A guide cell that has already aired. */
    val past: Color,
    /** A button resting over video: thin glass. */
    val control: Color,
    /** Thick glass (`--st-panel`): dialogs and sheets. */
    val panel: Color,
    /** The drawer's solid tone (`--st-drawer`); its fade towards the content is `Glass.kt`'s. */
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
    /** The fill of whatever is on screen: a faint wash of the tally. */
    val onScreen: Color,
    val tallyGlow: Color,
    val error: Color,
    val ok: Color,
    /** The four tones the radio's shifting background cycles through (anti burn-in). */
    val ambient: List<Color>,
    val equalizerBar: Color,
    /** The screensaver's ground: actually black, so an OLED panel emits nothing across it. */
    val saverGround: Color,
    /**
     * The screensaver's three soft colours (`--st-saver-c1..c3`, ADR-037): cyan, violet and lime,
     * kept apart from every colour that means something (cue, current, tally, next, ok, error).
     */
    val saverTones: List<Color>,
    /** The selected or current thing on glass (`--st-glass-lit`), where amber would be too loud. */
    val glassLit: Color,
    /** Glass with nothing blurred behind it (`--st-glass-fallback`): nearly opaque, same layout. */
    val glassFallback: Color,
    /** The lit surface itself (`--st-light`), whatever the shell's cue is. */
    val light: Color,
    /** The top of a lit edge (`--st-edge`'s first line): light catching the glass's upper rim. */
    val edgeHighlight: Color,
    /** The rest of a lit edge (`--st-edge`'s second line): a faint line all the way round. */
    val edgeLine: Color,
    /** Amber: where you are and what is chosen (`--st-current`). */
    val current: Color,
    /** The amber wash under the current or chosen thing (`--st-current-glass`). */
    val currentGlass: Color,
    /** The amber line round it (`--st-current-edge`). */
    val currentEdge: Color,
    /** Text on a solid amber plate (`--st-on-current`). */
    val onCurrent: Color,
    /** The live badge's dark red glass (`--st-live-glass`). */
    val liveGlass: Color,
    /** The live badge's text (`--st-live-ink`). */
    val liveInk: Color,
    /** The live badge's red edge. */
    val liveEdge: Color,
)

/** The television's palette: focus is the lit surface. */
private val Merhav = StudioColors(
    ground = Color(0xFF05070C),
    surface = Color(0x8F161C2A), // rgba(22, 28, 42, 0.56)
    card = Color(0x6B222A3C), // rgba(34, 42, 60, 0.42)
    cardFocus = Color(0x38FFFFFF), // white 0.22
    airing = Color(0x29FFFFFF), // white 0.16
    future = Color(0x14FFFFFF), // white 0.08
    past = Color(0x0AFFFFFF), // white 0.04
    control = Color(0x6B222A3C),
    panel = Color(0xC2101420), // rgba(16, 20, 32, 0.76)
    drawer = Color(0xFF0C101A),
    rail = Color(0xA80E121C), // rgba(14, 18, 28, 0.66)
    dimOpen = Color(0x8005070C),
    scrim = Color(0xD105070C),
    hairline = Color(0x24FFFFFF),
    hairlineSoft = Color(0x12FFFFFF),
    track = Color(0x38FFFFFF),
    halo = Color(0x33FFFFFF),
    ink = Color(0xFFFFFFFF),
    ink2 = Color(0xCCECF1FA), // rgba(236, 241, 250, 0.80)
    ink3 = Color(0xA3ECF1FA), // rgba(236, 241, 250, 0.64)
    next = Color(0xFFA9C8FF),
    cue = Color(0xFFF4F6FB),
    onCue = Color(0xFF0A0F1C),
    tally = Color(0xFFFF5A5F),
    onTally = Color(0xFF1A0505),
    onScreen = Color(0x24FF5A5F), // tally 0.14
    tallyGlow = Color(0x61FF5A5F),
    error = Color(0xFFFF9C9C),
    ok = Color(0xFF7FD8A6),
    ambient = listOf(Color(0xFF0B1424), Color(0xFF08191C), Color(0xFF160F22), Color(0xFF1A120C)),
    equalizerBar = Color(0xE0F3F5F9),
    saverGround = Color(0xFF000000),
    saverTones = listOf(Color(0xFF5CC8E6), Color(0xFFB79CFF), Color(0xFFC6E37A)),
    glassLit = Color(0x29FFFFFF),
    glassFallback = Color(0xEB161B28), // rgba(22, 27, 40, 0.92)
    light = Color(0xFFF4F6FB),
    edgeHighlight = Color(0x3DFFFFFF), // white 0.24
    edgeLine = Color(0x1AFFFFFF), // white 0.10
    current = Color(0xFFFFB547),
    currentGlass = Color(0x29FFB547), // amber 0.16
    currentEdge = Color(0x59FFB547), // amber 0.35
    onCurrent = Color(0xFF140E00),
    liveGlass = Color(0x8C781C26), // rgba(120, 28, 38, 0.55)
    liveInk = Color(0xFFFFD6D7),
    liveEdge = Color(0x61FF5A5F), // tally 0.38
)

/**
 * The phone's palette (`studio-phone.css`): there is no focus, so the cue goes back to amber and
 * marks what is active or selected — the current tab, the primary action, a check, a switch that
 * is on. The same amber as "current" on a television, with the same meaning.
 */
private val MerhavPhone = Merhav.copy(
    cue = Merhav.current,
    onCue = Merhav.onCurrent,
)

/**
 * Merhav on a true-black ground, for OLED panels (ADR-028). Only the ground and what is mixed from
 * it go black; the glass keeps its tint, so focus, current and the tally read the same.
 */
private fun amoled(colors: StudioColors) = colors.copy(
    ground = Color(0xFF000000),
    scrim = Color(0xD1000000),
    dimOpen = Color(0x80000000),
)

/**
 * The Material roles, mapped from Merhav so every screen that still reads
 * `MaterialTheme.colorScheme` moves to the palette at once.
 *
 * Material wants opaque plates where it paints one itself (the root `Surface`), so its surface
 * roles are the glass *over the ground* — what the glass looks like with nothing but the ground
 * behind it — rather than the translucent tokens. `primary` is the cue: the lit surface on a
 * television, amber on a phone.
 */
private fun schemeFor(s: StudioColors) = darkColorScheme(
    primary = s.cue,
    onPrimary = s.onCue,
    primaryContainer = s.currentGlass.compositeOver(s.ground),
    onPrimaryContainer = s.current,
    inversePrimary = Color(0xFF7A5200),
    secondary = s.next,
    onSecondary = s.ground,
    secondaryContainer = s.cardFocus.compositeOver(s.ground),
    onSecondaryContainer = s.ink,
    tertiary = s.next,
    onTertiary = s.ground,
    background = s.ground,
    onBackground = s.ink,
    surface = s.surface.compositeOver(s.ground),
    onSurface = s.ink,
    surfaceVariant = s.cardFocus.compositeOver(s.ground),
    onSurfaceVariant = s.ink2,
    // Material tints a raised surface with this. Left at its default it would be `primary` — a
    // wash of the cue on every elevated sheet — so it is pinned to the glass instead.
    surfaceTint = s.surface.compositeOver(s.ground),
    inverseSurface = s.ink,
    inverseOnSurface = s.ground,
    error = s.error,
    onError = s.onTally,
    errorContainer = Color(0xFF3A1519),
    onErrorContainer = Color(0xFFFFD9D9),
    outline = s.ink3,
    outlineVariant = s.hairline,
    scrim = s.ground,
    surfaceBright = s.cardFocus.compositeOver(s.ground),
    surfaceDim = s.ground,
    surfaceContainerLowest = s.ground,
    surfaceContainerLow = s.surface.compositeOver(s.ground),
    surfaceContainer = s.card.compositeOver(s.ground),
    surfaceContainerHigh = s.glassFallback.compositeOver(s.ground),
    surfaceContainerHighest = s.cardFocus.compositeOver(s.ground),
)

/**
 * The canvas's corner radii (`--st-r-*`) as Material's ramp, so a component that reads the theme
 * lands on a Merhav corner without knowing which shell it is in. The television's are the
 * canvas's 1080p pixels halved; the phone's are as drawn. The app's own surfaces read the same
 * numbers by name from `LocalTvMetrics`.
 */
private fun shapesFor(television: Boolean) = if (television) {
    Shapes(
        extraSmall = RoundedCornerShape(percent = 50), // badge: a pill
        small = RoundedCornerShape(10.dp), // tile (`--st-r-tile` 20px)
        medium = RoundedCornerShape(11.dp), // control: a field, a key (`--st-r-control` 22px)
        large = RoundedCornerShape(22.dp), // panel, dialog (`--st-r-panel` 44px)
        extraLarge = RoundedCornerShape(22.dp), // panel, menu
    )
} else {
    Shapes(
        extraSmall = RoundedCornerShape(percent = 50), // badge: a pill
        small = RoundedCornerShape(14.dp), // tile
        medium = RoundedCornerShape(16.dp), // control
        large = RoundedCornerShape(28.dp), // panel, dialog
        extraLarge = RoundedCornerShape(28.dp), // sheet, menu
    )
}

private val LocalStudioColors = staticCompositionLocalOf { Merhav }
private val LocalStudioType = staticCompositionLocalOf { studioTypeFor(television = true, scale = 1f) }
private val LocalStudioTelevision = staticCompositionLocalOf { false }

/**
 * The parts of the language Material has no role for: current, the tally, next, the glass, the
 * guide's airing/future/past cells, the overlays, the ambient tones, and the ramp by its canvas
 * names.
 */
object StudioTheme {
    val colors: StudioColors
        @Composable @ReadOnlyComposable
        get() = LocalStudioColors.current

    val type: StudioType
        @Composable @ReadOnlyComposable
        get() = LocalStudioType.current

    /**
     * Which shell is showing. The base components (`ui/common/StudioControls.kt`) are shared by
     * both shells and draw the canvas's ten-foot or touch version from it — a dialog opened from
     * a shared screen has to know without being told by every caller. Unlike `LocalTvMetrics`,
     * which only the television shell's root provides, this is set above both shells, so it is
     * also right inside the prompts `MainActivity` draws over them.
     */
    val television: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalStudioTelevision.current
}

/**
 * @param mode dark or AMOLED; Merhav is drawn dark only (ADR-028)
 * @param fontScale the user's font-scale setting (FR-UI-19)
 * @param television which shell is showing. The canvas draws a ten-foot ramp and a touch ramp, and
 * they are not one ramp at two scales — on a television the body is smaller than a navigation
 * label, on a phone it is larger. It also decides what the cue is: the lit surface or amber.
 */
@Composable
fun TiviCopyTheme(
    mode: ThemeMode,
    fontScale: Float,
    television: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = remember(mode, television) {
        val shell = if (television) Merhav else MerhavPhone
        when (mode) {
            ThemeMode.DARK -> shell
            ThemeMode.AMOLED -> amoled(shell)
        }
    }
    val scheme = remember(colors) { schemeFor(colors) }
    val scale = fontScale.coerceIn(0.8f, 1.6f)
    val type = remember(television, scale) { studioTypeFor(television, scale) }
    val typography = remember(type) { typographyFor(type) }
    val shapes = remember(television) { shapesFor(television) }
    val reducedMotion = rememberReducedMotion()
    CompositionLocalProvider(
        LocalStudioColors provides colors,
        LocalStudioType provides type,
        LocalStudioTelevision provides television,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}
