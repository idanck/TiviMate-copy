package com.idanck.shidur.data.xmltv

import android.util.Xml
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.model.EpgChannel
import com.idanck.shidur.data.model.Programme
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Progress callback for the guide import (FR-EPG-06). */
fun interface XmltvProgress {
    fun onProgress(channels: Int, programmes: Int)
}

/**
 * Pull-parser XMLTV reader.
 *
 * Guides in this category routinely run to hundreds of megabytes uncompressed, so nothing is
 * accumulated: each `<channel>` and `<programme>` is handed to the sink and dropped
 * (NFR-PERF-05). The caller owns the stream and any gzip/zip wrapper around it.
 */
class XmltvParser(
    private val onChannel: (EpgChannel) -> Unit,
    private val onProgramme: (Programme) -> Unit,
    private val progress: XmltvProgress? = null,
    private val progressEvery: Int = 2_000,
) {

    /** @return the number of programmes read. */
    fun parse(input: InputStream): Int {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channels = 0
        var programmes = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        readChannel(parser)?.let { onChannel(it); channels++ }
                    }
                    "programme" -> {
                        readProgramme(parser)?.let { onProgramme(it); programmes++ }
                        if (progress != null && programmes % progressEvery == 0) {
                            progress.onProgress(channels, programmes)
                        }
                    }
                }
            }
            event = parser.next()
        }
        progress?.onProgress(channels, programmes)
        return programmes
    }

    private fun readChannel(parser: XmlPullParser): EpgChannel? {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        val names = ArrayList<String>(2)
        var icon: String? = null

        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                if (parser.eventType == XmlPullParser.END_DOCUMENT) break
                continue
            }
            when (parser.name) {
                "display-name" -> text(parser)?.let { if (it.isNotBlank()) names += it }
                "icon" -> icon = parser.getAttributeValue(null, "src")?.trim()?.takeIf { it.isNotBlank() }
                else -> skip(parser)
            }
        }
        if (id.isBlank()) return null
        return EpgChannel(id = id, displayNames = names, iconUrl = icon)
    }

    private fun readProgramme(parser: XmlPullParser): Programme? {
        val channelId = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = TimeModel.parseXmltvTime(parser.getAttributeValue(null, "start").orEmpty())
        val stop = TimeModel.parseXmltvTime(parser.getAttributeValue(null, "stop").orEmpty())

        var title: String? = null
        var subtitle: String? = null
        var description: String? = null
        var category: String? = null
        var icon: String? = null
        var rating: String? = null
        var episodeId: String? = null
        var season: Int? = null
        var episode: Int? = null

        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                if (parser.eventType == XmlPullParser.END_DOCUMENT) break
                continue
            }
            when (parser.name) {
                "title" -> if (title == null) title = text(parser) else skip(parser)
                "sub-title" -> if (subtitle == null) subtitle = text(parser) else skip(parser)
                "desc" -> if (description == null) description = text(parser) else skip(parser)
                "category" -> if (category == null) category = text(parser) else skip(parser)
                "icon" -> {
                    if (icon == null) icon = parser.getAttributeValue(null, "src")?.trim()
                    skip(parser)
                }
                "rating" -> {
                    val system = parser.getAttributeValue(null, "system")
                    readRatingValue(parser)?.let { v ->
                        if (rating == null) rating = if (system.isNullOrBlank()) v else "$system:$v"
                    }
                }
                "episode-num" -> {
                    val system = parser.getAttributeValue(null, "system")?.lowercase()
                    val value = text(parser)?.trim()
                    if (!value.isNullOrBlank()) {
                        if (episodeId == null) episodeId = value
                        if (system == "xmltv_ns") {
                            parseXmltvNs(value)?.let { (s, e) -> season = s; episode = e }
                        } else if (system == "onscreen" && season == null) {
                            parseOnScreen(value)?.let { (s, e) -> season = s; episode = e }
                        }
                    }
                }
                else -> skip(parser)
            }
        }

        if (channelId.isBlank() || start == null || title.isNullOrBlank()) return null
        // A missing `stop` is common; assume a 30-minute slot rather than dropping the entry.
        val end = stop?.takeIf { it > start } ?: (start + 30 * 60_000L)

        return Programme(
            epgChannelId = channelId,
            startUtc = start,
            endUtc = end,
            title = title,
            subtitle = subtitle,
            description = description,
            category = category,
            iconUrl = icon,
            seasonNumber = season,
            episodeNumber = episode,
            episodeId = episodeId,
            rating = rating,
        )
    }

    private fun readRatingValue(parser: XmlPullParser): String? {
        var value: String? = null
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                if (parser.eventType == XmlPullParser.END_DOCUMENT) break
                continue
            }
            if (parser.name == "value" && value == null) value = text(parser) else skip(parser)
        }
        return value
    }

    /** Reads the text of the current element and consumes its END_TAG. */
    private fun text(parser: XmlPullParser): String? {
        val sb = StringBuilder()
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            when (parser.eventType) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> skip(parser)
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim().takeIf { it.isNotEmpty() }
            }
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    /** Skips the current element and everything inside it. */
    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    companion object {
        /** `xmltv_ns` is zero-based and shaped `season . episode . part`. */
        internal fun parseXmltvNs(value: String): Pair<Int, Int>? {
            val parts = value.split('.')
            if (parts.size < 2) return null
            val season = parts[0].substringBefore('/').trim().toIntOrNull()?.plus(1)
            val episode = parts[1].substringBefore('/').trim().toIntOrNull()?.plus(1)
            if (season == null && episode == null) return null
            return (season ?: 0) to (episode ?: 0)
        }

        /** `onscreen` is free text; `S02E05` and `2x05` cover almost everything seen. */
        internal fun parseOnScreen(value: String): Pair<Int, Int>? {
            Regex("(?i)s\\s*(\\d{1,3})\\s*e\\s*(\\d{1,4})").find(value)?.let { m ->
                return m.groupValues[1].toInt() to m.groupValues[2].toInt()
            }
            Regex("^(\\d{1,3})\\s*x\\s*(\\d{1,4})$").find(value.trim())?.let { m ->
                return m.groupValues[1].toInt() to m.groupValues[2].toInt()
            }
            return null
        }
    }
}
