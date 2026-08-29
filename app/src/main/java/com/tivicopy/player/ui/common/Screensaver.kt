package com.tivicopy.player.ui.common

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.tasks.TaskItem
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * The burn-in guard (FR-UI-31, ADR-024).
 *
 * A television left on a still picture wears the picture into the panel. This app is unusually
 * good at producing one: it holds `FLAG_KEEP_SCREEN_ON` for as long as it is on screen, a paused
 * stream is a frozen frame that stays frozen until somebody comes back to it, an overlay left
 * open is a wall of unmoving text, and the radio backdrop — the one this app is most likely to be
 * left on for hours — is a static logo, a static name and a clock that changes one glyph a
 * minute. Nothing here dims the picture while it is *moving*: video is its own protection.
 *
 * Two skins over one mechanism:
 *
 * - **Dark**, for a still picture of any kind. The panel goes black — actually black, so an OLED
 *   emits nothing across almost all of it — and a small block of text drifts across it.
 * - **Ambient**, for an audio-only stream, which is to say the radio. Blacking out a station
 *   somebody is *listening* to would be the wrong answer to the right problem, so instead the
 *   station's own colour stays, dimmed, and drifts slowly around the hue it started from. When
 *   the owner has pointed the app at a task list, one open task at a time is printed with it,
 *   each in a different place from the last.
 *
 * Every pixel is therefore moving or dark, on a screen nobody is looking at.
 *
 * **This is the one thing in the app that animates while idle**, which `ui/common/Motion.kt`
 * otherwise forbids outright, and FR-UI-30 says out loud. The exception is the whole point: the
 * rule exists so an unattended television is not redrawing itself for nothing, and this redraws
 * an unattended television precisely so that it is not holding still. It is kept cheap on the
 * terms that rule is really about — the animation is driven at [TICK_MS] rather than at frame
 * rate, and every value it produces is read inside a `graphicsLayer` or `drawBehind` lambda, so
 * a tick redraws two nodes and recomposes nothing.
 */

// -------------------------------------------------------------------- decisions

/** What the panel is showing, for the purposes of deciding whether it needs protecting. */
enum class IdleScene {
    /** Moving video, filling the screen. Nothing to protect against. */
    MOVING,

    /** A frozen frame, a menu, a guide, an error — a still image, whatever produced it. */
    STILL,

    /** Sound with no picture: a radio station, or any other audio-only stream. */
    AUDIO_ONLY,
}

/**
 * What is on the panel, from what the player reports and where the shell has drawn it.
 *
 * @param playing the stream is actually running — [com.tivicopy.player.player.PlaybackStatus.playing]
 *   rather than the user's intent, because a paused *or* stalled stream is a still frame either way.
 * @param playWhenReady the user's intent, which is what tells a station that is rebuffering from a
 *   station that has been paused: the first is still the radio, the second is a still screen.
 * @param hasPicture the stream has a video track. An audio-only one never produces a frame.
 * @param failed playback has settled on an error, so what is on screen is a message.
 * @param videoFillsPanel the video is what the screen *is*, rather than something an overlay has
 *   covered or shrunk into a corner. The guide docks the picture into a preview slot and fills the
 *   rest of the panel with a grid that does not move for hours; that is a still screen with a
 *   moving stamp on it, and it burns in exactly like any other.
 */
fun idleScene(
    playing: Boolean,
    playWhenReady: Boolean,
    hasPicture: Boolean,
    failed: Boolean,
    videoFillsPanel: Boolean,
): IdleScene = when {
    failed -> IdleScene.STILL
    !hasPicture && playWhenReady -> IdleScene.AUDIO_ONLY
    playing && hasPicture && videoFillsPanel -> IdleScene.MOVING
    else -> IdleScene.STILL
}

/** How long a still screen is left alone before the screensaver takes it, in milliseconds. */
fun screensaverDelayMillis(idleMinutes: Int): Long = idleMinutes.coerceIn(1, 120) * 60_000L

/**
 * Whether the screensaver has anything to do at all right now.
 *
 * Separate from the countdown itself so the caller can arm one timer and forget it: this answers
 * "is this a screen worth protecting", the timer answers "has it been long enough".
 */
fun screensaverApplies(enabled: Boolean, scene: IdleScene, inPip: Boolean): Boolean =
    enabled && !inPip && scene != IdleScene.MOVING

// ------------------------------------------------------------------------ maths

/**
 * Where the drifting block sits at [phase] — a fraction of one horizontal cycle — as a fraction
 * of the travel it is allowed, in -1..1 on each axis.
 *
 * A Lissajous figure rather than a circle or a bounce, and with an *irrational* ratio between the
 * two axes ([DRIFT_RATIO] is the golden ratio) so the path never closes: the block is somewhere
 * new on every pass instead of retracing one loop for six hours, which would wear that loop into
 * the panel just as surely as standing still would.
 */
internal fun driftAt(phase: Float): Pair<Float, Float> {
    val x = sin(TWO_PI * phase)
    val y = sin(TWO_PI * (DRIFT_RATIO * phase + DRIFT_OFFSET))
    return x to y
}

/**
 * The station's hue, swung gently either side of itself.
 *
 * Deliberately a swing rather than a rotation through the whole wheel: this is still meant to
 * look like *that station's* screen at three in the morning, and a station whose colour is orange
 * turning green is a different screen, not a resting one.
 */
internal fun ambientHue(baseHue: Float, phase: Float, swingDegrees: Float): Float =
    ((baseHue + swingDegrees * sin(TWO_PI * phase)) % 360f + 360f) % 360f

private const val TWO_PI = (2.0 * PI).toFloat()

/** Golden ratio: an axis ratio that never resynchronises, so the drift path never repeats. */
private const val DRIFT_RATIO = 1.6180339f

/** A quarter turn between the axes, so the path opens out instead of starting as a diagonal. */
private const val DRIFT_OFFSET = 0.25f

// --------------------------------------------------------------------------- ui

/**
 * The screensaver itself, drawn over everything.
 *
 * @param ambient whether this is the radio's lit skin rather than the dark one.
 * @param channel the station or channel on screen, for its name and (when [ambient]) its colour.
 * @param headline what to call what is playing when there is no channel to name.
 * @param tasks the open tasks to cycle through, or empty for none. Only ever non-empty for the
 *   ambient skin, and only when the owner has both configured a list and switched it on.
 */
@Composable
fun Screensaver(
    ambient: Boolean,
    channel: Channel?,
    headline: String,
    tasks: List<TaskItem>,
    modifier: Modifier = Modifier,
) {
    // Asked for only by the skin that uses it: resolving a station's accent downloads and
    // samples its logo the first time that station is seen, and the dark skin is black.
    val accent = if (ambient) rememberStationAccent(channel) else 0
    val hsl = remember(accent) { rgbToHsl(accent) }
    val baseHue = hsl[0]
    val saturation = hsl[1]

    // One monotonic clock in seconds, from which both the drift and the colour take their own
    // period. Read from the system rather than accumulated, so a tick the device was too busy to
    // deliver is skipped rather than slowing the drift down for the rest of the night.
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000f
        }
    }

    // The screensaver arrives as a slow fade rather than a cut: it is replacing a picture
    // somebody may still glance up at, and Motion.ENTRANCE — a quarter of a second — would read
    // as the app doing something rather than as the screen settling.
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(FADE_IN_MS, easing = FastOutSlowInEasing)) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // A tap is a way to wake a phone up, and nothing more: without this it would fall
            // through to whatever the screensaver is covering, and the press that dismissed the
            // screen would also have pressed a button on it. The remote's half of the same rule
            // is in `MainActivity.dispatchKeyEvent`.
            .pointerInput(Unit) { detectTapGestures { } }
            // Read in the draw phase, so the fade costs redraws rather than recompositions.
            .graphicsLayer { alpha = fade.value }
            .drawBehind {
                if (!ambient) {
                    // Actually black. On an OLED panel an unlit pixel emits nothing and ages
                    // not at all, which makes this the strongest thing the app can do about
                    // burn-in — and on an LCD it is simply the darkest the screen goes.
                    drawRect(Color.Black)
                    return@drawBehind
                }
                val hue = ambientHue(baseHue, elapsed / HUE_PERIOD_SECONDS, HUE_SWING_DEGREES)
                val top = Color(hslToArgb(hue, saturation, AMBIENT_TOP_LIGHTNESS))
                val bottom = Color(hslToArgb(hue, saturation, AMBIENT_BOTTOM_LIGHTNESS))
                drawRect(Brush.verticalGradient(listOf(top, bottom)))

                // A pool of light that wanders on its own, slower period than the text and out
                // of phase with it. It is what keeps the *background* from being a fixed
                // gradient with something moving on top: on a dark station colour the top of a
                // vertical gradient is the brightest thing on the panel, and left in one place
                // it is the part that would age.
                val (gx, gy) = driftAt(elapsed / GLOW_PERIOD_SECONDS)
                val glow = Color(hslToArgb(hue, saturation, AMBIENT_GLOW_LIGHTNESS))
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = AMBIENT_GLOW_ALPHA), glow.copy(alpha = 0f)),
                        center = Offset(
                            size.width / 2f + gx * size.width * GLOW_TRAVEL,
                            size.height / 2f + gy * size.height * GLOW_TRAVEL,
                        ),
                        radius = size.minDimension * GLOW_RADIUS,
                    ),
                )
            },
    ) {
        val compact = maxHeight < COMPACT_SCREENSAVER_HEIGHT
        val density = LocalDensity.current
        val travelX = with(density) { maxWidth.toPx() } * DRIFT_FRACTION
        val travelY = with(density) { maxHeight.toPx() } * DRIFT_FRACTION

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
            modifier = Modifier
                .align(Alignment.Center)
                // Held to well under half the panel, because the drift below moves it by
                // [DRIFT_FRACTION] of the panel in each direction and the two together have to
                // stay inside it.
                .fillMaxWidth(if (compact) 0.86f else 0.56f)
                .graphicsLayer {
                    val (dx, dy) = driftAt(elapsed / DRIFT_PERIOD_SECONDS)
                    translationX = dx * travelX
                    translationY = dy * travelY
                },
        ) {
            ScreensaverClock(compact = compact, dim = !ambient)

            val name = channel?.displayName?.takeIf { it.isNotBlank() } ?: headline
            if (name.isNotBlank()) {
                Text(
                    text = name,
                    style = if (compact) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    color = Color.White.copy(alpha = if (ambient) 0.8f else DARK_TEXT_ALPHA),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (tasks.isNotEmpty()) {
                var index by remember(tasks) { mutableIntStateOf(0) }
                LaunchedEffect(tasks) {
                    while (true) {
                        delay(TASK_DWELL_MS)
                        index = (index + 1) % tasks.size
                    }
                }
                val position = index % tasks.size
                // Crossfade rather than a cut, and keyed on the task rather than on the index, so
                // a list that refreshes underneath does not re-animate the card it is already
                // showing. One recomposition every twelve seconds is the whole cost.
                Crossfade(
                    targetState = tasks[position] to position,
                    animationSpec = tween(TASK_FADE_MS, easing = FastOutSlowInEasing),
                    label = "task",
                ) { (task, at) ->
                    TaskCard(task = task, at = at, of = tasks.size, compact = compact)
                }
            }
        }
    }
}

/** The wall clock and today's date, large, in the middle of the drifting block. */
@Composable
private fun ScreensaverClock(compact: Boolean, dim: Boolean) {
    // The *configuration's* locale, as everywhere else in this app: the in-app language wraps
    // this composition's context rather than the JVM default (FR-UI-23).
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        ConfigurationCompat.getLocales(configuration).get(0) ?: Locale.getDefault()
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(MINUTE_MILLIS - now % MINUTE_MILLIS)
        }
    }
    Text(
        text = TimeModel.ltr(TimeModel.formatClock(now)),
        style = if (compact) {
            MaterialTheme.typography.displaySmall
        } else {
            MaterialTheme.typography.displayLarge
        },
        color = Color.White.copy(alpha = if (dim) DARK_TEXT_ALPHA else 1f),
        textAlign = TextAlign.Center,
    )
    Text(
        text = TimeModel.formatFullDate(now, locale),
        style = if (compact) {
            MaterialTheme.typography.labelMedium
        } else {
            MaterialTheme.typography.titleMedium
        },
        color = Color.White.copy(alpha = if (dim) DARK_SECONDARY_ALPHA else 0.75f),
        textAlign = TextAlign.Center,
    )
}

/**
 * One open task.
 *
 * [TextDirection.Content] on both lines of body text on purpose: a Hebrew task read by an
 * English interface — or the other way round — has to lay itself out by what it says, not by
 * which language the menus are in.
 */
@Composable
private fun TaskCard(task: TaskItem, at: Int, of: Int, compact: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color.White.copy(alpha = TASK_CARD_ALPHA),
                shape = RoundedCornerShape(16.dp),
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = TASK_BORDER_ALPHA),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 20.dp, vertical = if (compact) 10.dp else 16.dp),
    ) {
        Text(
            // The section the task came from, and where it sits in the list. The counter is
            // isolated because a bare Latin numeral beside Hebrew reorders otherwise (FR-UI-22).
            text = listOfNotNull(
                task.note,
                TimeModel.ltr("${at + 1}/$of"),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = task.title,
            style = (
                if (compact) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.titleMedium
                }
                ).copy(textDirection = TextDirection.Content),
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        task.due?.let { due ->
            Text(
                text = stringResource(R.string.screensaver_task_due, TimeModel.ltr(due)),
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * How often the drift advances.
 *
 * Not every frame. The block moves a few pixels a second, so twelve steps a second is already
 * finer than the eye can follow on a movement this slow — and it is the difference between an
 * unattended television redrawing two nodes twelve times a second and sixty.
 */
private const val TICK_MS = 80L

/** One horizontal sweep of the drifting block. Slow enough to read as still if you glance up. */
private const val DRIFT_PERIOD_SECONDS = 210f

/** How far the block travels either side of centre, as a fraction of the panel. */
private const val DRIFT_FRACTION = 0.16f

/** The pool of light behind the ambient skin: its own period, longer than the text's. */
private const val GLOW_PERIOD_SECONDS = 340f
private const val GLOW_TRAVEL = 0.22f
private const val GLOW_RADIUS = 0.75f
private const val AMBIENT_GLOW_ALPHA = 0.42f

/** One swing of the station's hue, and how far either side of it the swing reaches. */
private const val HUE_PERIOD_SECONDS = 190f
private const val HUE_SWING_DEGREES = 26f

/**
 * The ambient skin's gradient, darker at both ends than the station backdrop it replaces
 * (0.34/0.06 in `StationBackdrop.kt`). This is the same screen at rest: still the station's
 * colour, and putting less light through the panel while nobody is watching it.
 */
private const val AMBIENT_TOP_LIGHTNESS = 0.20f
private const val AMBIENT_BOTTOM_LIGHTNESS = 0.04f
private const val AMBIENT_GLOW_LIGHTNESS = 0.30f

/** How bright the dark skin's text is. Legible across a room, well short of a lit screen. */
private const val DARK_TEXT_ALPHA = 0.55f
private const val DARK_SECONDARY_ALPHA = 0.4f

private const val TASK_CARD_ALPHA = 0.10f
private const val TASK_BORDER_ALPHA = 0.18f

/** How long one task holds the card, and how long the change between two of them takes. */
private const val TASK_DWELL_MS = 12_000L
private const val TASK_FADE_MS = 700

private const val FADE_IN_MS = 1_200

private const val MINUTE_MILLIS = 60_000L

/** Below this the screensaver is on a phone rather than a panel, and lays out smaller. */
private val COMPACT_SCREENSAVER_HEIGHT = 400.dp
