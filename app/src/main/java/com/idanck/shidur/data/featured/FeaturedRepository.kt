package com.idanck.shidur.data.featured

import com.idanck.shidur.core.AppError
import com.idanck.shidur.core.Outcome
import com.idanck.shidur.data.net.HttpFactory
import com.idanck.shidur.data.net.RequestProfile
import com.idanck.shidur.data.prefs.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Whatever the owner currently wants promoted on the home screen (FR-UI-26).
 *
 * Deliberately the smallest thing that can be called a feature. There is no bundled feed, no
 * default address and no provider list: `Settings.featuredFeedUrl` starts empty, and while it is
 * empty this repository holds an empty list and the rail does not exist. The point is that the
 * plumbing — a setting, a fetch, a cache, a rail that appears when there is something in it — is
 * in place, so pointing it at a real source later is a one-line change on a device rather than a
 * new release.
 *
 * It follows [com.idanck.shidur.data.update.UpdateManager] closely, because that class already
 * answers every question this one raises: fetch on the default request profile rather than the
 * user's (a source's "allow insecure TLS" concession has no business here), keep the raw document
 * in preferences so a background fetch survives process death, and rate-limit by a stored
 * timestamp rather than by a timer that dies with the process.
 */
class FeaturedRepository(private val settings: Settings) {

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<List<FeaturedItem>> = _state

    /** One fetch at a time: the worker and a screen opening can ask within the same second. */
    private val lock = Mutex()

    /**
     * Refreshes the feed if it is due, or unconditionally when [force].
     *
     * Returns `Ok` in the two cases that are not failures — no address configured, and nothing
     * due yet — so a caller that only wants to know whether the network went wrong can treat
     * every `Err` as one.
     */
    suspend fun refresh(force: Boolean): Outcome<Unit> = lock.withLock {
        val snapshot = settings.state.value
        val url = snapshot.featuredFeedUrl.trim()
        if (url.isEmpty()) {
            // Nothing configured. Clearing here as well as in the setter covers a feed address
            // removed by a settings restore, which writes the preference without going through it.
            if (_state.value.isNotEmpty()) _state.value = emptyList()
            return@withLock Outcome.Ok(Unit)
        }
        if (!url.startsWith("https://", ignoreCase = true)) {
            return@withLock Outcome.Err(AppError(AppError.Kind.ParseFailed, "featured feed: not https"))
        }
        val age = System.currentTimeMillis() - snapshot.featuredLastCheckUtc
        if (!force && snapshot.featuredLastCheckUtc > 0 && age in 0 until CHECK_INTERVAL_MS) {
            return@withLock Outcome.Ok(Unit)
        }

        return@withLock when (val fetched = withContext(Dispatchers.IO) { fetch(url) }) {
            is Outcome.Ok -> {
                val (feed, raw) = fetched.value
                settings.rememberFeatured(System.currentTimeMillis(), raw)
                _state.value = feed.items
                Outcome.Ok(Unit)
            }
            // The previous feed stays on screen. A rail that empties itself because the host was
            // briefly unreachable is worse than one showing yesterday's promotion.
            is Outcome.Err -> fetched
        }
    }

    private fun fetch(url: String): Outcome<Pair<FeaturedFeed, String>> {
        val profile = RequestProfile(connectTimeoutSeconds = 15, readTimeoutSeconds = 20)
        return try {
            HttpFactory.client(profile)
                .newCall(HttpFactory.request(url, profile))
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return Outcome.Err(AppError.fromHttp(response.code))
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) Outcome.Err(AppError(AppError.Kind.EmptyResponse))
                    else FeaturedFeed.parse(body).map { it to body }
                }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Outcome.Err(AppError.of(t))
        }
    }

    /**
     * The last feed that parsed, read back at construction.
     *
     * Without this the rail would be empty for as long as the first fetch takes on every cold
     * start, which on a television is exactly the moment someone is looking at it.
     */
    private fun restore(): List<FeaturedItem> {
        val snapshot = settings.state.value
        if (snapshot.featuredFeedUrl.isBlank()) return emptyList()
        val cached = snapshot.featuredLastFeed ?: return emptyList()
        return FeaturedFeed.parse(cached).getOrNull()?.items.orEmpty()
    }

    companion object {
        /**
         * How stale a promotional rail is allowed to get. Shorter than the update check's day,
         * because this is editorial content someone may change in the morning, and long enough
         * that opening the home screen repeatedly costs one request, not one per visit.
         */
        const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
