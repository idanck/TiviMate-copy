package com.idanck.shidur.data.podcast

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** A podcast found in Podcast Index, before it is added (`pcSearch`, `pcTrending`, ADR-067). */
data class FoundPodcast(
    val indexId: Long,
    val title: String,
    val author: String?,
    val imageUrl: String?,
    val feedUrl: String,
    val language: String?,
    val categories: List<String>,
    val description: String?,
    val episodeCount: Int?,
    val newestUtc: Long?,
)

/** Why the directory gave nothing, for the screen's message. */
enum class DirectoryProblem {
    /** No Firebase project in this build, or the server's Podcast Index key is not set yet. */
    NOT_CONFIGURED,
    OFFLINE,
    /** Too many searches in a short time. */
    BUSY,
    OTHER,
}

/** An answer, or why there is none. */
sealed interface DirectoryResult {
    data class Found(val podcasts: List<FoundPodcast>) : DirectoryResult
    data class Problem(val problem: DirectoryProblem) : DirectoryResult
}

/**
 * Podcast Index through the project's callables (ADR-067): search by name or host, and the popular
 * rail. The app never holds the key; the server keeps every answer for hours and shares it.
 *
 * @param functions the project's callables, or null in a build without a Firebase project — then
 *   every call answers [DirectoryProblem.NOT_CONFIGURED], and adding by link still works.
 */
class PodcastDirectory(private val functions: () -> FirebaseFunctions?) {

    suspend fun search(query: String): DirectoryResult {
        val q = query.trim()
        if (q.length < 2) return DirectoryResult.Found(emptyList())
        return call("pcSearch", mapOf("q" to q))
    }

    /** The popular rail, Hebrew shows; [category] is one of Podcast Index's category names. */
    suspend fun trending(category: String? = null): DirectoryResult =
        call("pcTrending", buildMap { if (category != null) put("cat", category) })

    private suspend fun call(name: String, data: Map<String, Any?>): DirectoryResult {
        val f = functions() ?: return DirectoryResult.Problem(DirectoryProblem.NOT_CONFIGURED)
        return try {
            val answer = f.getHttpsCallable(name).call(data).await().getData() as? Map<*, *>
            DirectoryResult.Found(parse(answer?.get("podcasts")))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$name failed", e)
            DirectoryResult.Problem(problemOf(e))
        }
    }

    companion object {
        private const val TAG = "PodcastDirectory"

        internal fun parse(raw: Any?): List<FoundPodcast> = (raw as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val feedUrl = m["feedUrl"] as? String ?: return@mapNotNull null
            val title = m["title"] as? String ?: return@mapNotNull null
            FoundPodcast(
                indexId = (m["id"] as? Number)?.toLong() ?: 0,
                title = title,
                author = m["author"] as? String,
                imageUrl = m["image"] as? String,
                feedUrl = feedUrl,
                language = m["language"] as? String,
                categories = (m["categories"] as? List<*>).orEmpty().filterIsInstance<String>(),
                description = m["description"] as? String,
                episodeCount = (m["episodeCount"] as? Number)?.toInt(),
                newestUtc = (m["newestUtc"] as? Number)?.toLong(),
            )
        }

        internal fun problemOf(e: Exception): DirectoryProblem {
            val f = e as? FirebaseFunctionsException ?: return DirectoryProblem.OFFLINE
            val reason = (f.details as? Map<*, *>)?.get("reason") as? String
            return when {
                reason == "not-configured" || f.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION -> DirectoryProblem.NOT_CONFIGURED
                f.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> DirectoryProblem.BUSY
                f.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
                    f.code == FirebaseFunctionsException.Code.INTERNAL ||
                    f.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> DirectoryProblem.OFFLINE
                else -> DirectoryProblem.OTHER
            }
        }
    }
}
