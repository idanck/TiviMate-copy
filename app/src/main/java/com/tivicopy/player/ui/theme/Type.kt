package com.tivicopy.player.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tivicopy.player.R

/**
 * The app's two typefaces, and the type ramp every screen draws with (ADR-027).
 *
 * ## Two faces, with a strict division of labour
 *
 * The Studio language (`docs/design/studio/`) is broadcast graphics: a lower third, a tally light,
 * a programme title set the way a channel sets it on air. That title voice is **Karantina** — a
 * condensed Hebrew/Latin display face by Rony Koch, one weight (Bold) — and it is used for exactly
 * two things: programme titles and the app's own name. Everything else, from a screen heading to
 * the guide's smallest time label, is **IBM Plex Sans Hebrew** (Mike Abbink, Bold Monday). Both
 * are published under the SIL Open Font License 1.1 (`docs/licenses/`).
 *
 * Karantina is only reachable through [StudioType] ([StudioType.hero], [StudioType.title],
 * [StudioType.titleSmall]). The Material roles are all Plex, *including* the display roles: in this
 * codebase those are what the clocks are set in, and a condensed display face on a clock is the
 * wrong voice for a number read at a glance.
 *
 * ## Why the files are the upstream files, untouched
 *
 * Both families ship from `google/fonts` as static TrueType instances, so nothing here needed the
 * variable-font instancing Rubik did (ADR-019 — Compose ignores `FontVariation` below API 26). They
 * are also not subset: IBM Plex carries a Reserved Font Name, and under the OFL a subset is a
 * Modified Version that may not keep the name. At roughly 96 KB per Plex weight and 48 KB for
 * Karantina the whole set is about 435 KB — close to the 370 KB of Rubik it replaces.
 *
 * Plex Sans Hebrew's figures are already tabular (every digit has the same advance), which is what
 * the canvas's `.st-num` asks for on times — so a clock does not jitter as its minutes change, with
 * no font feature to switch on.
 *
 * Anything neither face has a glyph for — the ▶ in a playback label, an emoji in a channel name —
 * still falls back per character to the platform font.
 */
private val PlexSansHebrew = FontFamily(
    Font(R.font.ibm_plex_sans_hebrew_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_hebrew_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_hebrew_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_hebrew_bold, FontWeight.Bold),
)

private val Karantina = FontFamily(
    Font(R.font.karantina_bold, FontWeight.Bold),
)

/**
 * The Studio ramp by name, as the canvas names it (`.st-t-*` in `docs/design/studio/studio.css`).
 *
 * The Material [Typography] the screens read today is derived from this (see [typographyFor]); new
 * Studio components read these directly, through `Studio.type`.
 */
@Immutable
data class StudioType(
    /** Karantina. The programme title over the hero banner. */
    val hero: TextStyle,
    /** Karantina. A programme title in the lower third. */
    val title: TextStyle,
    /** Karantina. A programme title on a card, and the app's name in the top bar. */
    val titleSmall: TextStyle,
    /** A shelf or screen heading. */
    val h1: TextStyle,
    /** The label on a button. */
    val action: TextStyle,
    /** A navigation entry: the side menu on a television, the tab bar on a phone. */
    val nav: TextStyle,
    /** The first line of a list row (the canvas's `ListRow` / `PListRow` label). */
    val rowLabel: TextStyle,
    /** Body text, and a card's title. */
    val body: TextStyle,
    /** Secondary information and times. */
    val meta: TextStyle,
    /** The smallest text: badges, hints, a row's second line. */
    val caption: TextStyle,
)

/** One step of the ramp: a size in sp, a weight, and a line height as a multiple of the size. */
private class Step(val size: Float, val weight: FontWeight, val lineHeight: Float)

private class Ramp(
    val hero: Step,
    val title: Step,
    val titleSmall: Step,
    val h1: Step,
    val action: Step,
    val nav: Step,
    val rowLabel: Step,
    val body: Step,
    val meta: Step,
    val caption: Step,
)

/**
 * The ten-foot ramp: the canvas's 1080p pixel values halved, because a 1080p television is
 * 960×540dp. These are the canvas's sizes exactly, including caption at 10sp and meta at 11sp —
 * below the 12sp floor `docs/03` used to set. That floor is lifted by ADR-029 until the sizes have
 * been checked on a real panel; if they read too small, this table is the one place to change.
 *
 * Steps with no line height in the canvas (action, nav, the row label) take 1.3, the CSS `normal`
 * for these faces.
 */
private val TelevisionRamp = Ramp(
    hero = Step(70f, FontWeight.Bold, 0.9f),
    title = Step(54f, FontWeight.Bold, 0.9f),
    titleSmall = Step(32f, FontWeight.Bold, 1.0f),
    h1 = Step(17f, FontWeight.Bold, 1.2f),
    action = Step(15f, FontWeight.Bold, 1.3f),
    nav = Step(14f, FontWeight.Medium, 1.3f),
    rowLabel = Step(13f, FontWeight.SemiBold, 1.3f),
    body = Step(12f, FontWeight.Medium, 1.35f),
    meta = Step(11f, FontWeight.Normal, 1.35f),
    caption = Step(10f, FontWeight.Medium, 1.3f),
)

/** The touch ramp, from `studio-phone.css` (where a pixel is a dp). */
private val PhoneRamp = Ramp(
    hero = Step(48f, FontWeight.Bold, 0.9f),
    title = Step(40f, FontWeight.Bold, 0.9f),
    titleSmall = Step(32f, FontWeight.Bold, 1.0f),
    h1 = Step(20f, FontWeight.Bold, 1.2f),
    action = Step(16f, FontWeight.Bold, 1.3f),
    nav = Step(12f, FontWeight.SemiBold, 1.3f),
    rowLabel = Step(16f, FontWeight.SemiBold, 1.3f),
    body = Step(16f, FontWeight.Medium, 1.35f),
    meta = Step(14f, FontWeight.Normal, 1.35f),
    caption = Step(13f, FontWeight.Medium, 1.3f),
)

/**
 * The Studio ramp for one shell, scaled by the user's font-scale setting (FR-UI-19).
 *
 * @param television which of the two canvas ramps to use; the shells are picked the same way
 * @param scale the font-scale setting, already clamped by the caller
 */
internal fun studioTypeFor(television: Boolean, scale: Float): StudioType {
    val ramp = if (television) TelevisionRamp else PhoneRamp
    fun style(step: Step, family: FontFamily) = TextStyle(
        fontFamily = family,
        fontSize = (step.size * scale).sp,
        fontWeight = step.weight,
        lineHeight = (step.size * step.lineHeight * scale).sp,
    )
    return StudioType(
        hero = style(ramp.hero, Karantina),
        title = style(ramp.title, Karantina),
        titleSmall = style(ramp.titleSmall, Karantina),
        h1 = style(ramp.h1, PlexSansHebrew),
        action = style(ramp.action, PlexSansHebrew),
        nav = style(ramp.nav, PlexSansHebrew),
        rowLabel = style(ramp.rowLabel, PlexSansHebrew),
        body = style(ramp.body, PlexSansHebrew),
        meta = style(ramp.meta, PlexSansHebrew),
        caption = style(ramp.caption, PlexSansHebrew),
    )
}

/**
 * The Material roles, each pointed at one Studio step, so every existing screen moves to the new
 * ramp at once without a call site changing.
 *
 * The display and large headline roles take a Karantina step's *size* in Plex (see the file
 * comment), with a line height of 1.1 rather than Karantina's 0.9 — Plex is not condensed, and its
 * Hebrew ascenders need the room. Programme titles move to Karantina where each screen is redrawn
 * in Studio, by reading [StudioType] instead of these roles.
 */
internal fun typographyFor(type: StudioType): Typography {
    fun plexDisplay(step: TextStyle) = step.copy(
        fontFamily = PlexSansHebrew,
        lineHeight = step.fontSize * 1.1f,
    )
    return Typography(
        displayLarge = plexDisplay(type.hero),
        displayMedium = plexDisplay(type.title),
        displaySmall = plexDisplay(type.titleSmall),
        headlineLarge = plexDisplay(type.titleSmall),
        headlineMedium = plexDisplay(type.titleSmall),
        headlineSmall = type.h1,
        titleLarge = type.h1,
        titleMedium = type.rowLabel,
        titleSmall = type.body.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = type.body,
        bodyMedium = type.body,
        bodySmall = type.meta,
        labelLarge = type.action,
        labelMedium = type.meta.copy(fontWeight = FontWeight.Medium),
        labelSmall = type.caption,
    )
}
