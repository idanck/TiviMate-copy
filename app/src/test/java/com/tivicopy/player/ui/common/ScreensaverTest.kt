package com.tivicopy.player.ui.common

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

    @Test
    fun `moving video is left alone`() {
        assertEquals(
            IdleScene.MOVING,
            idleScene(
                playing = true,
                playWhenReady = true,
                hasPicture = true,
                failed = false,
                videoFillsPanel = true,
            ),
        )
    }

    /**
     * The case the guide produces: the picture is still playing, but it is a preview beside a
     * grid that has not changed in an hour. Most of the panel is a still image.
     */
    @Test
    fun `video playing under an overlay is a still screen`() {
        assertEquals(
            IdleScene.STILL,
            idleScene(
                playing = true,
                playWhenReady = true,
                hasPicture = true,
                failed = false,
                videoFillsPanel = false,
            ),
        )
    }

    @Test
    fun `a paused film is a still screen`() {
        assertEquals(
            IdleScene.STILL,
            idleScene(
                playing = false,
                playWhenReady = false,
                hasPicture = true,
                failed = false,
                videoFillsPanel = true,
            ),
        )
    }

    @Test
    fun `sound with no picture is the radio`() {
        assertEquals(
            IdleScene.AUDIO_ONLY,
            idleScene(
                playing = true,
                playWhenReady = true,
                hasPicture = false,
                failed = false,
                videoFillsPanel = true,
            ),
        )
    }

    /**
     * A station that is rebuffering is not playing, and is still the radio: it is the *intent*
     * that separates it from a station somebody stopped.
     */
    @Test
    fun `a rebuffering station is still the radio`() {
        assertEquals(
            IdleScene.AUDIO_ONLY,
            idleScene(
                playing = false,
                playWhenReady = true,
                hasPicture = false,
                failed = false,
                videoFillsPanel = true,
            ),
        )
    }

    @Test
    fun `a paused station is a still screen`() {
        assertEquals(
            IdleScene.STILL,
            idleScene(
                playing = false,
                playWhenReady = false,
                hasPicture = false,
                failed = false,
                videoFillsPanel = true,
            ),
        )
    }

    /** A settled error is a message on screen, and a message holds as still as anything else. */
    @Test
    fun `a failed stream is a still screen`() {
        assertEquals(
            IdleScene.STILL,
            idleScene(
                playing = true,
                playWhenReady = true,
                hasPicture = true,
                failed = true,
                videoFillsPanel = true,
            ),
        )
    }

    // ------------------------------------------------------------------ arming

    @Test
    fun `the guard is off when the setting is off, in picture-in-picture, and over moving video`() {
        assertTrue(screensaverApplies(enabled = true, scene = IdleScene.STILL, inPip = false))
        assertTrue(screensaverApplies(enabled = true, scene = IdleScene.AUDIO_ONLY, inPip = false))
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
