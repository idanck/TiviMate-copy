package com.idanck.shidur.data.podcast

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.idanck.shidur.data.account.AccountState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * The podcasts' sync between one account's devices (ADR-067): the library and each show's choices,
 * every episode's mark, and the queue, in the account's own documents —
 *
 * - `users/{uid}/podcasts/{hash(feed)}` — `feedUrl`, `title`, `inLibrary`, the show's settings and
 *   `updatedUtc`;
 * - `users/{uid}/podcastMarks/{hash(feed, guid)}` — `feedUrl`, `guid` and the mark's fields;
 * - `users/{uid}/podcastState/queue` — `keys` (feed and guid, in order) and `updatedUtc`.
 *
 * While signed in, the three are listened to and what another device wrote is applied when it is
 * newer ([PodcastRepository.merge]); everything written here is sent, through the repository's
 * hooks. On sign-in the first answer of each listener is also when this device's newer state is
 * uploaded, so a library built signed out joins the account rather than being overwritten by it.
 */
class PodcastSync(
    private val repo: PodcastRepository,
    private val account: StateFlow<AccountState>,
    private val firestore: () -> FirebaseFirestore?,
    private val scope: CoroutineScope,
) {
    private var uid: String? = null
    private val listeners = mutableListOf<ListenerRegistration>()
    private var queueUpdatedUtc = 0L

    fun start() {
        repo.onMarkChanged = { key, mark -> push { db, u -> markDoc(db, u, key).set(markData(key, mark), SetOptions.merge()) } }
        repo.onLibraryChanged = { p -> push { db, u -> podcastDoc(db, u, p.feedUrl).set(podcastData(p), SetOptions.merge()) } }
        repo.onQueueChanged = { keys ->
            queueUpdatedUtc = System.currentTimeMillis()
            push { db, u -> queueDoc(db, u).set(queueData(keys, queueUpdatedUtc)) }
        }
        scope.launch {
            account.map { (it as? AccountState.SignedIn)?.uid }.distinctUntilChanged().collect { signedIn ->
                detach()
                uid = signedIn
                if (signedIn != null) attach(signedIn)
            }
        }
    }

    private fun push(write: (FirebaseFirestore, String) -> Unit) {
        val u = uid ?: return
        val db = firestore() ?: return
        runCatching { write(db, u) }.onFailure { Log.w(TAG, "sync write failed", it) }
    }

    private fun detach() {
        listeners.forEach { it.remove() }
        listeners.clear()
    }

    private fun attach(u: String) {
        val db = firestore() ?: return
        var firstPodcasts = true
        listeners += db.collection("users").document(u).collection("podcasts").addSnapshotListener { snap, error ->
            if (error != null || snap == null) return@addSnapshotListener
            val remote = snap.documents.mapNotNull(::readPodcast)
            val upload = firstPodcasts
            firstPodcasts = false
            scope.launch {
                remote.forEach { r -> repo.applyRemotePodcast(r.feedUrl, r.title, r.inLibrary, r.prefs, r.updatedUtc) }
                if (upload) {
                    val byFeed = remote.associateBy { it.feedUrl }
                    repo.allPodcasts()
                        .filter { local -> (byFeed[local.feedUrl]?.updatedUtc ?: -1) < local.choicesUtc }
                        .forEach { local -> push { d, uu -> podcastDoc(d, uu, local.feedUrl).set(podcastData(local), SetOptions.merge()) } }
                }
            }
        }
        var firstMarks = true
        listeners += db.collection("users").document(u).collection("podcastMarks").addSnapshotListener { snap, error ->
            if (error != null || snap == null) return@addSnapshotListener
            val remote = snap.documents.mapNotNull(::readMark)
            val upload = firstMarks
            firstMarks = false
            scope.launch {
                remote.forEach { (key, mark) -> repo.applyRemoteMark(key, mark) }
                if (upload) {
                    val byKey = remote.toMap()
                    repo.allMarks()
                        .filter { (key, mark) -> (byKey[key]?.updatedUtc ?: -1) < mark.updatedUtc }
                        .forEach { (key, mark) -> push { d, uu -> markDoc(d, uu, key).set(markData(key, mark), SetOptions.merge()) } }
                }
            }
        }
        listeners += queueDoc(db, u).addSnapshotListener { snap, error ->
            if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
            val updated = snap.getLong("updatedUtc") ?: 0
            if (updated <= queueUpdatedUtc) return@addSnapshotListener
            queueUpdatedUtc = updated
            val keys = (snap.get("keys") as? List<*>).orEmpty().mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val feed = m["feedUrl"] as? String ?: return@mapNotNull null
                val guid = m["guid"] as? String ?: return@mapNotNull null
                EpisodeKey(feed, guid)
            }
            scope.launch { repo.applyRemoteQueue(keys) }
        }
    }

    private data class RemotePodcast(val feedUrl: String, val title: String, val inLibrary: Boolean, val prefs: PodcastPrefs, val updatedUtc: Long)

    private fun readPodcast(d: DocumentSnapshot): RemotePodcast? {
        val feed = d.getString("feedUrl") ?: return null
        return RemotePodcast(
            feedUrl = feed,
            title = d.getString("title").orEmpty(),
            inLibrary = d.getBoolean("inLibrary") ?: true,
            prefs = PodcastPrefs(
                speed = (d.getDouble("speed") ?: 0.0).toFloat(),
                skipIntroSeconds = (d.getLong("skipIntroSeconds") ?: 0).toInt(),
                skipOutroSeconds = (d.getLong("skipOutroSeconds") ?: 0).toInt(),
                notifyNew = d.getBoolean("notifyNew") ?: false,
                oldestFirst = d.getBoolean("oldestFirst") ?: false,
            ),
            updatedUtc = d.getLong("updatedUtc") ?: 0,
        )
    }

    private fun readMark(d: DocumentSnapshot): Pair<EpisodeKey, EpisodeMark>? {
        val feed = d.getString("feedUrl") ?: return null
        val guid = d.getString("guid") ?: return null
        return EpisodeKey(feed, guid) to EpisodeMark(
            positionMs = d.getLong("positionMs") ?: 0,
            durationMs = d.getLong("durationMs") ?: 0,
            played = d.getBoolean("played") ?: false,
            favourite = d.getBoolean("favourite") ?: false,
            favouriteUtc = d.getLong("favouriteUtc") ?: 0,
            historyUtc = d.getLong("historyUtc") ?: 0,
            lastPlayedUtc = d.getLong("lastPlayedUtc") ?: 0,
            device = d.getString("device"),
            updatedUtc = d.getLong("updatedUtc") ?: 0,
        )
    }

    companion object {
        private const val TAG = "PodcastSync"

        private fun podcastDoc(db: FirebaseFirestore, uid: String, feedUrl: String) =
            db.collection("users").document(uid).collection("podcasts").document(docId(feedUrl))

        private fun markDoc(db: FirebaseFirestore, uid: String, key: EpisodeKey) =
            db.collection("users").document(uid).collection("podcastMarks").document(docId(key.feedUrl + "\n" + key.guid))

        private fun queueDoc(db: FirebaseFirestore, uid: String) =
            db.collection("users").document(uid).collection("podcastState").document("queue")

        /** A document id from an address (and guid): 40 hex characters of its SHA-256. */
        internal fun docId(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(40)

        internal fun podcastData(p: Podcast): Map<String, Any?> = mapOf(
            "feedUrl" to p.feedUrl,
            "title" to p.title.take(300),
            "inLibrary" to p.inLibrary,
            "speed" to p.prefs.speed.toDouble(),
            "skipIntroSeconds" to p.prefs.skipIntroSeconds,
            "skipOutroSeconds" to p.prefs.skipOutroSeconds,
            "notifyNew" to p.prefs.notifyNew,
            "oldestFirst" to p.prefs.oldestFirst,
            "updatedUtc" to p.choicesUtc,
        )

        internal fun markData(key: EpisodeKey, m: EpisodeMark): Map<String, Any?> = mapOf(
            "feedUrl" to key.feedUrl,
            "guid" to key.guid,
            "positionMs" to m.positionMs,
            "durationMs" to m.durationMs,
            "played" to m.played,
            "favourite" to m.favourite,
            "favouriteUtc" to m.favouriteUtc,
            "historyUtc" to m.historyUtc,
            "lastPlayedUtc" to m.lastPlayedUtc,
            "device" to m.device,
            "updatedUtc" to m.updatedUtc,
        )

        internal fun queueData(keys: List<EpisodeKey>, updatedUtc: Long): Map<String, Any?> = mapOf(
            "keys" to keys.take(MAX_QUEUE).map { mapOf("feedUrl" to it.feedUrl, "guid" to it.guid) },
            "updatedUtc" to updatedUtc,
        )

        /** The rules' ceiling on a queue document's list. */
        const val MAX_QUEUE = 200
    }
}
