package com.idanck.shidur.ui.common

import com.idanck.shidur.core.AppError
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.VodItem
import com.idanck.shidur.player.PlaybackStatus
import com.idanck.shidur.ui.NowPlayingInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radio backdrop's arithmetic — which colour a station gets, and whether the screen is on.
 *
 * The rendering itself needs a device this repository's test tier does not have (docs/10), so
 * everything provable without one is pulled out into pure functions and proved here: that a logo
 * decides its own station's colour, that a logo with no colour of its own does not make every
 * station grey, and that pausing a station does not take its screen away.
 */
class StationBackdropTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val orange = 0xFFFF8000.toInt()
    private val transparentOrange = 0x00FF8000

    private fun hueOf(argb: Int): Float = rgbToHsl(argb)[0]
    private fun saturationOf(argb: Int): Float = rgbToHsl(argb)[1]
    private fun lightnessOf(argb: Int): Float = rgbToHsl(argb)[2]

    // ------------------------------------------------------------ logo sampling

    @Test
    fun `a wordmark on a white plate takes the colour of the wordmark`() {
        // What a station logo actually is: a few coloured pixels in a field of white.
        val pixels = IntArray(100) { if (it < 10) orange else white }

        val accent = requireNotNull(accentFromPixels(pixels))

        assertEquals(hueOf(orange), hueOf(accent), 1f)
    }

    @Test
    fun `black lettering counts for as little as white does`() {
        val pixels = IntArray(100) { if (it < 10) orange else black }

        val accent = requireNotNull(accentFromPixels(pixels))

        assertEquals(hueOf(orange), hueOf(accent), 1f)
    }

    @Test
    fun `the transparent margin around a logo is not part of it`() {
        assertNull(accentFromPixels(IntArray(64) { transparentOrange }))
    }

    @Test
    fun `a logo with nothing but black and white has no colour to give`() {
        assertNull(accentFromPixels(intArrayOf(white, black, white, black)))
    }

    @Test
    fun `an empty image is not a colour`() {
        assertNull(accentFromPixels(IntArray(0)))
    }

    @Test
    fun `a colourful pixel outweighs a muddy one`() {
        // Equal counts of a saturated red and a barely-tinted blue: the red has to win, or a
        // logo's background wash would decide the backdrop instead of its mark.
        val red = 0xFFFF0000.toInt()
        val washedBlue = 0xFF7A7A88.toInt()
        val pixels = IntArray(100) { if (it % 2 == 0) red else washedBlue }

        val accent = requireNotNull(accentFromPixels(pixels))

        assertTrue("expected a red-leaning accent, got ${Integer.toHexString(accent)}", hueOf(accent) < 30f || hueOf(accent) > 330f)
    }

    // -------------------------------------------------------------- the accent

    @Test
    fun `a station keeps its logo's hue at a lightness white text survives`() {
        val accent = stationAccentColour(logoAccent = orange, stationName = "Whatever FM")

        assertEquals(hueOf(orange), hueOf(accent), 2f)
        assertEquals(0.30f, lightnessOf(accent), 0.01f)
    }

    @Test
    fun `a black and white logo falls back to the station's name, not to grey`() {
        val grey = 0xFF808080.toInt()

        val accent = stationAccentColour(logoAccent = grey, stationName = "Radio Nowhere")

        assertTrue("a grey backdrop is the failure this exists to avoid", saturationOf(accent) > 0.2f)
        assertEquals(stationAccentColour(logoAccent = null, stationName = "Radio Nowhere"), accent)
    }

    @Test
    fun `two stations without logos still get two different colours`() {
        val one = stationAccentColour(logoAccent = null, stationName = "Galgalatz")
        val other = stationAccentColour(logoAccent = null, stationName = "Kan Gimel")

        assertNotEquals(one, other)
    }

    @Test
    fun `the same station gets the same colour every time`() {
        assertEquals(hueForName("Eco 99"), hueForName("Eco 99"))
        assertEquals(
            stationAccentColour(logoAccent = null, stationName = "Eco 99"),
            stationAccentColour(logoAccent = null, stationName = "Eco 99"),
        )
    }

    @Test
    fun `every station lands on one lightness, however loud its logo is`() {
        val screaming = stationAccentColour(0xFF00FF00.toInt(), "A")
        val murky = stationAccentColour(0xFF201810.toInt(), "B")
        val fromAName = stationAccentColour(null, "C")

        assertEquals(lightnessOf(screaming), lightnessOf(murky), 0.01f)
        assertEquals(lightnessOf(screaming), lightnessOf(fromAName), 0.01f)
    }

    @Test
    fun `a shade keeps the hue and only moves the lightness`() {
        val accent = stationAccentColour(orange, "Whatever FM")

        val deep = accentShade(accent, 0.06f)

        assertEquals(hueOf(accent), hueOf(deep), 2f)
        assertEquals(0.06f, lightnessOf(deep), 0.01f)
    }

    // ------------------------------------------------------------- colour space

    @Test
    fun `hsl round-trips through rgb`() {
        listOf(orange, 0xFF3366CC.toInt(), 0xFF12EE45.toInt(), white, black).forEach { colour ->
            val hsl = rgbToHsl(colour)
            assertEquals(
                "round trip of ${Integer.toHexString(colour)}",
                colour,
                hslToArgb(hsl[0], hsl[1], hsl[2]),
            )
        }
    }

    @Test
    fun `a grey has no hue rather than a division by zero`() {
        val hsl = rgbToHsl(0xFF404040.toInt())

        assertEquals(0f, hsl[1], 0.001f)
    }

    // ------------------------------------------------------- when it is on screen

    private val radio = PlaybackStatus(playing = true, playWhenReady = true, width = 0, height = 0)

    @Test
    fun `a stream with sound and no picture gets the backdrop`() {
        assertTrue(audioOnlyBackdropVisible(radio, wasVisible = false))
    }

    @Test
    fun `pausing a station keeps the screen it was on`() {
        // The regression this guards: the old test was `status.playing`, so pausing radio dropped
        // the screen to black — taking the pause button with it, leaving nothing to resume from.
        val paused = radio.copy(playing = false, playWhenReady = false)

        assertTrue(audioOnlyBackdropVisible(paused, wasVisible = true))
    }

    @Test
    fun `a station that stalls keeps its screen too`() {
        val stalled = radio.copy(playing = false, buffering = true)

        assertTrue(audioOnlyBackdropVisible(stalled, wasVisible = true))
    }

    @Test
    fun `a channel with a picture never gets it`() {
        val video = radio.copy(width = 1920, height = 1080)

        assertFalse(audioOnlyBackdropVisible(video, wasVisible = true))
    }

    @Test
    fun `a failure is not a radio station`() {
        val failed = radio.copy(playing = false, error = AppError(AppError.Kind.Network))

        assertFalse(audioOnlyBackdropVisible(failed, wasVisible = true))
    }

    @Test
    fun `a film started after a station is not drawn as that station`() {
        // `currentChannel` deliberately outlives an on-demand session, so the backdrop asks what
        // is *playing* instead: otherwise a film opened straight after radio came up wearing the
        // station's logo, name and colour.
        val station = Channel(playlistId = 1, identity = "eco99", name = "Eco 99")
        val film = VodItem(playlistId = 1, streamId = "17", name = "Anything")

        assertEquals(station, (NowPlayingInfo.Live(station) as NowPlayingInfo?).audioOnlyChannel())
        assertNull((NowPlayingInfo.OnDemand(film) as NowPlayingInfo?).audioOnlyChannel())
        assertNull((null as NowPlayingInfo?).audioOnlyChannel())
    }

    @Test
    fun `a channel that has not started yet shows nothing`() {
        val tuning = PlaybackStatus(buffering = true)

        assertFalse(audioOnlyBackdropVisible(tuning, wasVisible = false))
    }
}
