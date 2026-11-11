package com.idanck.shidur.data

import com.idanck.shidur.data.podcast.EpisodeKey
import com.idanck.shidur.data.podcast.EpisodeMark
import com.idanck.shidur.data.podcast.Podcast
import com.idanck.shidur.data.podcast.PodcastPrefs
import com.idanck.shidur.data.podcast.PodcastSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What the sync writes must match the Firestore rules' field lists and limits (ADR-067). */
class PodcastSyncTest {
    @Test
    fun `a document id is 40 hex characters, the same on every device`() {
        val id = PodcastSync.docId("https://example.com/feed.xml")
        assertEquals(40, id.length)
        assertEquals(true, id.all { it in "0123456789abcdef" })
        assertEquals(id, PodcastSync.docId("https://example.com/feed.xml"))
        assertNotEquals(id, PodcastSync.docId("https://example.com/feed.xml\ng1"))
    }

    @Test
    fun `a show is written with the rules' fields and its choices time`() {
        val p = Podcast(feedUrl = "https://example.com/feed.xml", title = "x".repeat(500), prefs = PodcastPrefs(speed = 1.5f), choicesUtc = 7)
        val data = PodcastSync.podcastData(p)
        assertEquals(
            setOf("feedUrl", "title", "inLibrary", "speed", "skipIntroSeconds", "skipOutroSeconds", "notifyNew", "oldestFirst", "updatedUtc"),
            data.keys,
        )
        assertEquals(300, (data["title"] as String).length)
        assertEquals(7L, data["updatedUtc"])
    }

    @Test
    fun `a mark carries its key and every field`() {
        val data = PodcastSync.markData(EpisodeKey("f", "g"), EpisodeMark(positionMs = 5, favourite = true, updatedUtc = 9))
        assertEquals("f", data["feedUrl"])
        assertEquals("g", data["guid"])
        assertEquals(11, data.size)
    }

    @Test
    fun `the queue is cut to the rules' ceiling`() {
        val keys = List(250) { EpisodeKey("f", "g$it") }
        val data = PodcastSync.queueData(keys, 3)
        assertEquals(PodcastSync.MAX_QUEUE, (data["keys"] as List<*>).size)
    }
}
