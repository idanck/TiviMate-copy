package com.tivicopy.player.player

import androidx.media3.exoplayer.DefaultLoadControl
import com.tivicopy.player.data.prefs.BufferPreset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `DefaultLoadControl` throws when its four durations are inconsistent, and the player is built
 * on the first frame — so an invalid preset is not a playback quirk, it is the app failing to
 * start.
 *
 * These assert against the real load control rather than a copy of its rules: the rules are
 * media3's to change, and a copy would stay green through the exact upgrade that starts
 * rejecting one of these presets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BufferDurationsTest {

    /** Fails if media3 rejects the combination, which is the whole point of the test. */
    private fun assertAccepted(minMs: Int, maxMs: Int, playbackMs: Int, rebufferMs: Int) {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, playbackMs, rebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    private fun assertAccepted(durations: BufferDurations) = assertAccepted(
        durations.minMs,
        durations.maxMs,
        durations.playbackMs,
        durations.rebufferMs,
    )

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
        assertAccepted(durations)
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
        assertAccepted(durations)
    }

    @Test
    fun `nonsense stored by an older install still yields something buildable`() {
        // Custom values live in preferences, so they can be anything a previous version wrote.
        assertAccepted(
            BufferDurations.reconcile(minMs = -1, maxMs = -1, playbackMs = -1, rebufferMs = -1),
        )
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
