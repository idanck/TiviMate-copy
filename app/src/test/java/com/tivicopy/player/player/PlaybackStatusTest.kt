package com.tivicopy.player.player

import com.tivicopy.player.core.AppError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a player's status says no retry is coming ([PlaybackStatus.failed]): what a split-screen
 * panel, now a [PlayerEngine] of its own (ADR-054), reads to show why it stopped and to try again
 * on OK rather than open its menu.
 */
class PlaybackStatusTest {

    @Test
    fun `nothing playing yet is not a failure`() {
        assertFalse(PlaybackStatus().failed)
        assertFalse(PlaybackStatus(buffering = true).failed)
    }

    @Test
    fun `an error while retries remain is not yet a failure`() {
        assertFalse(PlaybackStatus(error = AppError(AppError.Kind.Network), buffering = true).failed)
    }

    @Test
    fun `an error once the player has stopped trying is a failure`() {
        assertTrue(PlaybackStatus(error = AppError(AppError.Kind.Network), errorDetail = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED").failed)
    }

    @Test
    fun `a stream its server closed is a failure`() {
        assertTrue(PlaybackStatus(closed = true).failed)
    }
}
