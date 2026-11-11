package com.idanck.shidur.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.idanck.shidur.core.Outcome
import com.idanck.shidur.data.db.Database
import com.idanck.shidur.data.podcast.EpisodeFilter
import com.idanck.shidur.data.podcast.EpisodeKey
import com.idanck.shidur.data.podcast.EpisodeMark
import com.idanck.shidur.data.podcast.EpisodeStatus
import com.idanck.shidur.data.podcast.FetchResult
import com.idanck.shidur.data.podcast.PodcastDao
import com.idanck.shidur.data.podcast.PodcastFeed
import com.idanck.shidur.data.podcast.PodcastRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The podcast library and the viewer's marks (ADR-067): real SQL against a real SQLite file, a
 * feed handed in instead of fetched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PodcastRepositoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<Database>()
    private var now = DAY * 400

    @After
    fun close() {
        opened.forEach(Database::close)
        opened.clear()
    }

    private fun episode(n: Int, published: Long?, seconds: Long = 600) = PodcastFeed.FeedEpisode(
        guid = "g$n", title = "Episode $n", description = null, audioUrl = "https://cdn.example.com/$n.mp3",
        mimeType = "audio/mpeg", lengthBytes = 0, durationSeconds = seconds, publishedUtc = published,
        imageUrl = null, season = null, episode = n, explicit = false, link = null, chaptersUrl = null, transcriptUrl = null,
    )

    private fun show(vararg episodes: PodcastFeed.FeedEpisode) = PodcastFeed(
        title = "Show", author = null, description = null, imageUrl = null, link = null, language = "he",
        categories = emptyList(), episodes = episodes.toList(),
    )

    private var served = show()

    private fun repo(): PodcastRepository {
        val db = Database(context).also(opened::add)
        return PodcastRepository(PodcastDao(db), device = { "phone" }, clock = { now }) { _, _, _ ->
            Outcome.Ok(FetchResult.Fresh(served, etag = null, lastModified = null, finalUrl = null))
        }
    }

    private val url = "https://example.com/feed.xml"

    @Test
    fun `new episodes are the recent ones not yet heard`() = runBlocking {
        served = show(episode(3, now - DAY), episode(2, now - 10 * DAY), episode(1, now - 60 * DAY))
        val repo = repo()
        val podcast = (repo.add(url) as Outcome.Ok).value
        // Two weeks back from when it was added; the archive is not "new".
        assertEquals(listOf("g3", "g2"), repo.newEpisodes().map { it.guid })
        assertEquals(2, repo.library().single().newCount)

        repo.savePosition(EpisodeKey(url, "g3"), positionMs = 120_000, durationMs = 600_000)
        assertEquals(listOf("g2"), repo.newEpisodes().map { it.guid })
        assertEquals(listOf("g3"), repo.inProgress().map { it.guid })
        assertEquals(EpisodeStatus.STARTED, repo.episodes(podcast.id, EpisodeFilter.ALL).first().status)
    }

    @Test
    fun `near the end counts as played and starts over`() = runBlocking {
        served = show(episode(1, now))
        val repo = repo()
        repo.add(url)
        val key = EpisodeKey(url, "g1")
        repo.savePosition(key, positionMs = 590_000, durationMs = 600_000)
        val e = requireNotNull(repo.episode(key))
        assertTrue(e.mark.played)
        assertEquals(0L, e.mark.positionMs)
        assertTrue(repo.inProgress().isEmpty())
        assertEquals(listOf("g1"), repo.history().map { it.guid })
    }

    @Test
    fun `clearing the history keeps the position`() = runBlocking {
        served = show(episode(1, now))
        val repo = repo()
        repo.add(url)
        val key = EpisodeKey(url, "g1")
        repo.savePosition(key, 60_000, 600_000)
        repo.clearHistory()
        assertTrue(repo.history().isEmpty())
        assertEquals(60_000L, repo.episode(key)?.mark?.positionMs)
    }

    @Test
    fun `a favourite outlives the feed dropping it and the show leaving the library`() = runBlocking {
        served = show(episode(2, now), episode(1, now - DAY))
        val repo = repo()
        val podcast = (repo.add(url) as Outcome.Ok).value
        repo.setFavourite(EpisodeKey(url, "g1"), true)

        served = show(episode(3, now + DAY), episode(2, now))
        repo.refresh(podcast.id)
        assertEquals(setOf("g3", "g2", "g1"), repo.episodes(podcast.id, EpisodeFilter.ALL).map { it.guid }.toSet())

        repo.setInLibrary(podcast.id, false)
        assertTrue(repo.library().isEmpty())
        assertEquals(listOf("g1"), repo.favourites().map { it.guid })

        repo.setFavourite(EpisodeKey(url, "g1"), false)
        assertTrue(repo.favourites().isEmpty())
    }

    @Test
    fun `the queue comes before the next episode of the show`() = runBlocking {
        served = show(episode(3, now), episode(2, now - DAY), episode(1, now - 2 * DAY))
        val repo = repo()
        repo.add(url)
        // Newest first on the page: after episode 2 comes the newer one, 3.
        assertEquals("g3", repo.nextAfter(EpisodeKey(url, "g2"))?.guid)
        assertNull(repo.nextAfter(EpisodeKey(url, "g3")))

        repo.enqueue(EpisodeKey(url, "g1"), next = false)
        assertEquals("g1", repo.nextAfter(EpisodeKey(url, "g2"))?.guid)
        repo.savePosition(EpisodeKey(url, "g1"), 600_000, 600_000)
        assertTrue(repo.queue().isEmpty())
    }

    @Test
    fun `the newer mark wins a merge, whole`() {
        val local = EpisodeMark(positionMs = 100, favourite = true, updatedUtc = 10)
        val remote = EpisodeMark(positionMs = 500, favourite = false, updatedUtc = 20)
        assertEquals(remote, PodcastRepository.merge(local, remote))
        assertEquals(local, PodcastRepository.merge(local, remote.copy(updatedUtc = 5)))
        assertEquals(remote, PodcastRepository.merge(null, remote))
    }

    @Test
    fun `feed addresses as people paste them`() {
        assertEquals("https://example.com/rss", PodcastRepository.normalise(" example.com/rss "))
        assertEquals("https://example.com/rss", PodcastRepository.normalise("feed://example.com/rss"))
        assertEquals("https://example.com/rss", PodcastRepository.normalise("itpc://example.com/rss"))
        assertEquals("http://example.com/rss", PodcastRepository.normalise("feed:http://example.com/rss"))
        assertNull(PodcastRepository.normalise("ftp://example.com/rss"))
        assertNull(PodcastRepository.normalise("not a url"))
        assertFalse(PodcastRepository.normalise("https://example.com/a b").isNullOrEmpty())
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
