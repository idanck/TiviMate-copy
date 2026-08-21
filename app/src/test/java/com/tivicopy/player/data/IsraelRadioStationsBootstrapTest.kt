package com.tivicopy.player.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tivicopy.player.data.builtin.IsraelRadioStations
import com.tivicopy.player.data.db.Dao
import com.tivicopy.player.data.db.Database
import com.tivicopy.player.data.model.Playlist
import com.tivicopy.player.data.model.SourceType
import com.tivicopy.player.data.prefs.Settings
import com.tivicopy.player.data.repo.SourceRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/00-scope-and-platforms.md §0.1 / ADR-015: the app bootstraps one fixed built-in playlist
 * of Israeli radio stations on every cold start, alongside the TV channels one. This pins the
 * same idempotency contract [IsraelPublicChannelsBootstrapTest] pins for that playlist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IsraelRadioStationsBootstrapTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<Database>()

    private fun sources(): SourceRepository {
        val database = Database(context).also(opened::add)
        return SourceRepository(context, Dao(database), Settings(context))
    }

    @After
    fun closeHelpers() {
        opened.forEach(Database::close)
        opened.clear()
    }

    @Test
    fun `first cold start inserts exactly one radio playlist`() {
        val sources = sources()
        val dao = Dao(opened.first())

        runBlocking { sources.ensureRadioPlaylist() }

        val matches = dao.playlists().filter { it.url == IsraelRadioStations.M3U_URL }
        assertEquals(1, matches.size)
        assertEquals(IsraelRadioStations.NAME, matches.first().name)
        assertNull(matches.first().epgUrl)
    }

    @Test
    fun `a second cold start does not duplicate it`() {
        val sources = sources()
        val dao = Dao(opened.first())

        runBlocking {
            sources.ensureRadioPlaylist()
            sources.ensureRadioPlaylist()
        }

        assertEquals(1, dao.playlists().count { it.url == IsraelRadioStations.M3U_URL })
    }

    @Test
    fun `deleting it is undone by the next cold start`() {
        val sources = sources()
        val dao = Dao(opened.first())

        runBlocking { sources.ensureRadioPlaylist() }
        val seeded = dao.playlists().first { it.url == IsraelRadioStations.M3U_URL }
        dao.deletePlaylist(seeded.id)
        assertEquals(0, dao.playlists().size)

        runBlocking { sources.ensureRadioPlaylist() }

        assertEquals(1, dao.playlists().count { it.url == IsraelRadioStations.M3U_URL })
    }

    @Test
    fun `it coexists with the TV built-in playlist without interfering`() {
        val sources = sources()
        val dao = Dao(opened.first())

        runBlocking {
            sources.ensureBuiltInPlaylist()
            sources.ensureRadioPlaylist()
        }

        assertEquals(2, dao.playlists().size)
    }

    /**
     * A playlist that merely happens not to match [IsraelRadioStations.M3U_URL] or any entry in
     * [IsraelRadioStations.LEGACY_M3U_URLS] (currently empty) is not the built-in radio playlist
     * under an old address — it is left alone, and a fresh row is inserted beside it.
     */
    @Test
    fun `an unrelated playlist is left untouched and a fresh row is inserted beside it`() {
        val sources = sources()
        val dao = Dao(opened.first())

        val unrelatedId = dao.upsertPlaylist(
            Playlist(
                name = "My own subscription",
                type = SourceType.M3U_URL,
                url = "https://example.invalid/my-own.m3u",
                refreshIntervalMinutes = 24 * 60,
            ),
        )

        runBlocking { sources.ensureRadioPlaylist() }

        val all = dao.playlists()
        assertEquals(2, all.size)
        assertEquals(1, all.count { it.id == unrelatedId })
        assertEquals(1, all.count { it.url == IsraelRadioStations.M3U_URL })
    }
}
