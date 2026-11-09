package com.idanck.shidur.tv

import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Google TV "Continue watching" row (FR-TVI-02) and the launcher deep link (FR-TVI-04).
 *
 * Neither half can be exercised here — writing to the TV provider needs a television, and
 * following a deep link needs a launcher — so the decisions are pushed into pure functions and
 * pinned there. What is left in `WatchNextSync.kt` is `ContentResolver` calls with no judgement
 * in them, which is the point of the split.
 *
 * Plain JUnit: this file touches no Android type, and `WatchNextPlan` lives in its own file so
 * loading it cannot drag `androidx.tvprovider` in behind it.
 */
class WatchNextPlanTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun channel(
        id: Long,
        watched: Long,
        name: String = "Channel $id",
        hidden: Boolean = false,
        logo: String? = "https://example.com/$id.png",
    ) = Channel(
        id = id,
        playlistId = 1,
        identity = "i$id",
        name = name,
        logoUrl = logo,
        hidden = hidden,
        lastWatchedUtc = watched,
    )

    // ------------------------------------------------------------ the rows

    @Test
    fun `rows come back newest first`() {
        val entries = WatchNextPlan.entries(
            listOf(channel(1, now - 3 * 1000), channel(2, now - 1000), channel(3, now - 2 * 1000)),
            nowUtc = now,
        )
        assertEquals(listOf(2L, 3L, 1L), entries.map { it.channelId })
    }

    @Test
    fun `a row carries the channel identity and its own deep link`() {
        val entry = WatchNextPlan.entries(listOf(channel(7, now - 1000)), nowUtc = now).single()
        assertEquals("Channel 7", entry.title)
        assertEquals("https://example.com/7.png", entry.posterUri)
        assertEquals(now - 1000, entry.lastEngagementUtc)
        assertEquals(WatchNextPlan.deepLink(7), entry.deepLink)
    }

    @Test
    fun `what is on now becomes the row's description`() {
        val programme = Programme(epgChannelId = "e", startUtc = now, endUtc = now + 1000, title = "The News")
        val entry = WatchNextPlan.entries(
            listOf(channel(1, now - 1000)),
            nowByChannel = mapOf(1L to programme),
            nowUtc = now,
        ).single()
        assertEquals("The News", entry.description)
    }

    @Test
    fun `a channel with no guide still gets a row`() {
        assertNull(WatchNextPlan.entries(listOf(channel(1, now - 1000)), nowUtc = now).single().description)
    }

    /** A row claiming you were watching something you never opened is worse than a shorter row. */
    @Test
    fun `never-watched and hidden channels are left out`() {
        val entries = WatchNextPlan.entries(
            listOf(channel(1, 0), channel(2, now - 1000, hidden = true), channel(3, now - 2000)),
            nowUtc = now,
        )
        assertEquals(listOf(3L), entries.map { it.channelId })
    }

    @Test
    fun `a channel not watched for a month drops out`() {
        val entries = WatchNextPlan.entries(
            listOf(channel(1, now - 31 * day), channel(2, now - 29 * day)),
            nowUtc = now,
        )
        assertEquals(listOf(2L), entries.map { it.channelId })
    }

    @Test
    fun `the row is capped`() {
        val many = (1L..40L).map { channel(it, now - it * 1000) }
        assertEquals(WatchNextPlan.MAX_ENTRIES, WatchNextPlan.entries(many, nowUtc = now).size)
    }

    // ------------------------------------------------------------ taking rows out

    /**
     * Nothing expires a Watch Next row on its own, so the diff is the only thing that stops the
     * launcher offering a channel a playlist refresh deleted.
     */
    @Test
    fun `rows the plan no longer wants are named for deletion`() {
        assertEquals(listOf(3L, 4L), WatchNextPlan.stale(listOf(1L, 3L, 4L), listOf(1L, 2L)))
    }

    @Test
    fun `nothing is deleted when the plan still wants everything`() {
        assertTrue(WatchNextPlan.stale(listOf(1L, 2L), listOf(1L, 2L, 3L)).isEmpty())
    }

    // ------------------------------------------------------------ the deep link

    @Test
    fun `a deep link round-trips`() {
        assertEquals(42L, WatchNextPlan.channelIdFromDeepLink(WatchNextPlan.deepLink(42)))
    }

    @Test
    fun `a query string or fragment is ignored`() {
        assertEquals(7L, WatchNextPlan.channelIdFromDeepLink("shidur://channel/7?from=launcher"))
        assertEquals(7L, WatchNextPlan.channelIdFromDeepLink("shidur://channel/7#top"))
        assertEquals(7L, WatchNextPlan.channelIdFromDeepLink("shidur://channel/7/"))
    }

    /**
     * This arrives through an exported `ACTION_VIEW` filter, so anything that is not plainly one
     * of ours is refused rather than guessed at.
     */
    @Test
    fun `anything else is refused`() {
        listOf(
            null,
            "",
            "   ",
            "http://channel/1",
            "shidur://programme/1",
            "shidur://channel/",
            "shidur://channel/abc",
            "shidur://channel/-1",
            "shidur://channel/0",
            "shidur://channel/1/2",
            "shidur://channel/1x",
        ).forEach { assertNull("expected null for $it", WatchNextPlan.channelIdFromDeepLink(it)) }
    }
}
