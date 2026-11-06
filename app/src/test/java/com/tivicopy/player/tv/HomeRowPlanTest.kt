package com.tivicopy.player.tv

import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's own row on the Google TV home screen (FR-TVI-03). Plain JUnit, for the same reason
 * as [WatchNextPlanTest]: the decisions live in pure Kotlin and the provider half decides nothing.
 */
class HomeRowPlanTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun channel(
        id: Long,
        watched: Long = 0,
        favourite: Boolean = false,
        hidden: Boolean = false,
    ) = Channel(
        id = id,
        playlistId = 1,
        identity = "i$id",
        name = "Channel $id",
        logoUrl = "https://example.com/$id.png",
        favourite = favourite,
        hidden = hidden,
        lastWatchedUtc = watched,
    )

    @Test
    fun `favourites lead, in their own order, then recents newest first`() {
        val favourites = listOf(channel(5, favourite = true), channel(3, favourite = true))
        val recent = listOf(channel(1, watched = now - 2000), channel(2, watched = now - 1000))
        val entries = HomeRowPlan.entries(favourites, recent, nowUtc = now)
        assertEquals(listOf(5L, 3L, 2L, 1L), entries.map { it.channelId })
    }

    @Test
    fun `weights fall from first to last so the launcher keeps that order`() {
        val entries = HomeRowPlan.entries(
            listOf(channel(5, favourite = true)),
            listOf(channel(1, watched = now - 1000), channel(2, watched = now - 2000)),
            nowUtc = now,
        )
        assertEquals(listOf(3, 2, 1), entries.map { it.weight })
    }

    @Test
    fun `a favourite that was also watched recently is one card, in its favourite slot`() {
        val fav = channel(7, watched = now - 1000, favourite = true)
        val entries = HomeRowPlan.entries(listOf(fav), listOf(fav, channel(1, watched = now - 5000)), nowUtc = now)
        assertEquals(listOf(7L, 1L), entries.map { it.channelId })
    }

    @Test
    fun `hidden channels and stale recents are left out, stale favourites are not`() {
        val entries = HomeRowPlan.entries(
            favourites = listOf(channel(1, favourite = true, hidden = true), channel(2, watched = now - 90 * day, favourite = true)),
            recent = listOf(channel(3, watched = now - 31 * day), channel(4, watched = now - 1000, hidden = true), channel(5, watched = now - day)),
            nowUtc = now,
        )
        assertEquals(listOf(2L, 5L), entries.map { it.channelId })
    }

    @Test
    fun `a long favourites list still leaves room for recents`() {
        val favourites = (1L..30L).map { channel(it, favourite = true) }
        val recent = (101L..103L).map { channel(it, watched = now - it) }
        val entries = HomeRowPlan.entries(favourites, recent, nowUtc = now, max = 20)
        assertEquals(20, entries.size)
        assertEquals((1L..17L).toList() + listOf(101L, 102L, 103L), entries.map { it.channelId })
    }

    @Test
    fun `recents fill whatever favourites leave`() {
        val recent = (1L..30L).map { channel(it, watched = now - it * 1000) }
        val entries = HomeRowPlan.entries(listOf(channel(99, favourite = true)), recent, nowUtc = now, max = 5)
        assertEquals(listOf(99L, 1L, 2L, 3L, 4L), entries.map { it.channelId })
    }

    @Test
    fun `the programme on now is the second line, and blank or missing means none`() {
        val programme = Programme(epgChannelId = "e", startUtc = now, endUtc = now + 1000, title = "The News")
        val blank = programme.copy(title = "  ")
        val entries = HomeRowPlan.entries(
            favourites = listOf(channel(1, favourite = true), channel(2, favourite = true), channel(3, favourite = true)),
            recent = emptyList(),
            nowByChannel = mapOf(1L to programme, 2L to blank),
            nowUtc = now,
        )
        assertEquals("The News", entries[0].description)
        assertNull(entries[1].description)
        assertNull(entries[2].description)
        assertEquals("Channel 1", entries[0].title)
    }

    @Test
    fun `cards deep link to the channel they show`() {
        val entries = HomeRowPlan.entries(listOf(channel(42, favourite = true)), emptyList(), nowUtc = now)
        assertEquals("shidur://channel/42", entries.single().deepLink)
        assertEquals(42L, WatchNextPlan.channelIdFromDeepLink(entries.single().deepLink))
    }

    @Test
    fun `nothing to show is an empty row, not a crash`() {
        assertTrue(HomeRowPlan.entries(emptyList(), emptyList(), nowUtc = now).isEmpty())
        assertTrue(HomeRowPlan.entries(listOf(channel(1, favourite = true)), emptyList(), max = -1).isEmpty())
    }

    @Test
    fun `cards the plan no longer wants are taken out`() {
        assertEquals(listOf(3L), HomeRowPlan.stale(existing = listOf(1L, 2L, 3L), wanted = listOf(1L, 2L)))
    }
}
