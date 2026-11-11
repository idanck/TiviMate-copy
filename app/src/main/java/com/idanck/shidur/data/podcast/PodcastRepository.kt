package com.idanck.shidur.data.podcast

import com.idanck.shidur.core.AppError
import com.idanck.shidur.core.Outcome
import com.idanck.shidur.data.net.HttpFactory
import com.idanck.shidur.data.net.RequestProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The podcasts (ADR-067): the library, its feeds, and the viewer's marks on the episodes.
 *
 * Every write bumps [changes], which the screens watch to read again; [onMarkChanged] and
 * [onLibraryChanged] are where the account's sync listens (ADR-067, Sync), so a mark written here is
 * on its way to the viewer's other devices without the screen knowing anything about it.
 *
 * @param device what the history calls this device: "tv" or "phone".
 */
class PodcastRepository(
    private val dao: PodcastDao,
    private val device: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetcher: FeedFetcher = HttpFeedFetcher(),
) {

    private val _changes = MutableStateFlow(0L)

    /** Bumped on every write; a screen that shows podcasts reads again when it moves. */
    val changes: StateFlow<Long> = _changes

    /** Set by the account's sync. Called after the write, off the main thread. */
    @Volatile var onMarkChanged: ((EpisodeKey, EpisodeMark) -> Unit)? = null
    @Volatile var onLibraryChanged: ((Podcast) -> Unit)? = null
    @Volatile var onQueueChanged: ((List<EpisodeKey>) -> Unit)? = null

    private val refreshLock = Mutex()

    private fun changed() {
        _changes.value = _changes.value + 1
    }

    // ---------------------------------------------------------------- library

    suspend fun library(): List<Podcast> = io { dao.podcasts(libraryOnly = true) }
    suspend fun podcast(id: Long): Podcast? = io { dao.podcast(id) }
    suspend fun episodes(podcastId: Long, filter: EpisodeFilter): List<PodcastEpisode> = io {
        val oldestFirst = dao.podcast(podcastId)?.prefs?.oldestFirst ?: false
        dao.episodes(podcastId, filter, oldestFirst)
    }
    suspend fun episode(id: Long): PodcastEpisode? = io { dao.episode(id) }
    suspend fun episode(key: EpisodeKey): PodcastEpisode? = io { dao.episode(key) }
    suspend fun newEpisodes(limit: Int = 30): List<PodcastEpisode> = io { dao.newEpisodes(limit) }
    suspend fun inProgress(limit: Int = 30): List<PodcastEpisode> = io { dao.inProgress(limit) }
    suspend fun favourites(): List<PodcastEpisode> = io { dao.favourites() }
    suspend fun history(): List<PodcastEpisode> = io { dao.history() }
    suspend fun queue(): List<PodcastEpisode> = io { dao.queue() }
    suspend fun search(text: String): List<PodcastEpisode> = io { dao.search(text) }

    /**
     * Reads a feed and keeps the podcast: in the library with [inLibrary], or only known (an
     * episode opened from search). An address that is already known is read again. The address is
     * the one the viewer gave, normalised; a feed that moved (HTTP 301) is kept under where it went.
     */
    suspend fun add(feedUrl: String, inLibrary: Boolean = true, indexId: Long? = null): Outcome<Podcast> = io {
        val url = normalise(feedUrl) ?: return@io Outcome.Err(AppError(AppError.Kind.UnsupportedFormat, "not a feed address"))
        when (val fetched = fetcher.fetch(url, null, null)) {
            is Outcome.Err -> fetched
            is Outcome.Ok -> {
                val result = fetched.value as? FetchResult.Fresh
                    ?: return@io Outcome.Err(AppError(AppError.Kind.EmptyResponse, "feed sent nothing"))
                val now = clock()
                val finalUrl = result.finalUrl ?: url
                val id = dao.upsertPodcast(finalUrl, result.feed, inLibrary, now, indexId)
                dao.mergeFeed(id, finalUrl, result.feed.episodes)
                dao.markRefresh(id, now, ok = true, message = null, etag = result.etag, lastModified = result.lastModified)
                val podcast = dao.podcast(id)
                changed()
                podcast?.let { p -> onLibraryChanged?.invoke(p) }
                podcast?.let { Outcome.Ok(it) } ?: Outcome.Err(AppError(AppError.Kind.Unknown, "podcast not stored"))
            }
        }
    }

    suspend fun setInLibrary(id: Long, inLibrary: Boolean) = io {
        dao.setInLibrary(id, inLibrary, clock())
        val p = dao.podcast(id)
        if (!inLibrary) dao.pruneOrphan(id)
        changed()
        p?.let { onLibraryChanged?.invoke(it.copy(inLibrary = inLibrary)) }
    }

    suspend fun setPrefs(id: Long, prefs: PodcastPrefs) = io {
        dao.setPrefs(id, prefs, clock())
        changed()
        dao.podcast(id)?.let { onLibraryChanged?.invoke(it) }
    }

    /**
     * Reads every feed in the library that is due ([REFRESH_INTERVAL_MS]), or all of them with
     * [force]. Feeds are read one after another: a library is tens of feeds, not thousands, and a
     * podcast host that sees one request at a time from a device is a host that does not throttle it.
     * `Err` only when every feed read failed.
     */
    suspend fun refreshAll(force: Boolean): Outcome<Int> = refreshLock.withLock {
        io {
            val now = clock()
            val due = dao.podcasts(libraryOnly = true).filter { force || now - it.lastRefreshUtc >= REFRESH_INTERVAL_MS }
            var read = 0
            var firstError: AppError? = null
            for (p in due) {
                when (val r = refreshOne(p)) {
                    is Outcome.Ok -> read++
                    is Outcome.Err -> if (firstError == null) firstError = r.error
                }
            }
            if (read > 0) changed()
            if (due.isNotEmpty() && read == 0 && firstError != null) Outcome.Err(firstError) else Outcome.Ok(read)
        }
    }

    suspend fun refresh(id: Long): Outcome<Unit> = io {
        val p = dao.podcast(id) ?: return@io Outcome.Err(AppError(AppError.Kind.NotFound, "no podcast $id"))
        refreshOne(p).map { changed() }
    }

    private fun refreshOne(p: Podcast): Outcome<Unit> {
        val (etag, lastModified) = dao.validators(p.id)
        val now = clock()
        return when (val fetched = fetcher.fetch(p.feedUrl, etag, lastModified)) {
            is Outcome.Err -> {
                dao.markRefresh(p.id, now, ok = false, message = fetched.error.detail ?: fetched.error.kind.name, etag = null, lastModified = null)
                fetched
            }
            is Outcome.Ok -> when (val r = fetched.value) {
                FetchResult.NotModified -> {
                    dao.markRefresh(p.id, now, ok = true, message = null, etag = etag, lastModified = lastModified)
                    Outcome.Ok(Unit)
                }
                is FetchResult.Fresh -> {
                    dao.upsertPodcast(p.feedUrl, r.feed, inLibrary = null, nowUtc = now)
                    dao.mergeFeed(p.id, p.feedUrl, r.feed.episodes)
                    dao.markRefresh(p.id, now, ok = true, message = null, etag = r.etag, lastModified = r.lastModified)
                    Outcome.Ok(Unit)
                }
            }
        }
    }

    // ------------------------------------------------------------------ marks

    /**
     * Where the player got to. Called as it plays (every few seconds), on pause and on leaving.
     * Within [PLAYED_TAIL_MS] of the end — or the show's own outro skip — the episode counts as
     * played and its position goes back to the start, so a second listen begins at the beginning.
     */
    suspend fun savePosition(key: EpisodeKey, positionMs: Long, durationMs: Long) = io {
        val now = clock()
        val outroMs = (dao.podcastByFeed(key.feedUrl)?.prefs?.skipOutroSeconds ?: 0) * 1000L
        val tail = maxOf(PLAYED_TAIL_MS, outroMs)
        val finished = durationMs > 0 && positionMs >= durationMs - tail
        val mark = dao.updateMark(key) {
            it.copy(
                positionMs = if (finished) 0 else positionMs.coerceAtLeast(0),
                durationMs = if (durationMs > 0) durationMs else it.durationMs,
                played = finished || (it.played && positionMs <= 0),
                historyUtc = if (it.historyUtc > 0) it.historyUtc else now,
                lastPlayedUtc = now,
                device = device(),
                updatedUtc = now,
            )
        }
        if (finished) dao.dequeue(key)
        changed()
        onMarkChanged?.invoke(key, mark)
    }

    suspend fun setPlayed(key: EpisodeKey, played: Boolean) = editMark(key) {
        it.copy(played = played, positionMs = 0)
    }

    suspend fun setFavourite(key: EpisodeKey, favourite: Boolean) = editMark(key) {
        it.copy(favourite = favourite, favouriteUtc = if (favourite) clock() else 0)
    }

    suspend fun removeFromHistory(key: EpisodeKey) = editMark(key) { it.copy(historyUtc = 0) }

    suspend fun clearHistory() = io {
        val now = clock()
        val before = dao.allMarks().filter { it.second.historyUtc > 0 }
        dao.clearHistory(now)
        changed()
        before.forEach { (key, mark) -> onMarkChanged?.invoke(key, mark.copy(historyUtc = 0, updatedUtc = now)) }
    }

    private suspend fun editMark(key: EpisodeKey, change: (EpisodeMark) -> EpisodeMark) = io {
        val now = clock()
        val mark = dao.updateMark(key) { change(it).copy(updatedUtc = now) }
        changed()
        onMarkChanged?.invoke(key, mark)
        mark
    }

    // ------------------------------------------------------------------ queue

    suspend fun enqueue(key: EpisodeKey, next: Boolean) = io {
        dao.enqueue(key, next)
        changed()
        onQueueChanged?.invoke(dao.queueKeys())
    }

    suspend fun dequeue(key: EpisodeKey) = io {
        dao.dequeue(key)
        changed()
        onQueueChanged?.invoke(dao.queueKeys())
    }

    /**
     * What plays after [current] ends: the head of the queue, else the next episode of the same
     * show in the order it is listed (newer, for a show listed newest first). Null at the end.
     */
    suspend fun nextAfter(current: EpisodeKey): PodcastEpisode? = io {
        dao.queueKeys().firstOrNull { it != current }?.let { dao.episode(it) }
            ?: run {
                val here = dao.episode(current) ?: return@run null
                val oldestFirst = dao.podcast(here.podcastId)?.prefs?.oldestFirst ?: false
                val list = dao.episodes(here.podcastId, EpisodeFilter.ALL, oldestFirst)
                val i = list.indexOfFirst { it.guid == current.guid }
                // Newest first on the page means the next one to hear is the newer, the row above.
                val next = if (oldestFirst) list.getOrNull(i + 1) else list.getOrNull(i - 1)
                next?.takeIf { i >= 0 && it.status != EpisodeStatus.PLAYED }
            }
    }

    // ------------------------------------------------------------ for the sync

    /** Writes what another device sent, without telling the sync back. */
    suspend fun applyRemoteMark(key: EpisodeKey, mark: EpisodeMark) = io {
        val local = dao.mark(key)
        val merged = merge(local, mark)
        if (merged != local) {
            dao.putMark(key, merged)
            changed()
        }
    }

    suspend fun applyRemoteQueue(keys: List<EpisodeKey>) = io {
        dao.setQueue(keys)
        changed()
    }

    suspend fun allMarks(): List<Pair<EpisodeKey, EpisodeMark>> = io { dao.allMarks() }
    suspend fun allPodcasts(): List<Podcast> = io { dao.podcasts(libraryOnly = false) }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000
        const val PLAYED_TAIL_MS = 30_000L

        /**
         * The newer of two marks, by [EpisodeMark.updatedUtc]. A tie keeps the local one. The
         * whole mark moves together: position, played and the star were set by one person at one
         * moment, and mixing fields from two moments would invent a state nobody chose.
         */
        fun merge(local: EpisodeMark?, remote: EpisodeMark): EpisodeMark =
            if (local == null || remote.updatedUtc > local.updatedUtc) remote else local

        /**
         * A feed address as the viewer typed or pasted it: trimmed, `feed://` and `itpc://` (what
         * some sites link to) read as `https://`, a bare host given `https://`. Null when it is
         * not a web address at all.
         */
        fun normalise(raw: String): String? {
            var s = raw.trim()
            if (s.isEmpty()) return null
            val lower = s.lowercase(Locale.ROOT)
            s = when {
                lower.startsWith("feed://") -> "https://" + s.substring(7)
                lower.startsWith("itpc://") -> "https://" + s.substring(7)
                lower.startsWith("pcast://") -> "https://" + s.substring(8)
                lower.startsWith("feed:") && !lower.startsWith("feed://") -> s.substring(5)
                else -> s
            }
            if (!s.contains("://")) s = "https://$s"
            val scheme = s.substringBefore("://").lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return null
            val host = s.substringAfter("://").substringBefore('/').substringBefore('?')
            if (host.isEmpty() || ' ' in host || '.' !in host && host != "localhost") return null
            return s
        }
    }
}

/** What reading a feed gave. */
sealed interface FetchResult {
    data class Fresh(val feed: PodcastFeed, val etag: String?, val lastModified: String?, val finalUrl: String?) : FetchResult
    data object NotModified : FetchResult
}

/** Reads a feed; an interface so the tests can hand the repository documents without a network. */
fun interface FeedFetcher {
    fun fetch(url: String, etag: String?, lastModified: String?): Outcome<FetchResult>
}

/** Over OkHttp, with the default request profile, `If-None-Match` and `If-Modified-Since`. */
class HttpFeedFetcher : FeedFetcher {
    override fun fetch(url: String, etag: String?, lastModified: String?): Outcome<FetchResult> {
        val profile = RequestProfile(connectTimeoutSeconds = 15, readTimeoutSeconds = 30)
        val request = HttpFactory.request(url, profile).newBuilder().apply {
            if (!etag.isNullOrEmpty()) header("If-None-Match", etag)
            if (!lastModified.isNullOrEmpty()) header("If-Modified-Since", lastModified)
        }.build()
        return try {
            HttpFactory.client(profile).newCall(request).execute().use { response ->
                if (response.code == 304) return Outcome.Ok(FetchResult.NotModified)
                if (!response.isSuccessful) return Outcome.Err(AppError.fromHttp(response.code, null))
                val body = response.body ?: return Outcome.Err(AppError(AppError.Kind.EmptyResponse))
                if (body.contentLength() > MAX_BYTES) return Outcome.Err(AppError(AppError.Kind.ParseFailed, "feed too large"))
                val bytes = body.byteStream().use { input -> input.readNBytesCompat(MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) return Outcome.Err(AppError(AppError.Kind.ParseFailed, "feed too large"))
                val final = response.request.url.toString().takeIf { it != url }
                PodcastFeed.parse(bytes, body.contentType()?.charset()?.name()).map {
                    FetchResult.Fresh(it, response.header("ETag"), response.header("Last-Modified"), final)
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Outcome.Err(AppError.of(t))
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - total))
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private companion object {
        /** A feed of a decade of daily episodes with full notes is a few megabytes; more is not a feed. */
        const val MAX_BYTES = 24 * 1024 * 1024
    }
}
