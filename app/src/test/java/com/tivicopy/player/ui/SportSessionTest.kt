package com.tivicopy.player.ui

import com.tivicopy.player.core.AppError
import com.tivicopy.player.player.PanelStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the screensaver asks of a Sport or News mode grid (ADR-040, decision 3; ADR-041): a panel
 * is a picture being watched for as long as its channel has not failed, not only while its
 * decoder happens to be running.
 */
class SportSessionTest {

    @Test
    fun `a playing panel is a picture being watched`() {
        assertTrue(SportSession.showsPicture(hasChannel = true, flashes = false, status = PanelStatus(playing = true)))
    }

    @Test
    fun `a panel connecting or rebuffering is still a picture being watched`() {
        assertTrue(SportSession.showsPicture(hasChannel = true, flashes = false, status = PanelStatus(buffering = true)))
        assertTrue(
            SportSession.showsPicture(
                hasChannel = true,
                flashes = false,
                status = PanelStatus(buffering = true, error = AppError(AppError.Kind.Network)),
            ),
        )
    }

    @Test
    fun `the headlines are a picture being watched`() {
        assertTrue(SportSession.showsPicture(hasChannel = false, flashes = true, status = PanelStatus()))
    }

    @Test
    fun `an empty panel, a failed one and a closed one are still screens`() {
        assertFalse(SportSession.showsPicture(hasChannel = false, flashes = false, status = PanelStatus()))
        assertFalse(
            SportSession.showsPicture(
                hasChannel = true,
                flashes = false,
                status = PanelStatus(error = AppError(AppError.Kind.DecoderFailed), failed = true),
            ),
        )
        assertFalse(
            SportSession.showsPicture(hasChannel = true, flashes = false, status = PanelStatus(failed = true, closed = true)),
        )
    }
}
