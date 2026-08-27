package com.tivicopy.player.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * The app's motion: how anything that changes on screen gets from one state to the next.
 *
 * Until this file existed there was no motion at all — not one call to `animate*AsState`,
 * `AnimatedVisibility` or `Crossfade` anywhere in the app. Every focus ring, every rail that
 * opened, every overlay that replaced another one was a single-frame cut. Measured on the
 * television it was drawing three or four frames per press of the remote, where an animated
 * interface draws fifteen to twenty; that is what "the effects don't work" means in numbers.
 *
 * Three rules hold everything here together, and they are what keep motion from costing more
 * than it is worth on a modest television box:
 *
 * - **Animate what the GPU can do alone.** Scale and alpha go through `graphicsLayer`, whose
 *   lambda is read in the *draw* phase — the value changing redraws the node and does not
 *   recompose or re-measure it. That is why [focusLift] hands the animation into the lambda
 *   rather than reading it with `by` in the composition, which would recompose the tile on
 *   every frame of every focus change.
 * - **One vocabulary.** Focus lifts by [FOCUS_SCALE] on a spring; colours cross in [Motion.FAST];
 *   a surface arriving fades and rises in [Motion.ENTRANCE]. A screen that invents its own
 *   timing reads as a different app.
 * - **Nothing loops.** No idle animation anywhere: with nothing happening the app renders zero
 *   frames, and that stays true. A ten-foot interface that quietly redraws forever costs battery
 *   on the remote's box and frames on everything else.
 */
object Motion {

    /** A colour crossing, a label appearing: short enough to feel immediate. */
    const val FAST = 120

    /** The default for anything with size or position in it. */
    const val STANDARD = 200

    /** A whole surface arriving over the video. */
    const val ENTRANCE = 260

    /**
     * The focus lift.
     *
     * A spring rather than a duration: focus on a D-pad moves in bursts — a viewer holding the
     * key down crosses five tiles in half a second — and a spring interrupted halfway carries its
     * velocity into the next tile instead of restarting. Damped just short of a visible overshoot;
     * a tile that wobbles reads as a toy.
     */
    val FocusSpring = spring<Float>(
        dampingRatio = 0.78f,
        stiffness = Spring.StiffnessMediumLow,
    )
}

/** How much a focused card grows. Enough to find from across a room, short of hiding its neighbour. */
const val FOCUS_SCALE = 1.06f

/**
 * Lifts a focused element: it grows a little and is drawn over what it now overlaps.
 *
 * The scale is applied in a `graphicsLayer`, so nothing around it moves — a focused card in a
 * row does not push the row along, which is what makes this safe to put on every tile in a
 * `LazyRow`. [zIndex] is read from the boolean rather than from the animation so it costs one
 * recomposition per focus change instead of one per frame.
 */
@Composable
fun Modifier.focusLift(focused: Boolean, scale: Float = FOCUS_SCALE): Modifier {
    val factor = animateFloatAsState(
        targetValue = if (focused) scale else 1f,
        animationSpec = Motion.FocusSpring,
        label = "focusLift",
    )
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            // Read here, in the draw phase: the frames of this animation cost a redraw of one
            // node and no recomposition at all.
            scaleX = factor.value
            scaleY = factor.value
        }
}

/**
 * A colour that crosses to its new value instead of cutting to it.
 *
 * For focus rings and fills, where the call site already decides the colour with a `when`. Pass
 * a colour at zero alpha rather than [Color.Transparent] for the resting state: transparent is
 * transparent *black*, and interpolating a bright ring towards it darkens on the way out.
 */
@Composable
fun animatedColour(target: Color, durationMillis: Int = Motion.FAST): Color =
    animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
        label = "colour",
    ).value

/**
 * The way a surface arrives: a fade with a short rise under it.
 *
 * Keyed, so it replays whenever [key] changes — which is how one overlay replacing another reads
 * as a transition rather than a cut, without ever composing two screens at once. Composing both
 * would be the usual way to cross-fade, and on this shell it would mean two channel lists
 * querying, two guides fetching, and two screens asking for focus at the same time.
 *
 * Like [focusLift], the animation is read inside the `graphicsLayer` lambda, so the frames it
 * draws do not recompose the screen underneath.
 */
@Composable
fun Modifier.screenEntrance(key: Any?, rise: Dp = 12.dp): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(Motion.ENTRANCE, easing = FastOutSlowInEasing))
    }
    val risePx = with(LocalDensity.current) { rise.toPx() }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * risePx
    }
}
