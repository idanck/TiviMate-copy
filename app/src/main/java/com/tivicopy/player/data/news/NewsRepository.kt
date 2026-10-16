package com.tivicopy.player.data.news

import com.tivicopy.player.core.AppError
import com.tivicopy.player.core.Outcome
import com.tivicopy.player.data.net.HttpFactory
import com.tivicopy.player.data.net.RequestProfile
import com.tivicopy.player.data.prefs.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The headlines the screensaver scrolls beside its clock (FR-UI-33, ADR-037).
 *
 * Built on the terms [com.tivicopy.player.data.tasks.TaskRepository] settled: the default request
 * profile rather than a playlist's, what was last read kept in preferences so the screen has
 * something before the first fetch returns, and the rate limit on a stored timestamp rather than
 * a timer that dies with the process.
 *
 * Three differences, all because this is news. It is refreshed every ten minutes rather than
 * every half hour, because a headline an hour old is the one thing on this screen that has gone
 * off. There are several sources rather than one, fetched side by side, and one that fails keeps
 * its last headlines while the others update. And what is kept is the parsed headlines, not the
 * documents: a news feed is tens of kilobytes of descriptions and images for every line printed.
 */
class NewsRepository(private val settings: Settings) {

    private val _state = MutableStateFlow(NewsDigests.decode(settings.state.value.newsLastDigests))

    /** Every source's last headlines, by address. Only sources still in the settings list. */
    val state: StateFlow<Map<String, NewsDigest>> = _state

    /** One refresh at a time: the screensaver arming and the settings screen can ask together. */
    private val lock = Mutex()

    /**
     * Refreshes every enabled source if it is due, or unconditionally when [force].
     *
     * Also due, whatever the clock says, when an enabled source has never been read — a source
     * added a minute ago should not wait out the rest of somebody else's ten minutes.
     *
     * `Ok` when nothing was due, when there are no sources, and when at least one source was
     * read; `Err` only when every source failed, with the first failure.
     */
    suspend fun refresh(force: Boolean): Outcome<Unit> = lock.withLock {
        val snapshot = settings.state.value
        val listed = snapshot.newsSources.map { it.url }.toSet()
        val enabled = snapshot.newsSources.filter { it.enabled && NewsSource.acceptable(it.url) }
        // Sources removed in Settings, or by a restore that wrote the preference directly, take
        // their headlines with them.
        val kept = _state.value.filterKeys { it in listed }
        if (kept.size != _state.value.size) {
            _state.value = kept
            settings.rememberNews(snapshot.newsLastCheckUtc, NewsDigests.encode(kept))
        }
        if (enabled.isEmpty()) return@withLock Outcome.Ok(Unit)

        val age = System.currentTimeMillis() - snapshot.newsLastCheckUtc
        val unread = enabled.any { it.url !in kept }
        if (!force && !unread && snapshot.newsLastCheckUtc > 0 && age in 0 until CHECK_INTERVAL_MS) {
            return@withLock Outcome.Ok(Unit)
        }

        val results = coroutineScope {
            enabled.map { source -> async(Dispatchers.IO) { source.url to fetch(source.url) } }.awaitAll()
        }
        val now = System.currentTimeMillis()
        val next = kept.toMutableMap()
        var firstError: AppError? = null
        var anyRead = false
        for ((url, outcome) in results) {
            when (outcome) {
                is Outcome.Ok -> {
                    next[url] = NewsDigest(outcome.value.title, outcome.value.items, now)
                    anyRead = true
                }
                // The last headlines this source gave stay up. A screen that empties a column
                // because one site was briefly unreachable is worse than one an hour behind.
                is Outcome.Err -> if (firstError == null) firstError = outcome.error
            }
        }
        _state.value = next
        settings.rememberNews(now, NewsDigests.encode(next))
        val error = firstError
        if (anyRead || error == null) Outcome.Ok(Unit) else Outcome.Err(error)
    }

    /** Reads one source. */
    private fun fetch(url: String): Outcome<NewsFeed> {
        val profile = RequestProfile(connectTimeoutSeconds = 15, readTimeoutSeconds = 20)
        return try {
            HttpFactory.client(profile)
                .newCall(HttpFactory.request(url, profile))
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return Outcome.Err(AppError.fromHttp(response.code))
                    val body = response.body ?: return Outcome.Err(AppError(AppError.Kind.EmptyResponse))
                    if (body.contentLength() > MAX_BYTES) {
                        return Outcome.Err(AppError(AppError.Kind.UnsupportedFormat, "news feed: too large"))
                    }
                    val bytes = body.bytes()
                    if (bytes.isEmpty()) return Outcome.Err(AppError(AppError.Kind.EmptyResponse))
                    NewsFeed.parse(bytes, body.contentType()?.charset()?.name())
                }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Outcome.Err(AppError.of(t))
        }
    }

    companion object {
        /**
         * How stale the headlines are allowed to get. Ten minutes: news an hour old is the one
         * thing on this screen that has gone off, and a television left on all night should
         * still cost six requests an hour per source rather than one a minute.
         */
        const val CHECK_INTERVAL_MS = 10L * 60 * 1000

        /** A feed is a few hundred kilobytes at most; anything far larger is not one. */
        private const val MAX_BYTES = 4L * 1024 * 1024
    }
}
