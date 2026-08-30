package com.tivicopy.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What focus looks like, defined once for the whole app.
 *
 * ADR-023 gave the shell two shapes and told the channel list to take its corner and its ring
 * from [TvMetrics]. It stopped there, and an audit of every focusable thing in the app found
 * that most of the shell had never been part of that: five different focused fills
 * (`primary` at 0.22, at 0.28 and at 0.30, `surfaceVariant`, and a solid `primary`), three ring
 * widths on the same television (3dp on a card, 2dp hard-coded on a guide cell and on the
 * navigation pill, none at all on a settings slider), and one tile that drew its resting ring in
 * `Color.Transparent` — which is transparent *black*, the one thing both `Motion.kt` and
 * `TvSurfaces.kt` say not to do — while jumping its border from 0dp to 3dp, moving its own
 * contents on focus.
 *
 * None of that is visible in a screenshot of one screen. All of it is visible while moving a
 * remote across the app, which is the only way anyone actually uses it.
 *
 * So there is one function here, and everything focusable goes through it: [focusSurface]. It
 * takes the ring width from [LocalTvMetrics], so a phone keeps its 2dp and a television gets 3dp
 * without a single call site knowing which it is on, and it crosses both colours rather than
 * cutting them (`Motion.kt`).
 *
 * ## Four fills, and why not one
 *
 * A fill is how the *thing itself* answers "am I the one", and it depends on what is behind it:
 *
 * - [FocusFill.TINT] — a row, a chip, a grid cell: something on a surface, in a list of its
 *   peers. Focus tints it with the accent, which reads at a glance without repainting the row.
 * - [FocusFill.RAISE] — a card floating over live video. It cannot tint, because what is behind
 *   it is a moving picture and a translucent accent over that is a different colour every frame.
 *   It goes opaque and steps up the surface ramp instead.
 * - [FocusFill.NONE] — a tile whose own artwork *is* the content: an app icon, a film poster.
 *   Painting behind it does nothing, and painting over it hides the thing being chosen. The ring
 *   carries the whole answer.
 * - [FocusFill.FILLED] — a button. A button is not one of a list of peers; it is the one thing to
 *   press, and it says so by *becoming* the accent. That is a strong enough signal on its own, so
 *   this is the one fill that draws no ring: a ring around a filled button is a second answer to
 *   a question already answered.
 *
 * The ring is the same in the three that have one, which is what makes them one vocabulary
 * rather than three.
 */
enum class FocusFill {
    /** Accent tint. Rows, chips, grid cells, navigation pills. */
    TINT,

    /** Opaque step up the surface ramp. Cards over video. */
    RAISE,

    /** No fill; the ring alone. Tiles whose artwork is the content. */
    NONE,

    /** Becomes the accent, and draws no ring. Buttons. */
    FILLED,
}

/** How much of the picture a resting [FocusFill.RAISE] card still lets through. */
private const val RAISE_RESTING_ALPHA = 0.65f

/** The accent tint a focused [FocusFill.TINT] element takes. */
private const val TINT_FOCUSED_ALPHA = 0.22f

/**
 * The ring on the thing that is playing, or chosen, but is not where focus is.
 *
 * Same width as the focus ring, and only the alpha separates them. It used to be a thinner ring
 * in some places and a differently-coloured fill in others, which meant "this is the one playing"
 * and "this is the one you are on" were told apart differently on every screen.
 */
private const val SELECTED_RING_ALPHA = 0.45f

/**
 * Draws the app's focus treatment: clip, fill, ring.
 *
 * Deliberately does *not* include the focus lift — that is [focusLift], and it has to be applied
 * before this so the growth happens outside the clip.
 *
 * @param focused whether focus is on this element
 * @param shape the element's own shape; this modifier clips to it and rings it
 * @param selected whether this is the playing/chosen one, when focus is elsewhere
 * @param fill which of the four fills applies; see [FocusFill]
 * @param ringWidth defaults to the surrounding shell's ring — 2dp on a phone, 3dp on a
 *   television — and should only be passed explicitly by something that is not a normal
 *   focusable surface at all.
 */
@Composable
fun Modifier.focusSurface(
    focused: Boolean,
    shape: Shape,
    selected: Boolean = false,
    fill: FocusFill = FocusFill.TINT,
    ringWidth: Dp = LocalTvMetrics.current.focusRingWidth,
): Modifier {
    val scheme = MaterialTheme.colorScheme
    // Every resting value is its own colour at alpha zero, never `Color.Transparent`: transparent
    // is transparent *black*, and a bright ring interpolating towards it darkens on its way out.
    val ring = animatedColour(
        when {
            focused -> scheme.primary
            selected -> scheme.primary.copy(alpha = SELECTED_RING_ALPHA)
            else -> scheme.primary.copy(alpha = 0f)
        },
    )
    val background = when (fill) {
        FocusFill.TINT -> when {
            focused -> scheme.primary.copy(alpha = TINT_FOCUSED_ALPHA)
            selected -> scheme.surfaceVariant
            else -> scheme.surfaceVariant.copy(alpha = 0f)
        }

        FocusFill.RAISE -> when {
            focused -> scheme.surfaceVariant
            selected -> scheme.surface
            else -> scheme.surface.copy(alpha = RAISE_RESTING_ALPHA)
        }

        FocusFill.FILLED -> when {
            focused -> scheme.primary
            selected -> scheme.primaryContainer
            else -> scheme.surfaceVariant
        }

        // Not painted at all, rather than painted with something transparent: a background of any
        // colour still composites over the artwork underneath.
        FocusFill.NONE -> Color.Unspecified
    }
    return this
        .clip(shape)
        .then(
            if (fill == FocusFill.NONE) {
                Modifier
            } else {
                Modifier.background(animatedColour(background))
            },
        )
        // One width for all three states. It used to widen on focus in several places, which
        // moves the element's own contents inside the clip at the moment it is pressed.
        .border(if (fill == FocusFill.FILLED) 0.dp else ringWidth, ring, shape)
}
