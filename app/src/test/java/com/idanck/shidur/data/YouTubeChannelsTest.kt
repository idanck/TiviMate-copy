package com.idanck.shidur.data

import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.youtube.YouTubeChannels
import com.idanck.shidur.data.youtube.YtChannel
import com.idanck.shidur.data.youtube.YtChannelState
import com.idanck.shidur.data.youtube.YtListed
import com.idanck.shidur.data.youtube.YtVideo
import com.idanck.shidur.data.youtube.youtubeChannelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** YouTube channels in the channel list and guide (ADR-061): made-up rows, never database ones. */
class YouTubeChannelsTest {

    private val listed = listOf(
        YtListed("UCisowXt5wZkp2sR3rFh9lnQ", 900, "i24NEWS עברית", null),
        YtListed("UCKEImtWikw9usC1pl_9m1nQ", 901, "C14", "https://example.invalid/c14.jpg"),
    )

    private fun video(id: String, live: Boolean = false, upcoming: Boolean = false, startedAt: Long? = null, scheduledAt: Long? = null) = YtVideo(
        id = id, title = "t-$id", channelId = "UCisowXt5wZkp2sR3rFh9lnQ", channelTitle = "i24NEWS עברית", thumb = null,
        publishedAt = null, duration = null, live = live, upcoming = upcoming, startedAt = startedAt, scheduledAt = scheduledAt,
        viewers = null, embeddable = true,
    )

    @Test
    fun `rows carry their number, a negative id and the YouTube scheme`() {
        val rows = YouTubeChannels.channels(listed, playlistId = 7)
        assertEquals(listOf(900, 901), rows.map { it.number })
        assertTrue(rows.all { it.id < 0 && it.playlistId == 7L })
        assertEquals(2, rows.map { it.id }.toSet().size)
        assertEquals("UCKEImtWikw9usC1pl_9m1nQ", rows[1].youtubeChannelId)
        assertNull(Channel(playlistId = 1, identity = "x", name = "x", urls = listOf("http://a/b.m3u8")).youtubeChannelId)
    }

    @Test
    fun `the guide shows the live stream, then what is announced, and nothing off air`() {
        val channel = YouTubeChannels.channels(listed, 1).first()
        val now = 10_000_000_000L
        val state = YtChannelState(
            YtChannel("UCisowXt5wZkp2sR3rFh9lnQ", "i24NEWS עברית", null, null, null, null),
            live = listOf(video("l", live = true, startedAt = now - 3_600_000)),
            upcoming = listOf(video("u", upcoming = true, scheduledAt = now + 3 * 3_600_000)),
            recent = emptyList(),
        )
        val programmes = YouTubeChannels.programmes(channel, state, now)
        assertEquals(listOf("t-l", "t-u"), programmes.map { it.title })
        assertEquals(now - 3_600_000, programmes[0].startUtc)
        assertTrue(programmes[0].endUtc > now)

        val offAir = state.copy(live = emptyList(), upcoming = emptyList())
        assertTrue(YouTubeChannels.programmes(channel, offAir, now).isEmpty())
        assertTrue(YouTubeChannels.programmes(channel, null, now).isEmpty())
    }
}
