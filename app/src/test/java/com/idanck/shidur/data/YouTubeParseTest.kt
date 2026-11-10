package com.idanck.shidur.data

import com.idanck.shidur.data.youtube.YtLinkPoll
import com.idanck.shidur.data.youtube.YtParse
import com.idanck.shidur.data.youtube.YtWatched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the YouTube callables' answers (ADR-061): plain maps as the Functions SDK hands them over,
 * with numbers as whichever of Integer, Long or Double the JSON became.
 */
class YouTubeParseTest {

    private fun video(id: String, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to "Title $id",
        "channelId" to "UC_HwfTAcjBESKZRJq6BTCpg",
        "channelTitle" to "כאן | חדשות",
        "thumb" to "https://i.ytimg.com/vi/$id/mqdefault.jpg",
        "publishedAt" to 1794255499000.0,
        "duration" to 212,
        "live" to false,
        "upcoming" to false,
        "startedAt" to null,
        "scheduledAt" to null,
        "viewers" to null,
        "embeddable" to true,
    ) + extra

    @Test
    fun `a channel state with its videos is read whole`() {
        val raw = mapOf(
            "channel" to mapOf(
                "id" to "UC_HwfTAcjBESKZRJq6BTCpg",
                "title" to "כאן | חדשות",
                "handle" to "@kanhadashot",
                "thumb" to null,
                "subscribers" to 1_200_000L,
                "videos" to 45_000,
            ),
            "live" to listOf(video("live1", mapOf("live" to true, "duration" to null, "viewers" to 1234, "startedAt" to 1794200000000L))),
            "upcoming" to emptyList<Any>(),
            "recent" to listOf(video("a"), video("b")),
        )
        val state = YtParse.states(listOf(raw, "junk")).single()
        assertEquals("UC_HwfTAcjBESKZRJq6BTCpg", state.channel.id)
        assertEquals(1_200_000L, state.channel.subscribers)
        assertEquals(45_000L, state.channel.videos)
        assertNull(state.channel.thumb)
        val live = state.live.single()
        assertTrue(live.live)
        assertNull(live.duration)
        assertEquals(1234L, live.viewers)
        assertEquals(listOf("a", "b"), state.recent.map { it.id })
        assertEquals(212, state.recent.first().duration)
        assertEquals(1794255499000L, state.recent.first().publishedAt)
    }

    @Test
    fun `a video with no id or no embeddable flag`() {
        assertNull(YtParse.video(mapOf("title" to "x")))
        assertTrue(YtParse.video(video("c") - "embeddable")!!.embeddable)
        assertFalse(YtParse.video(video("d", mapOf("embeddable" to false)))!!.embeddable)
    }

    @Test
    fun `progress is the share of the length reached, and none for a live stream`() {
        val v = YtParse.video(video("e"))!!
        assertEquals(0.5f, YtWatched(v, 106, 0L).progress!!, 0.001f)
        assertEquals(1f, YtWatched(v, 999, 0L).progress!!, 0.001f)
        assertNull(YtWatched(v.copy(duration = null), 10, 0L).progress)
    }

    @Test
    fun `search results, link status, a link code and a poll`() {
        val search = YtParse.search(
            mapOf(
                "channels" to listOf(mapOf("id" to "UCisowXt5wZkp2sR3rFh9lnQ", "title" to "i24NEWS עברית", "thumb" to null), mapOf("title" to "no id")),
                "videos" to listOf(video("v1")),
                "cached" to true,
            ),
        )
        assertEquals(listOf("UCisowXt5wZkp2sR3rFh9lnQ"), search.channels.map { it.id })
        assertEquals(listOf("v1"), search.videos.map { it.id })

        val status = YtParse.linkStatus(mapOf("linked" to false, "tvReady" to true, "phoneReady" to true))
        assertFalse(status.linked)
        assertTrue(status.tvReady)

        val code = YtParse.linkCode(mapOf("userCode" to "ABCD-EFGH", "verificationUrl" to "https://www.google.com/device", "interval" to 5, "expiresIn" to 1800))!!
        assertEquals("ABCD-EFGH", code.userCode)
        assertEquals(1800, code.expiresInSeconds)
        assertNull(YtParse.linkCode(mapOf("interval" to 5)))

        assertEquals(YtLinkPoll.LINKED, YtParse.linkPoll(mapOf("status" to "linked")))
        assertEquals(YtLinkPoll.PENDING, YtParse.linkPoll(null))

        val subs = YtParse.subscriptions(mapOf("linked" to true, "channels" to emptyList<Any>(), "feed" to listOf(video("f1")), "stale" to false))
        assertTrue(subs.linked)
        assertEquals(listOf("f1"), subs.feed.map { it.id })
    }
}
