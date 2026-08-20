package com.tivicopy.player.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tivicopy.player.data.db.Dao
import com.tivicopy.player.data.db.Database
import com.tivicopy.player.data.model.Playlist
import com.tivicopy.player.data.model.SourceType
import com.tivicopy.player.data.model.VodItem
import com.tivicopy.player.data.xtream.XtreamClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The on-demand queries behind FR-PLB-28/29.
 *
 * One table holds films, series containers and episodes, and telling them apart is what keeps a
 * series openable, an episode playable, and neither of them loose in the grid. The last two cases
 * cover the other half of the same feature: `resume_position_ms` had never been written by
 * anything, so continue-watching could not fill.
 *
 * Robolectric, like `ChannelQueriesTest`, because these are real SQL against a real SQLite file.
 */
@RunWith(RobolectricTestRunner::class)
// The plain platform Application, never this app's: `App.onCreate` would drag WorkManager and the
// notification channels into a unit test.
@Config(sdk = [34], application = Application::class)
class VodQueriesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<Database>()

    @After
    fun closeHelpers() {
        opened.forEach(Database::close)
        opened.clear()
    }

    // ------------------------------------------------------------- fixtures

    private fun dao(): Dao = Dao(Database(context).also(opened::add))

    private fun Dao.newPlaylist(name: String = "Panel"): Long = upsertPlaylist(
        Playlist(name = name, type = SourceType.XTREAM, url = "http://example.invalid"),
    )

    private fun Dao.put(item: VodItem) = transaction { d -> upsertVod(d, item) }

    private fun film(playlistId: Long, streamId: String, name: String) = VodItem(
        playlistId = playlistId,
        streamId = streamId,
        name = name,
    )

    private fun seriesContainer(playlistId: Long, seriesId: String, name: String) = VodItem(
        playlistId = playlistId,
        streamId = XtreamClient.SERIES_PREFIX + seriesId,
        name = name,
        seriesId = seriesId,
    )

    private fun episode(
        playlistId: Long,
        seriesId: String,
        episodeId: String,
        name: String,
        season: Int,
        number: Int,
    ) = VodItem(
        playlistId = playlistId,
        streamId = XtreamClient.EPISODE_PREFIX + episodeId,
        name = name,
        seriesId = seriesId,
        seasonNumber = season,
        episodeNumber = number,
    )

    // ---------------------------------------------------------------- cases

    @Test
    fun `the grid shows films and series but not the episodes underneath them`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.put(film(playlist, "900", "A Film"))
        dao.put(seriesContainer(playlist, "31", "A Series"))
        dao.put(episode(playlist, "31", "4211", "Pilot", season = 1, number = 1))

        assertEquals(
            listOf("A Film", "A Series"),
            dao.vod(playlist).map { it.name },
        )
        assertEquals(3, dao.vod(playlist, includeEpisodes = true).size)
    }

    /**
     * The prefixes earn their keep here: a panel that issues the same id for a film and for an
     * episode would otherwise have the second overwrite the first, taking its favourite and its
     * resume position with it.
     */
    @Test
    fun `a film and an episode sharing a provider id stay separate rows`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.put(film(playlist, "4211", "A Film"))
        dao.put(episode(playlist, "31", "4211", "Pilot", season = 1, number = 1))

        assertEquals(2, dao.vod(playlist, includeEpisodes = true).size)
        assertEquals("A Film", dao.vod(playlist).single().name)
    }

    @Test
    fun `asking for series gets containers, never episodes`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.put(film(playlist, "900", "A Film"))
        dao.put(seriesContainer(playlist, "31", "A Series"))
        dao.put(episode(playlist, "31", "4211", "Pilot", season = 1, number = 1))

        assertEquals(listOf("A Series"), dao.vod(playlist, seriesOnly = true).map { it.name })
        assertEquals(
            listOf("A Series"),
            dao.vod(playlist, seriesOnly = true, includeEpisodes = true).map { it.name },
        )
    }

    @Test
    fun `a search answers with the series, not with sixty of its episodes`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.put(seriesContainer(playlist, "31", "Fauda"))
        repeat(3) { i ->
            dao.put(episode(playlist, "31", "42$i", "Fauda episode $i", season = 1, number = i))
        }

        assertEquals(listOf("Fauda"), dao.vod(playlist, search = "Fauda").map { it.name })
    }

    /**
     * The two halves of the favourite star have to agree. The grid hides episodes because a
     * catalogue of them is noise; "my favourites" is the one list where a starred episode is the
     * whole point, and before this the star on an episode wrote a flag nothing could show.
     */
    @Test
    fun `a starred episode is findable under favourites`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.put(film(playlist, "900", "A Film"))
        dao.put(episode(playlist, "31", "4211", "Pilot", season = 1, number = 1))
        val stored = dao.episodes(playlist, "31").single()
        dao.setVodFavourite(stored.id, true)

        assertEquals(
            listOf("Pilot"),
            dao.vod(playlist, favouritesOnly = true, includeEpisodes = true).map { it.name },
        )
        // And it is still not in the ordinary catalogue.
        assertEquals(listOf("A Film"), dao.vod(playlist).map { it.name })
    }

    @Test
    fun `episodes come back in the order a viewer meets them`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.put(episode(playlist, "31", "3", "S2E1", season = 2, number = 1))
        dao.put(episode(playlist, "31", "2", "S1E2", season = 1, number = 2))
        dao.put(episode(playlist, "31", "1", "S1E1", season = 1, number = 1))

        assertEquals(
            listOf("S1E1", "S1E2", "S2E1"),
            dao.episodes(playlist, "31").map { it.name },
        )
    }

    @Test
    fun `episodes are scoped to their own series and playlist`() {
        val dao = dao()
        val one = dao.newPlaylist("One")
        val two = dao.newPlaylist("Two")
        dao.put(episode(one, "31", "1", "Ours", season = 1, number = 1))
        dao.put(episode(one, "32", "2", "Another series", season = 1, number = 1))
        dao.put(episode(two, "31", "3", "Another playlist", season = 1, number = 1))

        assertEquals(listOf("Ours"), dao.episodes(one, "31").map { it.name })
    }

    /**
     * Opening a series fetches it again, and `upsertVod` updates before it inserts. Without that
     * the star and the place a viewer had reached would be wiped every time they came back.
     */
    @Test
    fun `re-fetching a series keeps the favourite and the resume position`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.put(episode(playlist, "31", "4211", "Pilot", season = 1, number = 1))
        val stored = dao.episodes(playlist, "31").single()
        dao.setVodFavourite(stored.id, true)
        dao.saveResume(stored.id, 90_000L, 1_700_000_000_000L)

        // The same episode as the panel would send it back: no local state on it at all.
        dao.put(episode(playlist, "31", "4211", "Pilot", season = 1, number = 1))

        val after = dao.episodes(playlist, "31").single()
        assertEquals(stored.id, after.id)
        assertTrue(after.favourite)
        assertEquals(90_000L, after.resumePositionMs)
    }

    @Test
    fun `a saved position puts the episode in continue-watching`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.put(episode(playlist, "31", "4211", "Pilot", season = 1, number = 1))
        val stored = dao.episodes(playlist, "31").single()

        assertTrue(dao.continueWatching().isEmpty())

        dao.saveResume(stored.id, 90_000L, 1_700_000_000_000L)

        assertEquals(listOf("Pilot"), dao.continueWatching().map { it.name })
    }

    /** Finishing is stored as zero, which is what takes the title back out of the row. */
    @Test
    fun `clearing the position takes the episode out of continue-watching`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.put(episode(playlist, "31", "4211", "Pilot", season = 1, number = 1))
        val stored = dao.episodes(playlist, "31").single()
        dao.saveResume(stored.id, 90_000L, 1_700_000_000_000L)

        dao.saveResume(stored.id, 0L, 1_700_000_100_000L)

        assertTrue(dao.continueWatching().isEmpty())
    }

    /**
     * Play-time reads the mark from the database, because the tile's snapshot goes stale the
     * moment another episode saves its place — and starting from a stale mark has the next
     * save overwrite the real one (FR-PLB-29).
     */
    @Test
    fun `the stored resume mark is the authority, not the tile's snapshot`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.put(episode(playlist, "31", "4211", "Pilot", season = 1, number = 1))
        val stored = dao.episodes(playlist, "31").single()

        dao.saveResume(stored.id, 30 * 60_000L, 1_700_000_000_000L)

        assertEquals(30 * 60_000L, dao.vodResumePosition(stored.id))
        // A row that does not exist resumes from the start rather than failing.
        assertEquals(0L, dao.vodResumePosition(999_999L))
    }

    /**
     * A pruned episode kept in the cache answers 404 through `/series/`, which reads like a
     * broken provider. The survivors keep what the user set, and the series container — the
     * same `series_id`, but not an episode — is spared.
     */
    @Test
    fun `a successful fetch prunes the episodes the panel stopped listing`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.put(seriesContainer(playlist, "31", "The Series"))
        dao.put(episode(playlist, "31", "4211", "Pilot", season = 1, number = 1))
        dao.put(episode(playlist, "31", "4212", "Fallout", season = 1, number = 2))
        val kept = dao.episodes(playlist, "31").first { it.episodeNumber == 1 }
        dao.setVodFavourite(kept.id, true)

        dao.transaction { d ->
            dao.deleteStaleEpisodes(d, playlist, "31", setOf(kept.streamId))
        }

        val after = dao.episodes(playlist, "31").single()
        assertEquals("Pilot", after.name)
        assertTrue(after.favourite)
        assertEquals(1, dao.vod(playlist, seriesOnly = true).size)
    }
}
