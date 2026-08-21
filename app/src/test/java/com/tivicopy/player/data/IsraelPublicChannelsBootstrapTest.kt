package com.tivicopy.player.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tivicopy.player.data.builtin.IsraelPublicChannels
import com.tivicopy.player.data.db.Dao
import com.tivicopy.player.data.db.Database
import com.tivicopy.player.data.prefs.Settings
import com.tivicopy.player.data.repo.SourceRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/00-scope-and-platforms.md §0.1: the app bootstraps one fixed built-in playlist of
 * Israeli free-to-air public channels on every cold start. This pins the idempotency contract
 * that makes that safe to call unconditionally from `App.onCreate` — no separate first-run flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IsraelPublicChannelsBootstrapTest {

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
    fun `first cold start inserts exactly one built-in playlist`() {
        val sources = sources()
        val dao = Dao(opened.first())

        runBlocking { sources.ensureBuiltInPlaylist() }

        val matches = dao.playlists().filter { it.url == IsraelPublicChannels.M3U_URL }
        assertEquals(1, matches.size)
        assertEquals(IsraelPublicChannels.NAME, matches.first().name)
        assertEquals(IsraelPublicChannels.EPG_URL, matches.first().epgUrl)
    }

    @Test
    fun `a second cold start does not duplicate it`() {
        val sources = sources()
        val dao = Dao(opened.first())

        runBlocking {
            sources.ensureBuiltInPlaylist()
            sources.ensureBuiltInPlaylist()
        }

        assertEquals(1, dao.playlists().count { it.url == IsraelPublicChannels.M3U_URL })
    }

    @Test
    fun `deleting it is undone by the next cold start`() {
        val sources = sources()
        val dao = Dao(opened.first())

        runBlocking { sources.ensureBuiltInPlaylist() }
        val seeded = dao.playlists().first { it.url == IsraelPublicChannels.M3U_URL }
        dao.deletePlaylist(seeded.id)
        assertEquals(0, dao.playlists().size)

        runBlocking { sources.ensureBuiltInPlaylist() }

        assertEquals(1, dao.playlists().count { it.url == IsraelPublicChannels.M3U_URL })
    }
}
