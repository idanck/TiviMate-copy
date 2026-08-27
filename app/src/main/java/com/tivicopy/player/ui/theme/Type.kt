package com.tivicopy.player.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tivicopy.player.R

/**
 * The app's own typeface, and the one type ramp every screen draws with.
 *
 * ## Why the app carries a font at all
 *
 * A teardown of Cellcom TV (`docs/adr/ADR-019`) found the single thing that separates it most
 * from a stock Android TV app is that it does not use the system font: it ships a six-weight
 * brand family, *Rag Sans CellcomTV 2022*, and every screen — hero titles, rail headings, the
 * EPG grid — is set in it. Until now this app used whatever the device's Hebrew default was,
 * which on Android TV is Roboto plus a fallback face for Hebrew: two designs in one line of
 * text, and neither chosen.
 *
 * Rag Sans is a licensed brand face and is not for sale to us, so this is **Rubik** — a
 * geometric Hebrew/Latin sans by Hubert & Fischer and Meir Sadan, published under the SIL Open
 * Font License 1.1 (`docs/licenses/Rubik-OFL.txt`), which is the closest free family in the same
 * genre and the one Hebrew UI face with a full working weight range. Latin and Hebrew come from
 * the same designer here, so a channel name in English and a programme title in Hebrew on the
 * same row are finally one typeface.
 *
 * ## Why four static files rather than the variable font
 *
 * Rubik ships as a `wght` variable font. Compose only applies `FontVariation` settings on API 26
 * and up, and this app's floor is API 23 (ADR-001) — a variable file there would render every
 * weight at the axis default (Light), which is exactly the wrong failure on a screen read from
 * three metres away. So the four weights the ramp actually asks for are instanced ahead of time
 * with `fontTools`, subset to Latin + Hebrew + punctuation (~93 KB each, ~370 KB in the APK).
 *
 * Anything Rubik has no glyph for — the ▶ in a playback label, an emoji in a channel name —
 * still falls back to the platform font per character, as it always did.
 */
private val Rubik = FontFamily(
    Font(R.font.rubik_regular, FontWeight.Normal),
    Font(R.font.rubik_medium, FontWeight.Medium),
    Font(R.font.rubik_semibold, FontWeight.SemiBold),
    Font(R.font.rubik_bold, FontWeight.Bold),
)

/**
 * A single type ramp scaled by the user's font-scale setting (FR-UI-19). Nothing lands below
 * 12sp at the smallest scale, which is the floor the 10-foot guidance in `docs/03` sets.
 *
 * The weights follow what the teardown found the benchmark doing: Bold for the things read
 * first from across a room (the hero, section headings), Medium for interface furniture (rail
 * labels, tile titles, clocks), Regular for anything that is a sentence. Nothing is set lighter
 * than Regular — Cellcom TV bundles a Light and an ExtraLight, but it draws them over its own
 * artwork, and this app's small text sits over live video where thin strokes lose to NFR-UX-03.
 */
internal fun typographyFor(scale: Float): Typography {
    fun style(size: Float, weight: FontWeight, lineHeight: Float) = TextStyle(
        fontFamily = Rubik,
        fontSize = (size * scale).sp,
        fontWeight = weight,
        lineHeight = (lineHeight * scale).sp,
    )
    return Typography(
        displaySmall = style(34f, FontWeight.Bold, 42f),
        headlineMedium = style(26f, FontWeight.Bold, 34f),
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
