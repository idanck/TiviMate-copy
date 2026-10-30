package com.tivicopy.player.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tivicopy.player.ui.theme.StudioTheme

/**
 * The app's motion: how anything that changes on screen gets from one state to the next.
 *
 * Until this file existed there was no motion at all — not one call to `animate*AsState`,
 * `AnimatedVisibility` or `Crossfade` anywhere in the app. Every focus ring, every rail that
 * opened, every overlay that replaced another one was a single-frame cut. Measured on the
 * television it was drawing three or four frames per press of the remote, where an animated
 * interface draws fifteen to twenty; that is what "the effects don't work" means in numbers.
 *
 * Four rules hold everything here together, and they are what keep motion from costing more
 * than it is worth on a modest television box:
 *
 * - **Animate what the GPU can do alone.** Scale and alpha go through `graphicsLayer`, whose
 *   lambda is read in the *draw* phase — the value changing redraws the node and does not
 *   recompose or re-measure it. That is why [focusLift] hands the animation into the lambda
 *   rather than reading it with `by` in the composition, which would recompose the tile on
 *   every frame of every focus change.
 * - **One vocabulary.** Focus lifts by [TvMetrics.focusScale] on a spring that settles in about
 *   220ms with a slight overshoot (Merhav's `--st-ease-spring`); colours cross in [Motion.FAST];
 *   a surface arrives in [Motion.ENTRANCE] and leaves in [Motion.EXIT]. A screen that invents its
 *   own timing reads as a different app.
 * - **Reduced motion is honoured.** With the system's animations turned off ("Remove animations",
 *   which sets the animator duration scale to zero), nothing grows or rises: focus keeps only its
 *   colour change, as the canvas's `prefers-reduced-motion` rule does ([LocalReducedMotion]).
 * - **Nothing loops.** No idle animation anywhere: with nothing happening the app renders zero
 *   frames. A ten-foot interface that quietly redraws forever costs battery on the remote's box
 *   and frames on everything else. The single exception is [Screensaver], which exists precisely
 *   so that an *unattended* screen does not hold still (FR-UI-31, ADR-024) — and it honours the
 *   spirit of this rule anyway: it steps twelve times a second rather than sixty, and it reads
 *   every animated value inside a `graphicsLayer` or `drawBehind` lambda, so a step redraws two
 *   nodes and recomposes nothing.
 */
object Motion {

    /** A colour crossing, a label appearing: the focus duration (`--st-dur-focus`). */
    const val FAST = 220

    /** The default for anything with size or position in it. */
    const val STANDARD = 200

    /** A whole surface arriving over the video (`--st-dur-panel`). */
    const val ENTRANCE = 360

    /**
     * A whole surface leaving (ADR-043, `--st-dur-exit`). Shorter than its arrival: what is going
     * has already been dismissed, and a departure that lingers reads as the remote not having
     * answered.
     */
    const val EXIT = 200

    /**
     * A colour crossing with reduced motion on: short and linear, the canvas's
     * `prefers-reduced-motion` transition.
     */
    const val REDUCED = 120

    /**
     * The one curve every timed transition uses (`--st-ease`, `cubic-bezier(0.2, 0, 0, 1)`):
     * it leaves at once and settles slowly, so a change reads as having already happened.
     */
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * The canvas's focus curve (`--st-ease-spring`, `cubic-bezier(0.3, 1.35, 0.5, 1)`): a slight
     * overshoot, for a timed transition that should land the way focus does.
     */
    val EaseSpring = CubicBezierEasing(0.3f, 1.35f, 0.5f, 1f)

    /**
     * The focus lift.
     *
     * A spring rather than a duration: focus on a D-pad moves in bursts — a viewer holding the
     * key down crosses five tiles in half a second — and a spring interrupted halfway carries its
     * velocity into the next tile instead of restarting. Tuned to the canvas's 220ms with its slight
     * overshoot (`--st-ease-spring`): about 7% past the target and back, which on a 6% growth is a
     * settle rather than a wobble.
     */
    val FocusSpring = spring<Float>(
        dampingRatio = 0.65f,
        stiffness = FOCUS_STIFFNESS,
    )
}

/** Settles a unit spring at a damping of 0.65 in about 200ms (4 / (ζ·√k)), the canvas's 220. */
private const val FOCUS_STIFFNESS = 1000f

/**
 * Whether the viewer has asked for less motion: true while the system's animator duration scale
 * is zero ("Remove animations" in the accessibility settings, or the developer option). Provided
 * once at the theme (`TiviCopyTheme`), so no component has to ask the platform itself.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Reads the system's animator duration scale once per context. Read rather than observed: the
 * setting is changed rarely and from another app, and the next activity start picks it up.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * How far a lifted element's shadow falls (`--st-lift`: `0 24px 60px` at 1080p, `0 8px 20px` on a
 * phone). The shadow is what makes the lift read as *rising* rather than just growing. The lift's
 * second part, a soft white glow round the lit element, is not drawn: Android's shadows are dark
 * only below API 28 and their colour is not reliably honoured above it, and the lit surface already
 * says where focus is.
 */
private val LIFT_ELEVATION_TV = 12.dp
private val LIFT_ELEVATION_PHONE = 6.dp

/**
 * Lifts a focused element: it grows a little, casts a shadow, and is drawn over what it now
 * overlaps.
 *
 * The scale is applied in a `graphicsLayer`, so nothing around it moves — a focused card in a
 * row does not push the row along, which is what makes this safe to put on every tile in a
 * `LazyRow`. [zIndex] is read from the boolean rather than from the animation so it costs one
 * recomposition per focus change instead of one per frame.
 *
 * With reduced motion on, nothing grows: the shadow still comes and goes, which is the canvas's
 * reduced rule (colour and shadow change, no transform).
 *
 * @param scale how much to grow; defaults to the shell's card scale ([TvMetrics.focusScale]),
 *   which is 1 on a phone
 * @param shape the element's own shape, for its shadow. Null casts none — for something that
 *   is not a raised object, such as a row on a panel.
 */
@Composable
fun Modifier.focusLift(
    focused: Boolean,
    scale: Float = LocalTvMetrics.current.focusScale,
    shape: Shape? = null,
): Modifier {
    val reduced = LocalReducedMotion.current
    val lift = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (reduced) tween<Float>(Motion.REDUCED, easing = LinearEasing) else Motion.FocusSpring,
        label = "focusLift",
    )
    val grow = if (reduced) 1f else scale
    val liftShape = shape
    val elevation = if (StudioTheme.television) LIFT_ELEVATION_TV else LIFT_ELEVATION_PHONE
    val elevationPx = if (liftShape == null) 0f else with(LocalDensity.current) { elevation.toPx() }
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            // Read here, in the draw phase: the frames of this animation cost a redraw of one
            // node and no recomposition at all.
            val factor = 1f + (grow - 1f) * lift.value
            scaleX = factor
            scaleY = factor
            if (liftShape != null) {
                this.shape = liftShape
                clip = false
                shadowElevation = elevationPx * lift.value.coerceIn(0f, 1f)
            }
        }
}

/**
 * A colour that crosses to its new value instead of cutting to it.
 *
 * For focus rings and fills, where the call site already decides the colour with a `when`. Pass
 * a colour at zero alpha rather than [Color.Transparent] for the resting state: transparent is
 * transparent *black*, and interpolating a bright ring towards it darkens on the way out.
 *
 * With reduced motion on, the crossing is [Motion.REDUCED] and linear.
 */
@Composable
fun animatedColour(target: Color, durationMillis: Int = Motion.FAST): Color =
    animatedColourState(target, durationMillis).value

/**
 * [animatedColour] as the crossing's own state, for a colour read where it is drawn
 * (`drawBehind`, a text's `ColorProducer`): its frames then redraw the one node and recompose
 * nothing. Read in a composable body it is [animatedColour], and recomposes that body every frame.
 */
@Composable
fun animatedColourState(target: Color, durationMillis: Int = Motion.FAST): State<Color> {
    val reduced = LocalReducedMotion.current
    return animateColorAsState(
        targetValue = target,
        animationSpec = if (reduced) {
            tween<Color>(Motion.REDUCED, easing = LinearEasing)
        } else {
            tween<Color>(durationMillis, easing = Motion.Ease)
        },
        label = "colour",
    )
}

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
 *
 * This is the arrival only. A surface that should also *leave* on screen — fade out rather than
 * stop being composed between two frames — is drawn in [SurfacePresence].
 */
@Composable
fun Modifier.screenEntrance(key: Any?, rise: Dp = 12.dp): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(Motion.ENTRANCE, easing = Motion.Ease))
    }
    // Reduced motion: a fade alone.
    val risePx = if (LocalReducedMotion.current) 0f else with(LocalDensity.current) { rise.toPx() }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * risePx
    }
}

/**
 * A surface that arrives and leaves, rather than appearing and vanishing between two frames.
 *
 * [screenEntrance] animates a surface in and does nothing on its way out: once it is no longer
 * composed it is gone, which on the television was a cut on every Back — the one cut ADR-021
 * left, and the one a viewer meets most often. This keeps the surface composed for [Motion.EXIT]
 * longer and fades it over that time (ADR-043). It is still one surface at a time: nothing
 * arrives under a surface that is leaving for the video, and a browse screen that gives way to
 * another is cut as before, because composing both is what ADR-021 ruled out.
 *
 * While the surface is going, the remote is not left in it. If focus was inside, it is released,
 * so a press made in that window is not spent on a surface already dismissed; and nothing drawn
 * directly in it can take focus again. (A list keeps its own focus group, whose rows this cannot
 * reach; they go with the list a few frames later, as they always did.)
 *
 * The arrival is [screenEntrance]'s: a fade with a short rise. A surface whose content replays
 * an entrance of its own as it changes — the browse scaffold — passes [rise] as zero and fades
 * alone, so the two do not stack into a leap.
 *
 * @param visible whether the surface is on screen; it stays composed until its exit has finished
 * @param rise how far the surface rises as it arrives; zero for a fade alone
 */
@Composable
fun SurfacePresence(
    visible: Boolean,
    modifier: Modifier = Modifier,
    rise: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // Whether the remote is somewhere inside: true while any focus target below holds it.
    var holdsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        // Only if the remote is in here. Whatever takes the screen next asks for focus a frame
        // after it composes (the station backdrop, the transport bar), and a blanket clear in the
        // same pass as that request would have thrown its answer away.
        if (!visible && holdsFocus) focusManager.clearFocus(force = true)
    }
    // Reduced motion: a fade alone.
    val risePx = if (LocalReducedMotion.current) 0 else with(LocalDensity.current) { rise.roundToPx() }
    val arrival = fadeIn(tween<Float>(Motion.ENTRANCE, easing = Motion.Ease)) + (
        if (risePx > 0) {
            slideInVertically(tween<IntOffset>(Motion.ENTRANCE, easing = Motion.Ease)) { risePx }
        } else {
            EnterTransition.None
        }
        )
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = arrival,
        exit = fadeOut(tween<Float>(Motion.EXIT, easing = Motion.Ease)),
    ) {
        Box(
            modifier = Modifier
                .onFocusChanged { holdsFocus = it.hasFocus }
                // Only while leaving: a property set here reaches every focus target under it up
                // to the next focus group, and the default is left alone while the surface is up.
                .then(if (visible) Modifier else Modifier.focusProperties { canFocus = false }),
        ) {
            content()
        }
    }
}
