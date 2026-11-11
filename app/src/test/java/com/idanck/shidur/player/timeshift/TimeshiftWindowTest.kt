package com.idanck.shidur.player.timeshift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimeshiftWindowTest {
    @get:Rule val folder = TemporaryFolder()

    private fun segment(sequence: Long, durationUs: Long = 2_000_000, bytes: Long = 1000, discontinuity: Boolean = false) =
        TimeshiftWindow.Segment(sequence, folder.newFile("seg_$sequence.ts"), durationUs, bytes, discontinuity)

    @Test
    fun dropsTheOldestOnceLongerThanItsCap() {
        val window = TimeshiftWindow(maxDurationUs = 10_000_000, maxBytes = Long.MAX_VALUE)
        val added = (0L until 8).map { segment(it).also(window::add) }
        assertEquals(5, window.size)
        assertEquals(10_000_000L, window.durationUs)
        assertFalse(added[0].file.exists())
        assertFalse(added[2].file.exists())
        assertTrue(added[3].file.exists())
        assertTrue(window.playlist().contains("#EXT-X-MEDIA-SEQUENCE:3\n"))
    }

    @Test
    fun dropsTheOldestOnceLargerThanItsCap() {
        val window = TimeshiftWindow(maxDurationUs = Long.MAX_VALUE, maxBytes = 4000)
        (0L until 6).forEach { window.add(segment(it)) }
        assertEquals(4, window.size)
        assertEquals(4000L, window.bytes)
    }

    @Test
    fun keepsAFewWhateverTheCaps() {
        val window = TimeshiftWindow(maxDurationUs = 1, maxBytes = 1)
        (0L until 6).forEach { window.add(segment(it)) }
        assertEquals(TimeshiftWindow.MIN_SEGMENTS, window.size)
    }

    @Test
    fun countsTheDiscontinuitiesItDrops() {
        val window = TimeshiftWindow(maxDurationUs = 6_000_000, maxBytes = Long.MAX_VALUE)
        window.add(segment(0))
        window.add(segment(1, discontinuity = true))
        window.add(segment(2))
        window.add(segment(3))
        window.add(segment(4))
        val playlist = window.playlist()
        assertTrue(playlist, playlist.contains("#EXT-X-DISCONTINUITY-SEQUENCE:1\n"))
        assertFalse(playlist.contains("#EXT-X-DISCONTINUITY\n"))
    }

    @Test
    fun writesALivePlaylist() {
        val window = TimeshiftWindow(maxDurationUs = Long.MAX_VALUE, maxBytes = Long.MAX_VALUE)
        window.add(segment(0, durationUs = 2_000_000))
        window.add(segment(1, durationUs = 2_480_000, discontinuity = true))
        val lines = window.playlist().lines()
        assertEquals("#EXTM3U", lines[0])
        assertTrue(lines.contains("#EXT-X-TARGETDURATION:3"))
        assertTrue(lines.contains("#EXT-X-MEDIA-SEQUENCE:0"))
        assertTrue(lines.contains("#EXTINF:2.480000,"))
        val tag = lines.indexOf("#EXT-X-DISCONTINUITY")
        assertTrue(lines[tag + 2].startsWith("file:///") && lines[tag + 2].endsWith("seg_1.ts"))
        assertFalse(lines.any { it.startsWith("#EXT-X-ENDLIST") })
    }

    @Test
    fun aWaiterWakesOnTheNextSegment() {
        val window = TimeshiftWindow(maxDurationUs = Long.MAX_VALUE, maxBytes = Long.MAX_VALUE)
        val seen = window.version
        Thread { Thread.sleep(50); window.add(segment(0)) }.start()
        val now = window.awaitChange(seen, timeoutMs = 5_000)
        assertTrue(now > seen)
        assertFalse(window.isEmpty)
    }
}
