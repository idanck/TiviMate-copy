package com.tivicopy.player.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 0.6.2 painted the station logo edge to edge on the square handed to the car, which a head unit
 * that scales that square to *fill* its slot then blew up and cropped — the station's name came
 * out cut off. These pin the margin [logoBounds] leaves around the logo instead, and that it
 * still makes the logo itself as large as that leaves room for.
 */
class LogoBoundsTest {

    private val canvas = RadioArtwork.SIZE_PX
    private val safeSide = (canvas * RadioArtwork.SAFE_AREA_FRACTION).roundToInt()

    /** Centred to within the odd pixel integer division cannot split. */
    private fun assertCentred(bounds: LogoBounds) {
        assertTrue(abs(bounds.left - (canvas - bounds.right)) <= 1)
        assertTrue(abs(bounds.top - (canvas - bounds.bottom)) <= 1)
    }

    @Test
    fun `a square logo stops short of the canvas edge on every side`() {
        val bounds = logoBounds(sourceWidth = 300, sourceHeight = 300)

        assertEquals(safeSide, bounds.width)
        assertEquals(safeSide, bounds.height)
        assertTrue(bounds.left > 0)
        assertTrue(bounds.top > 0)
        assertTrue(bounds.right < canvas)
        assertTrue(bounds.bottom < canvas)
        assertCentred(bounds)
    }

    @Test
    fun `a square logo survives a head unit cropping the square to a wide card`() {
        val bounds = logoBounds(sourceWidth = 300, sourceHeight = 300)
        // A 16:10 card filled by this square keeps only the middle band of its height.
        val kept = canvas * 10 / 16
        val cropTop = (canvas - kept) / 2

        assertTrue(bounds.top >= cropTop)
        assertTrue(bounds.bottom <= cropTop + kept)
    }

    @Test
    fun `a wide banner is scaled by its longer side and keeps its shape`() {
        val bounds = logoBounds(sourceWidth = 800, sourceHeight = 200)

        assertEquals(safeSide, bounds.width)
        assertTrue(bounds.height < bounds.width)
        assertEquals(4.0, bounds.width.toDouble() / bounds.height, 0.05)
        assertCentred(bounds)
    }

    @Test
    fun `a tall logo is scaled by its longer side too`() {
        val bounds = logoBounds(sourceWidth = 200, sourceHeight = 800)

        assertEquals(safeSide, bounds.height)
        assertEquals(4.0, bounds.height.toDouble() / bounds.width, 0.05)
        assertCentred(bounds)
    }

    @Test
    fun `a logo smaller than the safe area is still enlarged to it`() {
        val bounds = logoBounds(sourceWidth = 64, sourceHeight = 64)

        assertEquals(safeSide, bounds.width)
        assertEquals(safeSide, bounds.height)
    }

    @Test
    fun `a degenerate size still yields a rectangle inside the canvas`() {
        val bounds = logoBounds(sourceWidth = 0, sourceHeight = 0)

        assertTrue(bounds.width >= 1)
        assertTrue(bounds.height >= 1)
        assertTrue(bounds.left >= 0 && bounds.right <= canvas)
        assertTrue(bounds.top >= 0 && bounds.bottom <= canvas)
    }
}
