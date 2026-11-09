package com.idanck.shidur.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.idanck.shidur.core.TextNormalizer
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.ChannelGroup
import com.idanck.shidur.data.model.ChannelSort
import com.idanck.shidur.data.model.CatchupMode
import com.idanck.shidur.data.model.DecoderMode
import com.idanck.shidur.data.model.EpgChannel
import com.idanck.shidur.data.model.OutputFormat
import com.idanck.shidur.data.model.Playlist
import com.idanck.shidur.data.model.Programme
import com.idanck.shidur.data.model.Recording
import com.idanck.shidur.data.model.RecordingState
import com.idanck.shidur.data.model.SourceType
import com.idanck.shidur.data.model.VodItem
import com.idanck.shidur.data.prefs.SecureStore
import com.idanck.shidur.data.xtream.XtreamUrlCredentials
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Declares the escape character used by every `LIKE` clause here. See [Dao.likePattern]. */
private const val LIKE_ESCAPE = " ESCAPE '\\'"

/**
 * What tells an episode apart from the two kinds of row that belong in the on-demand grid.
 *
 * `vod` holds films (`series_id` null), series containers (`series_id` set, `season_number`
 * null) and episodes (both set). `parseSeriesEpisodes` guarantees an episode always carries a
 * season, which is what makes this exact rather than a guess. The Kotlin side of the same rule
 * is `VodItem.isEpisode` in `XtreamClient.kt`; these two are the only places it is spelled out,
 * and they have to move together.
 */
private const val IS_AN_EPISODE = "season_number IS NOT NULL"
private const val NOT_AN_EPISODE = "season_number IS NULL"

/**
 * Data access for [Database]. Every method takes or opens a writable handle; SQLiteOpenHelper
 * caches a single connection, and WAL lets guide writes proceed while the UI reads.
 */
class Dao(
    private val helper: Database,
    /** How a stored credential is read back; the keystore outside of tests. */
    private val decrypt: (String?) -> String? = SecureStore::decrypt,
) {

    private val db: SQLiteDatabase get() = helper.writableDatabase

    /**
     * Each source's Xtream account, decrypted once, for [XtreamUrlCredentials]: a keystore decrypt
     * per channel row read would cost more than the read. Dropped whenever a playlist's row
     * changes, so an edited password is what the next read puts back.
     */
    private val accounts = ConcurrentHashMap<Long, AccountSlot>()

    /**
     * Bumped after every playlist write, so a read that loaded a row from before the write cannot
     * cache it after the write has dropped the old entry.
     */
    private val accountsVersion = AtomicInteger()

    private class AccountSlot(val account: XtreamUrlCredentials.Account?)

    private fun forgetAccount(playlistId: Long) {
        accountsVersion.incrementAndGet()
        accounts.remove(playlistId)
    }

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
            // Masked with the row's own account, read back from what is stored beside it, and with
            // the account it replaces: a restore that brings new credentials can keep the guide
            // address read under the old ones, which would otherwise go to disk in the clear.
            val account = accountOf(p.type.name, p.username, p.password).account
            val replaced = if (p.id == 0L || account == null) null else accountFor(p.id)
            put(
                "epg_url",
                p.epgUrl?.let { XtreamUrlCredentials.mask(XtreamUrlCredentials.mask(it, account), replaced) },
            )
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
            forgetAccount(p.id)
            p.id
        }
    }

    fun deletePlaylist(id: Long) {
        db.delete("playlists", "id=?", arrayOf(id.toString()))
        forgetAccount(id)
    }

    fun markRefresh(playlistId: Long, whenUtc: Long, ok: Boolean, message: String?) {
        val v = ContentValues().apply {
            put("last_refresh_utc", whenUtc)
            put("last_refresh_ok", if (ok) 1 else 0)
            put("last_refresh_message", message)
        }
        db.update("playlists", v, "id=?", arrayOf(playlistId.toString()))
    }

    /** Rewrites only the stored credentials, so a refresh finishing meanwhile keeps its status. */
    fun setPlaylistCredentials(playlistId: Long, username: String?, password: String?) {
        val v = ContentValues().apply {
            put("username", username)
            put("password", password)
        }
        db.update("playlists", v, "id=?", arrayOf(playlistId.toString()))
        forgetAccount(playlistId)
    }

    /** [playlistId]'s Xtream account, or null for any other kind of source. */
    private fun accountFor(playlistId: Long): XtreamUrlCredentials.Account? {
        accounts[playlistId]?.let { return it.account }
        val version = accountsVersion.get()
        val slot = db.rawQuery(
            "SELECT type, username, password FROM playlists WHERE id=?",
            arrayOf(playlistId.toString()),
        ).firstOrNull { c -> accountOf(c.str("type"), c.strOrNull("username"), c.strOrNull("password")) }
            ?: return null
        // Cached even when the keystore could not read it: asking again for every channel row of a
        // long list is what would make that failure slow as well. The next playlist write retries.
        if (accountsVersion.get() == version) accounts[playlistId] = slot
        return slot.account
    }

    private fun accountOf(type: String, username: String?, password: String?): AccountSlot {
        if (type != SourceType.XTREAM.name) return NO_ACCOUNT
        val user = decrypt(username.orEmpty()) ?: return NO_ACCOUNT
        val pass = decrypt(password.orEmpty()) ?: return NO_ACCOUNT
        return AccountSlot(XtreamUrlCredentials.Account(user, pass))
    }

    /**
     * Takes the account out of URLs stored before [XtreamUrlCredentials] existed (docs/qa,
     * finding 9): every channel row of an Xtream source, its guide address, and the copy of that
     * address each guide channel was imported with. Run on every cold start; a row already stored
     * without the account no longer contains it, so it is not touched again.
     */
    fun maskStoredCredentials() {
        val xtream = db.rawQuery(
            "SELECT id FROM playlists WHERE type=?",
            arrayOf(SourceType.XTREAM.name),
        ).mapAll { it.getLong(0) }
        for (playlistId in xtream) {
            val account = accountFor(playlistId)?.takeIf { it.usable } ?: continue
            transaction { d ->
                // On the encoded password alone: the column is a JSON array, whose encoder
                // escapes every `/` of the path around it. A password that turns up elsewhere in a
                // URL only costs a row that masks to itself and is skipped.
                d.rawQuery(
                    "SELECT id, urls FROM channels WHERE playlist_id=? AND urls LIKE ?$LIKE_ESCAPE",
                    arrayOf(playlistId.toString(), likePattern(account.pass)),
                ).mapAll { c -> c.getLong(0) to c.getString(1) }
                    .forEach { (id, raw) ->
                        val masked = Json.encodeList(
                            Json.decodeList(raw).map { XtreamUrlCredentials.mask(it, account) },
                        )
                        if (masked == raw) return@forEach
                        val v = ContentValues().apply { put("urls", masked) }
                        d.update("channels", v, "id=?", arrayOf(id.toString()))
                    }

                val epgUrl = d.rawQuery(
                    "SELECT epg_url FROM playlists WHERE id=?",
                    arrayOf(playlistId.toString()),
                ).firstOrNull { c -> c.getString(0) }
                val maskedEpg = epgUrl?.let { XtreamUrlCredentials.mask(it, account) }
                if (epgUrl != null && maskedEpg != epgUrl) {
                    d.update(
                        "playlists",
                        ContentValues().apply { put("epg_url", maskedEpg) },
                        "id=?",
                        arrayOf(playlistId.toString()),
                    )
                    // Write-only bookkeeping, never read back, so the redacted form loses nothing.
                    d.update(
                        "epg_channels",
                        ContentValues().apply { put("source_url", TextNormalizer.redactUrl(epgUrl)) },
                        "source_url=?",
                        arrayOf(epgUrl),
                    )
                }
            }
        }
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
        epgUrl = c.strOrNull("epg_url")?.let { XtreamUrlCredentials.unmask(it, accountFor(c.long("id"))) },
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
            val pattern = likePattern(search)
            if (groupId == null) {
                // The filter is how a phone user asks "the HEB channels" without a group column on
                // screen, so it matches the group's name as well as the channel's. Only outside a
                // selected group: inside one every channel shares that name and the clause would
                // turn the filter into a no-op. The hidden-group exclusion above is a separate AND,
                // so a name match cannot resurrect a hidden group's channels.
                where.append(
                    " AND (name LIKE ?$LIKE_ESCAPE OR IFNULL(custom_name,'') LIKE ?$LIKE_ESCAPE" +
                        " OR group_id IN (SELECT id FROM channel_groups WHERE name LIKE ?$LIKE_ESCAPE))",
                )
                args += pattern; args += pattern; args += pattern
            } else {
                where.append(" AND (name LIKE ?$LIKE_ESCAPE OR IFNULL(custom_name,'') LIKE ?$LIKE_ESCAPE)")
                args += pattern; args += pattern
            }
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
     *
     * [onlyPlaylistId] and [excludePlaylistId] are the one exception to that. The bundled radio
     * playlist (ADR-015) is not another TV source, and spanning into it put television channels
     * in Radio's "recently watched" and radio stations in the channel list's. Callers pass
     * whichever end of that they mean; both default to null, which spans everything as before.
     */
    fun recentChannels(
        search: String? = null,
        limit: Int = 25,
        onlyPlaylistId: Long? = null,
        excludePlaylistId: Long? = null,
    ): List<Channel> {
        val where = StringBuilder("last_watched_utc>0 AND hidden=0")
        val args = mutableListOf<String>()
        if (onlyPlaylistId != null) {
            where.append(" AND playlist_id=?")
            args += onlyPlaylistId.toString()
        }
        if (excludePlaylistId != null) {
            where.append(" AND playlist_id<>?")
            args += excludePlaylistId.toString()
        }
        if (!search.isNullOrBlank()) {
            // Group names count as matches here too; see `channels`. Recents span playlists, and
            // group ids are globally unique, so the subselect needs no playlist scoping.
            where.append(
                " AND (name LIKE ?$LIKE_ESCAPE OR IFNULL(custom_name,'') LIKE ?$LIKE_ESCAPE" +
                    " OR group_id IN (SELECT id FROM channel_groups WHERE name LIKE ?$LIKE_ESCAPE))",
            )
            val pattern = likePattern(search)
            args += pattern; args += pattern; args += pattern
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
        put("urls", Json.encodeList(maskUrls(ch.playlistId, ch.urls)))
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

    /** A channel's URLs as stored: without its source's account ([XtreamUrlCredentials]). */
    private fun maskUrls(playlistId: Long, urls: List<String>): List<String> {
        val account = accountFor(playlistId) ?: return urls
        return urls.map { XtreamUrlCredentials.mask(it, account) }
    }

    /** A channel row's URLs with its source's account put back. */
    private fun readUrls(playlistId: Long, raw: String?): List<String> {
        val urls = Json.decodeList(raw)
        val account = accountFor(playlistId) ?: return urls
        return urls.map { XtreamUrlCredentials.unmask(it, account) }
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
        urls = readUrls(c.long("playlist_id"), c.strOrNull("urls")),
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

    /**
     * On-demand rows for one playlist.
     *
     * Episodes are excluded unless asked for: they are reached by opening their series, and a
     * playlist with a few hundred series would otherwise bury the films under tens of thousands
     * of loose tiles and answer a search for a series name with sixty of its episodes.
     */
    fun vod(
        playlistId: Long,
        seriesOnly: Boolean = false,
        movieOnly: Boolean = false,
        favouritesOnly: Boolean = false,
        includeEpisodes: Boolean = false,
        search: String? = null,
        limit: Int = 500,
    ): List<VodItem> {
        val where = StringBuilder("playlist_id=?")
        val args = mutableListOf(playlistId.toString())
        // A container is not an episode either, so asking for series excludes them whatever
        // [includeEpisodes] says — added once, so the clause cannot end up in the query twice.
        if (!includeEpisodes || seriesOnly) where.append(" AND $NOT_AN_EPISODE")
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

    /**
     * The episodes of one series, in the order a viewer expects to meet them.
     *
     * The exact complement of what [vod] shows: everything this returns is a row the grid hides.
     */
    fun episodes(playlistId: Long, seriesId: String): List<VodItem> = db.rawQuery(
        "SELECT * FROM vod WHERE playlist_id=? AND series_id=? AND $IS_AN_EPISODE " +
            "ORDER BY season_number, episode_number, name",
        arrayOf(playlistId.toString(), seriesId),
    ).mapAll(::readVod)

    fun continueWatching(limit: Int = 30): List<VodItem> =
        db.rawQuery(
            "SELECT * FROM vod WHERE resume_position_ms > 0 ORDER BY last_watched_utc DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).mapAll(::readVod)

    /**
     * The stored resume mark for one on-demand row.
     *
     * Read at play time rather than trusted from the caller: the screen's snapshot goes stale
     * the moment another episode saves its place, and starting from a stale mark has the next
     * save overwrite the real one — the viewer's position, lost to the row they pressed
     * (FR-PLB-29).
     */
    fun vodResumePosition(id: Long): Long = db.rawQuery(
        "SELECT resume_position_ms FROM vod WHERE id=?",
        arrayOf(id.toString()),
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /**
     * Drops the episodes the panel no longer lists for one series; the rows it still lists
     * survive with their favourite and resume marks. Called only after a successful
     * `get_series_info`, inside its transaction — pruning on a failed fetch would wipe the
     * cache the fetch exists to refresh. The episode guard spares the series container row.
     */
    fun deleteStaleEpisodes(
        d: SQLiteDatabase,
        playlistId: Long,
        seriesId: String,
        keepStreamIds: Set<String>,
    ) {
        val stale = d.rawQuery(
            "SELECT id, stream_id FROM vod WHERE playlist_id=? AND series_id=? AND $IS_AN_EPISODE",
            arrayOf(playlistId.toString(), seriesId),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.getString(1) !in keepStreamIds) add(c.getLong(0))
                }
            }
        }
        // Ids are numeric and chunked under SQLite's placeholder ceiling.
        stale.chunked(500).forEach { chunk ->
            d.execSQL("DELETE FROM vod WHERE id IN (${chunk.joinToString(",")})")
        }
    }

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

    private companion object {
        /** Not an Xtream source, or one whose account the keystore could not read back. */
        val NO_ACCOUNT = AccountSlot(null)
    }
}
