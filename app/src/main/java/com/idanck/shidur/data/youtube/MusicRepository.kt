package com.idanck.shidur.data.youtube

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.idanck.shidur.data.account.AccountState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * The music tab's data (ADR-069).
 *
 * From YouTube, through the Cloud Functions and their caches: the popular chart (`ytmChart`), a
 * playlist or album and its songs (`ytmPlaylist`), the account's playlists and liked songs
 * (`ytmLibrary`), and giving a like on YouTube (`ytmRate`).
 *
 * Kept by Shidur for the account, since the official API has none of it: the songs liked in Shidur,
 * the albums saved, and what was played. They are kept on the device, and, while signed in, in the
 * account's own documents (`users/{uid}/musicLikes`, `musicAlbums`, `musicHistory`), listened to so
 * another device's changes arrive. On sign-in the first answer is also when what this device kept
 * signed out joins the account.
 *
 * @param functions the project's callables, or null in a build without a Firebase project.
 * @param firestore the account's database, or null likewise.
 * @param device "tv" or "phone", for the history.
 */
class MusicRepository(
    context: Context,
    private val functions: () -> FirebaseFunctions?,
    private val firestore: () -> FirebaseFirestore?,
    private val account: StateFlow<AccountState>,
    private val scope: CoroutineScope,
    private val device: () -> String,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _likes = MutableStateFlow(readLikes())
    /** Songs liked in Shidur, newest first. */
    val likes: StateFlow<List<LikedSong>> = _likes

    private val _albums = MutableStateFlow(readAlbums())
    /** Albums saved in Shidur, newest first. */
    val albums: StateFlow<List<SavedAlbum>> = _albums

    private val _history = MutableStateFlow(readHistory())
    /** What was played in Shidur, newest first. */
    val history: StateFlow<List<PlayedSong>> = _history

    private val _chart = MutableStateFlow<List<YtSong>>(emptyList())
    /** Israel's popular music. */
    val chart: StateFlow<List<YtSong>> = _chart

    private val _playlists = MutableStateFlow<List<YtMusicList>>(emptyList())
    /** The connected account's own playlists. */
    val playlists: StateFlow<List<YtMusicList>> = _playlists

    private val _youtubeLiked = MutableStateFlow<List<YtSong>>(emptyList())
    /** The connected account's liked videos, kept to music by the server. */
    val youtubeLiked: StateFlow<List<YtSong>> = _youtubeLiked

    private val _linked = MutableStateFlow(false)
    /** A YouTube account is connected to the Shidur account. */
    val linked: StateFlow<Boolean> = _linked

    private val _problem = MutableStateFlow<YtProblem?>(null)
    /** Why the last read from YouTube brought nothing, or null. */
    val problem: StateFlow<YtProblem?> = _problem

    private val _reconnect = MutableStateFlow(false)
    /** A like could not reach YouTube: the connection lacks the scope that gives likes (ADR-069). */
    val reconnect: StateFlow<Boolean> = _reconnect

    private val _recentSearches = MutableStateFlow(readRecent())
    /** What was searched for on this device, newest first (`TvYtMusicSearch`). */
    val recentSearches: StateFlow<List<String>> = _recentSearches

    /** Playlists and albums opened on this visit, so going back and forth costs nothing. */
    private val lists = HashMap<String, Pair<YtMusicList, List<YtSong>>>()

    private var uid: String? = null
    private val listeners = mutableListOf<ListenerRegistration>()

    fun start() {
        scope.launch {
            account.map { (it as? AccountState.SignedIn)?.uid }.distinctUntilChanged().collect { signedIn ->
                listeners.forEach { it.remove() }
                listeners.clear()
                uid = signedIn
                if (signedIn == null) {
                    _playlists.value = emptyList()
                    _youtubeLiked.value = emptyList()
                    _linked.value = false
                } else {
                    attach(signedIn)
                }
            }
        }
    }

    // ---- From YouTube ----

    suspend fun refreshChart() {
        runCatching { call("ytmChart") }
            .onSuccess { data ->
                _chart.value = YtMusicParse.songs((data as? Map<*, *>)?.get("songs"))
                _problem.value = null
            }
            .onFailure { if (it is YouTubeRepository.YtException) _problem.value = it.problem }
    }

    /**
     * The account's playlists and liked songs, when a YouTube account is connected. The server keeps
     * them for an hour and half an hour, so asking whenever the library opens costs YouTube little.
     */
    suspend fun refreshLibrary() {
        if (uid == null) return
        for (part in listOf("playlists", "liked")) {
            val data = runCatching { call("ytmLibrary", mapOf("part" to part)) as? Map<*, *> }.getOrNull() ?: continue
            val linkedNow = data["linked"] as? Boolean ?: false
            _linked.value = linkedNow
            if (!linkedNow) {
                _playlists.value = emptyList()
                _youtubeLiked.value = emptyList()
                return
            }
            if (part == "playlists") _playlists.value = YtMusicParse.lists(data["playlists"])
            else _youtubeLiked.value = YtMusicParse.songs(data["songs"])
        }
    }

    /**
     * A music search (`ytmSearch`): from the server's six-hour cache where anybody asked the same,
     * from YouTube otherwise, out of the same searches a day as the video tab's.
     */
    suspend fun search(query: String, kind: MusicSearchKind): Result<MusicSearchResult> = runCatching {
        YtMusicParse.search(call("ytmSearch", mapOf("q" to query.trim(), "kind" to kind.wire)))
    }

    /** Keeps [query] among the recent searches, on this device only. */
    fun noteSearch(query: String) {
        val next = addRecent(_recentSearches.value, query, MAX_RECENT)
        if (next == _recentSearches.value) return
        _recentSearches.value = next
        prefs.edit().putString(K_RECENT, JSONArray(next).toString()).apply()
    }

    fun clearRecentSearches() {
        _recentSearches.value = emptyList()
        prefs.edit().remove(K_RECENT).apply()
    }

    private fun readRecent(): List<String> = runCatching {
        val array = JSONArray(prefs.getString(K_RECENT, null) ?: return emptyList())
        (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() } }
    }.getOrDefault(emptyList())

    /** A playlist or an album and its songs, from this visit's cache or the server's. */
    suspend fun list(id: String): Result<Pair<YtMusicList, List<YtSong>>> {
        lists[id]?.let { return Result.success(it) }
        return runCatching {
            val data = call("ytmPlaylist", mapOf("id" to id)) as? Map<*, *>
            val list = YtMusicParse.list(data?.get("list")) ?: throw YouTubeRepository.YtException(YtProblem.NOT_FOUND)
            (list to YtMusicParse.songs(data?.get("songs"))).also { lists[id] = it }
        }
    }

    // ---- Kept by Shidur ----

    /** Liked in Shidur, or among the account's likes on YouTube. */
    fun isLiked(id: String): Boolean = _likes.value.any { it.song.id == id } || _youtubeLiked.value.any { it.id == id }

    /**
     * Likes [song], or takes the like back. Kept in Shidur, and with a YouTube account connected sent
     * to YouTube as well (ADR-069); a connection without the scope for it raises [reconnect].
     */
    fun toggleLike(song: YtSong) {
        val like = !isLiked(song.id)
        val now = System.currentTimeMillis()
        if (like) {
            val item = LikedSong(song, now)
            writeLikes(listOf(item) + _likes.value.filterNot { it.song.id == song.id })
            push { db, u -> likeDoc(db, u, song.id).set(likeData(item)) }
        } else {
            writeLikes(_likes.value.filterNot { it.song.id == song.id })
            _youtubeLiked.value = _youtubeLiked.value.filterNot { it.id == song.id }
            push { db, u -> likeDoc(db, u, song.id).delete() }
        }
        if (uid != null && _linked.value) {
            scope.launch {
                val data = runCatching { call("ytmRate", mapOf("id" to song.id, "like" to like)) as? Map<*, *> }.getOrNull()
                if (data?.get("reason") == "reconnect") _reconnect.value = true
                if (data?.get("rated") == true) _reconnect.value = false
            }
        }
    }

    /**
     * The connected YouTube account changed (connected, replaced or disconnected): what was read for
     * the one before is dropped and read again.
     */
    fun accountChanged() {
        _playlists.value = emptyList()
        _youtubeLiked.value = emptyList()
        _reconnect.value = false
        lists.clear()
        scope.launch { refreshLibrary() }
    }

    fun dismissReconnect() {
        _reconnect.value = false
    }

    fun isSaved(listId: String): Boolean = _albums.value.any { it.list.id == listId }

    /** Saves an album in Shidur's library, or takes it out. Not in YouTube Music: the API has no saved albums. */
    fun toggleAlbum(list: YtMusicList) {
        if (isSaved(list.id)) {
            writeAlbums(_albums.value.filterNot { it.list.id == list.id })
            push { db, u -> albumDoc(db, u, list.id).delete() }
        } else {
            val item = SavedAlbum(list, System.currentTimeMillis())
            writeAlbums(listOf(item) + _albums.value)
            push { db, u -> albumDoc(db, u, list.id).set(albumData(item)) }
        }
    }

    /**
     * Notes that [song] started playing, from the playlist or album [context]. The same song again
     * within a minute is not noted twice. The history keeps the newest [MAX_HISTORY].
     */
    fun notePlayed(song: YtSong, context: String?) {
        val now = System.currentTimeMillis()
        val last = _history.value.firstOrNull()
        if (last != null && last.song.id == song.id && now - last.playedAt < SAME_PLAY_MS) return
        val item = PlayedSong(song, now, device(), context)
        val all = listOf(item) + _history.value
        val kept = all.take(MAX_HISTORY)
        val dropped = all.drop(MAX_HISTORY)
        writeHistory(kept)
        push { db, u -> historyDoc(db, u, item.docId).set(historyData(item)) }
        dropped.forEach { d -> push { db, u -> historyDoc(db, u, d.docId).delete() } }
    }

    // ---- Sync ----

    private fun push(write: (FirebaseFirestore, String) -> Unit) {
        val u = uid ?: return
        val db = firestore() ?: return
        runCatching { write(db, u) }.onFailure { Log.w(TAG, "sync write failed", it) }
    }

    private fun attach(u: String) {
        val db = firestore() ?: return
        val user = db.collection("users").document(u)
        var firstLikes = true
        listeners += user.collection("musicLikes").addSnapshotListener { snap, error ->
            if (error != null || snap == null) return@addSnapshotListener
            val remote = snap.documents.mapNotNull { readLike(it) }
            if (firstLikes) {
                firstLikes = false
                val ids = remote.map { it.song.id }.toHashSet()
                val mine = _likes.value.filterNot { it.song.id in ids }
                mine.forEach { item -> push { d, uu -> likeDoc(d, uu, item.song.id).set(likeData(item)) } }
                writeLikes((remote + mine).sortedByDescending { it.likedAt })
            } else {
                writeLikes(remote.sortedByDescending { it.likedAt })
            }
        }
        var firstAlbums = true
        listeners += user.collection("musicAlbums").addSnapshotListener { snap, error ->
            if (error != null || snap == null) return@addSnapshotListener
            val remote = snap.documents.mapNotNull { readAlbum(it) }
            if (firstAlbums) {
                firstAlbums = false
                val ids = remote.map { it.list.id }.toHashSet()
                val mine = _albums.value.filterNot { it.list.id in ids }
                mine.forEach { item -> push { d, uu -> albumDoc(d, uu, item.list.id).set(albumData(item)) } }
                writeAlbums((remote + mine).sortedByDescending { it.savedAt })
            } else {
                writeAlbums(remote.sortedByDescending { it.savedAt })
            }
        }
        var firstHistory = true
        listeners += user.collection("musicHistory").addSnapshotListener { snap, error ->
            if (error != null || snap == null) return@addSnapshotListener
            val remote = snap.documents.mapNotNull { readPlayed(it) }
            if (firstHistory) {
                firstHistory = false
                val ids = remote.map { it.docId }.toHashSet()
                val mine = _history.value.filterNot { it.docId in ids }
                mine.forEach { item -> push { d, uu -> historyDoc(d, uu, item.docId).set(historyData(item)) } }
                writeHistory((remote + mine).sortedByDescending { it.playedAt }.take(MAX_HISTORY))
            } else {
                writeHistory(remote.sortedByDescending { it.playedAt }.take(MAX_HISTORY))
            }
        }
    }

    // ---- Local storage ----

    private fun writeLikes(list: List<LikedSong>) {
        _likes.value = list
        prefs.edit().putString(K_LIKES, JSONArray().apply { list.forEach { put(JSONObject().put("song", songJson(it.song)).put("at", it.likedAt)) } }.toString()).apply()
    }

    private fun writeAlbums(list: List<SavedAlbum>) {
        _albums.value = list
        prefs.edit().putString(K_ALBUMS, JSONArray().apply { list.forEach { put(JSONObject().put("list", listJson(it.list)).put("at", it.savedAt)) } }.toString()).apply()
    }

    private fun writeHistory(list: List<PlayedSong>) {
        _history.value = list
        prefs.edit().putString(
            K_HISTORY,
            JSONArray().apply {
                list.forEach {
                    put(JSONObject().put("song", songJson(it.song)).put("at", it.playedAt).put("device", it.device).put("context", it.context ?: ""))
                }
            }.toString(),
        ).apply()
    }

    private fun readLikes(): List<LikedSong> = readArray(K_LIKES) { o ->
        val song = songOf(o.optJSONObject("song")) ?: return@readArray null
        LikedSong(song, o.optLong("at"))
    }

    private fun readAlbums(): List<SavedAlbum> = readArray(K_ALBUMS) { o ->
        val list = listOfJson(o.optJSONObject("list")) ?: return@readArray null
        SavedAlbum(list, o.optLong("at"))
    }

    private fun readHistory(): List<PlayedSong> = readArray(K_HISTORY) { o ->
        val song = songOf(o.optJSONObject("song")) ?: return@readArray null
        PlayedSong(song, o.optLong("at"), o.optString("device", "tv"), o.optString("context").ifEmpty { null })
    }

    private fun <T> readArray(key: String, read: (JSONObject) -> T?): List<T> = runCatching {
        val array = JSONArray(prefs.getString(key, null) ?: return emptyList())
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(read) }
    }.getOrDefault(emptyList())

    /** A callable's answer, or a [YouTubeRepository.YtException] carrying what to show. */
    private suspend fun call(name: String, data: Map<String, Any?> = emptyMap()): Any? {
        val f = functions() ?: throw YouTubeRepository.YtException(YtProblem.UNAVAILABLE)
        return try {
            f.getHttpsCallable(name).call(data).await().getData()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$name failed", e)
            throw YouTubeRepository.YtException(problemOf(e))
        }
    }

    private fun problemOf(e: Exception): YtProblem {
        val f = e as? com.google.firebase.functions.FirebaseFunctionsException ?: return YtProblem.OFFLINE
        val reason = (f.details as? Map<*, *>)?.get("reason") as? String
        return when {
            reason == "quota" -> YtProblem.QUOTA
            reason == "search-quota" -> YtProblem.SEARCH_QUOTA
            reason == "not-found" -> YtProblem.NOT_FOUND
            f.code == com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED -> YtProblem.SIGNED_OUT
            f.code == com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND -> YtProblem.NOT_FOUND
            f.code == com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE && reason == null -> YtProblem.OFFLINE
            else -> YtProblem.OTHER
        }
    }

    companion object {
        private const val TAG = "Music"
        private const val PREFS = "ytmusic"
        private const val K_LIKES = "likes"
        private const val K_ALBUMS = "albums"
        private const val K_HISTORY = "history"
        private const val K_RECENT = "recentSearches"
        private const val MAX_RECENT = 8

        /** The history's length, on the device and in the account. */
        const val MAX_HISTORY = 300
        private const val SAME_PLAY_MS = 60_000L

        private fun likeDoc(db: FirebaseFirestore, uid: String, videoId: String) =
            db.collection("users").document(uid).collection("musicLikes").document(videoId)

        private fun albumDoc(db: FirebaseFirestore, uid: String, listId: String) =
            db.collection("users").document(uid).collection("musicAlbums").document(listId)

        private fun historyDoc(db: FirebaseFirestore, uid: String, id: String) =
            db.collection("users").document(uid).collection("musicHistory").document(id)

        /** The fields `firestore.rules` allows, each within its bound. */
        internal fun likeData(l: LikedSong): Map<String, Any?> = trackData(l.song) + mapOf(
            "likedUtc" to l.likedAt,
            "updatedUtc" to l.likedAt,
        )

        internal fun albumData(a: SavedAlbum): Map<String, Any?> = mapOf(
            "listId" to a.list.id,
            "title" to a.list.title.take(300),
            "artist" to a.list.owner.take(200),
            "thumb" to a.list.thumb?.takeIf { it.length <= 2048 },
            "count" to a.list.count,
            "savedUtc" to a.savedAt,
            "updatedUtc" to a.savedAt,
        )

        internal fun historyData(p: PlayedSong): Map<String, Any?> = trackData(p.song) + mapOf(
            "playedUtc" to p.playedAt,
            "device" to (if (p.device == "phone") "phone" else "tv"),
            "context" to p.context?.takeIf { it.length <= 64 },
            "updatedUtc" to p.playedAt,
        )

        private fun trackData(s: YtSong): Map<String, Any?> = mapOf(
            "videoId" to s.id,
            "title" to s.title.take(300),
            "artist" to s.artist.take(200),
            "channelId" to s.channelId.take(64),
            "thumb" to s.thumb?.takeIf { it.length <= 2048 },
            "duration" to s.duration,
        )

        private fun songOfDoc(d: DocumentSnapshot): YtSong? {
            val id = d.getString("videoId") ?: return null
            return YtSong(
                id = id,
                title = d.getString("title").orEmpty(),
                artist = d.getString("artist").orEmpty(),
                channelId = d.getString("channelId").orEmpty(),
                thumb = d.getString("thumb"),
                duration = d.getLong("duration")?.toInt(),
                embeddable = true,
            )
        }

        private fun readLike(d: DocumentSnapshot): LikedSong? {
            val song = songOfDoc(d) ?: return null
            return LikedSong(song, d.getLong("likedUtc") ?: 0L)
        }

        private fun readAlbum(d: DocumentSnapshot): SavedAlbum? {
            val id = d.getString("listId") ?: return null
            return SavedAlbum(
                YtMusicList(
                    id = id,
                    title = d.getString("title").orEmpty(),
                    owner = d.getString("artist").orEmpty(),
                    thumb = d.getString("thumb"),
                    count = d.getLong("count")?.toInt(),
                    album = true,
                ),
                d.getLong("savedUtc") ?: 0L,
            )
        }

        private fun readPlayed(d: DocumentSnapshot): PlayedSong? {
            val song = songOfDoc(d) ?: return null
            return PlayedSong(song, d.getLong("playedUtc") ?: 0L, d.getString("device") ?: "tv", d.getString("context"))
        }

        private fun songJson(s: YtSong): JSONObject = JSONObject()
            .put("id", s.id)
            .put("title", s.title)
            .put("artist", s.artist)
            .put("channelId", s.channelId)
            .put("thumb", s.thumb ?: "")
            .put("duration", s.duration ?: 0)

        private fun songOf(o: JSONObject?): YtSong? {
            o ?: return null
            val id = o.optString("id").ifEmpty { return null }
            return YtSong(
                id = id,
                title = o.optString("title"),
                artist = o.optString("artist"),
                channelId = o.optString("channelId"),
                thumb = o.optString("thumb").ifEmpty { null },
                duration = o.optInt("duration", 0).takeIf { it > 0 },
                embeddable = true,
            )
        }

        private fun listJson(l: YtMusicList): JSONObject = JSONObject()
            .put("id", l.id)
            .put("title", l.title)
            .put("owner", l.owner)
            .put("thumb", l.thumb ?: "")
            .put("count", l.count ?: -1)
            .put("album", l.album)

        private fun listOfJson(o: JSONObject?): YtMusicList? {
            o ?: return null
            val id = o.optString("id").ifEmpty { return null }
            return YtMusicList(
                id = id,
                title = o.optString("title"),
                owner = o.optString("owner"),
                thumb = o.optString("thumb").ifEmpty { null },
                count = o.optInt("count", -1).takeIf { it >= 0 },
                album = o.optBoolean("album", true),
            )
        }
    }
}
