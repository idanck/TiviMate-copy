package com.idanck.shidur.data.news

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * A news source the owner of the device added in Settings (FR-UI-33): an address, and whether
 * its headlines are shown. Switching one off keeps it in the list, so a source somebody wants
 * back tomorrow does not have to be typed again on a remote.
 */
data class NewsSource(val url: String, val enabled: Boolean = true) {

    companion object {

        /** More than a television screen can tell apart by colour, and plenty to scroll. */
        const val MAX_SOURCES = 8

        /**
         * The list as preferences store it: a JSON array, so the backup file (FR-SET-08), which
         * copies every preference verbatim, carries it without knowing what it is.
         */
        fun encode(sources: List<NewsSource>): String = JSONArray().apply {
            sources.forEach { put(JSONObject().put("url", it.url).put("on", it.enabled)) }
        }.toString()

        /** Anything unreadable is an empty list: a corrupt preference must not take the screen down. */
        fun decode(raw: String?): List<NewsSource> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url").trim()
                    if (url.isEmpty()) continue
                    add(NewsSource(url, obj.optBoolean("on", true)))
                }
            }.distinctBy { it.url }.take(MAX_SOURCES)
        }

        /** HTTPS only, as the task list and the featured rail are: a headline is not worth a cleartext request. */
        fun acceptable(url: String): Boolean {
            val trimmed = url.trim()
            if (!trimmed.startsWith("https://", ignoreCase = true)) return false
            return runCatching { URI(trimmed).host }.getOrNull()?.isNotBlank() == true
        }
    }
}

/** What one source last said, kept so the screen has headlines before its first fetch returns. */
data class NewsDigest(val title: String?, val items: List<NewsFlash>, val fetchedUtc: Long)

/** A headline on its way to the screen: which source it came from, and that source's colour. */
data class SourcedFlash(
    val flash: NewsFlash,
    val source: String,
    /** The source's place in the settings list, which is what picks its colour. */
    val colourIndex: Int,
)

object NewsDigests {

    /** One document for every source, keyed by address, in the shape [decode] reads back. */
    fun encode(digests: Map<String, NewsDigest>): String = JSONObject().apply {
        put("version", 1)
        put(
            "sources",
            JSONArray().apply {
                digests.forEach { (url, digest) ->
                    put(
                        JSONObject()
                            .put("url", url)
                            .put("title", digest.title ?: "")
                            .put("at", digest.fetchedUtc)
                            .put(
                                "items",
                                JSONArray().apply {
                                    digest.items.forEach { item ->
                                        put(
                                            JSONObject()
                                                .put("id", item.id)
                                                .put("title", item.title)
                                                .put("t", item.publishedUtc ?: 0L),
                                        )
                                    }
                                },
                            ),
                    )
                }
            },
        )
    }.toString()

    fun decode(raw: String?): Map<String, NewsDigest> {
        if (raw.isNullOrBlank()) return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        if (json.optInt("version", 0) != 1) return emptyMap()
        val array = json.optJSONArray("sources") ?: return emptyMap()
        return buildMap {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                if (url.isEmpty()) continue
                val items = obj.optJSONArray("items")
                val flashes = buildList {
                    if (items != null) {
                        for (j in 0 until items.length()) {
                            val item = items.optJSONObject(j) ?: continue
                            val title = item.optString("title").trim()
                            if (title.isEmpty()) continue
                            val at = item.optLong("t", 0L)
                            add(NewsFlash(item.optString("id").ifEmpty { "$j:$title" }, title, at.takeIf { it > 0 }))
                        }
                    }
                }
                put(url, NewsDigest(obj.optString("title").trim().ifEmpty { null }, flashes, obj.optLong("at", 0L)))
            }
        }
    }

    /**
     * Every enabled source's headlines as one list, newest first — what the screensaver scrolls.
     *
     * Each source keeps at most [perSource], so one site that publishes forty items an hour
     * cannot push the others off the screen; a headline older than [maxAgeMs] is dropped, so a
     * source that stopped updating on Friday does not keep printing Friday's news under
     * Sunday's clock; and the same story carried by two sources is printed once. A headline
     * with no date is kept, after every dated one.
     */
    fun merge(
        sources: List<NewsSource>,
        digests: Map<String, NewsDigest>,
        nowUtc: Long,
        perSource: Int = PER_SOURCE,
        max: Int = MAX_FLASHES,
        maxAgeMs: Long = MAX_AGE_MS,
    ): List<SourcedFlash> {
        val all = buildList {
            sources.forEachIndexed { index, source ->
                if (!source.enabled) return@forEachIndexed
                val digest = digests[source.url] ?: return@forEachIndexed
                val label = label(source, digest)
                digest.items.asSequence()
                    .filter { item ->
                        val at = item.publishedUtc ?: return@filter true
                        nowUtc - at in -FUTURE_SLACK_MS..maxAgeMs
                    }
                    .take(perSource)
                    .forEach { add(SourcedFlash(it, label, index)) }
            }
        }
        // Stable, so undated headlines keep the order their source gave them.
        return all.sortedByDescending { it.flash.publishedUtc ?: Long.MIN_VALUE }
            .distinctBy { it.flash.title.lowercase(Locale.ROOT) }
            .take(max)
    }

    /**
     * What a source is called on screen: its own name for itself, cut to the part before the
     * slogan ("ynet - חדשות, כלכלה, ספורט…" is "ynet"), or its host before it has been read.
     */
    fun label(source: NewsSource, digest: NewsDigest?): String {
        val title = digest?.title?.let { shorten(it) }?.takeIf { it.isNotBlank() }
        return title ?: host(source.url)
    }

    internal fun shorten(title: String): String {
        val first = SLOGAN.split(title).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: title.trim()
        return if (first.length <= MAX_LABEL) first else first.take(MAX_LABEL - 1).trimEnd() + "…"
    }

    internal fun host(url: String): String {
        val host = runCatching { URI(url.trim()).host }.getOrNull().orEmpty()
        return host.removePrefix("www.").ifEmpty { url.trim() }
    }

    const val PER_SOURCE = 8
    const val MAX_FLASHES = 16
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val FUTURE_SLACK_MS = 60L * 60 * 1000
    private const val MAX_LABEL = 22

    /** The separators sites put between their name and their slogan. */
    private val SLOGAN = Regex("""\s+[-–—|:]\s+|\s*\|\s*""")
}
