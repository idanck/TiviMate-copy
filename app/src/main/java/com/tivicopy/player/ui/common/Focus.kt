package com.tivicopy.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
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
 * takes the ring width from [LocalTvMetrics], so no call site needs to know which shell it is
 * on, and it crosses both colours rather than cutting them (`Motion.kt`).
 *
 * ## Four fills, and why not one
 *
 * A fill is how the *thing itself* answers "am I the one", and it depends on what is behind it:
 *
 * - [FocusFill.TINT] — a row, a grid cell: something on a surface, in a list of its peers.
 *   Focus steps it up to `card-focus` inside a cue ring.
 * - [FocusFill.RAISE] — a card floating over live video. It cannot tint, because what is behind
 *   it is a moving picture and a translucent accent over that is a different colour every frame.
 *   It is an opaque card that steps up the surface ramp, and the card on screen takes the tally.
 * - [FocusFill.NONE] — a tile whose own artwork *is* the content: an app icon, a film poster.
 *   Painting behind it does nothing, and painting over it hides the thing being chosen. The ring
 *   carries the whole answer.
 * - [FocusFill.FILLED] — a button. A button is not one of a list of peers; it is the one thing to
 *   press, and it says so by *becoming* the cue. That is a strong enough signal on its own, so
 *   its edge only shows at rest: focused, the ring is the fill's own colour.
 *
 * The ring is the same in the three that have one, which is what makes them one vocabulary
 * rather than three.
 */
enum class FocusFill {
    /** Steps up to `card-focus` in a cue ring. Rows, grid cells, navigation entries. */
    TINT,

    /** Opaque step up the surface ramp. Cards over video. */
    RAISE,

    /** No fill; the ring alone. Tiles whose artwork is the content. */
    NONE,

    /** Becomes the cue. Buttons and chips. */
    FILLED,
}

/**
 * Draws the app's focus treatment: clip, fill, ring.
 *
 * The states are the Studio canvas's (`docs/design/studio/canvas/`, ADR-027), and its rule is that
 * focus has exactly one colour — the **cue**, `primary` — while what is on screen has another,
 * the **tally**. So:
 *
 * | Fill | Resting | Focused | Selected |
 * | --- | --- | --- | --- |
 * | [FocusFill.TINT] (`ListRow`, `GuideCell`) | nothing | `card-focus`, cue ring | `card` |
 * | [FocusFill.RAISE] (`ChannelCard`) | `card`, faint edge | `card-focus`, cue ring | on-screen fill, tally ring |
 * | [FocusFill.FILLED] (`Button`, `Chip`) | `control`, faint edge | cue | ink |
 * | [FocusFill.NONE] | nothing | cue ring | nothing |
 *
 * A filled element's content has to change colour with it; [filledContentColour] says to what.
 *
 * Deliberately does *not* include the focus lift — that is [focusLift], and it has to be applied
 * before this so the growth happens outside the clip.
 *
 * @param focused whether focus is on this element
 * @param shape the element's own shape; this modifier clips to it and rings it
 * @param selected for a card, that it is the channel on screen; for anything else, that it is
 *   the chosen one, when focus is elsewhere
 * @param fill which of the four fills applies; see [FocusFill]
 * @param ringWidth defaults to the surrounding shell's ring — 2dp in both shells, Studio's 4px at
 *   1080p — and should only be passed explicitly by something that is not a normal focusable
 *   surface at all.
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
            FocusFill.TINT, FocusFill.NONE -> if (focused) studio.cue else studio.cue.copy(alpha = 0f)
            FocusFill.RAISE -> when {
                focused -> studio.cue
                selected -> studio.tally
                else -> studio.hairlineSoft
            }
            FocusFill.FILLED -> when {
                focused -> studio.cue
                selected -> studio.ink
                else -> studio.hairline
            }
        },
    )
    val background = when (fill) {
        FocusFill.TINT -> when {
            focused -> studio.cardFocus
            selected -> studio.card
            else -> studio.cardFocus.copy(alpha = 0f)
        }

        FocusFill.RAISE -> when {
            focused -> studio.cardFocus
            selected -> studio.onScreen
            else -> studio.card
        }

        FocusFill.FILLED -> when {
            focused -> studio.cue
            selected -> studio.ink
            else -> studio.control
        }

        // Not painted at all, rather than painted with something transparent: a background of any
        // colour still composites over the artwork underneath.
        FocusFill.NONE -> Color.Unspecified
    }
    return this
        // Studio's tally glow (`--st-tally-glow`) round the card that is on screen.
        .then(if (fill == FocusFill.RAISE && selected && !focused) Modifier.tallyGlow(shape) else Modifier)
        .clip(shape)
        .then(
            if (fill == FocusFill.NONE) {
                Modifier
            } else {
                Modifier.background(animatedColour(background))
            },
        )
        // One width for every state. It used to widen on focus in several places, which moves
        // the element's own contents inside the clip at the moment it is pressed. A filled
        // element's edge matches its fill when focused or selected, so only the resting hairline
        // shows.
        .border(ringWidth, ring, shape)
}

/**
 * The colour of a [FocusFill.FILLED] element's label or icon, which has to turn over with its
 * fill: dark on the cue, dark on the ink of a selected chip or a primary button, and ink on a
 * resting control.
 *
 * @param muted for a resting chip, whose label is secondary until it is chosen
 */
@Composable
fun filledContentColour(focused: Boolean, selected: Boolean = false, muted: Boolean = false): Color {
    val studio = StudioTheme.colors
    return animatedColour(
        when {
            focused -> studio.onCue
            selected -> studio.ground
            muted -> studio.ink2
            else -> studio.ink
        },
    )
}

/**
 * Studio's tally glow (`--st-tally-glow`): a soft red light round what is on screen, drawn as a
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
