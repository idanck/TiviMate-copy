package com.idanck.shidur.ui.common

import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.player.PlaybackStatus
import com.idanck.shidur.ui.NowPlayingInfo
import com.idanck.shidur.ui.tv.TvOverlay
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screensaver's decisions and its arithmetic (FR-UI-31).
 *
 * Nothing here renders a pixel — this environment cannot — so the whole point of
 * `Screensaver.kt` keeping its judgement and its geometry in pure top-level functions is that
 * the two things that can actually be wrong are testable: *when* the screen is taken over, and
 * whether what then moves across it really covers the panel instead of tracing one loop.
 */
class ScreensaverTest {

    // ------------------------------------------------------------------ scenes

    /**
     * The owner's rule (ADR-040): the screensaver comes on in every state except while a picture
     * is being watched — full screen, with at most the bars that ride over the video. A film, a
     * catch-up programme or a recording is left alone for as long as it plays (ADR-049).
     */
    @Test
    fun `a film playing full screen is left alone`() {
        assertEquals(
            IdleScene.MOVING,
            idleScene(playing = true, hasPicture = true, failed = false, videoFillsPanel = true),
        )
        assertEquals(
            null,
            screensaverFor(enabled = true, channelEnabled = true, scene = IdleScene.MOVING, inPip = false),
        )
    }

    /**
     * ADR-049: a live channel full screen on the television is a picture being watched too, but it
     * is also what a room plays to nobody all evening, so it has a screensaver of its own.
     */
    @Test
    fun `a live channel playing full screen is watched, and goes to the channel's screensaver`() {
        val scene = idleScene(
            playing = true,
            hasPicture = true,
            failed = false,
            videoFillsPanel = true,
            liveChannel = true,
        )
        assertEquals(IdleScene.WATCHING, scene)
        assertEquals(
            SaverKind.CHANNEL,
            screensaverFor(enabled = true, channelEnabled = true, scene = scene, inPip = false),
        )
    }

    /**
     * Not radio, not menus, not paused (ADR-049): a live channel that is not filling the panel, not
     * playing, or that failed is a still screen like any other, and gets the clock as before.
     */
    @Test
    fun `a live channel under a menu, paused, failed or with no picture is still`() {
        assertEquals(
            IdleScene.STILL,
            idleScene(playing = true, hasPicture = true, failed = false, videoFillsPanel = false, liveChannel = true),
        )
        assertEquals(
            IdleScene.STILL,
            idleScene(playing = false, hasPicture = true, failed = false, videoFillsPanel = true, liveChannel = true),
        )
        assertEquals(
            IdleScene.STILL,
            idleScene(playing = true, hasPicture = true, failed = true, videoFillsPanel = true, liveChannel = true),
        )
        // A station is a live channel with no picture: the radio keeps the clock's screensaver.
        assertEquals(
            IdleScene.STILL,
            idleScene(playing = true, hasPicture = false, failed = false, videoFillsPanel = true, liveChannel = true),
        )
    }

    /**
     * A guide or a menu left open over a playing channel: the picture is still running, but it
     * is a preview beside a grid, or behind a page, that has not changed in an hour.
     */
    @Test
    fun `a channel playing under a guide or a menu is a still screen`() {
        assertEquals(
            IdleScene.STILL,
            idleScene(playing = true, hasPicture = true, failed = false, videoFillsPanel = false),
        )
    }

    /** The home screen's rails and furniture hold still over its banner's picture (ADR-039). */
    @Test
    fun `the home screen is a still screen, with a channel playing behind its banner`() {
        assertEquals(
            IdleScene.STILL,
            idleScene(
                playing = true,
                hasPicture = true,
                failed = false,
                videoFillsPanel = TvOverlay.HOME.videoFillsPanel,
            ),
        )
    }

    /** What still counts as the picture being the screen: the bare video and what rides over it. */
    @Test
    fun `only the bare video and its passthrough overlays leave the picture as the screen`() {
        val filling = TvOverlay.entries.filter { it.videoFillsPanel }.toSet()
        assertEquals(
            setOf(
                TvOverlay.NONE,
                TvOverlay.INFO,
                TvOverlay.MINI_GUIDE,
                TvOverlay.TRANSPORT,
                TvOverlay.PLAYBACK_MENU,
            ),
            filling,
        )
    }

    @Test
    fun `a paused film is a still screen`() {
        assertEquals(
            IdleScene.STILL,
            idleScene(playing = false, hasPicture = true, failed = false, videoFillsPanel = true),
        )
    }

    /**
     * A station is sound with no picture, and is handed over like any other screen that is not a
     * channel being watched — its own screen included, which ADR-030 used to exempt.
     */
    @Test
    fun `a station playing on its own screen is a still screen`() {
        assertEquals(
            IdleScene.STILL,
            idleScene(playing = true, hasPicture = false, failed = false, videoFillsPanel = true),
        )
    }

    @Test
    fun `nothing playing is a still screen`() {
        assertEquals(
            IdleScene.STILL,
            idleScene(playing = false, hasPicture = false, failed = false, videoFillsPanel = true),
        )
    }

    /** A settled error is a message on screen, and a message holds as still as anything else. */
    @Test
    fun `a failed stream is a still screen`() {
        assertEquals(
            IdleScene.STILL,
            idleScene(playing = true, hasPicture = true, failed = true, videoFillsPanel = true),
        )
    }

    // ------------------------------------------------------------------ arming

    @Test
    fun `the guard is off when the setting is off, in picture-in-picture, and over a film being watched`() {
        assertEquals(
            SaverKind.CLOCK,
            screensaverFor(enabled = true, channelEnabled = true, scene = IdleScene.STILL, inPip = false),
        )
        assertEquals(
            null,
            screensaverFor(enabled = false, channelEnabled = true, scene = IdleScene.STILL, inPip = false),
        )
        assertEquals(
            null,
            screensaverFor(enabled = true, channelEnabled = true, scene = IdleScene.STILL, inPip = true),
        )
        assertEquals(
            null,
            screensaverFor(enabled = true, channelEnabled = true, scene = IdleScene.MOVING, inPip = false),
        )
    }

    /**
     * ADR-049: the master switch turns both screensavers off; the channel's own switch turns off
     * only the channel's, and leaves a still screen to the clock as before.
     */
    @Test
    fun `the channel's screensaver answers to both switches, the clock's to the master one alone`() {
        assertEquals(
            null,
            screensaverFor(enabled = false, channelEnabled = true, scene = IdleScene.WATCHING, inPip = false),
        )
        assertEquals(
            null,
            screensaverFor(enabled = true, channelEnabled = false, scene = IdleScene.WATCHING, inPip = false),
        )
        assertEquals(
            null,
            screensaverFor(enabled = true, channelEnabled = true, scene = IdleScene.WATCHING, inPip = true),
        )
        assertEquals(
            SaverKind.CLOCK,
            screensaverFor(enabled = true, channelEnabled = false, scene = IdleScene.STILL, inPip = false),
        )
    }

    @Test
    fun `the delay is minutes, with a floor and a ceiling`() {
        assertEquals(600_000L, screensaverDelayMillis(10))
        // Never zero: "off" is the switch, not a timeout of nothing, and a screensaver that
        // armed instantly would take the screen while somebody was reading the guide.
        assertEquals(60_000L, screensaverDelayMillis(0))
        assertEquals(60_000L, screensaverDelayMillis(-5))
        assertEquals(120L * 60_000L, screensaverDelayMillis(9_999))
    }

    /**
     * ADR-040, decision 3: the countdown runs from the later of the last key press and the screen
     * turning still. A match watched for an hour and then stalling for a second is not handed
     * over at the stall.
     */
    @Test
    fun `a screen that turned still after the last key press is left alone for the idle period`() {
        val pressed = 1_000_000L
        val stalled = pressed + 3_600_000L
        assertEquals(stalled + 600_000L, screensaverDueAt(pressed, stalled, 10))
    }

    /** A key press on a screen that was already still restarts the countdown from the press. */
    @Test
    fun `a key press on a still screen restarts the countdown`() {
        val still = 1_000_000L
        val pressed = still + 120_000L
        assertEquals(pressed + 600_000L, screensaverDueAt(pressed, still, 10))
    }

    /** ADR-049: each screensaver waits its own idle period — ten minutes for the clock, an hour for the channel. */
    @Test
    fun `each screensaver waits its own idle period`() {
        val pressed = 1_000_000L
        assertEquals(
            pressed + 10 * 60_000L,
            saverDueAt(SaverKind.CLOCK, pressed, sceneSince = pressed, idleMinutes = 10, channelIdleMinutes = 60),
        )
        assertEquals(
            pressed + 60 * 60_000L,
            saverDueAt(SaverKind.CHANNEL, pressed, sceneSince = pressed, idleMinutes = 10, channelIdleMinutes = 60),
        )
        assertEquals(
            pressed + 120 * 60_000L,
            saverDueAt(SaverKind.CHANNEL, pressed, sceneSince = pressed, idleMinutes = 10, channelIdleMinutes = 120),
        )
    }

    /**
     * ADR-049: the channel's hour runs from the last key press, not from the moment the picture
     * last came back. A live stream that rebuffered forty minutes in is still due at the hour.
     */
    @Test
    fun `a stall does not restart the channel's hour`() {
        val pressed = 1_000_000L
        val recovered = pressed + 40 * 60_000L
        assertEquals(
            pressed + 60 * 60_000L,
            saverDueAt(SaverKind.CHANNEL, pressed, sceneSince = recovered, idleMinutes = 10, channelIdleMinutes = 60),
        )
    }

    /**
     * The clock's countdown, though, runs from the moment the screen turned still (ADR-040,
     * decision 3): a channel watched for fifty minutes and then failing is not blacked out at the
     * failure, but ten minutes after it.
     */
    @Test
    fun `a channel that fails after a long watch is given the clock's full wait`() {
        val pressed = 1_000_000L
        val failed = pressed + 50 * 60_000L
        assertEquals(
            failed + 10 * 60_000L,
            saverDueAt(SaverKind.CLOCK, pressed, sceneSince = failed, idleMinutes = 10, channelIdleMinutes = 60),
        )
    }

    /**
     * The stamp `sceneSince` is read from moves only when the kind of screen changes — so a key
     * press keeps the moment the screen turned still, and a screen turning still after a long
     * watch is stamped when that happens rather than when the watch began.
     */
    @Test
    fun `the scene clock moves only when the kind of screen changes`() {
        val clock = SceneClock(startedAt = 0L)
        assertEquals(0L, clock.stamp(null, now = 50L))
        assertEquals(100L, clock.stamp(SaverKind.CHANNEL, now = 100L))
        // A key press while the channel plays: still the same kind of screen.
        assertEquals(100L, clock.stamp(SaverKind.CHANNEL, now = 2_000L))
        // An hour later the channel fails: the screen turned still now.
        assertEquals(3_600_000L, clock.stamp(SaverKind.CLOCK, now = 3_600_000L))
        // A key press on the still screen does not move it.
        assertEquals(3_600_000L, clock.stamp(SaverKind.CLOCK, now = 3_700_000L))
        // A film: nothing to hand over; then a pause, which turns the screen still again.
        assertEquals(3_800_000L, clock.stamp(null, now = 3_800_000L))
        assertEquals(3_900_000L, clock.stamp(SaverKind.CLOCK, now = 3_900_000L))
    }

    @Test
    fun `the programme's bar runs from its start to its end and no further`() {
        assertEquals(0f, airedFraction(startUtc = 1_000L, endUtc = 2_000L, nowUtc = 500L), 0f)
        assertEquals(0.25f, airedFraction(startUtc = 1_000L, endUtc = 2_000L, nowUtc = 1_250L), 1e-6f)
        assertEquals(1f, airedFraction(startUtc = 1_000L, endUtc = 2_000L, nowUtc = 9_000L), 0f)
        // A programme with no length is over the moment after it starts, not a division by zero.
        assertEquals(1f, airedFraction(startUtc = 1_000L, endUtc = 1_000L, nowUtc = 1_001L), 0f)
    }

    // ------------------------------------------------------------------- drift

    @Test
    fun `the drift stays inside the box it is given`() {
        var phase = 0f
        while (phase < 40f) {
            val (x, y) = driftAt(phase)
            assertTrue("x out of range at $phase: $x", x in -1f..1f)
            assertTrue("y out of range at $phase: $y", y in -1f..1f)
            phase += 0.013f
        }
    }

    /**
     * The whole reason the two axes are in an irrational ratio: after a full horizontal cycle
     * the block is somewhere else, so the path never closes into a loop that could itself be
     * worn into the panel.
     */
    @Test
    fun `the drift does not repeat after one cycle`() {
        assertNotEquals(driftAt(0f), driftAt(1f))
        assertNotEquals(driftAt(0f), driftAt(2f))
        assertNotEquals(driftAt(3f), driftAt(7f))
    }

    /** And it does not merely wobble near the middle: over time it visits the corners. */
    @Test
    fun `the drift reaches the edges of its travel on both axes`() {
        var minX = 1f
        var maxX = -1f
        var minY = 1f
        var maxY = -1f
        var phase = 0f
        while (phase < 60f) {
            val (x, y) = driftAt(phase)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            phase += 0.007f
        }
        assertTrue("x range $minX..$maxX", minX < -0.95f && maxX > 0.95f)
        assertTrue("y range $minY..$maxY", minY < -0.95f && maxY > 0.95f)
    }

    // -------------------------------------------------------------------- hue

    @Test
    fun `the ambient hue swings around the station's own colour and stays on the wheel`() {
        assertEquals(200f, ambientHue(200f, 0f, 26f), 0.01f)
        assertEquals(226f, ambientHue(200f, 0.25f, 26f), 0.01f)
        assertEquals(174f, ambientHue(200f, 0.75f, 26f), 0.01f)
        // Wrapping below zero comes back round rather than going negative, which `hslToArgb`
        // would otherwise have to cope with.
        assertEquals(339f, ambientHue(5f, 0.75f, 26f), 0.01f)
        var phase = 0f
        while (phase < 12f) {
            val hue = ambientHue(350f, phase, 26f)
            assertTrue("hue off the wheel at $phase: $hue", hue >= 0f && hue < 360f)
            phase += 0.01f
        }
    }

    // ------------------------------------------------------------------- shift

    /** The anti burn-in step stays inside its few pixels, and never stands still twice running. */
    @Test
    fun `the shift stays in its box and moves on every step`() {
        var previous = shiftAt(0)
        for (step in 1..2_000) {
            val here = shiftAt(step)
            assertTrue("x out of range at $step", here.first in -1f..1f)
            assertTrue("y out of range at $step", here.second in -1f..1f)
            assertNotEquals("step $step stood still", previous, here)
            previous = here
        }
    }

    /** Over a night of three-minute steps, no position comes round again. */
    @Test
    fun `the shift does not return to where it was`() {
        val seen = (0 until 240).map { shiftAt(it) }
        assertEquals(seen.size, seen.toSet().size)
    }

    // -------------------------------------------------------------------- ring

    @Test
    fun `the ring fills up to the current second`() {
        assertEquals(RingDot.PASSED, ringDot(index = 3, second = 10))
        assertEquals(RingDot.NOW, ringDot(index = 10, second = 10))
        assertEquals(RingDot.AHEAD, ringDot(index = 11, second = 10))
        assertEquals(60, (0 until 60).count { ringDot(it, 59) != RingDot.AHEAD })
        assertEquals(1, (0 until 60).count { ringDot(it, 0) != RingDot.AHEAD })
    }

    // -------------------------------------------------------------------- feed

    /**
     * The column moves down without a seam: from one round above its start towards zero, and
     * round again — so the offset is always within one round, and always grows until it wraps.
     */
    @Test
    fun `the headlines move down continuously and wrap without a jump`() {
        val round = 900f
        val speed = 20f
        assertEquals(-round, feedOffset(0f, speed, round), 0.01f)
        assertEquals(-round + 200f, feedOffset(10f, speed, round), 0.01f)
        var previous = feedOffset(0f, speed, round)
        var t = 0.04f
        while (t < 200f) {
            val here = feedOffset(t, speed, round)
            assertTrue("out of range at $t: $here", here >= -round && here < 0f)
            // Down by a step, or wrapped by exactly one round — which, with identical copies
            // stacked, is the same picture.
            val moved = here - previous
            assertTrue("jumped at $t: $moved", moved in 0f..1f || abs(moved + round) < 1f)
            previous = here
            t += 0.04f
        }
    }

    /** After a whole night the arithmetic is still exact: no float drift into a stutter. */
    @Test
    fun `the headlines are still where they should be after a night`() {
        val night = 10f * 3600f
        assertEquals(-900f + (night * 20f) % 900f, feedOffset(night, 20f, 900f), 0.5f)
    }

    @Test
    fun `there are always enough copies of a round to fill the column`() {
        assertEquals(2, feedCopies(1080f, 0f))
        assertEquals(2, feedCopies(1080f, 2_000f))
        // A short round — one source with two headlines — is repeated until it covers the
        // column, plus the copy sliding in from the top.
        assertEquals(4, feedCopies(1080f, 400f))
    }

    @Test
    fun `today's headline shows the clock, an older one the date as well`() {
        val zone = ZoneId.systemDefault()
        val noon = ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val morning = ZonedDateTime.of(2026, 10, 15, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
        val yesterday = ZonedDateTime.of(2026, 10, 14, 21, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(flashTime(morning, noon).contains("09:05"))
        assertTrue(flashTime(yesterday, noon).contains("14/10"))
        assertTrue(flashTime(yesterday, noon).contains("21:30"))
    }

    // ----------------------------------------------------------- what is behind

    private val station = Channel(playlistId = 1, identity = "glz", name = "Galgalatz")
    private val news = Channel(playlistId = 1, identity = "kan11", name = "Kan 11")

    @Test
    fun `a station playing behind the screensaver is now playing`() {
        val playing = saverPlaying(
            NowPlayingInfo.Live(station),
            programme = null,
            status = PlaybackStatus(playing = true, playWhenReady = true),
        )
        assertEquals(SaverPlaying.Radio(station, playing = true), playing)
    }

    @Test
    fun `a paused station is a paused stream, not now playing`() {
        val playing = saverPlaying(NowPlayingInfo.Live(station), programme = null, status = PlaybackStatus())
        assertEquals(SaverPlaying.Video("Galgalatz", null, paused = true, live = false), playing)
    }

    @Test
    fun `a paused channel names itself and its programme`() {
        val playing = saverPlaying(
            NowPlayingInfo.Live(news),
            programme = "The evening news",
            status = PlaybackStatus(playing = false, playWhenReady = false, width = 1920, height = 1080),
        )
        assertEquals(SaverPlaying.Video("Kan 11", "The evening news", paused = true, live = true), playing)
    }

    @Test
    fun `nothing playing, or a failed stream, is the clock alone`() {
        assertEquals(SaverPlaying.None, saverPlaying(null, null, PlaybackStatus()))
        assertEquals(
            SaverPlaying.None,
            saverPlaying(
                NowPlayingInfo.Live(news),
                programme = "x",
                status = PlaybackStatus(
                    width = 1920,
                    height = 1080,
                    error = com.idanck.shidur.core.AppError(com.idanck.shidur.core.AppError.Kind.Network),
                ),
            ),
        )
    }

    /** The swing is a swing, not a rotation: the station's colour is still recognisable. */
    @Test
    fun `the ambient hue never leaves its swing`() {
        var phase = 0f
        while (phase < 12f) {
            val hue = ambientHue(180f, phase, 26f)
            assertTrue("hue $hue at $phase", abs(hue - 180f) <= 26.01f)
            phase += 0.01f
        }
    }
}
