package com.idanck.shidur.data.tasks

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
 * Whatever the owner of the device still has to do, for the radio screensaver to print
 * (FR-UI-32).
 *
 * A near-copy of [com.idanck.shidur.data.featured.FeaturedRepository], and deliberately so:
 * that class already settled every question this one raises — fetch on the default request
 * profile rather than a source's (a playlist's "allow insecure TLS" concession has no business
 * here), keep the raw document in preferences so the list survives a cold start, and rate-limit
 * on a stored timestamp rather than a timer that dies with the process.
 *
 * The two differences are both about what the document is. It is refreshed more often, because a
 * task list is something a person edits during the day and a promotional rail is not; and it is
 * fetched as text rather than as JSON, because [TaskFeed] reads Markdown too.
 */
class TaskRepository(private val settings: Settings) {

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<TaskFeed> = _state

    /** One fetch at a time: a screensaver arming and a settings screen can ask within a second. */
    private val lock = Mutex()

    /**
     * Refreshes the list if it is due, or unconditionally when [force].
     *
     * Returns `Ok` in the two cases that are not failures — no address configured, and nothing
     * due yet — so a caller that only wants to know whether the network went wrong can treat
     * every `Err` as one.
     */
    suspend fun refresh(force: Boolean): Outcome<Unit> = lock.withLock {
        val snapshot = settings.state.value
        val url = snapshot.taskFeedUrl.trim()
        if (url.isEmpty()) {
            // Clearing here as well as in the setter covers an address removed by a settings
            // restore, which writes the preference without going through it.
            if (_state.value.items.isNotEmpty()) _state.value = EMPTY
            return@withLock Outcome.Ok(Unit)
        }
        if (!url.startsWith("https://", ignoreCase = true)) {
            return@withLock Outcome.Err(AppError(AppError.Kind.ParseFailed, "task feed: not https"))
        }
        val age = System.currentTimeMillis() - snapshot.taskLastCheckUtc
        if (!force && snapshot.taskLastCheckUtc > 0 && age in 0 until CHECK_INTERVAL_MS) {
            return@withLock Outcome.Ok(Unit)
        }

        return@withLock when (val fetched = withContext(Dispatchers.IO) { fetch(url) }) {
            is Outcome.Ok -> {
                val (feed, raw) = fetched.value
                settings.rememberTasks(System.currentTimeMillis(), raw)
                _state.value = feed
                Outcome.Ok(Unit)
            }
            // The previous list stays on screen. A screen that empties itself because the host
            // was briefly unreachable is worse than one showing this morning's list.
            is Outcome.Err -> fetched
        }
    }

    private fun fetch(url: String): Outcome<Pair<TaskFeed, String>> {
        val profile = RequestProfile(connectTimeoutSeconds = 15, readTimeoutSeconds = 20)
        return try {
            HttpFactory.client(profile)
                .newCall(HttpFactory.request(url, profile))
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return Outcome.Err(AppError.fromHttp(response.code))
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) Outcome.Err(AppError(AppError.Kind.EmptyResponse))
                    else TaskFeed.parse(body).map { it to body }
                }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Outcome.Err(AppError.of(t))
        }
    }

    /**
     * The last document that parsed, read back at construction.
     *
     * Without this the screensaver would have nothing to show until its first fetch returned —
     * which is the moment it is arming, on a television nobody is sitting in front of, quite
     * possibly with no network at all.
     */
    private fun restore(): TaskFeed {
        val snapshot = settings.state.value
        if (snapshot.taskFeedUrl.isBlank()) return EMPTY
        val cached = snapshot.taskLastFeed ?: return EMPTY
        return TaskFeed.parse(cached).getOrNull() ?: EMPTY
    }

    companion object {
        private val EMPTY = TaskFeed(null, emptyList())

        /**
         * How stale the list is allowed to get. Half an hour: a task ticked off in the morning
         * should be gone from the screen by lunchtime, and a television left on the radio all
         * day should still cost two requests an hour rather than one a minute.
         */
        const val CHECK_INTERVAL_MS = 30L * 60 * 1000
    }
}
