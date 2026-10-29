package com.tivicopy.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tivicopy.player.ui.theme.StudioTheme

/**
 * What focus looks like, defined once for the whole app.
 *
 * ADR-023 gave the shell two shapes and told the channel list to take its corner and its ring
 * from [TvMetrics]. It stopped there, and an audit of every focusable thing in the app found
 * that most of the shell had never been part of that: five different focused fills, three ring
 * widths on the same television, and one tile that drew its resting ring in `Color.Transparent`
 * — which is transparent *black* — while jumping its border from 0dp to 3dp, moving its own
 * contents on focus. ADR-025 made it one function, [focusSurface], and everything focusable goes
 * through it. It takes the ring width from [LocalTvMetrics], so no call site needs to know which
 * shell it is on, and it crosses colours rather than cutting them (`Motion.kt`).
 *
 * ## Merhav's two treatments (ADR-046)
 *
 * Focus on a television is light, and there are exactly two ways it lands:
 *
 * - **A control lights up.** Buttons, chips, keys, menu rows and list rows that act like menu
 *   items become the *lit surface* (`--st-cue`, `#F4F6FB`) with dark content, lift and grow
 *   (1.06; 1.03 for a row the width of its panel). [FocusFill.FILLED] and [FocusFill.LIT].
 * - **A content tile is lit glass in a white ring.** Channel cards, posters, guide cells, station
 *   discs and the channel list's rows: the glass brightens (`--st-card-focus`), a white ring
 *   (`--st-ring`) closes round it, it lifts and grows, and its text stays white — the picture or the
 *   programme is the thing being chosen, and darkening it would hide it. [FocusFill.TINT],
 *   [FocusFill.RAISE] and, for artwork with nothing painted behind, [FocusFill.NONE].
 *
 * **Current is amber**, never focus: what is *chosen* while focus is elsewhere — the selected day,
 * chip or segment, the value in a list — is a wash of amber with an amber edge and amber text
 * (`--st-current`), the same mark the side menu gives the screen you are on.
 *
 * On a phone, which has no focus to speak of, the cue is amber (`studio-phone.css`), so the rare
 * keyboard focus there is amber too, and the same states read the same.
 */
enum class FocusFill {
    /** A content row or cell: lit glass in a ring under focus, amber when chosen. */
    TINT,

    /** A card over the picture: thin glass at rest, lit glass in a ring, the tally on screen. */
    RAISE,

    /** No fill; the ring alone. Tiles whose artwork is the content. */
    NONE,

    /** A control with a plate of its own — a button, a chip, a key: thin glass, then lit. */
    FILLED,

    /** A menu row: nothing at rest, the lit surface under focus, amber when chosen. */
    LIT,
}

/**
 * Draws the app's focus treatment: clip, fill, ring.
 *
 * | Fill | Resting | Focused | Selected |
 * | --- | --- | --- | --- |
 * | [FocusFill.TINT] (a channel row, `GuideCell`) | nothing | lit glass, white ring | amber glass |
 * | [FocusFill.RAISE] (`ChannelCard`) | thin glass, edge | lit glass, white ring | on-screen wash, tally ring |
 * | [FocusFill.NONE] (a poster) | nothing | white ring | nothing |
 * | [FocusFill.FILLED] (`Button`, `Chip`, a key) | thin glass, edge | lit surface | amber glass, amber edge |
 * | [FocusFill.LIT] (`ListRow`, a menu entry) | nothing | lit surface | amber glass, amber edge |
 *
 * A filled or lit element's content has to change colour with it; [filledContentColour] says to
 * what, and [LocalOnLitSurface] tells content that cannot be handed a colour.
 *
 * Deliberately does *not* include the focus lift — that is [focusLift], and it has to be applied
 * before this so the growth happens outside the clip.
 *
 * @param focused whether focus is on this element
 * @param shape the element's own shape; this modifier clips to it and rings it
 * @param selected for a card, that it is the channel on screen; for anything else, that it is
 *   the chosen one, when focus is elsewhere
 * @param fill which treatment applies; see [FocusFill]
 * @param ringWidth defaults to the surrounding shell's ring — 2dp in both shells, the canvas's
 *   4px at 1080p — and should only be passed explicitly by something that is not a normal
 *   focusable surface at all. A control's plate has a 1dp edge instead, whatever this says: the
 *   lit surface is its own answer and needs no ring.
 */
@Composable
fun Modifier.focusSurface(
    focused: Boolean,
    shape: Shape,
    selected: Boolean = false,
    fill: FocusFill = FocusFill.TINT,
    ringWidth: Dp = LocalTvMetrics.current.focusRingWidth,
): Modifier {
    val studio = StudioTheme.colors
    // Every resting value is its own colour at alpha zero, never `Color.Transparent`: transparent
    // is transparent *black*, and a bright ring interpolating towards it darkens on its way out.
    val ring = animatedColour(
        when (fill) {
            FocusFill.TINT -> when {
                focused -> studio.cue
                selected -> studio.currentEdge
                else -> studio.cue.copy(alpha = 0f)
            }
            FocusFill.NONE -> if (focused) studio.cue else studio.cue.copy(alpha = 0f)
            FocusFill.RAISE -> when {
                focused -> studio.cue
                selected -> studio.tally
                else -> studio.edgeLine
            }
            FocusFill.FILLED -> when {
                focused -> studio.cue
                selected -> studio.currentEdge
                else -> studio.edgeLine
            }
            FocusFill.LIT -> when {
                focused -> studio.cue
                selected -> studio.currentEdge
                else -> studio.cue.copy(alpha = 0f)
            }
        },
    )
    val background = when (fill) {
        FocusFill.TINT -> when {
            focused -> studio.cardFocus
            selected -> studio.currentGlass
            else -> studio.cardFocus.copy(alpha = 0f)
        }

        FocusFill.RAISE -> when {
            focused -> studio.cardFocus
            selected -> studio.onScreen
            else -> studio.card
        }

        FocusFill.FILLED -> when {
            focused -> studio.cue
            selected -> studio.currentGlass
            else -> studio.control
        }

        FocusFill.LIT -> when {
            focused -> studio.cue
            selected -> studio.currentGlass
            else -> studio.cue.copy(alpha = 0f)
        }

        // Not painted at all, rather than painted with something transparent: a background of any
        // colour still composites over the artwork underneath.
        FocusFill.NONE -> Color.Unspecified
    }
    // A control's plate carries a 1dp edge (the glass's own at rest, amber when chosen) rather
    // than the focus ring; a tile's ring is the shell's ring width. Either is one width in every
    // state: it used to widen on focus in several places, which moves the element's own contents
    // inside the clip at the moment it is pressed.
    val edgeWidth = if (fill == FocusFill.FILLED || fill == FocusFill.LIT) CONTROL_EDGE else ringWidth
    return this
        // The tally glow (`--st-tally-glow`) round the card that is on screen.
        .then(if (fill == FocusFill.RAISE && selected && !focused) Modifier.tallyGlow(shape) else Modifier)
        .clip(shape)
        .then(
            if (fill == FocusFill.NONE) {
                Modifier
            } else {
                Modifier.background(animatedColour(background))
            },
        )
        .border(edgeWidth, ring, shape)
}

/** The edge of a control's plate: 1px at 1080p on the canvas, drawn at 1dp so it survives. */
private val CONTROL_EDGE = 1.dp

/**
 * Whether the content being drawn sits on the lit surface — a focused [FocusFill.LIT] row or
 * control — and so has to turn dark. Provided by [FocusableRow] around its content, for parts that
 * are not handed a colour (a switch, a status pill).
 */
val LocalOnLitSurface = compositionLocalOf { false }

/**
 * The colour of a [FocusFill.FILLED] or [FocusFill.LIT] element's label or icon, which turns over
 * with its fill: dark on the lit surface, amber when it is the chosen one, ink at rest.
 *
 * @param muted for a resting chip or a secondary line, which is the second ink until chosen
 */
@Composable
fun filledContentColour(focused: Boolean, selected: Boolean = false, muted: Boolean = false): Color {
    val studio = StudioTheme.colors
    return animatedColour(
        when {
            focused -> if (muted) studio.onCue.copy(alpha = LIT_MUTED_ALPHA) else studio.onCue
            selected -> studio.current
            muted -> studio.ink2
            else -> studio.ink
        },
    )
}

/** A secondary line on the lit surface: the dark ink at the canvas's 66%. */
internal const val LIT_MUTED_ALPHA = 0.66f

/**
 * The tally glow (`--st-tally-glow`): a soft red light round what is on screen, drawn as a
 * coloured shadow. Shadow colours exist from Android 9 (API 28); below that a shadow can only be
 * black, which would read as depth rather than as the light, so older devices go without — the
 * on-screen fill and the tally ring already carry the state.
 */
@Composable
fun Modifier.tallyGlow(shape: Shape): Modifier {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return this
    val glow = StudioTheme.colors.tallyGlow
    return shadow(elevation = TALLY_GLOW_ELEVATION, shape = shape, clip = false, ambientColor = glow, spotColor = glow)
}

/** How far the glow spreads: `0 0 22px` at the phone's scale. */
private val TALLY_GLOW_ELEVATION = 11.dp
