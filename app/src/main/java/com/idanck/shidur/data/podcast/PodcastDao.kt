package com.idanck.shidur.data.podcast

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.idanck.shidur.data.db.Database
import com.idanck.shidur.data.db.bool
import com.idanck.shidur.data.db.firstOrNull
import com.idanck.shidur.data.db.int
import com.idanck.shidur.data.db.intOrNull
import com.idanck.shidur.data.db.long
import com.idanck.shidur.data.db.mapAll
import com.idanck.shidur.data.db.str
import com.idanck.shidur.data.db.strOrNull
import org.json.JSONArray

/**
 * Data access for the podcast tables (ADR-067, `Database.PODCAST_SCHEMA`).
 *
 * The feed's rows are rewritten by a refresh ([mergeFeed]); the viewer's marks and queue are only
 * ever written by the viewer, a restore or a sync, and are joined in on every read.
 */
class PodcastDao(private val helper: Database) {

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---------------------------------------------------------------- podcasts

    /** The library, or every known podcast with [libraryOnly] false. */
    fun podcasts(libraryOnly: Boolean = true): List<Podcast> = db.rawQuery(
        """
        SELECT p.*,
          (SELECT COUNT(*) FROM podcast_episodes e
             LEFT JOIN podcast_marks m ON m.feed_url = p.feed_url AND m.guid = e.guid
            WHERE e.podcast_id = p.id AND COALESCE(e.published_utc, 0) >= p.added_utc - $NEW_WINDOW_MS
              AND COALESCE(m.played, 0) = 0 AND COALESCE(m.position_ms, 0) = 0) AS new_count,
          (SELECT MAX(published_utc) FROM podcast_episodes e WHERE e.podcast_id = p.id) AS latest_utc
        FROM podcasts p
        ${if (libraryOnly) "WHERE p.in_library = 1" else ""}
        ORDER BY latest_utc DESC, p.title COLLATE NOCASE
        """.trimIndent(),
        null,
    ).mapAll(::readPodcast)

    fun podcast(id: Long): Podcast? = podcasts(libraryOnly = false).firstOrNull { it.id == id }

    fun podcastByFeed(feedUrl: String): Podcast? = podcasts(libraryOnly = false).firstOrNull { it.feedUrl == feedUrl }

    /**
     * Writes a podcast's feed data, creating it when its address is new. The viewer's choices on
     * an existing row — in the library or not, its own settings, when it was added — are kept.
     */
    fun upsertPodcast(feedUrl: String, feed: PodcastFeed, inLibrary: Boolean?, nowUtc: Long, indexId: Long? = null): Long {
        val values = ContentValues().apply {
            put("title", feed.title)
            put("author", feed.author)
            put("description", feed.description)
            put("image_url", feed.imageUrl)
            put("link", feed.link)
            put("language", feed.language)
            put("categories", JSONArray(feed.categories).toString())
            if (indexId != null) put("index_id", indexId)
        }
        val existing = db.rawQuery("SELECT id, in_library FROM podcasts WHERE feed_url = ?", arrayOf(feedUrl))
            .firstOrNull { it.long("id") to it.bool("in_library") }
        if (existing != null) {
            if (inLibrary != null && inLibrary != existing.second) {
                values.put("in_library", if (inLibrary) 1 else 0)
                // Added again: what came out while it was away is new from now.
                if (inLibrary) values.put("added_utc", nowUtc)
            }
            db.update("podcasts", values, "id = ?", arrayOf(existing.first.toString()))
            return existing.first
        }
        values.put("feed_url", feedUrl)
        values.put("in_library", if (inLibrary != false) 1 else 0)
        values.put("added_utc", nowUtc)
        return db.insertOrThrow("podcasts", null, values)
    }

    /**
     * A podcast known only by its address and name — from a backup or another device — until its
     * feed is read. An existing row keeps its feed data and takes [inLibrary] and [prefs].
     */
    fun ensurePodcast(feedUrl: String, title: String, inLibrary: Boolean, prefs: PodcastPrefs, prefsUpdatedUtc: Long, nowUtc: Long): Long {
        val id = db.rawQuery("SELECT id FROM podcasts WHERE feed_url = ?", arrayOf(feedUrl)).firstOrNull { it.long("id") }
            ?: db.insertOrThrow(
                "podcasts",
                null,
                ContentValues().apply {
                    put("feed_url", feedUrl)
                    put("title", title.ifBlank { feedUrl })
                    put("in_library", if (inLibrary) 1 else 0)
                    put("added_utc", nowUtc)
                },
            )
        db.update("podcasts", ContentValues().apply { put("in_library", if (inLibrary) 1 else 0) }, "id = ?", arrayOf(id.toString()))
        setPrefs(id, prefs, prefsUpdatedUtc)
        return id
    }

    fun setInLibrary(id: Long, inLibrary: Boolean, nowUtc: Long) {
        db.update(
            "podcasts",
            ContentValues().apply {
                put("in_library", if (inLibrary) 1 else 0)
                if (inLibrary) put("added_utc", nowUtc)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun setPrefs(id: Long, prefs: PodcastPrefs, nowUtc: Long) {
        db.update(
            "podcasts",
            ContentValues().apply {
                put("speed", prefs.speed.toDouble())
                put("skip_intro_seconds", prefs.skipIntroSeconds)
                put("skip_outro_seconds", prefs.skipOutroSeconds)
                put("notify_new", if (prefs.notifyNew) 1 else 0)
                put("oldest_first", if (prefs.oldestFirst) 1 else 0)
                put("prefs_updated_utc", nowUtc)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun markRefresh(id: Long, nowUtc: Long, ok: Boolean, message: String?, etag: String?, lastModified: String?) {
        db.update(
            "podcasts",
            ContentValues().apply {
                put("last_refresh_utc", nowUtc)
                put("last_refresh_ok", if (ok) 1 else 0)
                put("last_refresh_message", message)
                if (ok) {
                    put("etag", etag)
                    put("last_modified", lastModified)
                }
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    /** What a conditional GET sends back: `ETag` and `Last-Modified` from the last good read. */
    fun validators(id: Long): Pair<String?, String?> =
        db.rawQuery("SELECT etag, last_modified FROM podcasts WHERE id = ?", arrayOf(id.toString()))
            .firstOrNull { it.strOrNull("etag") to it.strOrNull("last_modified") } ?: (null to null)

    /**
     * Removes a podcast that is in no list of the viewer's: not in the library and with no episode
     * starred, in the history or queued. Called after it leaves the library.
     */
    fun pruneOrphan(id: Long) {
        db.execSQL(
            """
            DELETE FROM podcasts WHERE id = ? AND in_library = 0
              AND NOT EXISTS (SELECT 1 FROM podcast_marks m WHERE m.feed_url = podcasts.feed_url
                                AND (m.favourite = 1 OR m.history_utc > 0))
              AND NOT EXISTS (SELECT 1 FROM podcast_queue q WHERE q.feed_url = podcasts.feed_url)
            """.trimIndent(),
            arrayOf(id),
        )
    }

    // ---------------------------------------------------------------- episodes

    /**
     * Replaces a podcast's episodes with the feed's. An episode the feed no longer lists is kept
     * while the viewer still has it in a list (starred, in the history, queued); otherwise it goes.
     */
    fun mergeFeed(podcastId: Long, feedUrl: String, episodes: List<PodcastFeed.FeedEpisode>) {
        val d = db
        d.beginTransaction()
        try {
            val listed = HashSet<String>(episodes.size * 2)
            episodes.forEachIndexed { index, e ->
                listed += e.guid
                val values = ContentValues().apply {
                    put("podcast_id", podcastId)
                    put("guid", e.guid)
                    put("title", e.title)
                    put("description", e.description)
                    put("audio_url", e.audioUrl)
                    put("mime_type", e.mimeType)
                    put("length_bytes", e.lengthBytes)
                    put("duration_seconds", e.durationSeconds)
                    if (e.publishedUtc != null) put("published_utc", e.publishedUtc) else putNull("published_utc")
                    put("image_url", e.imageUrl)
                    if (e.season != null) put("season", e.season) else putNull("season")
                    if (e.episode != null) put("episode", e.episode) else putNull("episode")
                    put("explicit", if (e.explicit) 1 else 0)
                    put("link", e.link)
                    put("chapters_url", e.chaptersUrl)
                    put("transcript_url", e.transcriptUrl)
                    put("feed_order", index)
                }
                val updated = d.update("podcast_episodes", values, "podcast_id = ? AND guid = ?", arrayOf(podcastId.toString(), e.guid))
                if (updated == 0) d.insertOrThrow("podcast_episodes", null, values)
            }
            val keptByViewer = d.rawQuery(
                """
                SELECT guid FROM podcast_marks WHERE feed_url = ? AND (favourite = 1 OR history_utc > 0)
                UNION SELECT guid FROM podcast_queue WHERE feed_url = ?
                """.trimIndent(),
                arrayOf(feedUrl, feedUrl),
            ).mapAll { it.str("guid") }.toSet()
            d.rawQuery("SELECT id, guid FROM podcast_episodes WHERE podcast_id = ?", arrayOf(podcastId.toString()))
                .mapAll { it.long("id") to it.str("guid") }
                .filter { (_, guid) -> guid !in listed && guid !in keptByViewer }
                .forEach { (id, _) -> d.delete("podcast_episodes", "id = ?", arrayOf(id.toString())) }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /** One show's episodes, newest first unless the show says otherwise, through [filter]. */
    fun episodes(podcastId: Long, filter: EpisodeFilter = EpisodeFilter.ALL, oldestFirst: Boolean = false, limit: Int = 500): List<PodcastEpisode> =
        query(
            where = "e.podcast_id = ? AND ${filter.sql}",
            args = arrayOf(podcastId.toString()),
            order = if (oldestFirst) "COALESCE(e.published_utc, 0) ASC, e.feed_order DESC" else "COALESCE(e.published_utc, 0) DESC, e.feed_order ASC",
            limit = limit,
        )

    fun episode(id: Long): PodcastEpisode? =
        query(where = "e.id = ?", args = arrayOf(id.toString()), order = "e.id", limit = 1).firstOrNull()

    fun episode(key: EpisodeKey): PodcastEpisode? =
        query(where = "p.feed_url = ? AND e.guid = ?", args = arrayOf(key.feedUrl, key.guid), order = "e.id", limit = 1).firstOrNull()

    /** New in the library: neither started nor played, published since the show was added. */
    fun newEpisodes(limit: Int = 30): List<PodcastEpisode> = query(
        where = "p.in_library = 1 AND COALESCE(m.played, 0) = 0 AND COALESCE(m.position_ms, 0) = 0 " +
            "AND COALESCE(e.published_utc, 0) >= p.added_utc - $NEW_WINDOW_MS",
        args = emptyArray(),
        order = "COALESCE(e.published_utc, 0) DESC",
        limit = limit,
    )

    /** Started and not finished, the most recently heard first: "המשך האזנה". */
    fun inProgress(limit: Int = 30): List<PodcastEpisode> = query(
        where = "COALESCE(m.position_ms, 0) > 0 AND COALESCE(m.played, 0) = 0",
        args = emptyArray(),
        order = "m.last_played_utc DESC",
        limit = limit,
    )

    fun favourites(limit: Int = 500): List<PodcastEpisode> = query(
        where = "COALESCE(m.favourite, 0) = 1",
        args = emptyArray(),
        order = "m.favourite_utc DESC",
        limit = limit,
    )

    fun history(limit: Int = 300): List<PodcastEpisode> = query(
        where = "COALESCE(m.history_utc, 0) > 0",
        args = emptyArray(),
        order = "m.last_played_utc DESC",
        limit = limit,
    )

    fun search(text: String, limit: Int = 100): List<PodcastEpisode> {
        val like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return query(
            where = "(e.title LIKE ? ESCAPE '\\' OR p.title LIKE ? ESCAPE '\\')",
            args = arrayOf(like, like),
            order = "COALESCE(e.published_utc, 0) DESC",
            limit = limit,
        )
    }

    private fun query(where: String, args: Array<String>, order: String, limit: Int): List<PodcastEpisode> = db.rawQuery(
        """
        SELECT e.*, p.feed_url, p.title AS podcast_title, p.image_url AS podcast_image,
               m.position_ms, m.duration_ms, m.played, m.favourite, m.favourite_utc, m.history_utc,
               m.last_played_utc, m.device, m.updated_utc
        FROM podcast_episodes e
        JOIN podcasts p ON p.id = e.podcast_id
        LEFT JOIN podcast_marks m ON m.feed_url = p.feed_url AND m.guid = e.guid
        WHERE $where
        ORDER BY $order
        LIMIT $limit
        """.trimIndent(),
        args,
    ).mapAll(::readEpisode)

    // ------------------------------------------------------------------- marks

    fun mark(key: EpisodeKey): EpisodeMark? = db.rawQuery(
        "SELECT * FROM podcast_marks WHERE feed_url = ? AND guid = ?",
        arrayOf(key.feedUrl, key.guid),
    ).firstOrNull(::readMark)

    /** Every mark, for a backup and for a sync's first upload. */
    fun allMarks(): List<Pair<EpisodeKey, EpisodeMark>> =
        db.rawQuery("SELECT * FROM podcast_marks", null).mapAll { EpisodeKey(it.str("feed_url"), it.str("guid")) to readMark(it) }

    /** Writes a mark whole. A sync passes the merged mark; the viewer's own writes go through [updateMark]. */
    fun putMark(key: EpisodeKey, mark: EpisodeMark) {
        db.insertWithOnConflict(
            "podcast_marks",
            null,
            ContentValues().apply {
                put("feed_url", key.feedUrl)
                put("guid", key.guid)
                put("position_ms", mark.positionMs)
                put("duration_ms", mark.durationMs)
                put("played", if (mark.played) 1 else 0)
                put("favourite", if (mark.favourite) 1 else 0)
                put("favourite_utc", mark.favouriteUtc)
                put("history_utc", mark.historyUtc)
                put("last_played_utc", mark.lastPlayedUtc)
                put("device", mark.device)
                put("updated_utc", mark.updatedUtc)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Reads, changes and writes one mark in a transaction; returns what was written. */
    fun updateMark(key: EpisodeKey, change: (EpisodeMark) -> EpisodeMark): EpisodeMark {
        val d = db
        d.beginTransaction()
        try {
            val next = change(mark(key) ?: EpisodeMark())
            putMark(key, next)
            d.setTransactionSuccessful()
            return next
        } finally {
            d.endTransaction()
        }
    }

    /** "ניקוי ההיסטוריה": out of the history, with every position kept. */
    fun clearHistory(nowUtc: Long) {
        db.execSQL("UPDATE podcast_marks SET history_utc = 0, updated_utc = ? WHERE history_utc > 0", arrayOf(nowUtc))
    }

    // ------------------------------------------------------------------- queue

    fun queue(): List<PodcastEpisode> {
        val keys = queueKeys()
        if (keys.isEmpty()) return emptyList()
        return keys.mapNotNull { episode(it) }
    }

    fun queueKeys(): List<EpisodeKey> =
        db.rawQuery("SELECT feed_url, guid FROM podcast_queue ORDER BY sort_index", null)
            .mapAll { EpisodeKey(it.str("feed_url"), it.str("guid")) }

    /** Puts [key] at the end of the queue, or right at its head with [next]. Already queued: moved. */
    fun enqueue(key: EpisodeKey, next: Boolean) {
        val d = db
        d.beginTransaction()
        try {
            d.delete("podcast_queue", "feed_url = ? AND guid = ?", arrayOf(key.feedUrl, key.guid))
            val index = if (next) {
                (d.rawQuery("SELECT MIN(sort_index) AS v FROM podcast_queue", null).firstOrNull { it.intOrNull("v") } ?: 0) - 1
            } else {
                (d.rawQuery("SELECT MAX(sort_index) AS v FROM podcast_queue", null).firstOrNull { it.intOrNull("v") } ?: 0) + 1
            }
            d.insertOrThrow("podcast_queue", null, ContentValues().apply {
                put("feed_url", key.feedUrl)
                put("guid", key.guid)
                put("sort_index", index)
            })
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    fun dequeue(key: EpisodeKey) {
        db.delete("podcast_queue", "feed_url = ? AND guid = ?", arrayOf(key.feedUrl, key.guid))
    }

    fun setQueue(keys: List<EpisodeKey>) {
        val d = db
        d.beginTransaction()
        try {
            d.delete("podcast_queue", null, null)
            keys.distinct().forEachIndexed { index, key ->
                d.insertOrThrow("podcast_queue", null, ContentValues().apply {
                    put("feed_url", key.feedUrl)
                    put("guid", key.guid)
                    put("sort_index", index)
                })
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    // ----------------------------------------------------------------- readers

    private fun readPodcast(c: Cursor) = Podcast(
        id = c.long("id"),
        feedUrl = c.str("feed_url"),
        title = c.str("title"),
        author = c.strOrNull("author"),
        description = c.strOrNull("description"),
        imageUrl = c.strOrNull("image_url"),
        link = c.strOrNull("link"),
        language = c.strOrNull("language"),
        categories = runCatching {
            val a = JSONArray(c.str("categories"))
            List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList()),
        indexId = c.getColumnIndex("index_id").let { i -> if (i < 0 || c.isNull(i)) null else c.getLong(i) },
        inLibrary = c.bool("in_library"),
        addedUtc = c.long("added_utc"),
        lastRefreshUtc = c.long("last_refresh_utc"),
        lastRefreshOk = c.bool("last_refresh_ok"),
        lastRefreshMessage = c.strOrNull("last_refresh_message"),
        prefs = PodcastPrefs(
            speed = c.getFloat(c.getColumnIndexOrThrow("speed")),
            skipIntroSeconds = c.int("skip_intro_seconds"),
            skipOutroSeconds = c.int("skip_outro_seconds"),
            notifyNew = c.bool("notify_new"),
            oldestFirst = c.bool("oldest_first"),
        ),
        newCount = c.getColumnIndex("new_count").let { if (it < 0) 0 else c.getInt(it) },
        latestUtc = c.getColumnIndex("latest_utc").let { if (it < 0 || c.isNull(it)) 0 else c.getLong(it) },
    )

    private fun readEpisode(c: Cursor) = PodcastEpisode(
        id = c.long("id"),
        podcastId = c.long("podcast_id"),
        feedUrl = c.str("feed_url"),
        podcastTitle = c.str("podcast_title"),
        podcastImageUrl = c.strOrNull("podcast_image"),
        guid = c.str("guid"),
        title = c.str("title"),
        description = c.strOrNull("description"),
        audioUrl = c.str("audio_url"),
        mimeType = c.strOrNull("mime_type"),
        durationSeconds = c.long("duration_seconds"),
        publishedUtc = c.getColumnIndexOrThrow("published_utc").let { if (c.isNull(it)) null else c.getLong(it) },
        imageUrl = c.strOrNull("image_url"),
        season = c.intOrNull("season"),
        episode = c.intOrNull("episode"),
        chaptersUrl = c.strOrNull("chapters_url"),
        transcriptUrl = c.strOrNull("transcript_url"),
        link = c.strOrNull("link"),
        mark = readMark(c),
    )

    /** Reads a mark's columns; nulls (no mark row, from the LEFT JOIN) read as the empty mark. */
    private fun readMark(c: Cursor): EpisodeMark {
        fun l(name: String): Long = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) 0 else c.getLong(it) }
        return EpisodeMark(
            positionMs = l("position_ms"),
            durationMs = l("duration_ms"),
            played = l("played") != 0L,
            favourite = l("favourite") != 0L,
            favouriteUtc = l("favourite_utc"),
            historyUtc = l("history_utc"),
            lastPlayedUtc = l("last_played_utc"),
            device = c.strOrNull("device"),
            updatedUtc = l("updated_utc"),
        )
    }

    companion object {
        /**
         * How far before a show was added its episodes still count as new: a show added today
         * offers its last two weeks as new, not its whole archive.
         */
        const val NEW_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
    }
}

/** The filter chips of a show's page (`TvPodcastShow`). */
enum class EpisodeFilter(internal val sql: String) {
    ALL("1 = 1"),
    UNPLAYED("COALESCE(m.played, 0) = 0"),
    STARTED("COALESCE(m.position_ms, 0) > 0 AND COALESCE(m.played, 0) = 0"),
    FAVOURITES("COALESCE(m.favourite, 0) = 1"),
}
