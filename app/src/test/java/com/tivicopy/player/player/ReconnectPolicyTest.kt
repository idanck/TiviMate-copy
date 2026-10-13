package com.tivicopy.player.player

import com.tivicopy.player.core.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A live channel that lost its connection keeps trying; one that was refused does not. The
 * emulator QA run (docs/qa) found a channel left dead for minutes after a 20-second outage.
 */
class ReconnectPolicyTest {

    @Test
    fun `a lost connection or a timeout on a live channel is retried`() {
        assertTrue(ReconnectPolicy.applies(AppError.Kind.Network, live = true))
        assertTrue(ReconnectPolicy.applies(AppError.Kind.Timeout, live = true))
    }

    @Test
    fun `a server error counts as a lost connection`() {
        // 5xx is filed under Network by fromHttp, so a provider that blinked is retried too.
        assertTrue(ReconnectPolicy.applies(AppError.fromHttp(503).kind, live = true))
    }

    @Test
    fun `a refusal that will not pass on its own is not retried`() {
        listOf(
            AppError.Kind.NotFound,
            AppError.Kind.Forbidden,
            AppError.Kind.Unauthorized,
            AppError.Kind.GeoBlocked,
            AppError.Kind.DrmUnsupported,
            AppError.Kind.DecoderFailed,
        ).forEach { kind ->
            assertFalse(kind.name, ReconnectPolicy.applies(kind, live = true))
        }
    }

    @Test
    fun `an on-demand title is not retried from the beginning`() {
        assertFalse(ReconnectPolicy.applies(AppError.Kind.Network, live = false))
    }

    @Test
    fun `the wait grows and then holds`() {
        assertEquals(5_000L, ReconnectPolicy.delayMs(1))
        assertEquals(10_000L, ReconnectPolicy.delayMs(2))
        assertEquals(15_000L, ReconnectPolicy.delayMs(3))
        assertEquals(15_000L, ReconnectPolicy.delayMs(40))
        assertEquals(5_000L, ReconnectPolicy.delayMs(0))
    }
}
