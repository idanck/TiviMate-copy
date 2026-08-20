package com.tivicopy.player.data.repo

import com.tivicopy.player.core.AppError
import com.tivicopy.player.core.Outcome
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.db.Dao
import com.tivicopy.player.data.epg.EpgMatcher
import com.tivicopy.player.data.epg.MatchKind
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.EpgChannel
import com.tivicopy.player.data.model.EpgMatchStats
import com.tivicopy.player.data.model.Playlist
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.net.Decompressor
import com.tivicopy.player.data.net.HttpFactory
import com.tivicopy.player.data.prefs.Settings
import com.tivicopy.player.data.xmltv.XmltvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Progress of a guide import, for the UI (FR-EPG-06). */
data class EpgProgress(val channels: Int, val programmes: Int, val done: Boolean = false)

/**
 * Downloads, parses, stores and matches guide data.
 *
 * Programmes are written in batches inside a single transaction: a 500k-row guide inserted
 * row-by-row with autocommit would take minutes on a TV box, and the same work inside one
 * transaction takes seconds.
 */
class EpgRepository(
    private val dao: Dao,
    private val settings: Settings,
    private val sources: SourceRepository,
) {

    suspend fun refreshGuide(
        playlist: Playlist,
        onProgress: (EpgProgress) -> Unit = {},
    ): Outcome<EpgMatchStats> = withContext(Dispatchers.IO) {
        val url = playlist.epgUrl?.takeIf { it.isNotBlank() }
            ?: return@withContext Outcome.Err(
                AppError(AppError.Kind.NotFound, "no guide URL configured for this playlist"),
            )

        val imported = importFrom(url, playlist, onProgress)
        if (imported is Outcome.Err) return@withContext Outcome.Err(imported.error)

        pruneOldProgrammes()
        Outcome.Ok(rematch(playlist.id))
    }

    private fun importFrom(
        url: String,
        playlist: Playlist,
        onProgress: (EpgProgress) -> Unit,
    ): Outcome<Int> {
        val profile = sources.profileFor(playlist)
        return try {
            val stream = if (url.startsWith("file://") || url.startsWith("/")) {
                File(url.removePrefix("file://")).inputStream()
            } else {
                val response = HttpFactory.client(profile)
                    .newCall(HttpFactory.request(url, profile)).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return Outcome.Err(AppError.fromHttp(response.code))
                }
                response.body?.byteStream()
                    ?: return Outcome.Err(AppError(AppError.Kind.EmptyResponse, "no guide body"))
            }

            var count = 0
            Decompressor.wrap(stream).use { decompressed ->
                dao.transaction { db ->
                    val parser = XmltvParser(
                        onChannel = { ch -> dao.upsertEpgChannel(db, ch, url) },
                        onProgramme = { p -> dao.insertProgramme(db, p, priority = 0) },
                        progress = { channels, programmes ->
                            onProgress(EpgProgress(channels, programmes))
                        },
                    )
                    count = parser.parse(decompressed)
                }
            }
            onProgress(EpgProgress(dao.epgChannelCount(), count, done = true))
            if (count == 0) Outcome.Err(AppError(AppError.Kind.ParseFailed, "guide contained no programmes"))
            else Outcome.Ok(count)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Outcome.Err(AppError.of(t))
        }
    }

    /**
     * Re-runs the matcher and stores the binding on each channel.
     *
     * FR-EPG-12: only [Channel.resolvedEpgId] is written. `epgOffsetMinutes` and
     * `manualEpgId` are read but never rewritten here, which is what keeps a per-channel
     * offset from being reverted when some other channel's guide entry changes.
     */
    fun rematch(playlistId: Long): EpgMatchStats {
        val channels = dao.channels(playlistId, includeHidden = true)
        if (channels.isEmpty()) return EpgMatchStats()
        val epgChannels = dao.epgChannels(limit = MAX_EPG_CHANNELS)
        val results = EpgMatcher().match(channels, epgChannels)

        var exact = 0; var byName = 0; var fuzzy = 0; var manual = 0; var none = 0
        dao.transaction { db ->
            val byId = channels.associateBy { it.id }
            results.forEach { r ->
                when (r.kind) {
                    MatchKind.EXACT_ID -> exact++
                    MatchKind.NORMALIZED_NAME -> byName++
                    MatchKind.FUZZY -> fuzzy++
                    MatchKind.MANUAL -> manual++
                    MatchKind.NONE -> none++
                }
                val ch = byId[r.channelId] ?: return@forEach
                if (ch.resolvedEpgId != r.epgId || ch.matchKind != r.kind.name) {
                    db.execSQL(
                        "UPDATE channels SET resolved_epg_id=?, match_kind=? WHERE id=?",
                        arrayOf<Any?>(r.epgId, r.kind.name, ch.id),
                    )
                }
            }
        }

        return EpgMatchStats(
            totalChannels = channels.size,
            matchedExact = exact,
            matchedName = byName,
            matchedFuzzy = fuzzy,
            matchedManual = manual,
            unmatched = none,
        )
    }

    /** FR-EPG-08: a manual binding is written once and then treated as authoritative. */
    fun bindManually(channelId: Long, epgId: String?) {
        val ch = dao.channel(channelId) ?: return
        // Three columns, written as three columns. `upsertChannel` would rewrite the whole row
        // from this read, which a refresh landing in between would lose — the same race the
        // per-channel edits were moved off, and this one is now reachable from the UI too.
        dao.setChannelBinding(
            id = channelId,
            manualEpgId = epgId,
            resolvedEpgId = epgId ?: ch.resolvedEpgId,
            matchKind = if (epgId != null) MatchKind.MANUAL.name else ch.matchKind,
        )
    }

    fun unmatchedChannels(playlistId: Long): List<Channel> =
        dao.channels(playlistId, includeHidden = true).filter { it.resolvedEpgId.isNullOrBlank() }

    fun searchEpgChannels(query: String): List<EpgChannel> = dao.epgChannels(query)

    /**
     * FR-EPG-15: past programmes survive at least as long as the longest catch-up window in
     * the library, so the archive stays renderable; the configured retention floor applies
     * when no channel declares an archive.
     */
    fun pruneOldProgrammes(nowUtc: Long = System.currentTimeMillis()): Int {
        val longestCatchupDays = dao.allChannels().maxOfOrNull { it.catchupDays } ?: 0
        val retentionDays = maxOf(longestCatchupDays, settings.state.value.epgRetentionPastDays)
        val cutoff = nowUtc - retentionDays * 24L * 60 * 60 * 1000
        return dao.pruneProgrammes(cutoff)
    }

    // --------------------------------------------------------------- read paths

    /** Effective offset for a channel: per-channel override, else `tvg-shift`, else global. */
    fun offsetMinutesFor(channel: Channel): Int {
        val global = settings.state.value.globalEpgOffsetMinutes
        return channel.epgOffsetMinutes
            ?: (if (channel.tvgShiftMinutes != 0) channel.tvgShiftMinutes else global)
    }

    fun nowAndNext(channel: Channel, nowUtc: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val epgId = channel.resolvedEpgId ?: return null to null
        val offset = offsetMinutesFor(channel)
        // Offsets shift the *query* rather than the stored rows, so one channel's correction
        // can never rewrite guide data another channel shares.
        val probe = TimeModel.shift(nowUtc, -offset)
        val now = dao.programmeAt(epgId, probe)
        val next = dao.nextProgramme(epgId, now?.endUtc ?: probe)
        return now?.shifted(offset) to next?.shifted(offset)
    }

    fun programmesFor(channel: Channel, fromUtc: Long, toUtc: Long): List<Programme> {
        val epgId = channel.resolvedEpgId ?: return emptyList()
        val offset = offsetMinutesFor(channel)
        return dao.programmesForChannel(epgId, TimeModel.shift(fromUtc, -offset), TimeModel.shift(toUtc, -offset))
            .map { it.shifted(offset) }
    }

    /** Batched window read for the grid guide; keyed by channel id, already offset-corrected. */
    fun guideWindow(channels: List<Channel>, fromUtc: Long, toUtc: Long): Map<Long, List<Programme>> {
        if (channels.isEmpty()) return emptyMap()
        // Group channels by their offset so each distinct offset costs one windowed query.
        val byOffset = channels.filter { !it.resolvedEpgId.isNullOrBlank() }.groupBy { offsetMinutesFor(it) }
        val out = HashMap<Long, List<Programme>>(channels.size)
        byOffset.forEach { (offset, group) ->
            val ids = group.mapNotNull { it.resolvedEpgId }.toSet()
            val rows = dao.programmesInWindow(
                ids,
                TimeModel.shift(fromUtc, -offset),
                TimeModel.shift(toUtc, -offset),
            )
            group.forEach { ch ->
                val list = rows[ch.resolvedEpgId].orEmpty().map { it.shifted(offset) }
                out[ch.id] = list
            }
        }
        return out
    }

    fun searchProgrammes(query: String, fromUtc: Long) = dao.searchProgrammes(query, fromUtc)

    /** Reads the breakdown recorded by the last [rematch]; no matching work is repeated. */
    fun stats(playlistId: Long): EpgMatchStats {
        val channels = dao.channels(playlistId, includeHidden = true)
        val bound = channels.filter { !it.resolvedEpgId.isNullOrBlank() }
        return EpgMatchStats(
            totalChannels = channels.size,
            matchedExact = bound.count { it.matchKind == MatchKind.EXACT_ID.name },
            matchedName = bound.count { it.matchKind == MatchKind.NORMALIZED_NAME.name },
            matchedFuzzy = bound.count { it.matchKind == MatchKind.FUZZY.name },
            matchedManual = bound.count { it.matchKind == MatchKind.MANUAL.name },
            unmatched = channels.size - bound.size,
        )
    }

    private fun Programme.shifted(offsetMinutes: Int): Programme =
        if (offsetMinutes == 0) this
        else copy(
            startUtc = TimeModel.shift(startUtc, offsetMinutes),
            endUtc = TimeModel.shift(endUtc, offsetMinutes),
        )

    companion object {
        /** Upper bound on the matcher's candidate set; guides beyond this are rare. */
        const val MAX_EPG_CHANNELS = 20_000
    }
}
