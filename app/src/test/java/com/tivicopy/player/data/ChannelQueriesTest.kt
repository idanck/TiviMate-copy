package com.tivicopy.player.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tivicopy.player.data.db.Dao
import com.tivicopy.player.data.db.Database
import com.tivicopy.player.data.m3u.M3uEntry
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.ChannelGroup
import com.tivicopy.player.data.model.ChannelSort
import com.tivicopy.player.data.model.Playlist
import com.tivicopy.player.data.model.SourceType
import com.tivicopy.player.data.prefs.Settings
import com.tivicopy.player.data.repo.SourceRepository
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
 * The channel-management queries behind FR-ORG-02..07 and FR-UI-09.
 *
 * These exist because the requirements they serve were recorded as done while nothing in the app
 * could invoke them: the reads and writes were never exercised by anything, in production or in a
 * test. The last case here is the important one — it fails against the whole-row write these
 * edits used to go through, and passes against the targeted ones.
 *
 * Robolectric, like `DatabaseTest`, because these are real SQL against a real SQLite file.
 */
@RunWith(RobolectricTestRunner::class)
// The plain platform Application, never this app's: `App.onCreate` would drag WorkManager and the
// notification channels into a unit test.
@Config(sdk = [34], application = Application::class)
class ChannelQueriesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val opened = mutableListOf<Database>()

    private fun database(): Database = Database(context).also(opened::add)

    @After
    fun closeHelpers() {
        opened.forEach(Database::close)
        opened.clear()
    }

    // ------------------------------------------------------------- fixtures

    private fun dao(): Dao = Dao(database())

    private fun Dao.newPlaylist(name: String = "Test"): Long = upsertPlaylist(
        Playlist(name = name, type = SourceType.M3U_URL, url = "http://example.invalid/list.m3u"),
    )

    private fun Dao.newChannel(
        playlistId: Long,
        name: String,
        identity: String = "id:$name",
        number: Int = 0,
        sortIndex: Int = 0,
        groupId: Long? = null,
        customName: String? = null,
        watchCount: Int = 0,
    ): Long = upsertChannel(
        Channel(
            playlistId = playlistId,
            identity = identity,
            name = name,
            customName = customName,
            number = number,
            sortIndex = sortIndex,
            groupId = groupId,
            watchCount = watchCount,
            urls = listOf("http://example.invalid/$identity"),
        ),
    )

    // ------------------------------------------------------------ favourites

    @Test
    fun `the favourites view returns only what was marked`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        val one = dao.newChannel(playlist, "One", sortIndex = 0)
        dao.newChannel(playlist, "Two", sortIndex = 1)

        assertTrue(dao.channels(playlist, favouritesOnly = true).isEmpty())

        dao.setChannelFlags(one, favourite = true)
        assertEquals(listOf("One"), dao.channels(playlist, favouritesOnly = true).map { it.name })

        dao.setChannelFlags(one, favourite = false)
        assertTrue(dao.channels(playlist, favouritesOnly = true).isEmpty())
    }

    // --------------------------------------------------------------- hiding

    @Test
    fun `a hidden channel leaves every list and is found again by the hidden view`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        val one = dao.newChannel(playlist, "One")
        dao.newChannel(playlist, "Two")

        dao.setChannelFlags(one, hidden = true)

        assertEquals(listOf("Two"), dao.channels(playlist).map { it.name })
        assertEquals(listOf("One"), dao.channels(playlist, hiddenOnly = true).map { it.name })
        assertEquals(2, dao.channels(playlist, includeHidden = true).size)

        assertEquals(1, dao.showAllHidden(playlist))
        assertTrue(dao.channels(playlist, hiddenOnly = true).isEmpty())
        assertEquals(2, dao.channels(playlist).size)
    }

    /**
     * FR-ORG-03 names groups as well as channels. Hiding a group used to remove only its row from
     * the group column, leaving its channels sitting in All channels.
     */
    @Test
    fun `hiding a group hides the channels inside it`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        val sports = dao.upsertGroup(ChannelGroup(playlistId = playlist, name = "Sports"))
        dao.newChannel(playlist, "Match", groupId = sports)
        dao.newChannel(playlist, "News")

        assertEquals(2, dao.channels(playlist).size)

        dao.setGroupHidden(sports, hidden = true)
        assertEquals(listOf("News"), dao.channels(playlist).map { it.name })
        // The channel itself was never hidden, so it is not something the Hidden view can repair.
        assertTrue(dao.channels(playlist, hiddenOnly = true).isEmpty())

        dao.setGroupHidden(sports, hidden = false)
        assertEquals(2, dao.channels(playlist).size)
    }

    // --------------------------------------------------------------- search

    @Test
    fun `search matches the provider name and the name the user gave it`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.newChannel(playlist, "Channel 12", identity = "a")
        dao.newChannel(playlist, "Sport HD", identity = "b", customName = "ספורט")

        assertEquals(listOf("Channel 12"), dao.channels(playlist, search = "channel").map { it.name })
        // Hebrew has no case, so a plain LIKE is the right tool; what matters is that the custom
        // name is searched at all.
        assertEquals(listOf("Sport HD"), dao.channels(playlist, search = "ספורט").map { it.name })
    }

    /** A query is text, not a pattern: "100%" must not behave like "everything starting 100". */
    @Test
    fun `a percent sign in a query is matched literally`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.newChannel(playlist, "100% Sport", identity = "a")
        dao.newChannel(playlist, "1000 Movies", identity = "b")

        assertEquals(listOf("100% Sport"), dao.channels(playlist, search = "100%").map { it.name })
        assertEquals(listOf("100% Sport"), dao.channels(playlist, search = "0% S").map { it.name })
    }

    @Test
    fun `search inside a group stays inside that group`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        val sports = dao.upsertGroup(ChannelGroup(playlistId = playlist, name = "Sports"))
        dao.newChannel(playlist, "Sport One", identity = "a", groupId = sports)
        dao.newChannel(playlist, "Sport Two", identity = "b")

        assertEquals(
            listOf("Sport One"),
            dao.channels(playlist, groupId = sports, search = "sport").map { it.name },
        )
    }

    @Test
    fun `a guide entry resolves back to the channel that carries it`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        val id = dao.newChannel(playlist, "One")
        dao.upsertChannel(dao.channel(id)!!.copy(resolvedEpgId = "bbc.one"))

        assertEquals("One", dao.channelForEpgId("bbc.one")?.name)
        assertNull(dao.channelForEpgId("nothing.here"))
    }

    // ----------------------------------------------------------------- sort

    @Test
    fun `each sort preset orders the list its own way`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.newChannel(playlist, "Zulu", identity = "a", sortIndex = 0, watchCount = 1)
        dao.newChannel(playlist, "alpha", identity = "b", sortIndex = 1, watchCount = 9)
        dao.newChannel(playlist, "Mike", identity = "c", sortIndex = 2, watchCount = 5)

        assertEquals(
            listOf("Zulu", "alpha", "Mike"),
            dao.channels(playlist, sort = ChannelSort.PROVIDER).map { it.name },
        )
        // NOCASE, so "alpha" sorts with the letters rather than after every capital.
        assertEquals(
            listOf("alpha", "Mike", "Zulu"),
            dao.channels(playlist, sort = ChannelSort.NAME_ASC).map { it.name },
        )
        assertEquals(
            listOf("alpha", "Mike", "Zulu"),
            dao.channels(playlist, sort = ChannelSort.MOST_WATCHED).map { it.name },
        )
    }

    @Test
    fun `a renamed channel sorts under the name the user gave it`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.newChannel(playlist, "Zulu", identity = "a", sortIndex = 0, customName = "Alpha")
        dao.newChannel(playlist, "Bravo", identity = "b", sortIndex = 1)

        assertEquals(
            listOf("Zulu", "Bravo"),
            dao.channels(playlist, sort = ChannelSort.NAME_ASC).map { it.name },
        )
    }

    // -------------------------------------------------------------- ordering

    @Test
    fun `moving a channel swaps two positions and leaves the rest alone`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.newChannel(playlist, "One", identity = "a", sortIndex = 0)
        dao.newChannel(playlist, "Two", identity = "b", sortIndex = 1)
        dao.newChannel(playlist, "Three", identity = "c", sortIndex = 2)

        val ordered = dao.channels(playlist)
        dao.transaction {
            dao.setChannelSortIndex(ordered[0].id, ordered[1].sortIndex)
            dao.setChannelSortIndex(ordered[1].id, ordered[0].sortIndex)
        }

        assertEquals(listOf("Two", "One", "Three"), dao.channels(playlist).map { it.name })
    }

    // --------------------------------------------------------------- groups

    @Test
    fun `a group of your own can be made, renamed and deleted without taking its channels`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        val mine = dao.createCustomGroup(playlist, "Mine", 0)
        dao.newChannel(playlist, "One", groupId = mine)

        assertTrue(dao.group(mine)!!.custom)
        assertTrue(dao.renameGroup(mine, "Ours"))
        assertEquals("Ours", dao.group(mine)!!.name)

        dao.deleteGroup(mine)
        assertNull(dao.group(mine))
        // The channel survives, ungrouped, rather than disappearing with the group.
        assertEquals(listOf("One"), dao.channels(playlist).map { it.name })
        assertNull(dao.channels(playlist).single().groupId)
    }

    /**
     * A restore recreates groups it has no id for. The insert loses to the unique index on
     * (playlist_id, name), and ignoring that silently dropped the hidden flag and the ordering of
     * every group a refresh had already created — while the report still counted them as restored.
     */
    @Test
    fun `upserting a group by name updates the existing one instead of doing nothing`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        val existing = dao.upsertGroup(ChannelGroup(playlistId = playlist, name = "Adult"))

        val returned = dao.upsertGroup(
            ChannelGroup(playlistId = playlist, name = "Adult", hidden = true, sortIndex = 9),
        )

        assertEquals(existing, returned)
        assertTrue(dao.group(existing)!!.hidden)
        assertEquals(9, dao.group(existing)!!.sortIndex)
        assertEquals(1, dao.groups(playlist, includeHidden = true).size)
    }

    /** FR-EPG-08, written as three columns so a refresh landing mid-edit is not undone. */
    @Test
    fun `binding a channel to a guide entry leaves the provider's fields alone`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        val id = dao.newChannel(playlist, "Sport")

        dao.setChannelBinding(id, manualEpgId = "sport.he", resolvedEpgId = "sport.he", matchKind = "MANUAL")

        val after = dao.channel(id)!!
        assertEquals("sport.he", after.manualEpgId)
        assertEquals("sport.he", after.resolvedEpgId)
        assertEquals("MANUAL", after.matchKind)
        assertEquals("Sport", after.name)
    }

    @Test
    fun `a playlist cannot hold two groups with the same name`() {
        val dao = dao()
        val playlist = dao.newPlaylist()
        dao.createCustomGroup(playlist, "Mine", 0)

        assertEquals(-1L, dao.createCustomGroup(playlist, "Mine", 1))
    }

    /** FR-ORG-11: the setting existed for two releases with nothing reading it. */
    @Test
    fun `a new group is created hidden when the setting says so`() {
        // One helper, so `ensureGroup`'s handle and the reads below are the same connection.
        val helper = database()
        val dao = Dao(helper)
        val playlist = dao.newPlaylist()
        val db = helper.writableDatabase

        val shown = dao.ensureGroup(db, playlist, "Shown", 0, hiddenWhenNew = false)
        val hidden = dao.ensureGroup(db, playlist, "Hidden", 1, hiddenWhenNew = true)

        assertFalse(dao.group(shown)!!.hidden)
        assertTrue(dao.group(hidden)!!.hidden)

        // A group the user has since chosen to show must not be hidden again by the next refresh.
        dao.setGroupHidden(hidden, hidden = false)
        assertEquals(hidden, dao.ensureGroup(db, playlist, "Hidden", 1, hiddenWhenNew = true))
        assertFalse(dao.group(hidden)!!.hidden)
    }

    // ------------------------------------------------------- refresh vs edit

    /**
     * The regression test for the whole of this work.
     *
     * Every per-channel edit used to write all thirty columns from whatever `Channel` the caller
     * was holding. A refresh landing between reading that snapshot and writing the edit would have
     * its provider fields — the name here — pushed back to the stale values, silently undoing it.
     * `docs/11` describes the loser as "a single user edit"; it is the refresh that loses.
     */
    @Test
    fun `an edit made against a stale snapshot does not undo a refresh`() {
        val dao = dao()
        val settings = Settings(context)
        val repo = SourceRepository(context, dao, settings)
        val playlistId = dao.newPlaylist()
        val playlist = dao.playlist(playlistId)!!

        val entry = M3uEntry(name = "Sport", url = "http://example.invalid/sport.ts", tvgId = "sport")
        repo.merge(playlist, listOf(entry))

        // What a screen would be holding: the channel as it was before the refresh below.
        val stale = dao.channels(playlistId).single()
        assertEquals("Sport", stale.name)

        repo.merge(playlist, listOf(entry.copy(name = "Sport HD")))

        dao.setChannelCustomName(stale.id, "ספורט")

        val after = dao.channels(playlistId).single()
        assertEquals("Sport HD", after.name)
        assertEquals("ספורט", after.customName)
        assertEquals("ספורט", after.displayName)
    }

    /** FR-SRC-11: a refresh updates what the provider owns and touches nothing the user set. */
    @Test
    fun `a refresh keeps the favourite, the name, the number and the hidden flag`() {
        val dao = dao()
        val settings = Settings(context)
        val repo = SourceRepository(context, dao, settings)
        val playlistId = dao.newPlaylist()
        val playlist = dao.playlist(playlistId)!!

        val entry = M3uEntry(name = "Sport", url = "http://example.invalid/sport.ts", tvgId = "sport")
        repo.merge(playlist, listOf(entry))

        val id = dao.channels(playlistId).single().id
        dao.setChannelFlags(id, favourite = true, hidden = true)
        dao.setChannelCustomName(id, "ספורט")
        dao.setChannelNumber(id, 101)
        dao.setChannelSortIndex(id, 42)

        repo.merge(playlist, listOf(entry.copy(name = "Sport HD", tvgChno = 7)))

        val after = dao.channel(id)!!
        assertEquals("Sport HD", after.name)
        assertEquals("ספורט", after.customName)
        assertEquals(101, after.number)
        assertEquals(42, after.sortIndex)
        assertTrue(after.favourite)
        assertTrue(after.hidden)
    }
}
