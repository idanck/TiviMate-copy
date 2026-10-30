package com.tivicopy.player.ui.common

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.news.SourcedFlash
import com.tivicopy.player.data.prefs.FlashSpeed
import com.tivicopy.player.data.tasks.TaskItem
import com.tivicopy.player.player.PlaybackStatus
import com.tivicopy.player.ui.NowPlayingInfo
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The burn-in guard (FR-UI-31, ADR-024), drawn as Studio's screensaver (ADR-037).
 *
 * A television left on a still picture wears the picture into the panel. This app is unusually
 * good at producing one: it holds `FLAG_KEEP_SCREEN_ON` for as long as it is on screen, a paused
 * stream is a frozen frame that stays frozen until somebody comes back to it, and an overlay
 * left open is a wall of unmoving text. The one thing it leaves alone is a channel or a film
 * playing full screen (ADR-040): somebody is watching it, and the picture is its own protection.
 * Everything else is handed over after the idle period — a guide or a menu left open over a
 * playing channel, the home screen, and the radio's own screen included. A live channel left on
 * full screen on the television has a screensaver of its own, after a longer wait, that keeps the
 * picture playing under a clock (ADR-049, `ChannelScreensaver.kt`).
 *
 * What replaces the still screen is the canvas's `TvScreensaverCombo`: a black panel with a
 * studio clock at the start side — a ring of sixty dots filling with the seconds, twelve hour
 * marks, the time and the date — what is behind the screensaver under it, and, when the owner
 * has added news sources, their headlines scrolling down the end side in one continuous column.
 *
 * **It no longer drifts.** ADR-024 moved a block of text around the panel on a path that never
 * closed; ADR-037 holds the clock in place, because a clock that wanders is not one anybody can
 * find at a glance. What protects the panel instead is that almost all of it is black; that the
 * headlines never stop moving; and that every three minutes the whole layout steps a few pixels
 * along the same never-closing path ([shiftAt]) — too little to notice from a sofa, enough that
 * no ring dot or hour mark stands on the same pixels for hours.
 *
 * **This is one of the two things in the app that animate while idle** (the radio screen is the
 * other), which `ui/common/Motion.kt` otherwise forbids outright and FR-UI-30 says out loud. It is
 * kept cheap on the terms that rule is really about: the clock redraws once a second and
 * recomposes once a minute; the column of headlines moves by a `graphicsLayer` translation, so a
 * step of it redraws one layer and recomposes nothing; and nothing runs at frame rate.
 */

// -------------------------------------------------------------------- decisions

/** What the panel is showing, for the purposes of deciding whether it needs protecting. */
enum class IdleScene {
    /**
     * A film, a catch-up programme or a recording playing full screen — with, at most, the bars
     * that ride over it — or Sport and News modes' panels. Left alone: somebody is watching a
     * story with a beginning and an end, and the picture is its own protection.
     */
    MOVING,

    /**
     * A live channel playing full screen on the television, with at most the bars that ride over
     * it (ADR-049). Its picture moves, but a channel left on is also what a room plays to nobody
     * all evening: after its own, longer idle period it is handed to the channel's screensaver
     * ([SaverKind.CHANNEL]), which keeps it playing, dimmed.
     */
    WATCHING,

    /**
     * Anything else: a frozen frame, a station, a guide or a menu (over a playing channel or
     * not), an error, nothing at all. Handed to the screensaver once nobody has touched the
     * remote for the idle period.
     */
    STILL,
}

/**
 * What is on the panel, from what the player reports and where the shell has drawn it (ADR-040).
 *
 * The owner's rule: the screensaver comes on in every state except while a channel is being
 * watched — and a guide or a menu left open over a playing channel is not being watched, it is a
 * still screen that wears into the panel like any other. A station is sound with no picture, and
 * is handed over too, its own screen included (which reverses ADR-030's exception for it).
 *
 * @param playing the stream is actually running — [com.tivicopy.player.player.PlaybackStatus.playing]
 *   rather than the user's intent, because a paused *or* stalled stream is a still frame either way.
 * @param hasPicture the stream has a video track. An audio-only one never produces a frame.
 * @param failed playback has settled on an error, so what is on screen is a message.
 * @param videoFillsPanel the picture is what the panel *is*, rather than something a guide, a
 *   menu or the home screen has covered, docked into a corner or drawn its rails over. The guide's
 *   grid and the home screen's rails do not move for hours however lively the picture beside or
 *   behind them (ADR-024, ADR-039).
 * @param liveChannel what is playing is a live channel, on the television (ADR-049) — not a film,
 *   a catch-up programme or a recording, which run to an end somebody is waiting for, and not the
 *   phone, whose player is held in a hand rather than left on across a room.
 */
fun idleScene(
    playing: Boolean,
    hasPicture: Boolean,
    failed: Boolean,
    videoFillsPanel: Boolean,
    liveChannel: Boolean = false,
): IdleScene = when {
    !(playing && hasPicture && !failed && videoFillsPanel) -> IdleScene.STILL
    liveChannel -> IdleScene.WATCHING
    else -> IdleScene.MOVING
}

/** Which of the two screensavers a screen is handed to (ADR-049). */
enum class SaverKind {
    /** Studio's clock on black, with the headlines beside it (ADR-037): every still screen. */
    CLOCK,

    /**
     * The channel itself, still playing and dimmed, under a clock, the channel and its programme,
     * with the headlines running across the top (ADR-049): a live channel left on full screen.
     */
    CHANNEL,
}

/**
 * Which screensaver, if any, a screen is handed to once its idle period has run out (ADR-049).
 *
 * The master switch ([enabled]) governs both; [channelEnabled] is the channel's own, because
 * dimming a picture somebody may be watching from across the room is a different thing to ask
 * of a television than blacking out a menu nobody is reading. Picture-in-picture is never
 * handed over: the window is somebody else's screen.
 */
fun screensaverFor(enabled: Boolean, channelEnabled: Boolean, scene: IdleScene, inPip: Boolean): SaverKind? = when {
    !enabled || inPip -> null
    scene == IdleScene.STILL -> SaverKind.CLOCK
    scene == IdleScene.WATCHING && channelEnabled -> SaverKind.CHANNEL
    else -> null
}

/** How long a still screen is left alone before the screensaver takes it, in milliseconds. */
fun screensaverDelayMillis(idleMinutes: Int): Long = idleMinutes.coerceIn(1, 120) * 60_000L

/**
 * When the screensaver is due, on the clock [lastInteractionAt] and [stillSince] are read from:
 * the idle period after the later of the last key press and the moment the screen turned still
 * (ADR-040, decision 3). Measured from the key press alone, a picture watched for longer than the
 * idle period was handed over the instant it stalled, since the countdown it then armed had
 * already run out.
 */
fun screensaverDueAt(lastInteractionAt: Long, stillSince: Long, idleMinutes: Int): Long =
    maxOf(lastInteractionAt, stillSince) + screensaverDelayMillis(idleMinutes)

/**
 * When the screensaver of [kind] is due (ADR-049): each on its own idle period, and from a
 * different moment.
 *
 * - **The clock** counts from the later of the last key press and the moment the screen became
 *   still ([sceneSince]), as ADR-040's decision 3 set: a picture watched for an hour and then
 *   stalling is not handed over at the stall.
 * - **The channel** counts from the last key press alone. A live stream rebuffers now and then,
 *   which for that moment is a still screen; counting from when the picture came back would
 *   start the hour again at every stall, and on a provider that drops a frame every twenty
 *   minutes it would never come at all. Nobody has touched the remote: that is the whole test.
 */
fun saverDueAt(
    kind: SaverKind,
    lastInteractionAt: Long,
    sceneSince: Long,
    idleMinutes: Int,
    channelIdleMinutes: Int,
): Long = when (kind) {
    SaverKind.CLOCK -> screensaverDueAt(lastInteractionAt, sceneSince, idleMinutes)
    SaverKind.CHANNEL -> lastInteractionAt + screensaverDelayMillis(channelIdleMinutes)
}

/**
 * The moment the screen became the kind of screen it is, for [saverDueAt]'s `sceneSince`
 * (ADR-040, decision 3; ADR-049).
 *
 * The countdown [stamp]s it every time it runs — on every key press and every change of scene —
 * and it moves only when the kind of screen changes, so a key press on a still screen keeps the
 * moment it turned still. Not Compose state: nothing composes from it, and a write that
 * recomposed the root would restart the countdown that wrote it.
 */
class SceneClock(startedAt: Long) {
    private var kind: SaverKind? = null
    private var since: Long = startedAt

    /** Notes that the screen is now handed to [kind] (null for none) at [now]; when it became so. */
    fun stamp(kind: SaverKind?, now: Long): Long {
        if (kind != this.kind) {
            this.kind = kind
            since = now
        }
        return since
    }
}

/** What the screensaver says is behind it (ADR-037, decision 4). */
sealed interface SaverPlaying {
    /** A station, playing behind the screensaver — its own screen, or a menu left open over it. */
    data class Radio(val channel: Channel, val playing: Boolean) : SaverPlaying

    /** A picture: paused, or playing under an overlay that covers it. */
    data class Video(
        val channel: String?,
        val programme: String?,
        val paused: Boolean,
        val live: Boolean,
    ) : SaverPlaying

    /** Nothing playing, or a stream that failed: the clock alone. */
    data object None : SaverPlaying
}

/**
 * What to print under the clock, from what the player is carrying and what it reports.
 *
 * Named from [nowPlaying] rather than from the current channel, which deliberately outlives an
 * on-demand session: a film paused after a radio station must not be drawn as that station.
 *
 * @param programme the programme on air on the live channel, from the guide; null when unknown.
 */
internal fun saverPlaying(nowPlaying: NowPlayingInfo?, programme: String?, status: PlaybackStatus): SaverPlaying {
    if (status.error != null || nowPlaying == null) return SaverPlaying.None
    val paused = !status.playWhenReady
    val hasPicture = status.width > 0 || status.height > 0
    if (!hasPicture) {
        // Sound with no picture is a station. Playing behind a still screen, it is "now
        // playing"; paused, it is a paused stream like any other.
        val station = nowPlaying.audioOnlyChannel() ?: return SaverPlaying.None
        return if (paused) {
            SaverPlaying.Video(channel = station.displayName, programme = null, paused = true, live = false)
        } else {
            SaverPlaying.Radio(station, playing = status.playing)
        }
    }
    return when (nowPlaying) {
        is NowPlayingInfo.Live -> SaverPlaying.Video(nowPlaying.channel.displayName, programme, paused, live = true)
        is NowPlayingInfo.Catchup ->
            SaverPlaying.Video(nowPlaying.channel.displayName, nowPlaying.programme.title, paused, live = false)
        is NowPlayingInfo.OnDemand -> SaverPlaying.Video(null, nowPlaying.item.name, paused, live = false)
        is NowPlayingInfo.RecordingFile -> SaverPlaying.Video(null, nowPlaying.recording.title, paused, live = false)
    }
}

// ------------------------------------------------------------------------ maths

/**
 * A point on a path that never closes, at [phase], as a fraction of the travel allowed in -1..1
 * on each axis.
 *
 * A Lissajous figure with an *irrational* ratio between its axes ([DRIFT_RATIO] is the golden
 * ratio), so it never retraces itself: ADR-024 drifted the whole screensaver along it, and
 * ADR-037 steps the still layout along it ([shiftAt]).
 */
internal fun driftAt(phase: Float): Pair<Float, Float> {
    val x = sin(TWO_PI * phase)
    val y = sin(TWO_PI * (DRIFT_RATIO * phase + DRIFT_OFFSET))
    return x to y
}

/**
 * Where the whole layout stands after its [step]th shift, in -1..1 of [SHIFT_DP] on each axis.
 *
 * Each step moves the phase on by 1/φ², the golden angle as a fraction of a turn — the step
 * that spreads successive points most evenly — so consecutive positions are far apart and no
 * two are ever the same.
 */
internal fun shiftAt(step: Int): Pair<Float, Float> = driftAt(step * SHIFT_PHASE_STEP)

/**
 * A hue, swung gently either side of itself.
 *
 * Deliberately a swing rather than a rotation through the whole wheel: the canvas's
 * `st-amb-hue` is ±26° over two minutes, enough that the clock's colour is never quite the same
 * twice and not so much that cyan turns into green.
 */
internal fun ambientHue(baseHue: Float, phase: Float, swingDegrees: Float): Float =
    ((baseHue + swingDegrees * sin(TWO_PI * phase)) % 360f + 360f) % 360f

/** Where one of the ring's sixty dots stands against the current second. */
internal enum class RingDot { PASSED, NOW, AHEAD }

internal fun ringDot(index: Int, second: Int): RingDot = when {
    index == second -> RingDot.NOW
    index < second -> RingDot.PASSED
    else -> RingDot.AHEAD
}

/**
 * How far the column of headlines is translated, in pixels, [elapsedSeconds] after it appeared.
 *
 * The column is the same round of headlines repeated; translating it from minus one round's
 * height towards zero, and wrapping, moves it continuously *down* with no seam — the canvas's
 * `st-feed-down`. Computed in doubles, because after a night the distance travelled is in the
 * hundreds of thousands of pixels and a float would start to stutter.
 */
internal fun feedOffset(elapsedSeconds: Float, speedPxPerSecond: Float, roundHeightPx: Float): Float {
    if (roundHeightPx <= 0f) return 0f
    val travelled = (elapsedSeconds.toDouble() * speedPxPerSecond) % roundHeightPx
    return (travelled - roundHeightPx).toFloat()
}

/**
 * How many copies of a round the column needs so the viewport is never short of one: as many as
 * fit, plus the one sliding in from the top. Two at least, which is the canvas's own.
 */
internal fun feedCopies(viewportPx: Float, roundHeightPx: Float): Int =
    if (roundHeightPx <= 0f) 2 else (ceil(viewportPx / roundHeightPx).toInt() + 1).coerceAtLeast(2)

/** A headline's time: the clock for today's, the date and the clock for yesterday's. */
internal fun flashTime(publishedUtc: Long, nowUtc: Long): String =
    if (TimeModel.startOfLocalDay(publishedUtc) == TimeModel.startOfLocalDay(nowUtc)) {
        TimeModel.ltr(TimeModel.formatClock(publishedUtc))
    } else {
        TimeModel.formatDayTime(publishedUtc)
    }

private const val TWO_PI = (2.0 * PI).toFloat()

/** Golden ratio: an axis ratio that never resynchronises, so the path never repeats. */
private const val DRIFT_RATIO = 1.6180339f

/** A quarter turn between the axes, so the path opens out instead of starting as a diagonal. */
private const val DRIFT_OFFSET = 0.25f

/** 1/φ², the golden angle as a fraction of a full turn. */
private const val SHIFT_PHASE_STEP = 0.381966f

// --------------------------------------------------------------------------- ui

/**
 * The screensaver itself, drawn over everything.
 *
 * @param playing what is behind it: a station, a picture, or nothing.
 * @param flashes the headlines to scroll, newest first, or empty for the clock alone.
 * @param flashesUpdatedUtc when the headlines were last fetched, for the line between rounds.
 * @param colours whether the clock and the headlines are drawn in Studio's three soft colours.
 * @param speed how fast the headlines scroll.
 * @param tasks the owner's open tasks, one at a time under the clock (FR-UI-32); the caller passes
 *   them only while a station is behind the screensaver, and empty shows none.
 */
@Composable
fun Screensaver(
    playing: SaverPlaying,
    flashes: List<SourcedFlash>,
    flashesUpdatedUtc: Long,
    colours: Boolean,
    speed: FlashSpeed,
    modifier: Modifier = Modifier,
    tasks: List<TaskItem> = emptyList(),
) {
    val studio = StudioTheme.colors

    // The screensaver arrives as a slow fade rather than a cut: it is replacing a picture
    // somebody may still glance up at, and Motion.ENTRANCE — about a third of a second — would read
    // as the app doing something rather than as the screen settling.
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(FADE_IN_MS, easing = FastOutSlowInEasing)) }

    // The anti burn-in step (ADR-037): every few minutes, a few pixels along a path that never
    // closes, eased so that nobody watching sees a jump.
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
            .background(studio.saverGround),
    ) {
        // The canvas is drawn for a 1080p panel, 960×540dp; every size below is that canvas's,
        // scaled to whatever this screen is. A phone held upright stacks the two halves instead.
        val landscape = maxWidth >= maxHeight
        val scale = if (landscape) {
            min(maxWidth.value / CANVAS_WIDTH_DP, maxHeight.value / CANVAS_HEIGHT_DP)
        } else {
            min(maxWidth.value / PORTRAIT_WIDTH_DP, maxHeight.value / PORTRAIT_HEIGHT_DP)
        }
        val units = SaverUnits(scale, LocalDensity.current)
        val shiftPx = with(LocalDensity.current) { units.dp(SHIFT_DP).toPx() }
        val withFlashes = flashes.isNotEmpty()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = shiftX.value * shiftPx
                    translationY = shiftY.value * shiftPx
                },
        ) {
            if (landscape) {
                ClockBlock(
                    playing = playing,
                    tasks = tasks,
                    colours = colours,
                    units = units,
                    modifier = Modifier
                        // Without headlines the clock is the whole screen, and sits in the middle.
                        .align(if (withFlashes) Alignment.CenterStart else Alignment.Center)
                        .padding(start = if (withFlashes) units.dp(EDGE_DP) else 0.dp)
                        .width(units.dp(CLOCK_COLUMN_DP)),
                )
                if (withFlashes) {
                    FlashColumn(
                        flashes = flashes,
                        updatedUtc = flashesUpdatedUtc,
                        colours = colours,
                        speed = speed,
                        units = units,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = units.dp(EDGE_DP))
                            .width(units.dp(FLASH_COLUMN_DP))
                            .fillMaxHeight(),
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (withFlashes) Arrangement.Top else Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = if (withFlashes) units.dp(EDGE_DP) else 0.dp),
                ) {
                    ClockBlock(
                        playing = playing,
                        tasks = tasks,
                        colours = colours,
                        units = units,
                        modifier = Modifier.width(units.dp(CLOCK_COLUMN_DP)),
                    )
                    if (withFlashes) {
                        Spacer(modifier = Modifier.height(units.dp(PORTRAIT_GAP_DP)))
                        FlashColumn(
                            flashes = flashes,
                            updatedUtc = flashesUpdatedUtc,
                            colours = colours,
                            speed = speed,
                            units = units,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = units.dp(PORTRAIT_SIDE_DP)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The canvas's sizes, scaled to this screen. Text is sized in dp and converted, rather than in
 * sp: the screensaver is laid out as a picture, and a font scale meant for reading menus at arm's
 * length would push its text out of the ring it is centred in.
 */
@Immutable
internal class SaverUnits(
    val scale: Float,
    private val density: Density,
    /** The least the headlines' text may shrink to, for a column read as a panel (News mode). */
    val floors: FlashFloors = FlashFloors.NONE,
) {
    fun dp(value: Float): Dp = (value * scale).dp
    fun sp(value: Float): TextUnit = with(density) { (value * scale).dp.toSp() }

    /** [dp], but never below [floor] dp. */
    fun dp(value: Float, floor: Float): Dp = maxOf(value * scale, floor).dp

    /** [sp], but never below [floor] dp. */
    fun sp(value: Float, floor: Float): TextUnit = with(density) { maxOf(value * scale, floor).dp.toSp() }
}

/**
 * The least the headlines' parts may be, in dp, whatever the column's scale (`TvModeHeadlines`,
 * `PModeHeadlines`). The screensaver's own column has none: it is laid out as a picture. A panel
 * of headlines in News mode is read, and on a narrow panel its scale would take the header and the
 * source's tag below what can be read from a sofa, or below a phone's 12.
 */
@Immutable
internal class FlashFloors(
    val header: Float = 0f,
    val headerLine: Float = 0f,
    val time: Float = 0f,
    val timeLine: Float = 0f,
    val title: Float = 0f,
    val titleLine: Float = 0f,
    val chip: Float = 0f,
    val chipLine: Float = 0f,
    val chipHeight: Float = 0f,
) {
    companion object {
        val NONE = FlashFloors()

        /** 18px and 16px at 1080p for the header and the tag; the tag's plate 28px. */
        val TELEVISION = FlashFloors(header = 9f, headerLine = 11f, chip = 8f, chipLine = 10f, chipHeight = 14f)

        /** The phone's 12 for the header and the tag, 14 for the time, 15 on 20 for the titles. */
        val PHONE = FlashFloors(
            header = 12f,
            headerLine = 16f,
            time = 14f,
            timeLine = 14f,
            title = 15f,
            titleLine = 20f,
            chip = 12f,
            chipLine = 15f,
            chipHeight = 18f,
        )
    }
}

/**
 * The clock, what is behind the screensaver under it, and under that the open tasks, at the
 * canvas's 0.82 opacity.
 */
@Composable
private fun ClockBlock(
    playing: SaverPlaying,
    tasks: List<TaskItem>,
    colours: Boolean,
    units: SaverUnits,
    modifier: Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(units.dp(15f)),
        modifier = modifier.graphicsLayer {
            alpha = CONTENT_ALPHA
            // Each draw at 0.82 rather than one offscreen layer at 0.82: nothing here overlaps,
            // and an offscreen layer would be re-rendered every second for the ring.
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) {
        StudioClock(colours = colours, units = units)
        when (playing) {
            is SaverPlaying.Radio -> RadioLine(playing, colours, units)
            is SaverPlaying.Video -> VideoLine(playing, units)
            SaverPlaying.None -> Unit
        }
        // FR-UI-32: the radio's own screen is handed over now (ADR-040), so the task list that
        // rode along its bottom rides here instead, under the station it is shown with.
        TaskRotation(tasks = tasks, compact = true, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Studio's clock (`TvScreensaverCombo`): sixty dots filling with the seconds, twelve hour marks,
 * the time and the date. In colour, the passed seconds run from the first soft colour to the
 * second, the quarter marks take the first, a faint pool of it sits behind the ring, and the hue
 * of all three swings slowly (`st-amb-hue`).
 */
@Composable
private fun StudioClock(colours: Boolean, units: SaverUnits) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    // The *configuration's* locale, as everywhere else in this app: the in-app language wraps
    // this composition's context rather than the JVM default (FR-UI-23).
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        ConfigurationCompat.getLocales(configuration).get(0) ?: Locale.getDefault()
    }

    // A tick on every second, read by the ring in its draw lambda — so the ring redraws once a
    // second and nothing recomposes until the minute, or the day, changes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(SECOND_MILLIS - now % SECOND_MILLIS)
        }
    }
    val time by remember { derivedStateOf { TimeModel.ltr(TimeModel.formatClock(now)) } }
    val date by remember(locale) { derivedStateOf { TimeModel.formatShortDate(now, locale) } }
    val startedAt = remember { SystemClock.elapsedRealtime() }

    val first = studio.saverTones[0].toArgb()
    val second = studio.saverTones[1].toArgb()
    val passedGrey = PASSED_GREY
    val ahead = Color.White.copy(alpha = AHEAD_ALPHA)
    val ink = studio.ink
    val ink3 = studio.ink3
    val glowReach = units.dp(GLOW_REACH_DP)

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(units.dp(RING_BOX_DP))) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val second60 = ((now / SECOND_MILLIS) % 60).toInt()
            val phase = (SystemClock.elapsedRealtime() - startedAt) / 1000f / HUE_PERIOD_SECONDS - 0.25f
            fun tone(argb: Int): Color {
                val hsl = rgbToHsl(argb)
                return Color(hslToArgb(ambientHue(hsl[0], phase, HUE_SWING_DEGREES), hsl[1], hsl[2]))
            }
            val half = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            val c1 = if (colours) tone(first) else Color.Transparent
            val c2 = if (colours) tone(second) else Color.Transparent

            if (colours) {
                val reach = half + glowReach.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to c1.copy(alpha = GLOW_ALPHA),
                        GLOW_FADE to Color.Transparent,
                        center = centre,
                        radius = reach,
                    ),
                    radius = reach,
                    center = centre,
                )
            }
            for (i in 0 until 12) {
                val angle = i / 12f * TWO_PI - TWO_PI / 4f
                drawCircle(
                    color = if (colours && i % 3 == 0) c1 else ink3,
                    radius = half * MARK_RADIUS,
                    center = centre + Offset(cos(angle), sin(angle)) * (half * MARK_ORBIT),
                )
            }
            for (i in 0 until 60) {
                val angle = i / 60f * TWO_PI - TWO_PI / 4f
                val colour = when (ringDot(i, second60)) {
                    RingDot.NOW -> ink
                    RingDot.AHEAD -> ahead
                    RingDot.PASSED -> if (colours) lerp(c1, c2, i / 59f) else passedGrey
                }
                drawCircle(
                    color = colour,
                    radius = half * DOT_RADIUS,
                    center = centre + Offset(cos(angle), sin(angle)) * (half * DOT_ORBIT),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(units.dp(3f)),
        ) {
            Text(
                text = time,
                style = type.h1.copy(
                    fontSize = units.sp(54f),
                    lineHeight = units.sp(54f),
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = units.sp(-1f),
                ),
                color = studio.ink,
            )
            Text(
                text = date,
                style = type.meta.copy(fontSize = units.sp(13f), lineHeight = units.sp(17f)),
                color = studio.ink2,
            )
        }
    }
}

/** "Now playing", the station's disc and name, and the equaliser (ADR-037, decision 4). */
@Composable
private fun RadioLine(radio: SaverPlaying.Radio, colours: Boolean, units: SaverUnits) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(units.dp(10f)),
    ) {
        StationDisc(channel = radio.channel, size = units.dp(42f))
        Column(
            verticalArrangement = Arrangement.spacedBy(units.dp(2f)),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Text(
                text = stringResource(R.string.screensaver_now_playing),
                style = type.body.copy(
                    fontSize = units.sp(11f),
                    lineHeight = units.sp(14f),
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (colours) studio.saverTones[0] else studio.ink2,
            )
            Text(
                text = radio.channel.displayName,
                style = type.h1.copy(
                    fontSize = units.sp(19f),
                    lineHeight = units.sp(21f),
                    fontWeight = FontWeight.SemiBold,
                    textDirection = TextDirection.Content,
                ),
                color = studio.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The sound itself, measured from the player's own output (ADR-031).
        Equalizer(
            playing = radio.playing,
            description = stringResource(R.string.radio_equalizer),
            modifier = Modifier.width(units.dp(55f)).height(units.dp(22f)),
        )
    }
}

/** The picture behind the screensaver: paused, or live under an overlay; its channel and programme. */
@Composable
private fun VideoLine(video: SaverPlaying.Video, units: SaverUnits) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(units.dp(4f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(units.dp(7f)),
        ) {
            if (video.paused) {
                PausedBadge(units)
            } else if (video.live) {
                LiveBadge()
            }
            video.channel?.let { name ->
                Text(
                    text = name,
                    style = type.rowLabel.copy(
                        fontSize = units.sp(13f),
                        lineHeight = units.sp(17f),
                        textDirection = TextDirection.Content,
                    ),
                    color = studio.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        video.programme?.let { title ->
            Text(
                text = title,
                style = type.titleSmall.copy(
                    fontSize = units.sp(32f),
                    // 1.1 of the size: Noto's Hebrew needs the room Karantina's condensed lines did not.
                    lineHeight = units.sp(35.2f),
                    textDirection = TextDirection.Content,
                ),
                color = studio.ink,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PausedBadge(units: SaverUnits) {
    val studio = StudioTheme.colors
    val shape = MaterialTheme.shapes.extraSmall
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(units.dp(4f)),
        modifier = Modifier
            .height(units.dp(17f))
            .clip(shape)
            .background(studio.surface)
            .border(1.dp, studio.hairlineSoft, shape)
            .padding(horizontal = units.dp(7f)),
    ) {
        Icon(
            imageVector = StudioIcons.Pause,
            contentDescription = null,
            tint = studio.ink2,
            modifier = Modifier.size(units.dp(10f)),
        )
        Text(
            text = stringResource(R.string.screensaver_paused),
            style = StudioTheme.type.caption.copy(
                fontSize = units.sp(10f),
                lineHeight = units.sp(12f),
                fontWeight = FontWeight.SemiBold,
            ),
            color = studio.ink2,
        )
    }
}

/**
 * The headlines (FR-UI-33): one column the height of the screen, moving down without stopping,
 * newest at the top of each round, the round's header between one and the next, and the top and
 * bottom fading into the black. News mode's panel of headlines is this column too (ADR-042).
 */
@Composable
internal fun FlashColumn(
    flashes: List<SourcedFlash>,
    updatedUtc: Long,
    colours: Boolean,
    speed: FlashSpeed,
    units: SaverUnits,
    modifier: Modifier,
) {
    val ground = StudioTheme.colors.saverGround
    val sources = remember(flashes) { flashes.map { it.colourIndex }.distinct().size }
    val asOf = TimeModel.ltr(TimeModel.formatClock(if (updatedUtc > 0) updatedUtc else System.currentTimeMillis()))
    val header = if (sources == 1) {
        stringResource(R.string.screensaver_flashes_header_one, asOf)
    } else {
        stringResource(R.string.screensaver_flashes_header, sources, asOf)
    }
    val speedPx = with(LocalDensity.current) { units.dp(speed.dpPerSecond).toPx() }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            // Drawn over the column at full strength, outside its 0.82: the canvas's mask, which
            // on a black ground is the same thing as black fading in at each end.
            .drawWithContent {
                drawContent()
                val edge = size.height * FEED_EDGE
                drawRect(
                    brush = Brush.verticalGradient(listOf(ground, Color.Transparent), startY = 0f, endY = edge),
                    size = Size(size.width, edge),
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, ground),
                        startY = size.height - edge,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - edge),
                    size = Size(size.width, edge),
                )
            },
    ) {
        val viewport = constraints.maxHeight.toFloat()
        var roundHeight by remember { mutableIntStateOf(0) }
        val copies = feedCopies(viewport, roundHeight.toFloat())

        // Its own clock, finer than the ring's: text moving continuously reads as stepping at a
        // dozen steps a second. A step is a layer translation, so it redraws one layer.
        val startedAt = remember { SystemClock.elapsedRealtime() }
        var elapsed by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(FEED_TICK_MS)
                elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000f
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .graphicsLayer {
                    translationY = feedOffset(elapsed, speedPx, roundHeight.toFloat())
                    alpha = CONTENT_ALPHA
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) {
            repeat(copies) { copy ->
                FlashRound(
                    flashes = flashes,
                    header = header,
                    colours = colours,
                    units = units,
                    modifier = if (copy == 0) {
                        Modifier.onSizeChanged { roundHeight = it.height }
                    } else {
                        // The copies are scenery: a screen reader reads the headlines once.
                        Modifier.clearAndSetSemantics { }
                    },
                )
            }
        }
    }
}

@Composable
private fun FlashRound(
    flashes: List<SourcedFlash>,
    header: String,
    colours: Boolean,
    units: SaverUnits,
    modifier: Modifier,
) {
    val studio = StudioTheme.colors
    val nowUtc = remember(flashes) { System.currentTimeMillis() }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = header,
            style = StudioTheme.type.meta.copy(
                fontSize = units.sp(11f, units.floors.header),
                lineHeight = units.sp(14f, units.floors.headerLine),
                fontWeight = FontWeight.SemiBold,
            ),
            color = studio.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = units.dp(15f), bottom = units.dp(2f)),
        )
        flashes.forEach { flash -> FlashRow(flash, nowUtc, colours, units) }
    }
}

/** One headline: the source's bar, the time, the source's chip, and up to two lines of title. */
@Composable
private fun FlashRow(item: SourcedFlash, nowUtc: Long, colours: Boolean, units: SaverUnits) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val tone = studio.saverTones[item.colourIndex % studio.saverTones.size]
    val hairline = studio.hairlineSoft
    Row(
        horizontalArrangement = Arrangement.spacedBy(units.dp(11f)),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .padding(vertical = units.dp(12f))
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(units.dp(3f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(units.dp(1.5f)))
                .background(if (colours) tone else studio.ink3),
        )
        Column(verticalArrangement = Arrangement.spacedBy(units.dp(6f))) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(units.dp(8f)),
            ) {
                item.flash.publishedUtc?.let { at ->
                    Text(
                        text = flashTime(at, nowUtc),
                        style = type.h1.copy(
                            fontSize = units.sp(15f, units.floors.time),
                            lineHeight = units.sp(15f, units.floors.timeLine),
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = studio.ink,
                    )
                }
                SourceChip(label = item.source, tone = tone, colours = colours, units = units)
            }
            Text(
                text = item.flash.title,
                style = type.h1.copy(
                    // Was 20sp on 24 until 0.18.1, which the owner found too large.
                    fontSize = units.sp(17f, units.floors.title),
                    lineHeight = units.sp(21f, units.floors.titleLine),
                    fontWeight = FontWeight.SemiBold,
                    // A Hebrew headline under an English interface, or the other way round,
                    // lays itself out by what it says.
                    textDirection = TextDirection.Content,
                ),
                color = studio.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SourceChip(label: String, tone: Color, colours: Boolean, units: SaverUnits) {
    val studio = StudioTheme.colors
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .height(units.dp(16f, units.floors.chipHeight))
            .widthIn(max = units.dp(CHIP_MAX_DP))
            .clip(shape)
            .background(if (colours) tone.copy(alpha = CHIP_FILL_ALPHA) else studio.surface)
            .border(1.dp, if (colours) tone.copy(alpha = CHIP_LINE_ALPHA) else studio.hairlineSoft, shape)
            .padding(horizontal = units.dp(6f)),
    ) {
        Text(
            text = label,
            style = StudioTheme.type.caption.copy(
                fontSize = units.sp(9.5f, units.floors.chip),
                lineHeight = units.sp(12f, units.floors.chipLine),
                fontWeight = FontWeight.SemiBold,
                textDirection = TextDirection.Content,
            ),
            color = if (colours) tone else studio.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The open tasks, one at a time (FR-UI-32): twelve seconds each, cross-faded, under the
 * screensaver's clock while a station plays behind it (ADR-040).
 */
@Composable
internal fun TaskRotation(tasks: List<TaskItem>, compact: Boolean, modifier: Modifier = Modifier) {
    if (tasks.isEmpty()) return
    var index by remember(tasks) { mutableIntStateOf(0) }
    LaunchedEffect(tasks) {
        while (true) {
            delay(TASK_DWELL_MS)
            index = (index + 1) % tasks.size
        }
    }
    val position = index % tasks.size
    // Crossfade rather than a cut, and keyed on the task rather than on the index, so a list that
    // refreshes underneath does not re-animate the card it is already showing. One recomposition
    // every twelve seconds is the whole cost.
    Crossfade(
        targetState = tasks[position] to position,
        animationSpec = tween(TASK_FADE_MS, easing = FastOutSlowInEasing),
        label = "task",
        modifier = modifier,
    ) { (task, at) ->
        TaskCard(task = task, at = at, of = tasks.size, compact = compact)
    }
}

/**
 * One open task, on a card of thin glass in the three inks (`TvScreensaverTasks`, `PScreensaver`;
 * ADR-046): the section and the counter in the third ink, the task in ink, its due date in the
 * second.
 *
 * [TextDirection.Content] on both lines of body text on purpose: a Hebrew task read by an
 * English interface — or the other way round — has to lay itself out by what it says, not by
 * which language the menus are in.
 */
@Composable
private fun TaskCard(task: TaskItem, at: Int, of: Int, compact: Boolean) {
    val studio = StudioTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            // `--st-r-card`: 24px at 1080p, 20 on a phone.
            .glass(GlassMaterial.THIN, RoundedCornerShape(if (StudioTheme.television) 12.dp else 20.dp))
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
            color = studio.ink3,
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
            color = studio.ink,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        task.due?.let { due ->
            Text(
                text = stringResource(R.string.screensaver_task_due, TimeModel.ltr(due)),
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                color = studio.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The canvas's panel, in dp, and the phone held upright it is re-laid out for. */
private const val CANVAS_WIDTH_DP = 960f
private const val CANVAS_HEIGHT_DP = 540f
private const val PORTRAIT_WIDTH_DP = 420f
private const val PORTRAIT_HEIGHT_DP = 880f

/** The canvas's 96px from each edge, 520px for the clock and 760px for the headlines. */
private const val EDGE_DP = 48f
private const val CLOCK_COLUMN_DP = 260f
private const val FLASH_COLUMN_DP = 380f
private const val PORTRAIT_GAP_DP = 24f
private const val PORTRAIT_SIDE_DP = 24f

/** The ring's 480px box; its dots and marks as fractions of half of it (200/240 and 226/240). */
private const val RING_BOX_DP = 240f
private const val DOT_ORBIT = 200f / 240f
private const val DOT_RADIUS = 4.5f / 240f
private const val MARK_ORBIT = 226f / 240f
private const val MARK_RADIUS = 6f / 240f

/** The pool of colour behind the ring: 60px past it, 14% strong, gone by 68% of the way out. */
private const val GLOW_REACH_DP = 30f
private const val GLOW_ALPHA = 0.14f
private const val GLOW_FADE = 0.68f

/** A passed second without colour, and one still to come. */
private val PASSED_GREY = Color(0xB3AEB8C8)
private const val AHEAD_ALPHA = 0.08f

/** `st-amb-hue`: ±26° over the 120-second ambient cycle. */
private const val HUE_PERIOD_SECONDS = 120f
private const val HUE_SWING_DEGREES = 26f

/** Everything on the screensaver is drawn at 0.82 (`TvScreensaverCombo`). */
private const val CONTENT_ALPHA = 0.82f

/** How much of the headline column fades into the black at each end. */
private const val FEED_EDGE = 0.14f

/** How often the headlines step: smooth for slow text, still nowhere near frame rate. */
private const val FEED_TICK_MS = 40L

private const val CHIP_MAX_DP = 140f
private const val CHIP_FILL_ALPHA = 0.16f
private const val CHIP_LINE_ALPHA = 0.40f

/**
 * The anti burn-in step: how far, how often and how slowly (ADR-037). Six dp is about twelve
 * pixels on a 1080p panel — more than a ring dot is wide, so none of them lands where it was.
 * The channel's screensaver steps as often and as slowly, further (ADR-049).
 */
private const val SHIFT_DP = 6f
internal const val SHIFT_EVERY_MS = 3L * 60 * 1000
internal const val SHIFT_MS = 2_000

/** How long one task holds the card, and how long the change between two of them takes. */
private const val TASK_DWELL_MS = 12_000L
private const val TASK_FADE_MS = 700

private const val FADE_IN_MS = 1_200

private const val SECOND_MILLIS = 1_000L
