package com.tivicopy.player.data.news

import android.util.Xml
import com.tivicopy.player.core.AppError
import com.tivicopy.player.core.Outcome
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * One headline, as the screensaver prints it beside its clock (FR-UI-33, ADR-037).
 *
 * A time and a line of text, and nothing else: the screen is read from across a room by
 * somebody who is not sitting in front of it, so a summary, an image or a link would be
 * information the surface has no way to use.
 */
data class NewsFlash(
    /** Stable within one source; what the merged list de-duplicates on before the title. */
    val id: String,
    val title: String,
    /** When the source says it was published, or null when it gave no date this reader knows. */
    val publishedUtc: Long?,
)

/**
 * A news source's document, read: what it calls itself and its headlines, newest first as the
 * source ordered them.
 *
 * **Why there is no built-in source.** The same reason there is no built-in task list
 * (FR-UI-32): this app installs itself on other people's televisions, and which newsroom's
 * headlines scroll past on somebody's screen at night is theirs to choose. The setting ships
 * empty; the reader ships here.
 *
 * **The formats are the ones news sites actually publish**, recognised by their content rather
 * than by an extension or a content type:
 *
 * - *RSS 2.0* (`<rss><channel><item>`) and *RSS 1.0* (`<rdf:RDF><item>`) — what nearly every
 *   Israeli and international news site still serves;
 * - *Atom* (`<feed><entry>`);
 * - *JSON Feed* (`{"version":"https://jsonfeed.org/…","items":[…]}`), and any JSON document —
 *   or bare array — whose items carry a `title` (or `headline` / `text`) and a time, for a
 *   script that would rather emit a list than an XML dialect.
 *
 * Failure follows [com.tivicopy.player.data.tasks.TaskFeed]: one unreadable *item* is dropped and
 * the rest still show, and a document with nothing in it is an empty source rather than an
 * error. An HTML page — the usual result of pasting a site's address instead of its feed's — is
 * refused, so the settings screen can say so instead of silently showing nothing.
 */
data class NewsFeed(val title: String?, val items: List<NewsFlash>) {

    companion object {

        /** A ceiling on how much of one source is kept. The screen shows a dozen at most. */
        const val MAX_ITEMS = 20

        /** Reads a document from its bytes, in [charset] when the server named one. */
        fun parse(body: ByteArray, charset: String? = null): Outcome<NewsFeed> {
            val start = firstSignificant(body)
            if (start < 0) return Outcome.Ok(NewsFeed(null, emptyList()))
            return when (body[start].toInt().toChar()) {
                '{', '[' -> parseJson(decode(body, charset))
                else -> parseXml(body, charset)
            }
        }

        fun parse(raw: String): Outcome<NewsFeed> = parse(raw.toByteArray(Charsets.UTF_8), "UTF-8")

        // ------------------------------------------------------------------- xml

        private fun parseXml(body: ByteArray, charset: String?): Outcome<NewsFeed> = try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            // Relaxed, where the parser offers it: a feed with one `&nbsp;` the site forgot to
            // declare is still a feed, and refusing all of it over one entity is the wrong trade.
            runCatching { parser.setFeature(FEATURE_RELAXED, true) }
            // Null lets the parser read the encoding from the XML declaration, which is where an
            // RSS file says it is windows-1255; a header that names one is believed first.
            parser.setInput(ByteArrayInputStream(body), charset)
            readXml(parser)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Outcome.Err(AppError(AppError.Kind.ParseFailed, "news feed is not XML", t))
        }

        private fun readXml(parser: XmlPullParser): Outcome<NewsFeed> {
            var event = parser.next()
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return Outcome.Ok(NewsFeed(null, emptyList()))
            val root = local(parser.name)
            if (root !in FEED_ROOTS) {
                return Outcome.Err(AppError(AppError.Kind.UnsupportedFormat, "news feed: <$root> is not a feed"))
            }

            var feedTitle: String? = null
            val items = mutableListOf<NewsFlash>()
            // The names of the open elements, so "title" can be told apart by its parent: the
            // channel's title names the source, an item's names the headline, and an image's
            // names nothing anybody needs.
            val path = ArrayDeque<String>().apply { addLast(root) }
            while (true) {
                event = parser.next()
                when (event) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.END_TAG -> path.removeLastOrNull()
                    XmlPullParser.START_TAG -> {
                        val name = local(parser.name)
                        val parent = path.lastOrNull()
                        when {
                            name in ITEM_TAGS -> {
                                readItem(parser, items.size)?.let { if (items.size < MAX_ITEMS) items += it }
                            }
                            name == "title" && feedTitle == null && parent in FEED_TITLE_PARENTS -> {
                                feedTitle = clean(readText(parser)).ifEmpty { null }
                            }
                            else -> path.addLast(name)
                        }
                    }
                }
            }
            return Outcome.Ok(NewsFeed(feedTitle, items.distinctBy { it.id }))
        }

        /** One `<item>` or `<entry>`, read to its end tag. Null when it has nothing to print. */
        private fun readItem(parser: XmlPullParser, index: Int): NewsFlash? {
            var title = ""
            var description = ""
            var id = ""
            var link = ""
            var date: String? = null
            var depth = 1
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.START_TAG -> {
                        // Atom's link is an attribute, and an empty element; RSS's is text.
                        val href = parser.getAttributeValue(null, "href")
                        when (local(parser.name)) {
                            // The first of each wins, so an Atom entry's `<source><title>` —
                            // the name of the feed it was copied from — cannot replace its own.
                            "title" -> if (title.isEmpty()) title = readText(parser) else skip(parser)
                            "description", "summary" ->
                                if (description.isEmpty()) description = readText(parser) else skip(parser)
                            "guid", "id" -> if (id.isEmpty()) id = readText(parser) else skip(parser)
                            "link" -> if (href != null) {
                                if (link.isEmpty()) link = href
                                skip(parser)
                            } else {
                                link = readText(parser)
                            }
                            // The first date wins: `pubDate` and `published` come before
                            // `updated` in every feed this was written against, and a story's
                            // time is when it broke, not when somebody fixed a typo in it.
                            in DATE_TAGS -> {
                                val text = readText(parser)
                                if (date == null) date = text
                            }
                            else -> depth++
                        }
                    }
                }
            }
            val headline = clean(title).ifEmpty { clean(description).take(MAX_TITLE_FALLBACK) }
            if (headline.isEmpty()) return null
            val key = id.trim().ifEmpty { link.trim() }.ifEmpty { "$index:$headline" }
            return NewsFlash(id = key, title = headline, publishedUtc = date?.let { parseDate(it) })
        }

        /**
         * An element's text, to its end tag, with any markup inside it dropped rather than
         * thrown over. `nextText` would throw on an Atom `type="xhtml"` title, which is a
         * `<div>` with the words inside it.
         */
        private fun readText(parser: XmlPullParser): String {
            val out = StringBuilder()
            var depth = 1
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.TEXT -> out.append(parser.text)
                }
            }
            return out.toString()
        }

        private fun skip(parser: XmlPullParser) {
            var depth = 1
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> return
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                }
            }
        }

        /** `dc:date` is `date`, `atom:link` is `link`: the prefix is never what decides. */
        private fun local(name: String?): String = name.orEmpty().substringAfter(':').lowercase(Locale.ROOT)

        // ------------------------------------------------------------------ json

        private fun parseJson(raw: String): Outcome<NewsFeed> {
            val text = raw.removePrefix("\uFEFF").trim()
            val (title, array) = try {
                if (text.startsWith("[")) {
                    null to JSONArray(text)
                } else {
                    val json = JSONObject(text)
                    Pair(
                        json.optString("title").trim().ifEmpty { null },
                        JSON_LISTS.firstNotNullOfOrNull { json.optJSONArray(it) },
                    )
                }
            } catch (t: Throwable) {
                return Outcome.Err(AppError(AppError.Kind.ParseFailed, "news feed is not JSON", t))
            }
            if (array == null) return Outcome.Ok(NewsFeed(clean(title.orEmpty()).ifEmpty { null }, emptyList()))
            val items = buildList {
                for (index in 0 until array.length()) {
                    if (size >= MAX_ITEMS) break
                    val obj = array.optJSONObject(index) ?: continue
                    val headline = JSON_TITLES.firstNotNullOfOrNull { key ->
                        clean(obj.optString(key)).ifEmpty { null }
                    }?.take(MAX_TITLE_FALLBACK) ?: continue
                    val id = JSON_IDS.firstNotNullOfOrNull { key -> obj.optString(key).trim().ifEmpty { null } }
                        ?: "$index:$headline"
                    // `opt` and `toString` rather than `optString`, so a Unix timestamp given as
                    // a number reads the same as one given as a string.
                    val date = JSON_DATES.firstNotNullOfOrNull { key ->
                        obj.opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.ifEmpty { null }
                    }
                    add(NewsFlash(id = id, title = headline, publishedUtc = date?.let { parseDate(it) }))
                }
            }
            return Outcome.Ok(NewsFeed(clean(title.orEmpty()).ifEmpty { null }, items.distinctBy { it.id }))
        }

        // ----------------------------------------------------------------- dates

        /**
         * A feed's idea of a time, in UTC milliseconds; null when it is none this reader knows.
         *
         * RSS writes RFC 822 (`Thu, 15 Oct 2026 20:51:00 +0300`), Atom and JSON Feed write
         * ISO 8601, and a hand-made JSON list writes whatever its author thought of — so this
         * accepts those, a Unix timestamp in seconds or milliseconds, and a bare `20:51`, which
         * is taken as the most recent time the clock read that. A time with no zone is local:
         * the television and the newsroom it was pointed at are, in practice, in the same one.
         */
        internal fun parseDate(raw: String, zone: ZoneId = ZoneId.systemDefault(), nowUtc: Long = System.currentTimeMillis()): Long? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            if (s.all { it.isDigit() }) {
                val n = s.toLongOrNull() ?: return null
                return if (n >= MILLIS_THRESHOLD) n else n * 1000
            }
            BARE_TIME.matchEntire(s)?.let { m ->
                val time = runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
                    ?: return null
                val today = Instant.ofEpochMilli(nowUtc).atZone(zone).toLocalDate()
                val at = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
                // A headline at 23:50 read at 00:10 is from yesterday, not from the coming night.
                return if (at - nowUtc > FUTURE_SLACK_MS) at - DAY_MS else at
            }
            runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            runCatching { return Instant.parse(s).toEpochMilli() }
            runCatching { return LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }

            // RFC 822. The day name is dropped before parsing: it is optional, it is redundant,
            // and it is the part feeds most often get wrong ("Thurs,", a localised name).
            val bare = DAY_NAME.replace(s, "")
            val withOffset = ZONE_NAME.find(bare)?.let { m ->
                val offset = NAMED_ZONES[m.groupValues[1].uppercase(Locale.ROOT)]
                if (offset != null) bare.substring(0, m.range.first) + " " + offset else null
            } ?: bare
            runCatching { return ZonedDateTime.parse(withOffset, RFC_822).toInstant().toEpochMilli() }
            // A zone this table does not know — IST is Israel's to an Israeli newsroom and
            // India's to everybody else — is read as local time rather than as no time at all.
            val local = ZONE_NAME.find(bare)?.let { bare.substring(0, it.range.first).trim() } ?: bare
            runCatching { return LocalDateTime.parse(local, RFC_822_LOCAL).atZone(zone).toInstant().toEpochMilli() }
            return null
        }

        // ------------------------------------------------------------------ text

        /**
         * A headline, reduced to something a television can print: markup dropped, the HTML
         * entities feeds double-escape into their titles decoded, and everything on one line.
         */
        internal fun clean(text: String): String {
            var out = TAG.replace(text, " ")
            out = ENTITY.replace(out) { m -> decodeEntity(m.groupValues[1]) ?: m.value }
            // Again, for a title that escaped its markup twice and has just been unescaped once.
            out = TAG.replace(out, " ")
            out = WHITESPACE.replace(out, " ")
            return out.trim()
        }

        private fun decodeEntity(name: String): String? = when {
            name.startsWith("#x") || name.startsWith("#X") -> codePoint(name.substring(2).toIntOrNull(16))
            name.startsWith("#") -> codePoint(name.substring(1).toIntOrNull())
            else -> NAMED_ENTITIES[name]
        }

        /** Null for a number that is no character, which a feed is quite capable of writing. */
        private fun codePoint(value: Int?): String? =
            value?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }

        private fun firstSignificant(body: ByteArray): Int {
            var i = 0
            // A UTF-8 byte-order mark is not where a document starts.
            if (body.size >= 3 && body[0] == 0xEF.toByte() && body[1] == 0xBB.toByte() && body[2] == 0xBF.toByte()) i = 3
            while (i < body.size && body[i].toInt().toChar().isWhitespace()) i++
            return if (i < body.size) i else -1
        }

        private fun decode(body: ByteArray, charset: String?): String {
            val named = charset?.let { runCatching { Charset.forName(it) }.getOrNull() }
            return String(body, named ?: Charsets.UTF_8)
        }

        private const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"

        private val FEED_ROOTS = setOf("rss", "feed", "rdf")
        private val FEED_TITLE_PARENTS = setOf("channel", "feed")
        private val ITEM_TAGS = setOf("item", "entry")
        private val DATE_TAGS = setOf("pubdate", "published", "date", "issued", "updated", "modified")

        private val JSON_LISTS = listOf("items", "flashes", "news", "articles", "entries", "data")
        private val JSON_TITLES = listOf("title", "headline", "text", "content_text", "summary")
        private val JSON_IDS = listOf("id", "guid", "url", "link")
        private val JSON_DATES = listOf(
            "date_published", "published", "pubDate", "date", "time", "timestamp", "created_at",
            "date_modified", "updated",
        )

        /** A headline made from a description, which may be the whole story, is cut here. */
        private const val MAX_TITLE_FALLBACK = 160

        /** A number this large is milliseconds already; below it, seconds (until the year 5138). */
        private const val MILLIS_THRESHOLD = 100_000_000_000L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val FUTURE_SLACK_MS = 60L * 60 * 1000

        private val BARE_TIME = Regex("""(\d{1,2}):(\d{2})""")
        private val DAY_NAME = Regex("""^\s*\p{L}+\.?,\s*""")
        private val ZONE_NAME = Regex("""\s([A-Za-z]{1,4})\s*$""")

        /** The zones RFC 822 names, and the two an Israeli newsroom is likely to print. */
        private val NAMED_ZONES = mapOf(
            "GMT" to "+0000", "UT" to "+0000", "UTC" to "+0000", "Z" to "+0000",
            "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500",
            "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700",
            "IDT" to "+0300", "CET" to "+0100", "CEST" to "+0200", "BST" to "+0100",
        )

        private val RFC_822: DateTimeFormatter = DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .parseLenient()
            .appendPattern("d MMM yyyy H:mm[:ss] ")
            .appendOffset("+HHMM", "+0000")
            .toFormatter(Locale.ENGLISH)

        private val RFC_822_LOCAL: DateTimeFormatter = DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .parseLenient()
            .appendPattern("d MMM yyyy H:mm[:ss]")
            .toFormatter(Locale.ENGLISH)

        /** An HTML tag: `<` and a letter, so "inflation < 3%" keeps its sign. */
        private val TAG = Regex("""</?[A-Za-z][^<>]*>""")
        private val ENTITY = Regex("""&(#?[A-Za-z0-9]+);""")
        private val WHITESPACE = Regex("""\s+""")

        private val NAMED_ENTITIES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…",
            "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
        )
    }
}
