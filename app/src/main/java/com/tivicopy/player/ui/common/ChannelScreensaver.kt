package com.tivicopy.player.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The channel's own screensaver (ADR-049): what a live channel left on full screen on the
 * television becomes once nobody has touched the remote for its idle period (an hour by default).
 *
 * Unlike Studio's clock on black ([Screensaver]) it does not take the picture away. The channel
 * keeps playing, dimmed by a third ([DIM_ALPHA]), so a room that has the news on in the background
 * still has the news on; what changes is that the panel stops showing the same picture at full
 * strength to nobody, and that it says, from across the room, what time it is and what is on:
 *
 * - **across the top**, News mode's running strip of headlines ([NewsTicker], ADR-045), as a bar
 *   floating inside the safe area — only when there are headlines to run (the switch for news
 *   beside the clock is on and a source has some); otherwise nothing;
 * - **at the bottom, on the end side** (`Alignment.BottomEnd`: the bottom-left corner in Hebrew,
 *   mirrored to the bottom right in English, as every layout in the app is), one plate of glass:
 *   the time, large; the channel's mark, number and name; and the programme on air with its hours
 *   and a thin bar of how far through it is.
 *
 * Everything sits inside the television's safe area (48dp by 27dp, the lower third's), and the
 * whole of it steps a few pixels every three minutes along the same never-closing path the clock
 * screensaver steps along ([shiftAt], ADR-037), so neither the plate nor the strip's label stands
 * on the same pixels for hours over a picture that may itself be holding a channel's logo still.
 *
 * Its cost is FR-UI-30's exception, on the same terms as the clock's: the strip moves a layer a
 * frame, and only while it is shown; the plate recomposes once a minute for the time and the bar;
 * the step is two `Animatable`s read in a `graphicsLayer`, eased over two seconds every three
 * minutes; and the dimming is one rectangle, drawn once.
 *
 * @param channel the live channel playing behind it; null draws the clock alone.
 * @param programme what the guide says is on air now, or null when it does not know.
 * @param headlines whether to run the strip of headlines across the top.
 * @param opacity the viewer's overlay-opacity setting (FR-UI-18), with [PLATE_MIN_OPACITY] under it.
 * @param onProgrammeEnded asks for the guide's "now" again. Nothing else refreshes it while nobody
 *   zaps, and an hour unattended is usually at least one programme later than the last zap.
 */
@Composable
fun ChannelScreensaver(
    channel: Channel?,
    programme: Programme?,
    headlines: Boolean,
    opacity: Float,
    onProgrammeEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors

    // As the clock's screensaver arrives: a slow fade, read in the draw phase, so the picture
    // settles into its dimmer self rather than cutting to it.
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(CHANNEL_SAVER_FADE_MS, easing = FastOutSlowInEasing)) }

    // The anti burn-in step, as the clock's (ADR-037): every three minutes, eased over two seconds,
    // to the next point of a path that never closes. Further than the clock's six: the time here is
    // drawn larger, and its strokes are wider than six dp.
    val shiftX = remember { Animatable(0f) }
    val shiftY = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var step = 0
        while (true) {
            delay(SHIFT_EVERY_MS)
            step++
            val (x, y) = shiftAt(step)
            launch { shiftX.animateTo(x, tween(SHIFT_MS, easing = FastOutSlowInEasing)) }
            shiftY.animateTo(y, tween(SHIFT_MS, easing = FastOutSlowInEasing))
        }
    }
    val shiftPx = with(LocalDensity.current) { CHANNEL_SHIFT.toPx() }

    // The guide's "now" is refreshed by a zap, and nobody zaps here: ask again when the programme
    // on screen ends, and once on arrival if it already has. Keyed on its end, so the answer —
    // the next programme, or nothing — rearms this once rather than asking in a loop.
    val programmeEnded by rememberUpdatedState(onProgrammeEnded)
    LaunchedEffect(programme?.endUtc) {
        val end = programme?.endUtc
        if (end != null) {
            val wait = end - System.currentTimeMillis()
            if (wait > 0) delay(wait + PROGRAMME_ROLL_SLACK_MS)
        }
        programmeEnded()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // A tap wakes a phone and does nothing else, as on the clock's screensaver; the
            // remote's half of the rule is `MainActivity.dispatchKeyEvent`.
            .pointerInput(Unit) { detectTapGestures { } }
            .graphicsLayer { alpha = fade.value }
            // The picture, a third darker. One flat rectangle: no edge anywhere to wear in.
            .background(studio.saverGround.copy(alpha = DIM_ALPHA)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = SAVER_SAFE_X, vertical = SAVER_SAFE_Y)
                .graphicsLayer {
                    translationX = shiftX.value * shiftPx
                    translationY = shiftY.value * shiftPx
                },
        ) {
            if (headlines) {
                // News mode's own strip (ADR-045), floating as a bar rather than banded across the
                // top edge: inside the safe area, and with nothing of it touching the panel's edge.
                // `live` is false because the activity already keeps the headlines fresh for as
                // long as either screensaver is up.
                val bar = RoundedCornerShape(TICKER_CORNER)
                NewsTicker(
                    live = false,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .height(TICKER_HEIGHT)
                        .clip(bar)
                        .litEdge(bar),
                )
            }
            NowOnAirPlate(
                channel = channel,
                programme = programme,
                opacity = opacity,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * The plate in the corner: the time, large, over the channel and what it is showing. Recomposes
 * once a minute, when the clock moves and the bar with it.
 */
@Composable
private fun NowOnAirPlate(channel: Channel?, programme: Programme?, opacity: Float, modifier: Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(MINUTE_MS - now % MINUTE_MS)
        }
    }
    Column(
        modifier = modifier
            .width(PLATE_WIDTH)
            .panelSurface(
                opacity = opacity.coerceAtLeast(PLATE_MIN_OPACITY),
                shape = MaterialTheme.shapes.large,
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val clock = type.hero
        Text(
            text = TimeModel.ltr(TimeModel.formatClock(now)),
            style = clock.copy(
                fontSize = clock.fontSize * PLATE_CLOCK_SCALE,
                lineHeight = clock.fontSize * PLATE_CLOCK_SCALE,
                fontWeight = FontWeight.SemiBold,
            ),
            color = studio.ink,
            maxLines = 1,
        )
        if (channel != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The channel's own mark, or its monogram when it has none or it will not load.
                ChannelLogo(channel = channel, sizeDp = LOGO_DP)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (channel.number > 0) {
                            Text(
                                // Isolated, so a Latin numeral beside Hebrew keeps its place (FR-UI-22).
                                text = TimeModel.ltr(channel.number.toString()),
                                style = type.rowLabel,
                                color = studio.ink2,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = channel.displayName,
                            style = type.rowLabel.copy(textDirection = TextDirection.Content),
                            color = studio.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = programme?.title ?: stringResource(R.string.no_guide_data),
                        style = type.h1.copy(textDirection = TextDirection.Content),
                        color = if (programme != null) studio.ink else studio.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            programme?.let { on ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Text(
                        text = TimeModel.formatClockRange(on.startUtc, on.endUtc),
                        style = type.meta,
                        color = studio.ink2,
                        maxLines = 1,
                    )
                    StudioProgress(
                        fraction = airedFraction(on.startUtc, on.endUtc, now),
                        height = PROGRESS_HEIGHT,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** How far through a programme from [startUtc] to [endUtc] the clock at [nowUtc] is, 0..1. */
internal fun airedFraction(startUtc: Long, endUtc: Long, nowUtc: Long): Float {
    val span = (endUtc - startUtc).coerceAtLeast(1L)
    return ((nowUtc - startUtc).toFloat() / span).coerceIn(0f, 1f)
}

/** The picture under the channel's screensaver, dimmed by this much (the owner's "about 30%"). */
private const val DIM_ALPHA = 0.3f

/** The television's safe area, as the lower third and the side menu keep it (1080p pixels halved). */
private val SAVER_SAFE_X = 48.dp
private val SAVER_SAFE_Y = 27.dp

/**
 * How far the step moves everything, either way on each axis: about twenty pixels at 1080p, more
 * than the large time's strokes are wide, so no digit lands where it stood.
 */
private val CHANNEL_SHIFT = 10.dp

/** News mode's strip on a television (`SportScreen`), here a bar with its corners rounded. */
private val TICKER_HEIGHT = 48.dp
private val TICKER_CORNER = 12.dp

/** The plate: the lower third's tile proportions, wide enough for a programme title. */
private val PLATE_WIDTH = 400.dp
private const val PLATE_MIN_OPACITY = 0.8f
private const val LOGO_DP = 44
private val PROGRESS_HEIGHT = 3.dp

/** The time: 120px at 1080p against the hero step's 88. */
private const val PLATE_CLOCK_SCALE = 60f / 44f

/** The fade in, as slow as the clock's screensaver's. */
private const val CHANNEL_SAVER_FADE_MS = 1_200

/** A guide's programmes meet on the minute; ask a moment after, so "now" is the next one. */
private const val PROGRAMME_ROLL_SLACK_MS = 5_000L

private const val MINUTE_MS = 60_000L
