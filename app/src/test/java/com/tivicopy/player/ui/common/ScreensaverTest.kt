package com.tivicopy.player.ui.common

import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.player.PlaybackStatus
import com.tivicopy.player.ui.NowPlayingInfo
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
     * The owner's rule (ADR-040): the screensaver comes on in every state except while a channel
     * is playing. The shell no longer has a say, so the same answer holds under the guide, the
     * home screen or Settings as over the bare video.
     */
    @Test
    fun `a playing picture is left alone, whatever is drawn over it`() {
        assertEquals(IdleScene.MOVING, idleScene(playing = true, hasPicture = true, failed = false))
    }

    @Test
    fun `a paused film is a still screen`() {
        assertEquals(IdleScene.STILL, idleScene(playing = false, hasPicture = true, failed = false))
    }

    /**
     * A station is sound with no picture, and is handed over like any other screen that is not a
     * playing picture — its own screen included, which ADR-030 used to exempt.
     */
    @Test
    fun `a playing station is a still screen`() {
        assertEquals(IdleScene.STILL, idleScene(playing = true, hasPicture = false, failed = false))
    }

    @Test
    fun `nothing playing is a still screen`() {
        assertEquals(IdleScene.STILL, idleScene(playing = false, hasPicture = false, failed = false))
    }

    /** A settled error is a message on screen, and a message holds as still as anything else. */
    @Test
    fun `a failed stream is a still screen`() {
        assertEquals(IdleScene.STILL, idleScene(playing = true, hasPicture = true, failed = true))
    }

    // ------------------------------------------------------------------ arming

    @Test
    fun `the guard is off when the setting is off, in picture-in-picture, and over a playing picture`() {
        assertTrue(screensaverApplies(enabled = true, scene = IdleScene.STILL, inPip = false))
        assertTrue(!screensaverApplies(enabled = false, scene = IdleScene.STILL, inPip = false))
        assertTrue(!screensaverApplies(enabled = true, scene = IdleScene.STILL, inPip = true))
        assertTrue(!screensaverApplies(enabled = true, scene = IdleScene.MOVING, inPip = false))
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
                    error = com.tivicopy.player.core.AppError(com.tivicopy.player.core.AppError.Kind.Network),
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
