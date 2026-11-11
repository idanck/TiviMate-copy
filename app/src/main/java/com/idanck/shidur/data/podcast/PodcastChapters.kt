package com.idanck.shidur.data.podcast

import com.idanck.shidur.data.net.HttpFactory
import com.idanck.shidur.data.net.RequestProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * An episode's chapters, from the JSON file its `podcast:chapters` points at (ADR-067): the
 * Podcasting 2.0 format, `{"version": "1.2.0", "chapters": [{"startTime": 0, "title": "…"}]}`.
 * Chapters marked `toc: false` are not listed, as the format asks.
 */
object PodcastChapters {

    data class Chapter(val startMs: Long, val title: String)

    suspend fun fetch(url: String): List<Chapter> = withContext(Dispatchers.IO) {
        runCatching {
            val profile = RequestProfile(connectTimeoutSeconds = 10, readTimeoutSeconds = 15)
            HttpFactory.client(profile).newCall(HttpFactory.request(url, profile)).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body ?: return@use emptyList()
                if (body.contentLength() > MAX_BYTES) return@use emptyList()
                parse(body.string())
            }
        }.getOrDefault(emptyList())
    }

    fun parse(raw: String): List<Chapter> {
        val array = runCatching { JSONObject(raw).optJSONArray("chapters") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Chapter>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.has("toc") && !o.optBoolean("toc", true)) continue
            val start = o.optDouble("startTime", Double.NaN)
            if (start.isNaN() || start < 0) continue
            val title = o.optString("title").trim()
            if (title.isEmpty()) continue
            out += Chapter((start * 1000).toLong(), title)
        }
        return out.sortedBy { it.startMs }.distinctBy { it.startMs }
    }

    /** The chapter playing at [positionMs]: the last one that starts at or before it. */
    fun at(chapters: List<Chapter>, positionMs: Long): Int = chapters.indexOfLast { it.startMs <= positionMs }

    private const val MAX_BYTES = 512 * 1024
}
