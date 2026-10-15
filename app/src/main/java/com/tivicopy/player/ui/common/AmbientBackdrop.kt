package com.tivicopy.player.ui.common

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.tivicopy.player.ui.theme.StudioTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * Studio's shifting background for the radio (`docs/design/studio/studio.css`, `.st-amb-*`;
 * ADR-030).
 *
 * The radio is the screen this app is most likely to be left on for hours, so it protects itself
 * rather than waiting for a screensaver to do it: nothing on it holds still.
 *
 * - The **ground** moves through four dark tones (`--st-amb-1..4`) on a 120-second cycle.
 * - A **pool of light** in Studio's halo blue wanders on two axes with periods of 47 and 76
 *   seconds — near the golden ratio, so the path it traces never closes into a loop that could
 *   itself wear into the panel.
 * - The pool's **hue** swings ±26° on the same 120-second cycle.
 *
 * Everything that changes is read inside `drawBehind`, from a clock ticking twelve times a
 * second, so a step redraws one node and recomposes nothing — the same budget the screensaver
 * kept (ADR-024).
 */
@Composable
fun Modifier.studioAmbient(): Modifier {
    val studio = StudioTheme.colors
    val tones = remember(studio.ambient) { studio.ambient.map { it.toArgb() } }
    val halo = studio.halo
    val start = remember { SystemClock.elapsedRealtime() }
    val elapsed = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsed.longValue = SystemClock.elapsedRealtime() - start
            delay(AMBIENT_TICK_MS)
        }
    }
    return this.drawBehind {
        val seconds = elapsed.longValue / 1000f
        drawRect(Color(ambientTone(tones, seconds)))
        val (fx, fy) = ambientPoolCentre(seconds)
        val centre = Offset(size.width * fx, size.height * fy)
        val radius = min(size.width, size.height) * POOL_RADIUS
        val colour = Color(ambientPoolColour(halo.toArgb(), seconds))
        drawCircle(
            brush = Brush.radialGradient(
                0f to colour.copy(alpha = halo.alpha),
                0.34f to colour.copy(alpha = POOL_MID_ALPHA),
                0.66f to colour.copy(alpha = 0f),
                center = centre,
                radius = radius,
            ),
            radius = radius,
            center = centre,
        )
    }
}

// --------------------------------------------------------------------------- pure

/**
 * The ground colour [seconds] into the cycle: the four tones in turn, eased in and out between
 * each, back to the first after [AMBIENT_CYCLE_S] (the CSS keyframes at 0/25/50/75/100%).
 */
internal fun ambientTone(tones: List<Int>, seconds: Float): Int {
    require(tones.isNotEmpty())
    if (tones.size == 1) return tones[0]
    val phase = ((seconds % AMBIENT_CYCLE_S) + AMBIENT_CYCLE_S) % AMBIENT_CYCLE_S / AMBIENT_CYCLE_S
    val scaled = phase * tones.size
    val from = scaled.toInt().coerceIn(0, tones.size - 1)
    val to = (from + 1) % tones.size
    val t = easeInOut(scaled - from)
    return lerpArgb(tones[from], tones[to], t)
}

/**
 * Where the pool of light is, as fractions of the screen, [seconds] in.
 *
 * It rests at (0.75, 0.37) — where the canvas puts it — and travels 0.29 of the width towards
 * the start and 0.28 of the height downwards, each axis on its own period, eased at both ends as
 * the CSS `ease-in-out` is.
 */
internal fun ambientPoolCentre(seconds: Float): Pair<Float, Float> {
    val x = POOL_REST_X - POOL_TRAVEL_X * swing(seconds, POOL_PERIOD_X_S)
    val y = POOL_REST_Y + POOL_TRAVEL_Y * swing(seconds, POOL_PERIOD_Y_S)
    return x to y
}

/** The pool's colour [seconds] in: [base] with its hue swung up to ±[AMBIENT_HUE_SWING] degrees. */
internal fun ambientPoolColour(base: Int, seconds: Float): Int {
    val hsl = rgbToHsl(base)
    val offset = AMBIENT_HUE_SWING * cos(2 * PI * seconds / AMBIENT_CYCLE_S).toFloat()
    val hue = ((hsl[0] + offset) % 360f + 360f) % 360f
    return hslToArgb(hue, hsl[1], hsl[2])
}

/** 0 at rest, 1 at the far end, back to 0 after [period] seconds; smooth at both ends. */
private fun swing(seconds: Float, period: Float): Float =
    ((1 - cos(2 * PI * seconds / period)) / 2).toFloat()

private fun easeInOut(t: Float): Float = ((1 - cos(PI * t.coerceIn(0f, 1f))) / 2).toFloat()

private fun lerpArgb(a: Int, b: Int, t: Float): Int {
    fun ch(shift: Int): Int {
        val x = (a shr shift) and 0xFF
        val y = (b shr shift) and 0xFF
        return (x + (y - x) * t).toInt().coerceIn(0, 255)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

internal const val AMBIENT_CYCLE_S = 120f
internal const val AMBIENT_HUE_SWING = 26f
internal const val POOL_PERIOD_X_S = 47f
internal const val POOL_PERIOD_Y_S = 76f
private const val POOL_REST_X = 0.75f
private const val POOL_REST_Y = 0.37f
private const val POOL_TRAVEL_X = 0.29f
private const val POOL_TRAVEL_Y = 0.28f
/** The pool is 1200px across at 1080p: a radius of 600px, against the 1080px height. */
private const val POOL_RADIUS = 0.56f
private const val POOL_MID_ALPHA = 0.14f

/** Twelve steps a second: smooth at the speeds this moves, and a fifth of a display's frames. */
private const val AMBIENT_TICK_MS = 80L
