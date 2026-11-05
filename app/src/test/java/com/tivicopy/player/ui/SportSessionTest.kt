package com.tivicopy.player.ui

import com.tivicopy.player.core.AppError
import com.tivicopy.player.player.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the screensaver asks of a Sport or News mode grid (ADR-040, decision 3; ADR-041): a panel
 * is a picture being watched for as long as its channel has not failed, not only while its
 * decoder happens to be running. And which panels News mode keeps going from four to two (ADR-045).
 */
class SportSessionTest {

    @Test
    fun `a playing panel is a picture being watched`() {
        assertTrue(SportSession.showsPicture(hasChannel = true, flashes = false, status = PlaybackStatus(playing = true)))
    }

    @Test
    fun `a panel connecting or rebuffering is still a picture being watched`() {
        assertTrue(SportSession.showsPicture(hasChannel = true, flashes = false, status = PlaybackStatus(buffering = true)))
        assertTrue(
            SportSession.showsPicture(
                hasChannel = true,
                flashes = false,
                status = PlaybackStatus(buffering = true, error = AppError(AppError.Kind.Network)),
            ),
        )
    }

    @Test
    fun `the headlines are a picture being watched`() {
        assertTrue(SportSession.showsPicture(hasChannel = false, flashes = true, status = PlaybackStatus()))
    }

    @Test
    fun `an empty panel, a failed one and a closed one are still screens`() {
        assertFalse(SportSession.showsPicture(hasChannel = false, flashes = false, status = PlaybackStatus()))
        assertFalse(
            SportSession.showsPicture(
                hasChannel = true,
                flashes = false,
                status = PlaybackStatus(error = AppError(AppError.Kind.DecoderFailed), errorDetail = "ERROR_CODE_DECODER_INIT_FAILED"),
            ),
        )
        assertFalse(
            SportSession.showsPicture(hasChannel = true, flashes = false, status = PlaybackStatus(closed = true)),
        )
    }

    @Test
    fun `going to two keeps the panel in focus and the next one showing something`() {
        assertEquals(listOf(2, 3), SportSession.keptPanels(listOf(true, false, true, true), focused = 2, keep = 2))
    }

    @Test
    fun `counting on from the panel in focus goes round to the first`() {
        assertEquals(listOf(3, 0), SportSession.keptPanels(listOf(true, true, false, true), focused = 3, keep = 2))
    }

    @Test
    fun `empty panels are passed over while there are filled ones`() {
        assertEquals(listOf(0, 3), SportSession.keptPanels(listOf(true, false, false, true), focused = 0, keep = 2))
    }

    @Test
    fun `the panel in focus stays even when empty, and empty ones make up the number`() {
        assertEquals(listOf(1, 2), SportSession.keptPanels(listOf(true, false, true, false), focused = 1, keep = 2))
        assertEquals(listOf(0, 1), SportSession.keptPanels(listOf(false, false, false, false), focused = 0, keep = 2))
    }
}
