package com.idanck.shidur.data.handover

import android.database.sqlite.SQLiteDatabase
import com.idanck.shidur.data.db.Database
import com.idanck.shidur.data.prefs.Settings
import org.json.JSONArray
import java.io.File

/**
 * Everything the user made in the old package, as one SQLite file (ADR-060).
 *
 * Not the backup file (FR-SET-08): that one carries channel state only for channels that exist
 * when it is restored, and a new install has none yet, so favourites, hidden channels, numbers and
 * the order would all be dropped; it carries no watch history and no recordings, and leaves the
 * PIN out by design. The bridge copies the user's tables row for row instead, with their ids, so
 * everything that points at a channel by id (history, recordings, the last channel, Sport and News
 * mode's panels) still points at the same one.
 *
 * Left behind on purpose: the guide (`programmes`, `epg_channels`), which is downloaded again, and
 * every playlist is marked due so that happens at once.
 */
object HandoverSnapshot {

    /** Raised when the file layout changes; the new app refuses a format it does not know. */
    const val FORMAT = 1

    /** The tables that hold what the user made, parents first. */
    val TABLES = listOf("playlists", "channel_groups", "channels", "vod", "recordings", "history")

    private const val META_TABLE =
        "CREATE TABLE IF NOT EXISTS handover_meta (key TEXT PRIMARY KEY, value TEXT)"
    private const val PREFS_TABLE =
        "CREATE TABLE IF NOT EXISTS handover_prefs (key TEXT PRIMARY KEY, type TEXT NOT NULL, value TEXT)"

    /** What the two sides compare to know nothing was lost on the way. */
    data class Counts(
        val playlists: Int,
        val groups: Int,
        val channels: Int,
        val favourites: Int,
        val history: Int,
        val recordings: Int,
        val scheduled: Int,
    )

    /** What the new app took in. */
    data class Imported(
        val counts: Counts,
        val prefs: Map<String, Any>,
        val sourceVersionCode: Int,
        /** Playlists whose saved credentials the bridge could not unseal, so none came across. */
        val unreadableCredentials: Int,
    )

    class HandoverException(message: String) : Exception(message)

    // ---- The bridge's side ------------------------------------------------------------------

    /**
     * Writes the snapshot of the database at [live] and of [prefs] to [target].
     *
     * The live file is attached to a new connection rather than the other way round: attaching
     * to the app's own connection would switch its write-ahead log off for the life of the
     * process. One transaction covers every table, so the copy is consistent; it also holds the
     * live file's write lock for the second or so it takes.
     *
     * The credentials go across in the clear: they are sealed with a Keystore key that belongs to
     * this package and cannot leave it ([com.idanck.shidur.data.prefs.SecureStore]). The file is
     * deleted by the caller as soon as it is open.
     */
    fun write(
        live: File,
        prefs: Map<String, Any?>,
        decrypt: (String?) -> String?,
        target: File,
        sourceVersionCode: Int,
    ): Counts {
        target.delete()
        File(target.path + "-journal").delete()
        val db = SQLiteDatabase.openOrCreateDatabase(target, null)
        try {
            Database.SCHEMA.forEach(db::execSQL)
            db.execSQL(META_TABLE)
            db.execSQL(PREFS_TABLE)
            db.execSQL("ATTACH DATABASE ? AS live", arrayOf<Any>(live.absolutePath))
            var unreadable = 0
            db.beginTransaction()
            val counts = try {
                TABLES.forEach { copyTable(db, from = "live", to = "main", table = it) }

                credentials(db, "main").forEach { (id, user, password) ->
                    val plainUser = decrypt(user)
                    val plainPassword = decrypt(password)
                    if ((user != null && plainUser == null) || (password != null && plainPassword == null)) {
                        unreadable++
                    }
                    db.execSQL(
                        "UPDATE main.playlists SET username = ?, password = ? WHERE id = ?",
                        arrayOf<Any?>(plainUser, plainPassword, id),
                    )
                }

                prefs.forEach { (key, value) ->
                    val (type, raw) = encodePref(value) ?: return@forEach
                    db.execSQL(
                        "INSERT OR REPLACE INTO main.handover_prefs (key, type, value) VALUES (?, ?, ?)",
                        arrayOf<Any?>(key, type, raw),
                    )
                }

                val counted = count(db, "main")
                val meta = mapOf(
                    "format" to FORMAT.toString(),
                    "sourceVersionCode" to sourceVersionCode.toString(),
                    "createdUtc" to System.currentTimeMillis().toString(),
                    "unreadableCredentials" to unreadable.toString(),
                ) + counted.toMeta()
                meta.forEach { (key, value) ->
                    db.execSQL(
                        "INSERT OR REPLACE INTO main.handover_meta (key, value) VALUES (?, ?)",
                        arrayOf<Any?>(key, value),
                    )
                }
                db.setTransactionSuccessful()
                counted
            } finally {
                db.endTransaction()
            }
            db.execSQL("DETACH DATABASE live")
            return counts
        } finally {
            db.close()
        }
    }

    // ---- The new app's side -----------------------------------------------------------------

    /**
     * Replaces the user's tables in the database at [live] with the snapshot's, seals the
     * credentials again with [encrypt], and checks the counts before committing: a mismatch rolls
     * the whole import back and throws, leaving this install as it was.
     *
     * The caller makes sure nothing else writes those tables meanwhile (`App.onCreate` holds the
     * start-up work back until the import is done) and restarts the process afterwards, since
     * everything read before it is stale.
     */
    fun importInto(snapshot: File, live: File, encrypt: (String?) -> String?): Imported {
        val db = SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val meta = readMeta(db)
            val format = meta["format"]?.toIntOrNull() ?: 0
            if (format != FORMAT) throw HandoverException("snapshot format $format, expected $FORMAT")
            val expected = countsFromMeta(meta) ?: throw HandoverException("snapshot has no counts")
            val prefs = readPrefs(db)

            db.execSQL("ATTACH DATABASE ? AS live", arrayOf<Any>(live.absolutePath))
            db.beginTransaction()
            val actual = try {
                TABLES.reversed().forEach { db.execSQL("DELETE FROM live.\"$it\"") }
                TABLES.forEach { copyTable(db, from = "main", to = "live", table = it) }
                credentials(db, "main").forEach { (id, user, password) ->
                    db.execSQL(
                        "UPDATE live.playlists SET username = ?, password = ? WHERE id = ?",
                        arrayOf<Any?>(encrypt(user), encrypt(password), id),
                    )
                }
                // The guide stayed behind; a playlist last refreshed at 0 is due at once, and its
                // guide is loaded with it.
                db.execSQL("UPDATE live.playlists SET last_refresh_utc = 0")
                val stored = count(db, "live")
                if (stored != expected) throw HandoverException("counts differ: sent $expected, stored $stored")
                db.setTransactionSuccessful()
                stored
            } finally {
                db.endTransaction()
            }
            db.execSQL("DETACH DATABASE live")
            return Imported(
                counts = actual,
                prefs = prefs,
                sourceVersionCode = meta["sourceVersionCode"]?.toIntOrNull() ?: 0,
                unreadableCredentials = meta["unreadableCredentials"]?.toIntOrNull() ?: 0,
            )
        } finally {
            db.close()
        }
    }

    /**
     * Whether a setting from the old package belongs in the new one. Not the update channel's
     * record (another manifest, other version numbers), not the hand-over's own, and not a
     * recording folder chosen through the system picker: that grant was made to the old package
     * and does not move, so the new app asks for the folder again.
     */
    fun importable(key: String, value: Any): Boolean = when (key) {
        in Settings.HANDOVER_KEYS -> false
        Settings.K_UPDATE_SKIPPED, Settings.K_UPDATE_LAST_CHECK, Settings.K_UPDATE_MANIFEST -> false
        Settings.K_REC_FOLDER -> !isPickedFolder(value)
        else -> true
    }

    /** A folder granted through the system picker, which the new package cannot use. */
    fun isPickedFolder(value: Any?): Boolean = (value as? String)?.startsWith("content:") == true

    // ---- Shared -----------------------------------------------------------------------------

    /** A preference value as a type name and text, or null for a type preferences cannot hold. */
    internal fun encodePref(value: Any?): Pair<String, String>? = when (value) {
        is Boolean -> "boolean" to value.toString()
        is Int -> "int" to value.toString()
        is Long -> "long" to value.toString()
        is Float -> "float" to value.toString()
        is String -> "string" to value
        is Set<*> -> "set" to JSONArray(value.filterIsInstance<String>()).toString()
        else -> null
    }

    internal fun decodePref(type: String, raw: String?): Any? = when (type) {
        "boolean" -> raw?.toBooleanStrictOrNull()
        "int" -> raw?.toIntOrNull()
        "long" -> raw?.toLongOrNull()
        "float" -> raw?.toFloatOrNull()
        "string" -> raw
        "set" -> runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.getOrNull()
        else -> null
    }

    private fun Counts.toMeta(): Map<String, String> = mapOf(
        "count.playlists" to playlists.toString(),
        "count.groups" to groups.toString(),
        "count.channels" to channels.toString(),
        "count.favourites" to favourites.toString(),
        "count.history" to history.toString(),
        "count.recordings" to recordings.toString(),
        "count.scheduled" to scheduled.toString(),
    )

    private fun countsFromMeta(meta: Map<String, String>): Counts? {
        fun n(key: String) = meta["count.$key"]?.toIntOrNull()
        return Counts(
            playlists = n("playlists") ?: return null,
            groups = n("groups") ?: return null,
            channels = n("channels") ?: return null,
            favourites = n("favourites") ?: return null,
            history = n("history") ?: return null,
            recordings = n("recordings") ?: return null,
            scheduled = n("scheduled") ?: return null,
        )
    }

    /** The counts both sides compare, read from the database attached as [schema]. */
    internal fun count(db: SQLiteDatabase, schema: String): Counts {
        fun n(sql: String): Int = db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return Counts(
            playlists = n("SELECT COUNT(*) FROM $schema.playlists"),
            groups = n("SELECT COUNT(*) FROM $schema.channel_groups"),
            channels = n("SELECT COUNT(*) FROM $schema.channels"),
            favourites = n("SELECT COUNT(*) FROM $schema.channels WHERE favourite = 1") +
                n("SELECT COUNT(*) FROM $schema.vod WHERE favourite = 1"),
            history = n("SELECT COUNT(*) FROM $schema.history"),
            recordings = n("SELECT COUNT(*) FROM $schema.recordings"),
            scheduled = n("SELECT COUNT(*) FROM $schema.recordings WHERE state = 'SCHEDULED'"),
        )
    }

    /**
     * Copies [table] row for row, ids included, over the columns both sides have: a bridge and a
     * new app a schema version apart still meet on everything they share.
     */
    private fun copyTable(db: SQLiteDatabase, from: String, to: String, table: String) {
        val source = columns(db, from, table).toSet()
        val shared = columns(db, to, table).filter { it in source }
        if (shared.isEmpty()) return
        val list = shared.joinToString(", ") { "\"$it\"" }
        db.execSQL("INSERT INTO $to.\"$table\" ($list) SELECT $list FROM $from.\"$table\"")
    }

    private fun columns(db: SQLiteDatabase, schema: String, table: String): List<String> =
        db.rawQuery("PRAGMA $schema.table_info(\"$table\")", null).use { c ->
            val name = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(name)) }
        }

    /** Read in full before any row is changed, so no cursor is open over the rows being updated. */
    private fun credentials(db: SQLiteDatabase, schema: String): List<Triple<Long, String?, String?>> =
        db.rawQuery("SELECT id, username, password FROM $schema.playlists", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Triple(
                            c.getLong(0),
                            if (c.isNull(1)) null else c.getString(1),
                            if (c.isNull(2)) null else c.getString(2),
                        ),
                    )
                }
            }
        }

    private fun readMeta(db: SQLiteDatabase): Map<String, String> =
        db.rawQuery("SELECT key, value FROM main.handover_meta", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), if (c.isNull(1)) "" else c.getString(1)) }
        }

    private fun readPrefs(db: SQLiteDatabase): Map<String, Any> =
        db.rawQuery("SELECT key, type, value FROM main.handover_prefs", null).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val value = decodePref(c.getString(1), if (c.isNull(2)) null else c.getString(2))
                    if (value != null) put(c.getString(0), value)
                }
            }
        }
}
