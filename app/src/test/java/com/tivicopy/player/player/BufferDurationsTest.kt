package com.tivicopy.player.player

import com.tivicopy.player.data.prefs.BufferPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DefaultLoadControl` throws when its four durations are inconsistent, and the player is built
 * on the first frame — so an invalid preset is not a playback quirk, it is the app failing to
 * start. These are the exact invariants it asserts.
 */
class BufferDurationsTest {

    private fun assertAccepted(minMs: Int, maxMs: Int, playbackMs: Int, rebufferMs: Int) {
        assertTrue("bufferForPlaybackMs must not be negative", playbackMs >= 0)
        assertTrue("bufferForPlaybackAfterRebufferMs must not be negative", rebufferMs >= 0)
        assertTrue("minBufferMs cannot be less than bufferForPlaybackMs", minMs >= playbackMs)
        assertTrue(
            "minBufferMs cannot be less than bufferForPlaybackAfterRebufferMs",
            minMs >= rebufferMs,
        )
        assertTrue("maxBufferMs cannot be less than minBufferMs", maxMs >= minMs)
    }

    @Test
    fun `every preset is a set of durations the load control accepts`() {
        BufferPreset.entries.forEach { preset ->
            assertAccepted(preset.minMs, preset.maxMs, preset.playbackMs, preset.rebufferMs)
        }
    }

    @Test
    fun `a minimum below either threshold is raised to it`() {
        val durations = BufferDurations.reconcile(
            minMs = 500,
            maxMs = 3_000,
            playbackMs = 400,
            rebufferMs = 1_000,
        )

        assertEquals(1_000, durations.minMs)
        assertAccepted(durations.minMs, durations.maxMs, durations.playbackMs, durations.rebufferMs)
    }

    @Test
    fun `a maximum below the minimum is raised to it`() {
        val durations = BufferDurations.reconcile(
            minMs = 8_000,
            maxMs = 1_000,
            playbackMs = 1_500,
            rebufferMs = 3_000,
        )

        assertEquals(8_000, durations.minMs)
        assertEquals(8_000, durations.maxMs)
    }

    @Test
    fun `nonsense stored by an older install still yields something buildable`() {
        // Custom values live in preferences, so they can be anything a previous version wrote.
        val durations = BufferDurations.reconcile(
            minMs = -1,
            maxMs = -1,
            playbackMs = -1,
            rebufferMs = -1,
        )

        assertAccepted(durations.minMs, durations.maxMs, durations.playbackMs, durations.rebufferMs)
    }

    @Test
    fun `a valid preset is passed through unchanged`() {
        val large = BufferPreset.LARGE
        val durations = BufferDurations.reconcile(
            large.minMs,
            large.maxMs,
            large.playbackMs,
            large.rebufferMs,
        )

        assertEquals(
            BufferDurations(large.minMs, large.maxMs, large.playbackMs, large.rebufferMs),
            durations,
        )
    }
}
