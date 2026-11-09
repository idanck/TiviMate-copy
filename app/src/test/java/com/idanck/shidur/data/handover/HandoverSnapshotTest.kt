package com.idanck.shidur.data.handover

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.idanck.shidur.data.db.Database
import com.idanck.shidur.data.handover.HandoverSnapshot.Counts
import com.idanck.shidur.data.handover.HandoverSnapshot.HandoverException
import com.idanck.shidur.data.prefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The bridge's snapshot and the new app's import of it (ADR-060): every user table, ids included,
 * the settings with their own types, the credentials sealed again, the counts checked, and nothing
 * changed when anything is wrong.
 */
@RunWith(RobolectricTestRunner::class)
// The plain platform Application: `App.onCreate` would drag WorkManager into a unit test.
@Config(sdk = [34], application = Application::class)
class HandoverSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** An empty database with this app's schema, as a first start leaves it. */
    private fun freshDatabase(name: String): File {
        val file = File(context.cacheDir, name)
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db -> Database.SCHEMA.forEach(db::execSQL) }
        return file
    }

    /** `handover/snapshot-v1.sql`, the format-1 snapshot written out by hand, as a file. */
    private fun fixture(edit: (SQLiteDatabase) -> Unit = {}): File {
        val file = File(context.cacheDir, "fixture.db")
        file.delete()
        val script = requireNotNull(javaClass.classLoader?.getResourceAsStream("handover/snapshot-v1.sql")) {
            "test resource handover/snapshot-v1.sql is missing"
        }.bufferedReader().readText()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            script.lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(db::execSQL)
            edit(db)
        }
        return file
    }

    private fun <T> query(file: File, sql: String, read: (android.database.Cursor) -> T): T =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, null).use { c -> c.moveToFirst(); read(c) }
        }

    private fun count(file: File, table: String): Int = query(file, "SELECT COUNT(*) FROM $table") { it.getInt(0) }

    /** What a first start of the new app has already written before the import runs. */
    private fun firstStart(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO playlists (name, type, url) VALUES ('Built-in', 'M3U_URL', 'https://example.com/a.m3u')")
            db.execSQL("INSERT INTO programmes (epg_channel_id, start_utc, end_utc, title) VALUES ('kan', 1, 2, 'p')")
        }
    }

    private val seal: (String?) -> String? = { it?.let { value -> "sealed:$value" } }

    @Test
    fun `a format-1 snapshot is taken in whole, ids and user state included`() {
        val live = freshDatabase("live.db")
        firstStart(live)

        val imported = HandoverSnapshot.importInto(fixture(), live, seal)

        assertEquals(Counts(playlists = 2, groups = 2, channels = 4, favourites = 3, history = 2, recordings = 2, scheduled = 1), imported.counts)
        assertEquals(135, imported.sourceVersionCode)
        // The first start's own playlist is replaced, not kept beside the old ones.
        assertEquals(2, count(live, "playlists"))
        assertEquals("3,7", query(live, "SELECT group_concat(id) FROM (SELECT id FROM playlists ORDER BY id)") { it.getString(0) })
        // Favourites, a custom name, hidden and locked channels, their order and numbers.
        assertEquals("Twelve", query(live, "SELECT custom_name FROM channels WHERE id = 102") { it.getString(0) })
        assertEquals(2, count(live, "channels WHERE favourite = 1"))
        assertEquals("1,1,5,99", query(live, "SELECT hidden || ',' || locked || ',' || sort_index || ',' || number FROM channels WHERE id = 103") { it.getString(0) })
        assertEquals(1, count(live, "channel_groups WHERE hidden = 1"))
        // History, recordings (the schedule included) and where a film was left off.
        assertEquals(14, query(live, "SELECT watch_count FROM history WHERE channel_id = 101") { it.getInt(0) })
        assertEquals("SCHEDULED", query(live, "SELECT state FROM recordings WHERE id = 302") { it.getString(0) })
        assertEquals(61_000, query(live, "SELECT resume_position_ms FROM vod WHERE id = 201") { it.getInt(0) })
    }

    @Test
    fun `credentials are sealed again and every playlist is due at once`() {
        val live = freshDatabase("live.db")

        HandoverSnapshot.importInto(fixture(), live, seal)

        assertEquals("sealed:alice", query(live, "SELECT username FROM playlists WHERE id = 3") { it.getString(0) })
        assertEquals("sealed:s3cret", query(live, "SELECT password FROM playlists WHERE id = 3") { it.getString(0) })
        assertTrue(query(live, "SELECT username FROM playlists WHERE id = 7") { it.isNull(0) })
        assertEquals(0, count(live, "playlists WHERE last_refresh_utc <> 0"))
    }

    @Test
    fun `settings keep their own types, the PIN included`() {
        val imported = HandoverSnapshot.importInto(fixture(), freshDatabase("live.db"), seal)

        assertEquals(101L, imported.prefs["last_channel"])
        assertEquals(-60, imported.prefs["epg_offset"])
        assertEquals(true, imported.prefs["subtitles_enabled"])
        assertEquals(1.25f, imported.prefs["subtitle_scale"])
        assertEquals("aGFzaA==", imported.prefs[Settings.K_PIN_HASH])
        assertEquals("c2FsdA==", imported.prefs[Settings.K_PIN_SALT])
        assertEquals("101,,102", imported.prefs["sport_slots"])
    }

    @Test
    fun `the update record and a picked recording folder stay behind`() {
        val imported = HandoverSnapshot.importInto(fixture(), freshDatabase("live.db"), seal)
        val kept = imported.prefs.filter { (k, v) -> HandoverSnapshot.importable(k, v) }

        assertFalse(Settings.K_UPDATE_MANIFEST in kept)
        assertFalse(Settings.K_REC_FOLDER in kept)
        assertTrue(HandoverSnapshot.isPickedFolder(imported.prefs[Settings.K_REC_FOLDER]))
        assertTrue("sport_slots" in kept)
        assertTrue(Settings.K_PIN_HASH in kept)
        // A folder given as a plain path is the same path for the new package.
        assertTrue(HandoverSnapshot.importable(Settings.K_REC_FOLDER, "/storage/emulated/0/Shidur"))
        Settings.HANDOVER_KEYS.forEach { assertFalse(it, HandoverSnapshot.importable(it, 1L)) }
    }

    @Test
    fun `a snapshot of another format is refused and nothing changes`() {
        val live = freshDatabase("live.db")
        firstStart(live)
        val newer = fixture { it.execSQL("UPDATE handover_meta SET value = '2' WHERE key = 'format'") }

        assertThrows(HandoverException::class.java) { HandoverSnapshot.importInto(newer, live, seal) }

        assertEquals(1, count(live, "playlists"))
        assertEquals(1, count(live, "programmes"))
    }

    @Test
    fun `counts that do not match roll the whole import back`() {
        val live = freshDatabase("live.db")
        firstStart(live)
        val short = fixture { it.execSQL("UPDATE handover_meta SET value = '5' WHERE key = 'count.channels'") }

        assertThrows(HandoverException::class.java) { HandoverSnapshot.importInto(short, live, seal) }

        assertEquals(1, count(live, "playlists"))
        assertEquals(0, count(live, "channels"))
    }

    @Test
    fun `the bridge's snapshot round-trips, credentials in the clear only on the way`() {
        val old = freshDatabase("old.db")
        SQLiteDatabase.openDatabase(old.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO playlists (id, name, type, url, username, password, last_refresh_utc) VALUES (5, 'P', 'XTREAM', 'http://p.example.com', 'enc:bob', 'enc:pw', 99)")
            db.execSQL("INSERT INTO channels (id, playlist_id, identity, name, favourite) VALUES (50, 5, 'a', 'A', 1)")
            db.execSQL("INSERT INTO history (channel_id, last_watched_utc, watch_count) VALUES (50, 10, 3)")
            db.execSQL("INSERT INTO programmes (epg_channel_id, start_utc, end_utc, title) VALUES ('a', 1, 2, 'guide')")
        }
        val snapshot = File(context.cacheDir, "snapshot.db")
        val prefs = mapOf<String, Any?>(
            "an_int" to 7, "a_long" to 7L, "a_float" to 0.5f, "a_bool" to false,
            "a_string" to "x", "a_set" to setOf("a", "b"),
        )

        val sent = HandoverSnapshot.write(
            live = old,
            prefs = prefs,
            decrypt = { it?.removePrefix("enc:") },
            target = snapshot,
            sourceVersionCode = 135,
        )

        assertEquals("bob", query(snapshot, "SELECT username FROM playlists WHERE id = 5") { it.getString(0) })
        assertEquals(0, count(snapshot, "programmes"))

        val live = freshDatabase("live.db")
        val imported = HandoverSnapshot.importInto(snapshot, live, encrypt = { it?.let { v -> "new:$v" } })

        assertEquals(sent, imported.counts)
        assertEquals(Counts(1, 0, 1, 1, 1, 0, 0), imported.counts)
        assertEquals("new:bob", query(live, "SELECT username FROM playlists WHERE id = 5") { it.getString(0) })
        assertEquals(prefs, imported.prefs)
        assertEquals(0, imported.unreadableCredentials)
    }

    @Test
    fun `a credential the bridge cannot unseal is counted, not sent sealed`() {
        val old = freshDatabase("old.db")
        SQLiteDatabase.openDatabase(old.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO playlists (id, name, type, url, username, password) VALUES (5, 'P', 'XTREAM', 'http://p.example.com', 'enc:bob', 'enc:pw')")
        }
        val snapshot = File(context.cacheDir, "snapshot.db")

        HandoverSnapshot.write(old, emptyMap(), decrypt = { null }, target = snapshot, sourceVersionCode = 135)
        val imported = HandoverSnapshot.importInto(snapshot, freshDatabase("live.db"), seal)

        assertEquals(1, imported.unreadableCredentials)
    }

    @Test
    fun `every preference type is written and read back as itself`() {
        listOf(true, 3, 3L, 1.5f, "text", setOf("x", "y")).forEach { value ->
            val (type, raw) = requireNotNull(HandoverSnapshot.encodePref(value))
            assertEquals(value, HandoverSnapshot.decodePref(type, raw))
        }
        assertNull(HandoverSnapshot.encodePref(null))
        assertNull(HandoverSnapshot.decodePref("unknown", "1"))
    }
}
