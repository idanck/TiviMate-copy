package com.tivicopy.player.data

import android.app.Application
import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tivicopy.player.data.db.Dao
import com.tivicopy.player.data.db.Database
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Playlist
import com.tivicopy.player.data.model.SourceType
import com.tivicopy.player.data.xtream.XtreamUrlCredentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/qa finding 9: the `playlists` row kept an Xtream password encrypted while every channel row
 * and the guide address beside it carried the same password in the clear. Stored URLs now hold a
 * placeholder, and reading them back puts the account in again.
 */
@RunWith(RobolectricTestRunner::class)
// The plain platform Application, never this app's: `App.onCreate` would drag WorkManager and
// the notification channels into a unit test.
@Config(sdk = [34], application = Application::class)
class XtreamCredentialsAtRestTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var database: Database

    // Credentials are stored as typed here: the subject is the URLs, not the keystore.
    private lateinit var dao: Dao

    @Before
    fun open() {
        database = Database(app)
        dao = Dao(database) { it }
    }

    @After
    fun close() {
        database.close()
    }

    private fun xtream(password: String = PASS) = dao.upsertPlaylist(
        Playlist(
            name = "Panel",
            type = SourceType.XTREAM,
            url = HOST,
            username = USER,
            password = password,
            epgUrl = "$HOST/xmltv.php?username=$USER&password=$PASS",
        ),
    )

    private fun channel(playlistId: Long, url: String) = dao.upsertChannel(
        Channel(playlistId = playlistId, identity = "news", name = "News", urls = listOf(url)),
    )

    private fun raw(sql: String, id: Long): String =
        database.readableDatabase.rawQuery(sql, arrayOf(id.toString())).use { c ->
            c.moveToFirst()
            c.getString(0)
        }

    @Test
    fun `a stored stream URL leaves the password out and reads back whole`() {
        val playlistId = xtream()
        val id = channel(playlistId, LIVE)

        assertFalse(raw("SELECT urls FROM channels WHERE id=?", id).contains(PASS))
        assertEquals(listOf(LIVE), dao.channel(id)!!.urls)
    }

    @Test
    fun `the stored guide address leaves the password out and reads back whole`() {
        val playlistId = xtream()

        assertFalse(raw("SELECT epg_url FROM playlists WHERE id=?", playlistId).contains(PASS))
        assertEquals("$HOST/xmltv.php?username=$USER&password=$PASS", dao.playlist(playlistId)!!.epgUrl)
    }

    @Test
    fun `rows stored before the change are rewritten without the password`() {
        val playlistId = xtream()
        val id = channel(playlistId, LIVE)
        // What an install from before this change has on disk.
        database.writableDatabase.update(
            "channels",
            ContentValues().apply { put("urls", """["${LIVE.replace("/", "\\/")}"]""") },
            "id=?",
            arrayOf(id.toString()),
        )
        assertTrue(raw("SELECT urls FROM channels WHERE id=?", id).contains(PASS))

        dao.maskStoredCredentials()

        assertFalse(raw("SELECT urls FROM channels WHERE id=?", id).contains(PASS))
        assertEquals(listOf(LIVE), dao.channel(id)!!.urls)
    }

    @Test
    fun `a changed password is the one read back`() {
        val playlistId = xtream()
        val id = channel(playlistId, LIVE)
        dao.channel(id) // Caches the account the new password has to replace.

        // The guide address comes back carrying the old password, as a restore can bring it.
        dao.upsertPlaylist(dao.playlist(playlistId)!!.copy(password = "n3w"))

        assertEquals(listOf("$HOST/live/$USER/n3w/7.ts"), dao.channel(id)!!.urls)
        assertFalse(raw("SELECT epg_url FROM playlists WHERE id=?", playlistId).contains(PASS))
        assertEquals("$HOST/xmltv.php?username=$USER&password=n3w", dao.playlist(playlistId)!!.epgUrl)
    }

    @Test
    fun `an M3U source's URLs are stored as given`() {
        val playlistId = dao.upsertPlaylist(Playlist(name = "List", type = SourceType.M3U_URL, url = "$HOST/get.php"))
        val id = channel(playlistId, LIVE)

        assertTrue(raw("SELECT urls FROM channels WHERE id=?", id).contains(PASS))
        assertEquals(listOf(LIVE), dao.channel(id)!!.urls)
    }

    @Test
    fun `masking matches the account only where the panel puts it`() {
        val account = XtreamUrlCredentials.Account("a", "p@ss/w0rd&")
        val stream = "http://a.example/live/a/p%40ss%2Fw0rd%26/1.ts"
        val guide = "http://a.example/xmltv.php?username=a&password=p%40ss%2Fw0rd%26"

        val masked = XtreamUrlCredentials.mask(stream, account)
        assertEquals("http://a.example/live/{xc-user}/{xc-pass}/1.ts", masked)
        assertEquals(stream, XtreamUrlCredentials.unmask(masked, account))
        assertEquals(guide, XtreamUrlCredentials.unmask(XtreamUrlCredentials.mask(guide, account), account))
        // A username short enough to turn up in the host is left wherever it is not the account.
        assertEquals("http://a.example/other/a/1.ts", XtreamUrlCredentials.mask("http://a.example/other/a/1.ts", account))
    }

    private companion object {
        const val HOST = "http://panel.example:8080"
        const val USER = "qa"
        const val PASS = "qa123"
        const val LIVE = "$HOST/live/$USER/$PASS/7.ts"
    }
}
