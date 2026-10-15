package com.tivicopy.player.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radio's shifting background (ADR-030) and the song split on its screen.
 *
 * The drawing needs a device; the arithmetic that decides what is drawn does not, so it is proved
 * here: the ground walks through Studio's four tones and comes back, the pool of light stays on
 * screen and never retraces itself, and its hue stays inside its swing.
 */
class AmbientBackdropTest {

    private val tones = listOf(0xFF0B1424.toInt(), 0xFF08191C.toInt(), 0xFF160F22.toInt(), 0xFF1A120C.toInt())

    @Test
    fun `the ground is each tone at its quarter of the cycle, and back to the first at the end`() {
        assertEquals(tones[0], ambientTone(tones, 0f))
        assertEquals(tones[1], ambientTone(tones, AMBIENT_CYCLE_S / 4))
        assertEquals(tones[2], ambientTone(tones, AMBIENT_CYCLE_S / 2))
        assertEquals(tones[3], ambientTone(tones, AMBIENT_CYCLE_S * 3 / 4))
        assertEquals(tones[0], ambientTone(tones, AMBIENT_CYCLE_S))
    }

    @Test
    fun `between two tones the ground is neither`() {
        val between = ambientTone(tones, AMBIENT_CYCLE_S / 8)
        assertNotEquals(tones[0], between)
        assertNotEquals(tones[1], between)
        // Opaque throughout: a ground that went translucent would show whatever is behind it.
        assertEquals(0xFF, (between ushr 24) and 0xFF)
    }

    @Test
    fun `the pool of light stays on screen`() {
        var t = 0f
        while (t < 600f) {
            val (x, y) = ambientPoolCentre(t)
            assertTrue("x $x at $t", x in 0f..1f)
            assertTrue("y $y at $t", y in 0f..1f)
            t += 0.7f
        }
    }

    @Test
    fun `the pool does not retrace its path after one period of either axis`() {
        val start = ambientPoolCentre(0f)
        assertNotEquals(start, ambientPoolCentre(POOL_PERIOD_X_S))
        assertNotEquals(start, ambientPoolCentre(POOL_PERIOD_Y_S))
    }

    @Test
    fun `the pool's hue stays within its swing`() {
        val base = 0xFF4670C4.toInt()
        val baseHue = rgbToHsl(base)[0]
        var t = 0f
        while (t < AMBIENT_CYCLE_S) {
            val hue = rgbToHsl(ambientPoolColour(base, t))[0]
            var delta = hue - baseHue
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            assertTrue("hue moved $delta at $t", kotlin.math.abs(delta) <= AMBIENT_HUE_SWING + 1f)
            t += 3f
        }
    }

    @Test
    fun `a song announced as artist and title is split, anything else is all title`() {
        val split = splitSong("Omer Adam - Tel Aviv")
        assertEquals("Omer Adam", split.artist)
        assertEquals("Tel Aviv", split.title)

        val plain = splitSong("News at the hour")
        assertNull(plain.artist)
        assertEquals("News at the hour", plain.title)

        // A dash with nothing on one side is not a separator.
        assertNull(splitSong(" - Title").artist)
        assertNull(splitSong("Artist - ").artist)
        // A hyphenated word is not a separator either.
        assertNull(splitSong("Hip-hop hour").artist)
    }
}
