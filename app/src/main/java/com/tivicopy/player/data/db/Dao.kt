package com.tivicopy.player.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.ChannelGroup
import com.tivicopy.player.data.model.ChannelSort
import com.tivicopy.player.data.model.CatchupMode
import com.tivicopy.player.data.model.DecoderMode
import com.tivicopy.player.data.model.EpgChannel
import com.tivicopy.player.data.model.OutputFormat
import com.tivicopy.player.data.model.Playlist
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.model.Recording
import com.tivicopy.player.data.model.RecordingState
import com.tivicopy.player.data.model.SourceType
import com.tivicopy.player.data.model.VodItem

/** Declares the escape character used by every `LIKE` clause here. See [Dao.likePattern]. */
private const val LIKE_ESCAPE = " ESCAPE '\\'"

/**
 * Data access for [Database]. Every method takes or opens a writable handle; SQLiteOpenHelper
 * caches a single connection, and WAL lets guide writes proceed while the UI reads.
 */
class Dao(private val helper: Database) {

    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun <T> transaction(body: (SQLiteDatabase) -> T): T {
        val d = db
        d.beginTransaction()
        return try {
            val r = body(d)
            d.setTransactionSuccessful()
            r
        } finally {
            d.endTransaction()
        }
    }

    // ---------------------------------------------------------------- playlists

    fun playlists(): List<Playlist> =
        db.rawQuery("SELECT * FROM playlists ORDER BY sort_index, id", null).mapAll(::readPlaylist)

    fun playlist(id: Long): Playlist? =
        db.rawQuery("SELECT * FROM playlists WHERE id=?", arrayOf(id.toString()))
            .firstOrNull(::readPlaylist)

    fun upsertPlaylist(p: Playlist): Long {
        val v = ContentValues().apply {
            put("name", p.name)
            put("type", p.type.name)
            put("url", p.url)
            put("username", p.username)
            put("password", p.password)
            put("output_format", p.outputFormat.name)
            put("user_agent", p.userAgent)
            put("headers", Json.encodeMap(p.headers))
            put("epg_url", p.epgUrl)
            put("refresh_interval_minutes", p.refreshIntervalMinutes)
            put("enabled", if (p.enabled) 1 else 0)
            put("colour", p.colour)
            put("last_refresh_utc", p.lastRefreshUtc)
            put("last_refresh_ok", if (p.lastRefreshOk) 1 else 0)
            put("last_refresh_message", p.lastRefreshMessage)
            put("sort_index", p.sortIndex)
        }
        return if (p.id == 0L) {
            db.insert("playlists", null, v)
        } else {
            v.put("id", p.id)
            db.update("playlists", v, "id=?", arrayOf(p.id.toString()))
            p.id
        }
    }

    fun deletePlaylist(id: Long) {
        db.delete("playlists", "id=?", arrayOf(id.toString()))
    }

    fun markRefresh(playlistId: Long, whenUtc: Long, ok: Boolean, message: String?) {
        val v = ContentValues().apply {
            put("last_refresh_utc", whenUtc)
            put("last_refresh_ok", if (ok) 1 else 0)
            put("last_refresh_message", message)
        }
        db.update("playlists", v, "id=?", arrayOf(playlistId.toString()))
    }

    private fun readPlaylist(c: android.database.Cursor) = Playlist(
        id = c.long("id"),
        name = c.str("name"),
        type = runCatching { SourceType.valueOf(c.str("type")) }.getOrDefault(SourceType.M3U_URL),
        url = c.str("url"),
        username = c.strOrNull("username"),
        password = c.strOrNull("password"),
        outputFormat = OutputFormat.from(c.strOrNull("output_format")),
        userAgent = c.strOrNull("user_agent"),
        headers = Json.decodeMap(c.strOrNull("headers")),
        epgUrl = c.strOrNull("epg_url"),
        refreshIntervalMinutes = c.int("refresh_interval_minutes"),
        enabled = c.bool("enabled"),
        colour = c.int("colour"),
        lastRefreshUtc = c.long("last_refresh_utc"),
        lastRefreshOk = c.bool("last_refresh_ok"),
        lastRefreshMessage = c.strOrNull("last_refresh_message"),
        sortIndex = c.int("sort_index"),
    )

    // ------------------------------------------------------------------- groups

    fun groups(playlistId: Long, includeHidden: Boolean = false): List<ChannelGroup> {
        val where = if (includeHidden) "playlist_id=?" else "playlist_id=? AND hidden=0"
        return db.query("channel_groups", null, where, arrayOf(playlistId.toString()), null, null, "sort_index, name")
            .mapAll(::readGroup)
    }

    fun group(id: Long): ChannelGroup? =
        db.query("channel_groups", null, "id=?", arrayOf(id.toString()), null, null, null)
            .firstOrNull(::readGroup)

    /**
     * Returns the id of an existing group with this name, or creates one.
     *
     * [hiddenWhenNew] applies only on creation (FR-ORG-11): a group the user has already chosen to
     * show must not be hidden again by the next refresh.
     */
    fun ensureGroup(
        d: SQLiteDatabase,
        playlistId: Long,
        name: String,
        sortIndex: Int,
        hiddenWhenNew: Boolean = false,
    ): Long {
        d.query("channel_groups", arrayOf("id"), "playlist_id=? AND name=?", arrayOf(playlistId.toString(), name), null, null, null)
            .use { c -> if (c.moveToFirst()) return c.getLong(0) }
        val v = ContentValues().apply {
            put("playlist_id", playlistId)
            put("name", name)
            put("custom", 0)
            put("hidden", if (hiddenWhenNew) 1 else 0)
            put("sort_index", sortIndex)
        }
        return d.insert("channel_groups", null, v)
    }

    /**
     * Creates a group the user owns (FR-ORG-02). Returns -1 when the playlist already has one by
     * that name, which the unique index enforces — the caller reports that rather than silently
     * doing nothing.
     */
    fun createCustomGroup(playlistId: Long, name: String, sortIndex: Int): Long = db.insertWithOnConflict(
        "channel_groups",
        null,
        ContentValues().apply {
            put("playlist_id", playlistId)
            put("name", name)
            put("custom", 1)
            put("hidden", 0)
            put("sort_index", sortIndex)
        },
        SQLiteDatabase.CONFLICT_IGNORE,
    )

    fun setGroupHidden(id: Long, hidden: Boolean) {
        db.update(
            "channel_groups",
            ContentValues().apply { put("hidden", if (hidden) 1 else 0) },
            "id=?",
            arrayOf(id.toString()),
        )
    }

    /** Renames a group. Returns false when the playlist already has one by that name. */
    fun renameGroup(id: Long, name: String): Boolean = runCatching {
        db.update(
            "channel_groups",
            ContentValues().apply { put("name", name) },
            "id=?",
            arrayOf(id.toString()),
        ) > 0
    }.getOrDefault(false)

    fun upsertGroup(g: ChannelGroup): Long {
        val v = ContentValues().apply {
            put("playlist_id", g.playlistId)
            put("name", g.name)
            put("custom", if (g.custom) 1 else 0)
            put("hidden", if (g.hidden) 1 else 0)
            put("locked", if (g.locked) 1 else 0)
            put("sort_index", g.sortIndex)
        }
        if (g.id != 0L) {
            db.update("channel_groups", v, "id=?", arrayOf(g.id.toString()))
            return g.id
        }
        val inserted = db.insertWithOnConflict("channel_groups", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted != -1L) return inserted
        // The unique index on (playlist_id, name) rejected the insert. Ignoring that silently is
        // what made restoring a backup drop the hidden flag and the ordering of every group the
        // refresh had already recreated, while still reporting them as restored.
        db.update("channel_groups", v, "playlist_id=? AND name=?", arrayOf(g.playlistId.toString(), g.name))
        return db.query(
            "channel_groups", arrayOf("id"), "playlist_id=? AND name=?",
            arrayOf(g.playlistId.toString(), g.name), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
    }

    fun deleteGroup(id: Long) {
        db.update("channels", ContentValues().apply { putNull("group_id") }, "group_id=?", arrayOf(id.toString()))
        db.delete("channel_groups", "id=?", arrayOf(id.toString()))
    }

    private fun readGroup(c: android.database.Cursor) = ChannelGroup(
        id = c.long("id"),
        playlistId = c.long("playlist_id"),
        name = c.str("name"),
        custom = c.bool("custom"),
        hidden = c.bool("hidden"),
        locked = c.bool("locked"),
        sortIndex = c.int("sort_index"),
    )

    // ----------------------------------------------------------------- channels

    /**
     * A `LIKE` pattern that matches [query] literally.
     *
     * `%` and `_` are wildcards, so a search for "100%" without this matches everything that
     * starts with "100". The escape character has to be declared per clause with `ESCAPE`.
     */
    fun likePattern(query: String): String = "%" + query
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_") + "%"

    fun channels(
        playlistId: Long,
        groupId: Long? = null,
        favouritesOnly: Boolean = false,
        hiddenOnly: Boolean = false,
        includeHidden: Boolean = false,
        search: String? = null,
        sort: ChannelSort = ChannelSort.PROVIDER,
        limit: Int = 0,
    ): List<Channel> {
        val where = StringBuilder("playlist_id=?")
        val args = mutableListOf(playlistId.toString())
        if (groupId != null) { where.append(" AND group_id=?"); args += groupId.toString() }
        if (favouritesOnly) where.append(" AND favourite=1")
        when {
            hiddenOnly -> where.append(" AND hidden=1")
            !includeHidden -> {
                where.append(" AND hidden=0")
                // FR-ORG-03 covers whole groups, not just channels. Hiding a group used to remove
                // only its row from the group column, leaving its channels under All channels.
                where.append(
                    " AND (group_id IS NULL OR group_id NOT IN " +
                        "(SELECT id FROM channel_groups WHERE hidden=1))",
                )
            }
        }
        if (!search.isNullOrBlank()) {
            where.append(" AND (name LIKE ?$LIKE_ESCAPE OR IFNULL(custom_name,'') LIKE ?$LIKE_ESCAPE)")
            val pattern = likePattern(search)
            args += pattern; args += pattern
        }
        // `sort.orderBy` is a constant on the enum, never anything the user typed.
        val sql = "SELECT * FROM channels WHERE $where ORDER BY ${sort.orderBy}" +
            if (limit > 0) " LIMIT $limit" else ""
        return db.rawQuery(sql, args.toTypedArray()).mapAll(::readChannel)
    }

    /** The channel a guide entry belongs to, so a search hit on a programme can be played. */
    fun channelForEpgId(epgId: String): Channel? = db.rawQuery(
        "SELECT * FROM channels WHERE (manual_epg_id=? OR resolved_epg_id=?) AND hidden=0 LIMIT 1",
        arrayOf(epgId, epgId),
    ).firstOrNull(::readChannel)

    /** Puts every hidden channel in a playlist back on screen. Returns how many were restored. */
    fun showAllHidden(playlistId: Long): Int = db.update(
        "channels",
        ContentValues().apply { put("hidden", 0) },
        "playlist_id=? AND hidden=1",
        arrayOf(playlistId.toString()),
    )

    fun allChannels(includeHidden: Boolean = true): List<Channel> {
        val where = if (includeHidden) "1=1" else "hidden=0"
        return db.rawQuery("SELECT * FROM channels WHERE $where ORDER BY playlist_id, sort_index, number", null)
            .mapAll(::readChannel)
    }

    fun channel(id: Long): Channel? =
        db.rawQuery("SELECT * FROM channels WHERE id=?", arrayOf(id.toString())).firstOrNull(::readChannel)

    fun favouriteChannels(): List<Channel> =
        db.rawQuery("SELECT * FROM channels WHERE favourite=1 AND hidden=0 ORDER BY sort_index, number, name", null)
            .mapAll(::readChannel)

    /**
     * Recently watched, newest first. Deliberately spans every playlist — the same choice
     * `continueWatching` makes for on-demand — because "what was I just watching" is not a
     * question about the playlist that happens to be selected.
     */
    fun recentChannels(search: String? = null, limit: Int = 25): List<Channel> {
        val where = StringBuilder("last_watched_utc>0 AND hidden=0")
        val args = mutableListOf<String>()
        if (!search.isNullOrBlank()) {
            where.append(" AND (name LIKE ?$LIKE_ESCAPE OR IFNULL(custom_name,'') LIKE ?$LIKE_ESCAPE)")
            val pattern = likePattern(search)
            args += pattern; args += pattern
        }
        args += limit.toString()
        return db.rawQuery(
            "SELECT * FROM channels WHERE $where ORDER BY last_watched_utc DESC LIMIT ?",
            args.toTypedArray(),
        ).mapAll(::readChannel)
    }

    fun channelValues(ch: Channel): ContentValues = ContentValues().apply {
        put("playlist_id", ch.playlistId)
        put("identity", ch.identity)
        put("stream_id", ch.streamId)
        put("tvg_id", ch.tvgId)
        put("name", ch.name)
        put("custom_name", ch.customName)
        put("logo_url", ch.logoUrl)
        put("custom_logo_url", ch.customLogoUrl)
        if (ch.groupId == null) putNull("group_id") else put("group_id", ch.groupId)
        put("number", ch.number)
        put("urls", Json.encodeList(ch.urls))
        put("user_agent", ch.userAgent)
        put("headers", Json.encodeMap(ch.headers))
        put("license_type", ch.licenseType)
        put("license_key", ch.licenseKey)
        put("catchup_mode", ch.catchupMode.name)
        put("catchup_source", ch.catchupSource)
        put("catchup_days", ch.catchupDays)
        put("tvg_shift_minutes", ch.tvgShiftMinutes)
        if (ch.epgOffsetMinutes == null) putNull("epg_offset_minutes") else put("epg_offset_minutes", ch.epgOffsetMinutes)
        put("video_decoder", ch.videoDecoder.name)
        put("audio_decoder", ch.audioDecoder.name)
        put("favourite", if (ch.favourite) 1 else 0)
        put("hidden", if (ch.hidden) 1 else 0)
        put("locked", if (ch.locked) 1 else 0)
        put("sort_index", ch.sortIndex)
        put("extra_attributes", Json.encodeMap(ch.extraAttributes))
        put("manual_epg_id", ch.manualEpgId)
        put("resolved_epg_id", ch.resolvedEpgId)
        put("match_kind", ch.matchKind)
        put("watch_count", ch.watchCount)
        put("last_watched_utc", ch.lastWatchedUtc)
    }

    fun upsertChannel(ch: Channel): Long {
        val v = channelValues(ch)
        return if (ch.id == 0L) db.insert("channels", null, v)
        else {
            db.update("channels", v, "id=?", arrayOf(ch.id.toString()))
            ch.id
        }
    }

    fun updateChannel(d: SQLiteDatabase, ch: Channel) {
        d.update("channels", channelValues(ch), "id=?", arrayOf(ch.id.toString()))
    }

    fun setChannelFlags(id: Long, favourite: Boolean? = null, hidden: Boolean? = null, locked: Boolean? = null) {
        val v = ContentValues()
        favourite?.let { v.put("favourite", if (it) 1 else 0) }
        hidden?.let { v.put("hidden", if (it) 1 else 0) }
        locked?.let { v.put("locked", if (it) 1 else 0) }
        writeChannelFields(id, v)
    }

    // ------------------------------------------------- targeted channel edits
    //
    // A user edit must touch only the columns it owns. [upsertChannel] writes all thirty from
    // whatever `Channel` the caller happens to be holding, so an edit made against a snapshot
    // taken before a refresh lands would push the stale *provider* fields — name, logo, urls,
    // catch-up — back over the fresh ones. `docs/11` records that race as "the loser is a single
    // user edit"; it is in fact the refresh that loses. These setters close it: each writes one
    // concept, and the row keeps everything the caller never asked to change.

    private fun writeChannelFields(id: Long, v: ContentValues) {
        if (v.size() > 0) db.update("channels", v, "id=?", arrayOf(id.toString()))
    }

    /** FR-ORG-04: a local rename. Null restores the provider's own name. */
    fun setChannelCustomName(id: Long, customName: String?) =
        writeChannelFields(id, ContentValues().apply { put("custom_name", customName) })

    /** FR-ORG-10: a user-supplied logo, overriding whatever the playlist carried. */
    fun setChannelCustomLogo(id: Long, url: String?) =
        writeChannelFields(id, ContentValues().apply { put("custom_logo_url", url) })

    /** FR-ORG-09: the number numeric zapping uses. */
    fun setChannelNumber(id: Long, number: Int) =
        writeChannelFields(id, ContentValues().apply { put("number", number) })

    /** FR-ORG-05: manual ordering. */
    fun setChannelSortIndex(id: Long, sortIndex: Int) =
        writeChannelFields(id, ContentValues().apply { put("sort_index", sortIndex) })

    /** FR-ORG-02: move a channel between groups; null leaves it ungrouped. */
    fun setChannelGroup(id: Long, groupId: Long?) = writeChannelFields(
        id,
        ContentValues().apply { if (groupId == null) putNull("group_id") else put("group_id", groupId) },
    )

    /** FR-EPG-12: a per-channel guide offset. */
    fun setChannelEpgOffset(id: Long, minutes: Int?) = writeChannelFields(
        id,
        ContentValues().apply {
            if (minutes == null) putNull("epg_offset_minutes") else put("epg_offset_minutes", minutes)
        },
    )

    /** FR-PLB-04: a decoder choice remembered for one channel. */
    fun setChannelDecoders(id: Long, video: DecoderMode, audio: DecoderMode) = writeChannelFields(
        id,
        ContentValues().apply {
            put("video_decoder", video.name)
            put("audio_decoder", audio.name)
        },
    )

    /** FR-EPG-08: the guide entry a channel is bound to, and how it was arrived at. */
    fun setChannelBinding(id: Long, manualEpgId: String?, resolvedEpgId: String?, matchKind: String) =
        writeChannelFields(
            id,
            ContentValues().apply {
                put("manual_epg_id", manualEpgId)
                put("resolved_epg_id", resolvedEpgId)
                put("match_kind", matchKind)
            },
        )

    /** Drops every local customisation, leaving the provider's own values in place. */
    fun resetChannelCustomisations(id: Long) = writeChannelFields(
        id,
        ContentValues().apply {
            putNull("custom_name")
            putNull("custom_logo_url")
            putNull("epg_offset_minutes")
            put("video_decoder", DecoderMode.AUTO.name)
            put("audio_decoder", DecoderMode.AUTO.name)
        },
    )

    fun recordWatch(channelId: Long, whenUtc: Long) {
        db.execSQL(
            "UPDATE channels SET last_watched_utc=?, watch_count=watch_count+1 WHERE id=?",
            arrayOf<Any>(whenUtc, channelId),
        )
    }

    private fun readChannel(c: android.database.Cursor) = Channel(
        id = c.long("id"),
        playlistId = c.long("playlist_id"),
        identity = c.str("identity"),
        streamId = c.strOrNull("stream_id"),
        tvgId = c.strOrNull("tvg_id"),
        name = c.str("name"),
        customName = c.strOrNull("custom_name"),
        logoUrl = c.strOrNull("logo_url"),
        customLogoUrl = c.strOrNull("custom_logo_url"),
        groupId = c.getColumnIndexOrThrow("group_id").let { if (c.isNull(it)) null else c.getLong(it) },
        number = c.int("number"),
        urls = Json.decodeList(c.strOrNull("urls")),
        userAgent = c.strOrNull("user_agent"),
        headers = Json.decodeMap(c.strOrNull("headers")),
        licenseType = c.strOrNull("license_type"),
        licenseKey = c.strOrNull("license_key"),
        catchupMode = runCatching { CatchupMode.valueOf(c.str("catchup_mode")) }.getOrDefault(CatchupMode.NONE),
        catchupSource = c.strOrNull("catchup_source"),
        catchupDays = c.int("catchup_days"),
        tvgShiftMinutes = c.int("tvg_shift_minutes"),
        epgOffsetMinutes = c.intOrNull("epg_offset_minutes"),
        videoDecoder = runCatching { DecoderMode.valueOf(c.str("video_decoder")) }.getOrDefault(DecoderMode.AUTO),
        audioDecoder = runCatching { DecoderMode.valueOf(c.str("audio_decoder")) }.getOrDefault(DecoderMode.AUTO),
        favourite = c.bool("favourite"),
        hidden = c.bool("hidden"),
        locked = c.bool("locked"),
        sortIndex = c.int("sort_index"),
        extraAttributes = Json.decodeMap(c.strOrNull("extra_attributes")),
        manualEpgId = c.strOrNull("manual_epg_id"),
        resolvedEpgId = c.strOrNull("resolved_epg_id"),
        matchKind = c.strOrNull("match_kind") ?: "NONE",
        watchCount = c.int("watch_count"),
        lastWatchedUtc = c.long("last_watched_utc"),
    )

    // ---------------------------------------------------------------- guide data

    fun upsertEpgChannel(d: SQLiteDatabase, ch: EpgChannel, sourceUrl: String?) {
        val v = ContentValues().apply {
            put("epg_id", ch.id)
            put("display_names", Json.encodeList(ch.displayNames))
            put("icon_url", ch.iconUrl)
            put("source_url", sourceUrl)
        }
        d.insertWithOnConflict("epg_channels", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun epgChannels(search: String? = null, limit: Int = 500): List<EpgChannel> {
        val sql = StringBuilder("SELECT * FROM epg_channels")
        val args = mutableListOf<String>()
        if (!search.isNullOrBlank()) {
            sql.append(" WHERE epg_id LIKE ?$LIKE_ESCAPE OR display_names LIKE ?$LIKE_ESCAPE")
            val pattern = likePattern(search)
            args += pattern; args += pattern
        }
        sql.append(" ORDER BY epg_id LIMIT ").append(limit)
        return db.rawQuery(sql.toString(), args.toTypedArray()).mapAll { c ->
            EpgChannel(
                id = c.str("epg_id"),
                displayNames = Json.decodeList(c.strOrNull("display_names")),
                iconUrl = c.strOrNull("icon_url"),
            )
        }
    }

    fun epgChannelCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM epg_channels", null).firstOrNull { it.getInt(0) } ?: 0

    fun insertProgramme(d: SQLiteDatabase, p: Programme, priority: Int) {
        val v = ContentValues().apply {
            put("epg_channel_id", p.epgChannelId)
            put("start_utc", p.startUtc)
            put("end_utc", p.endUtc)
            put("title", p.title)
            put("subtitle", p.subtitle)
            put("description", p.description)
            put("category", p.category)
            put("icon_url", p.iconUrl)
            if (p.seasonNumber == null) putNull("season_number") else put("season_number", p.seasonNumber)
            if (p.episodeNumber == null) putNull("episode_number") else put("episode_number", p.episodeNumber)
            put("episode_id", p.episodeId)
            put("rating", p.rating)
            put("source_priority", priority)
        }
        d.insertWithOnConflict("programmes", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Window query for the grid guide; [epgIds] is the visible page of channels. */
    fun programmesInWindow(epgIds: Collection<String>, fromUtc: Long, toUtc: Long): Map<String, List<Programme>> {
        if (epgIds.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<Programme>>(epgIds.size)
        epgIds.chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = ArrayList<String>(chunk.size + 2)
            args.addAll(chunk)
            args += toUtc.toString()
            args += fromUtc.toString()
            db.rawQuery(
                "SELECT * FROM programmes WHERE epg_channel_id IN ($placeholders) " +
                    "AND start_utc < ? AND end_utc > ? ORDER BY epg_channel_id, start_utc",
                args.toTypedArray(),
            ).mapAll(::readProgramme).forEach { p ->
                out.getOrPut(p.epgChannelId) { mutableListOf() }.add(p)
            }
        }
        return out
    }

    fun programmesForChannel(epgId: String, fromUtc: Long, toUtc: Long): List<Programme> =
        db.rawQuery(
            "SELECT * FROM programmes WHERE epg_channel_id=? AND start_utc < ? AND end_utc > ? ORDER BY start_utc",
            arrayOf(epgId, toUtc.toString(), fromUtc.toString()),
        ).mapAll(::readProgramme)

    fun programmeAt(epgId: String, atUtc: Long): Programme? =
        db.rawQuery(
            "SELECT * FROM programmes WHERE epg_channel_id=? AND start_utc<=? AND end_utc>? ORDER BY start_utc DESC LIMIT 1",
            arrayOf(epgId, atUtc.toString(), atUtc.toString()),
        ).firstOrNull(::readProgramme)

    fun nextProgramme(epgId: String, afterUtc: Long): Programme? =
        db.rawQuery(
            "SELECT * FROM programmes WHERE epg_channel_id=? AND start_utc>=? ORDER BY start_utc LIMIT 1",
            arrayOf(epgId, afterUtc.toString()),
        ).firstOrNull(::readProgramme)

    fun searchProgrammes(query: String, fromUtc: Long, limit: Int = 200): List<Programme> =
        db.rawQuery(
            "SELECT * FROM programmes WHERE title LIKE ?$LIKE_ESCAPE AND end_utc>=? ORDER BY start_utc LIMIT ?",
            arrayOf(likePattern(query), fromUtc.toString(), limit.toString()),
        ).mapAll(::readProgramme)

    fun programmeCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM programmes", null).firstOrNull { it.getInt(0) } ?: 0

    /**
     * FR-EPG-15: past programmes are kept for at least the longest catch-up window in the
     * library, so the archive can still be rendered; anything older is evicted.
     */
    fun pruneProgrammes(olderThanUtc: Long): Int =
        db.delete("programmes", "end_utc < ?", arrayOf(olderThanUtc.toString()))

    fun clearProgrammes() {
        db.delete("programmes", null, null)
        db.delete("epg_channels", null, null)
    }

    private fun readProgramme(c: android.database.Cursor) = Programme(
        id = c.long("id"),
        epgChannelId = c.str("epg_channel_id"),
        startUtc = c.long("start_utc"),
        endUtc = c.long("end_utc"),
        title = c.str("title"),
        subtitle = c.strOrNull("subtitle"),
        description = c.strOrNull("description"),
        category = c.strOrNull("category"),
        iconUrl = c.strOrNull("icon_url"),
        seasonNumber = c.intOrNull("season_number"),
        episodeNumber = c.intOrNull("episode_number"),
        episodeId = c.strOrNull("episode_id"),
        rating = c.strOrNull("rating"),
    )

    // ---------------------------------------------------------------------- VOD

    fun upsertVod(d: SQLiteDatabase, item: VodItem) {
        val v = ContentValues().apply {
            put("playlist_id", item.playlistId)
            put("stream_id", item.streamId)
            put("name", item.name)
            put("category_name", item.categoryName)
            put("poster_url", item.posterUrl)
            put("plot", item.plot)
            put("year", item.year)
            put("rating", item.rating)
            put("duration_seconds", item.durationSeconds)
            put("container_extension", item.containerExtension)
            put("series_id", item.seriesId)
            if (item.seasonNumber == null) putNull("season_number") else put("season_number", item.seasonNumber)
            if (item.episodeNumber == null) putNull("episode_number") else put("episode_number", item.episodeNumber)
        }
        // Preserve favourite / resume state across refreshes by updating first.
        val updated = d.update(
            "vod", v, "playlist_id=? AND stream_id=?",
            arrayOf(item.playlistId.toString(), item.streamId),
        )
        if (updated == 0) d.insertWithOnConflict("vod", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun vod(
        playlistId: Long,
        seriesOnly: Boolean = false,
        movieOnly: Boolean = false,
        favouritesOnly: Boolean = false,
        search: String? = null,
        limit: Int = 500,
    ): List<VodItem> {
        val where = StringBuilder("playlist_id=?")
        val args = mutableListOf(playlistId.toString())
        if (seriesOnly) where.append(" AND series_id IS NOT NULL")
        if (movieOnly) where.append(" AND series_id IS NULL")
        if (favouritesOnly) where.append(" AND favourite=1")
        if (!search.isNullOrBlank()) {
            where.append(" AND name LIKE ?$LIKE_ESCAPE"); args += likePattern(search)
        }
        return db.rawQuery(
            "SELECT * FROM vod WHERE $where ORDER BY name LIMIT $limit", args.toTypedArray(),
        ).mapAll(::readVod)
    }

    fun continueWatching(limit: Int = 30): List<VodItem> =
        db.rawQuery(
            "SELECT * FROM vod WHERE resume_position_ms > 0 ORDER BY last_watched_utc DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).mapAll(::readVod)

    fun saveResume(vodId: Long, positionMs: Long, whenUtc: Long) {
        db.execSQL(
            "UPDATE vod SET resume_position_ms=?, last_watched_utc=? WHERE id=?",
            arrayOf<Any>(positionMs, whenUtc, vodId),
        )
    }

    fun setVodFavourite(vodId: Long, favourite: Boolean) {
        db.execSQL("UPDATE vod SET favourite=? WHERE id=?", arrayOf<Any>(if (favourite) 1 else 0, vodId))
    }

    private fun readVod(c: android.database.Cursor) = VodItem(
        id = c.long("id"),
        playlistId = c.long("playlist_id"),
        streamId = c.str("stream_id"),
        name = c.str("name"),
        categoryName = c.strOrNull("category_name"),
        posterUrl = c.strOrNull("poster_url"),
        plot = c.strOrNull("plot"),
        year = c.strOrNull("year"),
        rating = c.strOrNull("rating"),
        durationSeconds = c.int("duration_seconds"),
        containerExtension = c.str("container_extension"),
        seriesId = c.strOrNull("series_id"),
        seasonNumber = c.intOrNull("season_number"),
        episodeNumber = c.intOrNull("episode_number"),
        favourite = c.bool("favourite"),
        resumePositionMs = c.long("resume_position_ms"),
        lastWatchedUtc = c.long("last_watched_utc"),
    )

    // --------------------------------------------------------------- recordings

    fun upsertRecording(r: Recording): Long {
        val v = ContentValues().apply {
            put("channel_id", r.channelId)
            put("channel_name", r.channelName)
            put("title", r.title)
            put("start_utc", r.startUtc)
            put("end_utc", r.endUtc)
            put("state", r.state.name)
            put("file_path", r.filePath)
            put("file_size_bytes", r.fileSizeBytes)
            put("message", r.message)
            put("series_key", r.seriesKey)
            put("watched", if (r.watched) 1 else 0)
            put("resume_position_ms", r.resumePositionMs)
            put("gap_count", r.gapCount)
        }
        return if (r.id == 0L) db.insert("recordings", null, v)
        else {
            db.update("recordings", v, "id=?", arrayOf(r.id.toString()))
            r.id
        }
    }

    fun recording(id: Long): Recording? =
        db.rawQuery("SELECT * FROM recordings WHERE id=?", arrayOf(id.toString())).firstOrNull(::readRecording)

    fun recordings(states: Set<RecordingState>? = null): List<Recording> {
        val sql = StringBuilder("SELECT * FROM recordings")
        if (!states.isNullOrEmpty()) {
            sql.append(" WHERE state IN (").append(states.joinToString(",") { "'${it.name}'" }).append(")")
        }
        sql.append(" ORDER BY start_utc DESC")
        return db.rawQuery(sql.toString(), null).mapAll(::readRecording)
    }

    fun pendingRecordings(nowUtc: Long): List<Recording> =
        db.rawQuery(
            "SELECT * FROM recordings WHERE state='SCHEDULED' AND end_utc > ? ORDER BY start_utc",
            arrayOf(nowUtc.toString()),
        ).mapAll(::readRecording)

    fun activeRecordings(): List<Recording> =
        db.rawQuery("SELECT * FROM recordings WHERE state='RECORDING'", null).mapAll(::readRecording)

    fun deleteRecording(id: Long) {
        db.delete("recordings", "id=?", arrayOf(id.toString()))
    }

    private fun readRecording(c: android.database.Cursor) = Recording(
        id = c.long("id"),
        channelId = c.long("channel_id"),
        channelName = c.str("channel_name"),
        title = c.str("title"),
        startUtc = c.long("start_utc"),
        endUtc = c.long("end_utc"),
        state = runCatching { RecordingState.valueOf(c.str("state")) }.getOrDefault(RecordingState.FAILED),
        filePath = c.strOrNull("file_path"),
        fileSizeBytes = c.long("file_size_bytes"),
        message = c.strOrNull("message"),
        seriesKey = c.strOrNull("series_key"),
        watched = c.bool("watched"),
        resumePositionMs = c.long("resume_position_ms"),
        gapCount = c.int("gap_count"),
    )
}
