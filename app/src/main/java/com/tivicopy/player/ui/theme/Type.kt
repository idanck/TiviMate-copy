package com.tivicopy.player.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tivicopy.player.R

/**
 * The app's typeface, and the type ramp every screen draws with (ADR-046).
 *
 * ## One family
 *
 * Merhav (`docs/design/merhav/`) sets everything in **Noto Sans Hebrew** — the screen heading,
 * the programme title over the picture, the guide's smallest time label. Studio's division of
 * labour between a condensed display face for titles and a text face for the rest (ADR-027) is
 * gone: what was Karantina's job is the same family at 800, and the canvas's "display" voice is a
 * weight, not a second font. Noto Sans Hebrew is published by Google under the SIL Open Font
 * License 1.1 (`docs/licenses/NotoSansHebrew-OFL.txt`), and is also the Hebrew face Android itself
 * falls back to, so an app string and a system string next to it now agree.
 *
 * ## Why five static files, not the variable font
 *
 * `google/fonts` ships Noto Sans Hebrew only as a variable font (`wght` 100–900, `wdth` 62.5–100).
 * The five weights the canvas uses (400, 500, 600, 700, 800) are cut from it as static instances
 * with `fontTools.varLib.instancer` at `wdth` 100, and subset to Latin + Hebrew + punctuation —
 * exactly ADR-019's method for Rubik, for ADR-019's reason: Compose applies `FontVariation` only from
 * API 26, and this app's floor is API 23 (ADR-001), where a variable file draws every weight at its
 * axis default, which for this font is Thin. Each weight is about 44 KB; the five come to about
 * 220 KB, half of the Plex and Karantina set they replace.
 *
 * Noto has no Reserved Font Name, so the instanced, subset files keep the family's name.
 *
 * Its figures are tabular in every weight (each digit has the same advance), which is what the
 * canvas's `.st-num` asks for on times: a clock does not jitter as its minutes change, with no
 * font feature to switch on.
 *
 * Anything the subset has no glyph for — the ▶ in a playback label, an emoji in a channel name —
 * still falls back per character to the platform font.
 */
private val NotoSansHebrew = FontFamily(
    Font(R.font.noto_sans_hebrew_regular, FontWeight.Normal),
    Font(R.font.noto_sans_hebrew_medium, FontWeight.Medium),
    Font(R.font.noto_sans_hebrew_semibold, FontWeight.SemiBold),
    Font(R.font.noto_sans_hebrew_bold, FontWeight.Bold),
    Font(R.font.noto_sans_hebrew_extrabold, FontWeight.ExtraBold),
)

/**
 * The ramp by name, as the canvas names it (`.st-t-*` in `docs/design/merhav/canvas/studio.css`).
 *
 * The Material [Typography] the screens read is derived from this (see [typographyFor]); the
 * app's own components read these directly, through `StudioTheme.type`.
 */
@Immutable
data class StudioType(
    /** 800. The programme title over the home banner. */
    val hero: TextStyle,
    /** 800. A programme title in the lower third. */
    val title: TextStyle,
    /** 700. A programme title on a card, a panel's heading, and the app's name in the top bar. */
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

/**
 * One step of the ramp: a size in sp, a weight, a line height as a multiple of the size, and the
 * canvas's tracking in em (negative on the three display steps, nothing elsewhere).
 */
private class Step(val size: Float, val weight: FontWeight, val lineHeight: Float, val tracking: Float = 0f)

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
 * for this face. The row label is `ListRow`'s 26px label, which the canvas sets inline rather than
 * as a step of its own.
 */
private val TelevisionRamp = Ramp(
    hero = Step(44f, FontWeight.ExtraBold, 1.05f, -0.015f),
    title = Step(28f, FontWeight.ExtraBold, 1.1f, -0.01f),
    titleSmall = Step(20f, FontWeight.Bold, 1.15f, -0.005f),
    h1 = Step(17f, FontWeight.Bold, 1.2f),
    action = Step(14f, FontWeight.SemiBold, 1.3f),
    nav = Step(14f, FontWeight.Medium, 1.3f),
    rowLabel = Step(13f, FontWeight.SemiBold, 1.3f),
    body = Step(12f, FontWeight.Medium, 1.4f),
    meta = Step(11f, FontWeight.Normal, 1.4f),
    caption = Step(10f, FontWeight.Medium, 1.3f),
)

/** The touch ramp, from `studio-phone.css` (where a pixel is a dp). Never below 12. */
private val PhoneRamp = Ramp(
    hero = Step(32f, FontWeight.ExtraBold, 1.05f, -0.01f),
    title = Step(26f, FontWeight.ExtraBold, 1.1f, -0.01f),
    titleSmall = Step(22f, FontWeight.Bold, 1.15f, -0.005f),
    h1 = Step(18f, FontWeight.Bold, 1.2f),
    action = Step(16f, FontWeight.SemiBold, 1.3f),
    nav = Step(12f, FontWeight.SemiBold, 1.3f),
    rowLabel = Step(16f, FontWeight.SemiBold, 1.3f),
    body = Step(16f, FontWeight.Medium, 1.4f),
    meta = Step(14f, FontWeight.Normal, 1.4f),
    caption = Step(13f, FontWeight.Medium, 1.3f),
)

/**
 * The ramp for one shell, scaled by the user's font-scale setting (FR-UI-19).
 *
 * @param television which of the two canvas ramps to use; the shells are picked the same way
 * @param scale the font-scale setting, already clamped by the caller
 */
internal fun studioTypeFor(television: Boolean, scale: Float): StudioType {
    val ramp = if (television) TelevisionRamp else PhoneRamp
    fun style(step: Step) = TextStyle(
        fontFamily = NotoSansHebrew,
        fontSize = (step.size * scale).sp,
        fontWeight = step.weight,
        lineHeight = (step.size * step.lineHeight * scale).sp,
        letterSpacing = step.tracking.em,
    )
    return StudioType(
        hero = style(ramp.hero),
        title = style(ramp.title),
        titleSmall = style(ramp.titleSmall),
        h1 = style(ramp.h1),
        action = style(ramp.action),
        nav = style(ramp.nav),
        rowLabel = style(ramp.rowLabel),
        body = style(ramp.body),
        meta = style(ramp.meta),
        caption = style(ramp.caption),
    )
}

/**
 * The Material roles, each pointed at one step, so every screen that still reads
 * `MaterialTheme.typography` moves to the ramp without a call site changing. With one family the
 * display roles are the display steps themselves; in this codebase they set the few clocks and
 * readouts that use them.
 */
internal fun typographyFor(type: StudioType): Typography = Typography(
    displayLarge = type.hero,
    displayMedium = type.title,
    displaySmall = type.titleSmall,
    headlineLarge = type.titleSmall,
    headlineMedium = type.titleSmall,
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
