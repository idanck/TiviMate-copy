package com.idanck.shidur.data.youtube

import android.content.Context
import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * The YouTube channels this device keeps and what is on them (ADR-061).
 *
 * The channels are kept here, on the device, and need no YouTube account: "הערוצים שלך". What is
 * on them comes from the `ytChannels` callable, which answers from a cache shared by every user and
 * fetched again only once it is ten minutes old, so opening the screen again and again costs
 * YouTube nothing. "Continue watching" is kept here too, and never leaves the device.
 *
 * @param functions the project's callables, or null in a build without a Firebase project, where
 *   every call answers [YtProblem.UNAVAILABLE] and nothing else changes.
 */
class YouTubeRepository(
    context: Context,
    private val functions: () -> FirebaseFunctions?,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _saved = MutableStateFlow(readSaved())
    /** The saved channels' ids, in the order they were added. */
    val saved: StateFlow<List<String>> = _saved

    private val _channels = MutableStateFlow<Map<String, YtChannelState>>(emptyMap())
    /** What is on each saved channel, once fetched; channels opened from elsewhere too. */
    val channels: StateFlow<Map<String, YtChannelState>> = _channels

    private val _problem = MutableStateFlow<YtProblem?>(null)
    /** Why the last refresh brought nothing, or null. */
    val problem: StateFlow<YtProblem?> = _problem

    private val _stale = MutableStateFlow(false)
    /** The server answered from an old cache because the day's quota is gone. */
    val stale: StateFlow<Boolean> = _stale

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _history = MutableStateFlow(readHistory())
    /** Watched here, newest first. */
    val history: StateFlow<List<YtWatched>> = _history

    private val _listed = MutableStateFlow(readListed())
    /** The YouTube channels in Shidur's channel list, by number. */
    val listed: StateFlow<List<YtListed>> = _listed

    private val _subs = MutableStateFlow<YtSubsState>(YtSubsState.Hidden)
    /** The subscriptions rail: hidden, an invitation to connect, or the feed. */
    val subs: StateFlow<YtSubsState> = _subs

    private val refreshing = Mutex()
    @Volatile private var refreshedAt = 0L

    /**
     * Asks for the saved channels again, unless that was done less than [REFRESH_EVERY_MS] ago
     * and [force] is false. The server's cache decides what it costs YouTube.
     */
    suspend fun refresh(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - refreshedAt < REFRESH_EVERY_MS && _channels.value.isNotEmpty()) return
        refreshing.withLock {
            val ids = _saved.value
            if (ids.isEmpty()) {
                _problem.value = null
                return
            }
            _loading.value = true
            try {
                val f = functions() ?: run {
                    _problem.value = YtProblem.UNAVAILABLE
                    return
                }
                val data = f.getHttpsCallable("ytChannels").call(mapOf("ids" to ids.take(MAX_SAVED))).await().getData() as? Map<*, *>
                val states = YtParse.states(data?.get("channels"))
                _channels.value = _channels.value + states.associateBy { it.channel.id }
                _stale.value = data?.get("stale") as? Boolean ?: false
                _problem.value = null
                refreshedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ytChannels failed", e)
                _problem.value = problemOf(e)
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * A channel from what the viewer typed or pasted: a channel id, "@handle" or a youtube.com
     * address. Not saved by this; [save] does that.
     */
    suspend fun resolve(input: String): Result<YtChannelState> = try {
        val f = functions() ?: throw YtException(YtProblem.UNAVAILABLE)
        val data = f.getHttpsCallable("ytResolve").call(mapOf("input" to input.trim())).await().getData() as? Map<*, *>
        val state = YtParse.state(data?.get("channel")) ?: throw YtException(YtProblem.NOT_FOUND)
        _channels.value = _channels.value + (state.channel.id to state)
        Result.success(state)
    } catch (e: CancellationException) {
        throw e
    } catch (e: YtException) {
        Result.failure(e)
    } catch (e: Exception) {
        Log.w(TAG, "ytResolve failed", e)
        Result.failure(YtException(problemOf(e)))
    }

    /** One channel's state, fetched if this device has not seen it yet (a channel page opened from a card). */
    suspend fun channel(id: String): YtChannelState? {
        _channels.value[id]?.let { return it }
        return try {
            val f = functions() ?: return null
            val data = f.getHttpsCallable("ytChannels").call(mapOf("ids" to listOf(id))).await().getData() as? Map<*, *>
            YtParse.states(data?.get("channels")).firstOrNull()?.also { _channels.value = _channels.value + (id to it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ytChannels for one channel failed", e)
            null
        }
    }

    /** Search: from the server's six-hour cache where anybody asked the same, from YouTube otherwise. */
    suspend fun search(query: String, kind: YtSearchKind): Result<YtSearchResult> = runCall {
        YtParse.search(call("ytSearch", mapOf("q" to query, "kind" to kind.wire)))
    }

    /**
     * Brings the subscriptions rail up to date. With no Shidur account signed in, or no television
     * route set up on the server, there is nothing to offer and the rail stays hidden; a connection
     * that existed and is gone (revoked at Google, or expired) invites connecting again.
     */
    suspend fun refreshSubscriptions(signedIn: Boolean, phone: Boolean = false) {
        if (!signedIn) {
            _subs.value = YtSubsState.Hidden
            return
        }
        var linkedNow = false
        try {
            val status = YtParse.linkStatus(call("ytLinkStatus"))
            linkedNow = status.linked
            if (!status.linked) {
                // Each shell connects its own way: the television by a code, the phone by Google's
                // account picker. The rail invites only where that way is set up on the server.
                val ready = if (phone) status.phoneReady else status.tvReady
                _subs.value = if (ready) YtSubsState.Invite(revoked = prefs.getBoolean(K_WAS_LINKED, false)) else YtSubsState.Hidden
                return
            }
            val subs = YtParse.subscriptions(call("ytSubscriptions"))
            if (!subs.linked) {
                _subs.value = YtSubsState.Invite(revoked = true)
                return
            }
            prefs.edit().putBoolean(K_WAS_LINKED, true).apply()
            if (subs.stale) _stale.value = true
            _subs.value = YtSubsState.Feed(subs.feed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: YtException) {
            if (e.problem == YtProblem.QUOTA) _stale.value = true
            _subs.value = when {
                e.problem == YtProblem.SIGNED_OUT -> YtSubsState.Hidden
                // Connected, but the feed did not come: what was shown stays, or the rail says the
                // feed could not be loaded. Never the invitation to connect an account that is.
                linkedNow -> (_subs.value as? YtSubsState.Feed) ?: YtSubsState.Feed(emptyList(), failed = true)
                else -> _subs.value
            }
        }
    }

    /** The television asks Google for a code to show (the flow for TVs and limited-input devices). */
    suspend fun linkStart(): Result<YtLinkCode> = runCall {
        YtParse.linkCode(call("ytLinkStart")) ?: throw YtException(YtProblem.OTHER)
    }

    /** One poll: has the code been approved yet? */
    suspend fun linkPoll(): Result<YtLinkPoll> = runCall {
        YtParse.linkPoll(call("ytLinkPoll")).also { if (it == YtLinkPoll.LINKED) prefs.edit().putBoolean(K_WAS_LINKED, true).apply() }
    }

    /** The phone hands over the one-time code Google's authorization returned for the web client. */
    suspend fun linkWithCode(code: String): Result<Unit> = runCall {
        call("ytLinkCode", mapOf("code" to code))
        prefs.edit().putBoolean(K_WAS_LINKED, true).apply()
        Unit
    }

    /** A callable's answer, or a [YtException] carrying what to show. */
    private suspend fun call(name: String, data: Map<String, Any?> = emptyMap()): Any? {
        val f = functions() ?: throw YtException(YtProblem.UNAVAILABLE)
        return try {
            f.getHttpsCallable(name).call(data).await().getData()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$name failed", e)
            throw YtException(problemOf(e))
        }
    }

    private inline fun <T> runCall(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: YtException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(YtException(YtProblem.OTHER))
    }

    fun isListed(id: String): Boolean = _listed.value.any { it.id == id }

    /**
     * Puts [state]'s channel in Shidur's channel list with the lowest number free from 900, and
     * saves it too, so its live stream is kept up to date with the rest.
     */
    fun addToList(state: YtChannelState) {
        if (isListed(state.channel.id)) return
        save(state.channel.id)
        val taken = _listed.value.map { it.number }.toSet()
        val number = generateSequence(YouTubeChannels.FIRST_NUMBER) { it + 1 }.first { it !in taken }
        writeListed((_listed.value + YtListed(state.channel.id, number, state.channel.title, state.channel.thumb)).sortedBy { it.number })
    }

    fun removeFromList(id: String) = writeListed(_listed.value.filterNot { it.id == id })

    private fun readListed(): List<YtListed> = runCatching {
        val array = JSONArray(prefs.getString(K_LISTED, null) ?: return emptyList())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            YtListed(id, o.optInt("number", 0), o.optString("title"), o.optString("thumb").ifEmpty { null })
        }.filter { it.number > 0 }.sortedBy { it.number }
    }.getOrDefault(emptyList())

    private fun writeListed(list: List<YtListed>) {
        _listed.value = list
        val array = JSONArray()
        for (l in list) array.put(JSONObject().put("id", l.id).put("number", l.number).put("title", l.title).put("thumb", l.thumb ?: ""))
        prefs.edit().putString(K_LISTED, array.toString()).apply()
    }

    fun isSaved(id: String): Boolean = id in _saved.value

    fun save(id: String) {
        if (id in _saved.value || _saved.value.size >= MAX_SAVED) return
        writeSaved(_saved.value + id)
    }

    fun remove(id: String) = writeSaved(_saved.value - id)

    fun toggleSaved(id: String) = if (isSaved(id)) remove(id) else save(id)

    /** Where [video] was left, in seconds, or 0. */
    fun positionOf(videoId: String): Int = _history.value.firstOrNull { it.video.id == videoId }?.position ?: 0

    /**
     * Notes that [video] was watched up to [position] seconds. A live stream is kept with no
     * position; a video watched to within a minute of its end is dropped, as finished.
     */
    fun noteWatched(video: YtVideo, position: Int) {
        val rest = _history.value.filterNot { it.video.id == video.id }
        val finished = video.duration != null && video.duration - position < FINISHED_WITHIN_S
        val next = if (finished || video.live || video.upcoming) rest else listOf(YtWatched(video, position, System.currentTimeMillis())) + rest
        val trimmed = next.take(MAX_HISTORY)
        _history.value = trimmed
        prefs.edit().putString(K_HISTORY, historyJson(trimmed)).apply()
    }

    private fun readSaved(): List<String> {
        if (!prefs.getBoolean(K_SEEDED, false)) {
            prefs.edit().putBoolean(K_SEEDED, true).putString(K_SAVED, DEFAULT_CHANNELS.joinToString("\n")).apply()
        }
        return prefs.getString(K_SAVED, null).orEmpty().split('\n').filter { it.isNotBlank() }
    }

    private fun writeSaved(ids: List<String>) {
        _saved.value = ids
        prefs.edit().putString(K_SAVED, ids.joinToString("\n")).apply()
    }

    private fun readHistory(): List<YtWatched> = runCatching {
        val array = JSONArray(prefs.getString(K_HISTORY, null) ?: return emptyList())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val v = o.optJSONObject("video") ?: return@mapNotNull null
            val video = YtVideo(
                id = v.optString("id").ifEmpty { return@mapNotNull null },
                title = v.optString("title"),
                channelId = v.optString("channelId"),
                channelTitle = v.optString("channelTitle"),
                thumb = v.optString("thumb").ifEmpty { null },
                publishedAt = v.optLong("publishedAt", 0L).takeIf { it > 0 },
                duration = v.optInt("duration", 0).takeIf { it > 0 },
                live = false,
                upcoming = false,
                startedAt = null,
                scheduledAt = null,
                viewers = null,
                embeddable = true,
            )
            YtWatched(video, o.optInt("position", 0), o.optLong("at", 0L))
        }
    }.getOrDefault(emptyList())

    private fun historyJson(list: List<YtWatched>): String = JSONArray().apply {
        for (w in list) {
            val v = w.video
            put(
                JSONObject()
                    .put("video", JSONObject().apply {
                        put("id", v.id)
                        put("title", v.title)
                        put("channelId", v.channelId)
                        put("channelTitle", v.channelTitle)
                        put("thumb", v.thumb ?: "")
                        put("publishedAt", v.publishedAt ?: 0L)
                        put("duration", v.duration ?: 0)
                    })
                    .put("position", w.position)
                    .put("at", w.watchedAt),
            )
        }
    }.toString()

    private fun problemOf(e: Exception): YtProblem {
        val f = e as? FirebaseFunctionsException ?: return YtProblem.OFFLINE
        val reason = (f.details as? Map<*, *>)?.get("reason") as? String
        return when {
            reason == "quota" -> YtProblem.QUOTA
            reason == "search-quota" -> YtProblem.SEARCH_QUOTA
            reason == "not-found" || reason == "not-a-channel" -> YtProblem.NOT_FOUND
            reason == "not-configured" -> YtProblem.NOT_CONFIGURED
            f.code == FirebaseFunctionsException.Code.UNAUTHENTICATED -> YtProblem.SIGNED_OUT
            f.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> YtProblem.OTHER
            f.code == FirebaseFunctionsException.Code.NOT_FOUND || f.code == FirebaseFunctionsException.Code.INVALID_ARGUMENT -> YtProblem.NOT_FOUND
            f.code == FirebaseFunctionsException.Code.UNAVAILABLE && reason == null -> YtProblem.OFFLINE
            f.code == FirebaseFunctionsException.Code.INTERNAL || f.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> YtProblem.OFFLINE
            else -> YtProblem.OTHER
        }
    }

    /** A failure carrying the problem to show. */
    class YtException(val problem: YtProblem) : Exception(problem.name)

    companion object {
        private const val TAG = "YouTube"
        private const val PREFS = "youtube"
        private const val K_SAVED = "saved"
        private const val K_SEEDED = "seeded"
        private const val K_HISTORY = "history"
        private const val K_WAS_LINKED = "was_linked"
        private const val K_LISTED = "listed"

        /** The server takes at most 50 ids a call. */
        const val MAX_SAVED = 50
        private const val MAX_HISTORY = 20
        private const val FINISHED_WITHIN_S = 60
        private const val REFRESH_EVERY_MS = 2 * 60 * 1000L

        /**
         * What a new device starts with, so the screen is not empty the first time: the Israeli
         * news channels' own YouTube channels, by id (looked up through `ytSearch` and `ytResolve`
         * on 2026-11-09). The viewer removes or adds to them freely.
         */
        val DEFAULT_CHANNELS = listOf(
            "UC_HwfTAcjBESKZRJq6BTCpg", // כאן | חדשות
            "UCisowXt5wZkp2sR3rFh9lnQ", // i24NEWS עברית
            "UCZH1ZFHSLSZEh_Ek2L-vrgA", // החדשות (N12)
            "UCKEImtWikw9usC1pl_9m1nQ", // C14
            "UCKqFqiCe1dCUxRe0_YNZ6gg", // כאן 11
        )
    }
}
