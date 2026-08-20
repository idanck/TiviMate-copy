package com.tivicopy.player.data

import com.tivicopy.player.ui.resumeMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FR-PLB-29: what gets stored as a resume mark, and — the part that matters — what does not.
 *
 * The review of 2026-08-20 found that writing zero for an early position is not harmless. A
 * failover restarts the source from zero (`PlayerEngine.startCurrentSource` defaults to it), so
 * a single network blip followed by the user giving up would have written that zero over a real
 * position and taken the title out of continue-watching. Leaving the mark alone is the answer,
 * and it is what the first two cases pin down.
 */
class ResumeMarkTest {

    private val hour = 60L * 60 * 1000

    @Test
    fun `a position worth returning to is stored`() {
        assertEquals(40 * 60_000L, resumeMark(position = 40 * 60_000L, duration = hour))
    }

    /** Not zero. Zero would erase whatever the last real session stored. */
    @Test
    fun `a position in the opening minute leaves the stored mark alone`() {
        assertNull(resumeMark(position = 0L, duration = hour))
        assertNull(resumeMark(position = 59_000L, duration = hour))
    }

    /** The restart case in full: a live duration, and a position reset to the start. */
    @Test
    fun `a source that restarted from zero does not erase the mark`() {
        assertNull(resumeMark(position = 0L, duration = 45 * 60_000L))
    }

    @Test
    fun `finishing clears the mark so the title leaves continue-watching`() {
        assertEquals(0L, resumeMark(position = hour - 1_000L, duration = hour))
        assertEquals(0L, resumeMark(position = hour, duration = hour))
    }

    /** The tail is the last twentieth, so three minutes short of an hour is still watching. */
    @Test
    fun `short of the tail is still a place to resume`() {
        val position = hour - 4 * 60_000L
        assertEquals(position, resumeMark(position = position, duration = hour))
    }

    /**
     * Duration is `C.TIME_UNSET` until the source has been read, and negative when unknown.
     * Neither is a reason to call a title finished.
     */
    @Test
    fun `an unknown duration never counts as finished`() {
        assertEquals(40 * 60_000L, resumeMark(position = 40 * 60_000L, duration = 0L))
        assertEquals(40 * 60_000L, resumeMark(position = 40 * 60_000L, duration = -1L))
        assertNull(resumeMark(position = 5_000L, duration = -1L))
    }
}
