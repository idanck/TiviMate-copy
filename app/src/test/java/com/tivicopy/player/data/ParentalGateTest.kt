package com.tivicopy.player.data

import com.tivicopy.player.data.prefs.ParentalGate
import com.tivicopy.player.data.prefs.PinAttemptLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-PRF-04/05/07. Until 2026-10-13 the PIN was stored and nothing ever asked for it: a locked
 * channel played on the first press and Settings opened with "require the PIN" switched on.
 */
class ParentalGateTest {

    private fun play(
        channelLocked: Boolean = true,
        groupLocked: Boolean = false,
        pinSet: Boolean = true,
        lockChannels: Boolean = true,
        unlocked: Boolean = false,
    ) = ParentalGate.channelNeedsPin(channelLocked, groupLocked, pinSet, lockChannels, unlocked)

    @Test
    fun `a locked channel waits for the PIN`() {
        assertTrue(play())
    }

    @Test
    fun `a channel in a locked group waits for the PIN`() {
        assertTrue(play(channelLocked = false, groupLocked = true))
    }

    @Test
    fun `an unlocked channel plays`() {
        assertFalse(play(channelLocked = false))
    }

    @Test
    fun `nothing is gated without a PIN, or with the channel switch off, or once unlocked`() {
        assertFalse(play(pinSet = false))
        assertFalse(play(lockChannels = false))
        assertFalse(play(unlocked = true))
    }

    @Test
    fun `Settings wait for the PIN only when asked to`() {
        assertTrue(ParentalGate.settingsNeedPin(pinSet = true, lockSettings = true, unlocked = false))
        assertFalse(ParentalGate.settingsNeedPin(pinSet = true, lockSettings = false, unlocked = false))
        assertFalse(ParentalGate.settingsNeedPin(pinSet = false, lockSettings = true, unlocked = false))
        assertFalse(ParentalGate.settingsNeedPin(pinSet = true, lockSettings = true, unlocked = true))
    }

    @Test
    fun `loosening parental controls waits for the PIN once there is one`() {
        assertTrue(ParentalGate.parentalChangeNeedsPin(pinSet = true, unlocked = false))
        assertFalse(ParentalGate.parentalChangeNeedsPin(pinSet = true, unlocked = true))
        assertFalse(ParentalGate.parentalChangeNeedsPin(pinSet = false, unlocked = false))
    }

    @Test
    fun `three wrong PINs lock the prompt, and the lockout doubles`() {
        val limiter = PinAttemptLimiter(threshold = 3, baseBackoffMillis = 15_000)
        val t = 1_000_000L
        limiter.onFailure(t)
        limiter.onFailure(t)
        assertFalse(limiter.isLocked(t))
        limiter.onFailure(t)
        assertEquals(15_000L, limiter.remainingLockMillis(t))
        limiter.onFailure(t)
        assertEquals(30_000L, limiter.remainingLockMillis(t))
        assertFalse(limiter.isLocked(t + 30_000))
    }

    @Test
    fun `a correct PIN clears the count`() {
        val limiter = PinAttemptLimiter(threshold = 3, baseBackoffMillis = 15_000)
        val t = 1_000_000L
        repeat(3) { limiter.onFailure(t) }
        limiter.onSuccess()
        assertFalse(limiter.isLocked(t))
        limiter.onFailure(t)
        assertFalse(limiter.isLocked(t))
    }
}
