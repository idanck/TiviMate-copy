package com.idanck.shidur.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.idanck.shidur.data.builtin.IsraelPublicChannels
import com.idanck.shidur.data.db.Dao
import com.idanck.shidur.data.db.Database
import com.idanck.shidur.data.model.Playlist
import com.idanck.shidur.data.model.SourceType
import com.idanck.shidur.data.prefs.Settings
import com.idanck.shidur.data.repo.SourceRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /**
     * 0.4.4 shipped seeding this playlist at a URL that was never served (ADR-013). Correcting the
     * constant is only half the fix: the row already on those devices has to be moved to the new
     * address, or bootstrap leaves the dead one in the list and inserts a working one beside it.
     */
    @Test
    fun `a playlist seeded at a legacy url is corrected in place`() {
        val sources = sources()
        val dao = Dao(opened.first())

        val legacyId = dao.upsertPlaylist(
            Playlist(
                name = IsraelPublicChannels.NAME,
                type = SourceType.M3U_URL,
                url = IsraelPublicChannels.LEGACY_M3U_URLS.first(),
                epgUrl = "https://updates.idanck.com/il-epg/guide.xml",
                refreshIntervalMinutes = 24 * 60,
                sortIndex = 7,
                colour = 0xFF00FF00.toInt(),
                lastRefreshUtc = 1_700_000_000_000,
                lastRefreshOk = false,
                lastRefreshMessage = "NotFound",
            ),
        )

        runBlocking { sources.ensureBuiltInPlaylist() }

        val all = dao.playlists()
        assertEquals(1, all.size)
        val migrated = all.single()
        assertEquals(legacyId, migrated.id)
        assertEquals(IsraelPublicChannels.M3U_URL, migrated.url)
        assertEquals(IsraelPublicChannels.EPG_URL, migrated.epgUrl)
        // The user's own settings on the row survive the correction...
        assertEquals(7, migrated.sortIndex)
        assertEquals(0xFF00FF00.toInt(), migrated.colour)
        // ...while the failure recorded against the dead URL is cleared, so RefreshWorker treats
        // the corrected row as due immediately rather than a day from now.
        assertEquals(0L, migrated.lastRefreshUtc)
        assertNull(migrated.lastRefreshMessage)
    }

    @Test
    fun `a legacy row is corrected only once, not on every start`() {
        val sources = sources()
        val dao = Dao(opened.first())

        dao.upsertPlaylist(
            Playlist(
                name = IsraelPublicChannels.NAME,
                type = SourceType.M3U_URL,
                url = IsraelPublicChannels.LEGACY_M3U_URLS.first(),
            ),
        )

        runBlocking {
            sources.ensureBuiltInPlaylist()
            sources.ensureBuiltInPlaylist()
        }

        assertEquals(1, dao.playlists().size)
    }
}
