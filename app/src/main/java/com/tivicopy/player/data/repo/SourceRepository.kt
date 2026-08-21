package com.tivicopy.player.data.repo

import com.tivicopy.player.core.AppError
import com.tivicopy.player.core.Outcome
import com.tivicopy.player.data.builtin.IsraelPublicChannels
import com.tivicopy.player.data.builtin.IsraelRadioStations
import com.tivicopy.player.data.db.Dao
import com.tivicopy.player.data.m3u.M3uEntry
import com.tivicopy.player.data.m3u.M3uParser
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.OutputFormat
import com.tivicopy.player.data.model.Playlist
import com.tivicopy.player.data.model.RefreshReport
import com.tivicopy.player.data.model.SourceType
import com.tivicopy.player.data.net.HttpFactory
import com.tivicopy.player.data.net.RequestProfile
import com.tivicopy.player.data.prefs.SecureStore
import com.tivicopy.player.data.prefs.Settings
import com.tivicopy.player.data.xtream.XtreamAccount
import com.tivicopy.player.data.xtream.XtreamClient
import com.tivicopy.player.data.xtream.extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File

/**
 * Ingests a source into the database and keeps it fresh.
 *
 * The refresh contract (FR-SRC-11 / FR-SRC-12) is the important part: a refresh is a *merge*,
 * never a replace. Existing rows are matched on the stable identity `tvg-id -> stream-id ->
 * normalised name`; provider-owned fields (name, logo, URL, group, catch-up) are overwritten,
 * user-owned fields (favourite, hidden, locked, custom name/logo, ordering, decoder choice,
 * per-channel EPG offset, manual EPG binding) are left untouched. A source that answers with
 * an error, or with nothing, aborts the merge and leaves the previous snapshot in place.
 */
class SourceRepository(
    private val context: android.content.Context,
    private val dao: Dao,
    private val settings: Settings,
) {

    fun profileFor(playlist: Playlist): RequestProfile {
        val s = settings.state.value
        return RequestProfile(
            userAgent = playlist.userAgent?.takeIf { it.isNotBlank() } ?: s.userAgent,
            headers = playlist.headers,
            connectTimeoutSeconds = s.connectTimeoutSeconds,
            readTimeoutSeconds = s.readTimeoutSeconds,
            retries = s.retries,
            allowInsecureTls = s.allowInsecureTls,
        )
    }

    fun xtreamClient(playlist: Playlist): XtreamClient? {
        if (playlist.type != SourceType.XTREAM) return null
        val user = SecureStore.decrypt(playlist.username) ?: return null
        val pass = SecureStore.decrypt(playlist.password) ?: return null
        return XtreamClient(playlist.url, user, pass, profileFor(playlist))
    }

    /** Stores a playlist, encrypting its credentials before they touch the database. */
    fun savePlaylist(playlist: Playlist): Long = dao.upsertPlaylist(
        playlist.copy(
            username = playlist.username?.let(SecureStore::encrypt),
            password = playlist.password?.let(SecureStore::encrypt),
        ),
    )

    /**
     * The one bundled playlist this app ships with (docs/00-scope-and-platforms.md §0.1).
     * Idempotent and self-healing: run on every cold start, it only inserts the row the first
     * time, and quietly reinstates it if the user ever deletes it — no separate "first run" flag,
     * matching the rest of the app's preference for idempotent bootstrap over stored one-shot state.
     *
     * A row seeded at one of [IsraelPublicChannels.LEGACY_M3U_URLS] is this same playlist at an
     * address that has stopped serving it, so it is corrected in place — keeping its id, and with
     * it the user's ordering, colour and per-channel edits — rather than abandoned next to a
     * freshly inserted duplicate.
     *
     * Deliberately does not fetch here: a [Playlist] left with [Playlist.lastRefreshUtc] at `0` is
     * what the periodic [com.tivicopy.player.work.RefreshWorker] already treats as immediately due
     * — so the first real refresh happens on its normal worker cadence without this bootstrap step
     * needing its own network call (and its own failure handling). A corrected row is reset to `0`
     * for the same reason: its stored timestamp is from a refresh against the dead URL, and waiting
     * out the interval before retrying the working one would strand it for another day.
     */
    suspend fun ensureBuiltInPlaylist() = withContext(Dispatchers.IO) {
        val playlists = dao.playlists()
        if (playlists.any { it.url == IsraelPublicChannels.M3U_URL }) return@withContext

        val stale = playlists.firstOrNull { it.url in IsraelPublicChannels.LEGACY_M3U_URLS }
        if (stale != null) {
            // Straight through the DAO, not savePlaylist: this row came out of the database with
            // its credentials already encrypted, and savePlaylist would encrypt them a second time.
            dao.upsertPlaylist(
                stale.copy(
                    url = IsraelPublicChannels.M3U_URL,
                    epgUrl = IsraelPublicChannels.EPG_URL,
                    lastRefreshUtc = 0,
                    lastRefreshOk = true,
                    lastRefreshMessage = null,
                ),
            )
            return@withContext
        }

        savePlaylist(
            Playlist(
                name = IsraelPublicChannels.NAME,
                type = SourceType.M3U_URL,
                url = IsraelPublicChannels.M3U_URL,
                epgUrl = IsraelPublicChannels.EPG_URL,
                refreshIntervalMinutes = 24 * 60,
            ),
        )
    }

    /**
     * The second bundled playlist (ADR-015): curated Israeli radio stations, audio-only. Same
     * idempotent, self-healing bootstrap contract as [ensureBuiltInPlaylist] — a deliberate
     * second copy of that shape rather than a shared helper, matching this app's preference for
     * explicit code over an abstraction serving exactly two call sites.
     */
    suspend fun ensureRadioPlaylist() = withContext(Dispatchers.IO) {
        val playlists = dao.playlists()
        if (playlists.any { it.url == IsraelRadioStations.M3U_URL }) return@withContext

        val stale = playlists.firstOrNull { it.url in IsraelRadioStations.LEGACY_M3U_URLS }
        if (stale != null) {
            dao.upsertPlaylist(
                stale.copy(
                    url = IsraelRadioStations.M3U_URL,
                    lastRefreshUtc = 0,
                    lastRefreshOk = true,
                    lastRefreshMessage = null,
                ),
            )
            return@withContext
        }

        savePlaylist(
            Playlist(
                name = IsraelRadioStations.NAME,
                type = SourceType.M3U_URL,
                url = IsraelRadioStations.M3U_URL,
                refreshIntervalMinutes = 24 * 60,
            ),
        )
    }

    suspend fun refresh(playlistId: Long, localFileReader: (() -> BufferedReader)? = null): Outcome<RefreshReport> =
        withContext(Dispatchers.IO) {
            val playlist = dao.playlist(playlistId)
                ?: return@withContext Outcome.Err(AppError(AppError.Kind.NotFound, "playlist $playlistId"))

            val fetched: Outcome<Fetched> = when (playlist.type) {
                SourceType.M3U_URL -> fetchM3uUrl(playlist)
                SourceType.M3U_FILE -> fetchM3uFile(playlist, localFileReader)
                SourceType.XTREAM -> fetchXtream(playlist)
            }

            when (fetched) {
                is Outcome.Err -> {
                    // FR-SRC-12: the previous snapshot survives a failed refresh.
                    dao.markRefresh(playlistId, System.currentTimeMillis(), false, fetched.error.kind.name)
                    Outcome.Err(fetched.error)
                }
                is Outcome.Ok -> {
                    val entries = fetched.value.entries
                    if (entries.isEmpty()) {
                        dao.markRefresh(playlistId, System.currentTimeMillis(), false, "empty")
                        Outcome.Err(AppError(AppError.Kind.EmptyResponse, "source returned no channels"))
                    } else {
                        val report = merge(playlist, entries)
                        fetched.value.epgUrl?.takeIf { it.isNotBlank() && playlist.epgUrl.isNullOrBlank() }?.let { url ->
                            dao.upsertPlaylist(dao.playlist(playlistId)!!.copy(epgUrl = url))
                        }
                        fetched.value.vod.takeIf { it.isNotEmpty() }?.let { items ->
                            dao.transaction { db ->
                                items.forEach { dao.upsertVod(db, it.copy(playlistId = playlistId)) }
                            }
                        }
                        dao.markRefresh(playlistId, System.currentTimeMillis(), true, null)
                        Outcome.Ok(report)
                    }
                }
            }
        }

    private data class Fetched(
        val entries: List<M3uEntry>,
        val epgUrl: String? = null,
        val vod: List<com.tivicopy.player.data.model.VodItem> = emptyList(),
        val account: XtreamAccount? = null,
    )

    private fun fetchM3uUrl(playlist: Playlist): Outcome<Fetched> {
        val profile = profileFor(playlist)
        return try {
            HttpFactory.client(profile)
                .newCall(HttpFactory.request(playlist.url, profile)).execute().use { response ->
                    if (!response.isSuccessful) {
                        return Outcome.Err(AppError.fromHttp(response.code, response.peekBodySafely()))
                    }
                    val body = response.body ?: return Outcome.Err(AppError(AppError.Kind.EmptyResponse))
                    val parsed = body.charStream().buffered().use { M3uParser.parse(it) }
                    Outcome.Ok(Fetched(parsed.entries, parsed.epgUrl))
                }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Outcome.Err(AppError.of(t))
        }
    }

    private fun fetchM3uFile(playlist: Playlist, reader: (() -> BufferedReader)?): Outcome<Fetched> = try {
        val stream = reader?.invoke() ?: openLocal(playlist.url)
        val parsed = stream.use { M3uParser.parse(it) }
        Outcome.Ok(Fetched(parsed.entries, parsed.epgUrl))
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        Outcome.Err(AppError.of(t))
    }

    /**
     * A local playlist is normally a Storage Access Framework document, because that is the
     * only way to read a user-chosen file on Android 10+ without a broad storage permission.
     * A plain path is still accepted for sideloaded setups that use one.
     */
    private fun openLocal(url: String): BufferedReader =
        if (url.startsWith("content://")) {
            val stream = context.contentResolver.openInputStream(android.net.Uri.parse(url))
                ?: throw java.io.FileNotFoundException(url)
            stream.bufferedReader()
        } else {
            File(url.removePrefix("file://")).bufferedReader()
        }

    private fun fetchXtream(playlist: Playlist): Outcome<Fetched> {
        val client = xtreamClient(playlist)
            ?: return Outcome.Err(AppError(AppError.Kind.Unauthorized, "credentials unavailable"))

        val account = client.account()
        if (account is Outcome.Err) return Outcome.Err(account.error)

        val live = client.liveChannels(playlist.outputFormat)
        if (live is Outcome.Err) return Outcome.Err(live.error)

        // VOD and series are best-effort: a panel that refuses them must not block live TV.
        val vod = client.vod().getOrNull().orEmpty() + client.series().getOrNull().orEmpty()

        return Outcome.Ok(
            Fetched(
                entries = live.getOrNull().orEmpty(),
                epgUrl = client.xmltvUrl(),
                vod = vod,
                account = account.getOrNull(),
            ),
        )
    }

    /** The non-destructive merge described in the class comment. */
    internal fun merge(playlist: Playlist, entries: List<M3uEntry>): RefreshReport {
        val existing = dao.channels(playlist.id, includeHidden = true).associateBy { it.identity }
        val seen = HashSet<String>(entries.size)
        var added = 0
        var renamed = 0

        // Identity is derived through name normalisation, so it is computed once per entry
        // rather than re-derived inside the loop: a 10k-channel playlist would otherwise cost
        // 10^8 normalisations to collect each channel's alternate URLs.
        val identities = entries.map { it.identity }
        val alternatesByIdentity = HashMap<String, MutableList<String>>(entries.size)
        entries.forEachIndexed { i, entry ->
            val list = alternatesByIdentity.getOrPut(identities[i]) { ArrayList(1) }
            if (entry.url !in list) list.add(entry.url)
        }

        dao.transaction { db ->
            val groupIds = HashMap<String, Long>()
            var groupSort = 0

            entries.forEachIndexed { index, entry ->
                val identity = identities[index]
                // A playlist can legitimately repeat an identity (backup URLs for one channel);
                // the extra URLs are folded into the first occurrence for failover.
                if (!seen.add(identity)) {
                    return@forEachIndexed
                }

                val groupName = entry.groupTitle?.takeIf { it.isNotBlank() } ?: UNGROUPED
                val groupId = groupIds.getOrPut(groupName) {
                    // FR-ORG-11: the setting existed but nothing read it, so a provider adding
                    // twenty categories always dropped twenty new rows into the group column.
                    // Only groups created here are affected; one the user has shown stays shown.
                    dao.ensureGroup(
                        db,
                        playlist.id,
                        groupName,
                        groupSort++,
                        hiddenWhenNew = !settings.state.value.showNewGroups,
                    )
                }

                val alternates = alternatesByIdentity[identity].orEmpty().toList()

                val previous = existing[identity]
                if (previous == null) {
                    added++
                    dao.upsertChannel(
                        Channel(
                            playlistId = playlist.id,
                            identity = identity,
                            streamId = M3uEntry.streamIdFromUrl(entry.url),
                            tvgId = entry.tvgId,
                            name = entry.name,
                            logoUrl = entry.tvgLogo,
                            groupId = groupId,
                            number = entry.tvgChno ?: (index + 1),
                            urls = alternates,
                            userAgent = entry.userAgent,
                            headers = entry.headers,
                            licenseType = entry.licenseType,
                            licenseKey = entry.licenseKey,
                            catchupMode = entry.catchupMode,
                            catchupSource = entry.catchupSource,
                            catchupDays = entry.catchupDays,
                            tvgShiftMinutes = entry.tvgShiftMinutes,
                            sortIndex = index,
                            extraAttributes = entry.extraAttributes,
                        ),
                    )
                } else {
                    if (previous.name != entry.name) renamed++
                    // Provider-owned fields refresh; everything the user touched is carried over.
                    dao.updateChannel(
                        db,
                        previous.copy(
                            streamId = M3uEntry.streamIdFromUrl(entry.url) ?: previous.streamId,
                            tvgId = entry.tvgId ?: previous.tvgId,
                            name = entry.name,
                            logoUrl = entry.tvgLogo ?: previous.logoUrl,
                            groupId = if (previous.groupId == null) groupId else previous.groupId,
                            number = if (previous.number == 0) entry.tvgChno ?: (index + 1) else previous.number,
                            urls = alternates,
                            userAgent = entry.userAgent ?: previous.userAgent,
                            headers = if (entry.headers.isNotEmpty()) entry.headers else previous.headers,
                            licenseType = entry.licenseType ?: previous.licenseType,
                            licenseKey = entry.licenseKey ?: previous.licenseKey,
                            catchupMode = entry.catchupMode,
                            catchupSource = entry.catchupSource ?: previous.catchupSource,
                            catchupDays = entry.catchupDays,
                            tvgShiftMinutes = entry.tvgShiftMinutes,
                            extraAttributes = previous.extraAttributes + entry.extraAttributes,
                        ),
                    )
                }
            }

            // Channels the source no longer lists are removed; their group survives if it
            // still holds anything, so a user-assigned custom group is not silently emptied.
            existing.keys.filter { it !in seen }.forEach { goneIdentity ->
                existing[goneIdentity]?.let { ch ->
                    db.delete("channels", "id=?", arrayOf(ch.id.toString()))
                }
            }
        }

        val removed = existing.keys.count { it !in seen }
        return RefreshReport(
            added = added,
            removed = removed,
            renamed = renamed,
            total = seen.size,
            ok = true,
        )
    }

    /** Convenience for the "add source" flow: probe an Xtream server before saving. */
    suspend fun probeXtream(
        url: String,
        username: String,
        password: String,
        format: OutputFormat,
    ): Outcome<XtreamAccount> = withContext(Dispatchers.IO) {
        val s = settings.state.value
        val client = XtreamClient(
            url, username, password,
            RequestProfile(
                userAgent = s.userAgent,
                connectTimeoutSeconds = s.connectTimeoutSeconds,
                readTimeoutSeconds = s.readTimeoutSeconds,
                allowInsecureTls = s.allowInsecureTls,
            ),
        )
        // Touching the live list too proves the credentials work for streaming, not just login.
        val account = client.account()
        if (account is Outcome.Ok) {
            client.liveChannels(format)
        }
        account
    }

    companion object {
        const val UNGROUPED = "Ungrouped"
    }
}

/** Reads a short prefix of an error body for diagnosis without buffering a whole playlist. */
private fun okhttp3.Response.peekBodySafely(limit: Long = 512): String? =
    runCatching { peekBody(limit).string() }.getOrNull()
