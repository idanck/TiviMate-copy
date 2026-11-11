package com.idanck.shidur.data.podcast

import android.util.Xml
import com.idanck.shidur.core.AppError
import com.idanck.shidur.core.Outcome
import com.idanck.shidur.data.news.NewsFeed
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * A podcast's RSS document, read (ADR-067): the show and its episodes, as the feed lists them.
 *
 * A podcast is RSS 2.0 whose items carry an `<enclosure>` — the audio file — with Apple's
 * `itunes:*` tags beside the plain ones and, in some feeds, the Podcasting 2.0 namespace
 * (`podcast:chapters`, `podcast:transcript`). Prefixes are never what decides: `itunes:image` and
 * `image` are told apart by where they sit and by whether they carry `href`.
 *
 * An item without an audio enclosure is not an episode here — a video-only or a text-only item has
 * nothing this player can play — and is dropped; the rest of the feed still reads. An HTML page,
 * the usual result of pasting a site's address instead of its feed's, is refused.
 */
data class PodcastFeed(
    val title: String,
    val author: String?,
    val description: String?,
    val imageUrl: String?,
    val link: String?,
    val language: String?,
    val categories: List<String>,
    val episodes: List<FeedEpisode>,
) {

    /** One `<item>` with something to play. Times are UTC milliseconds, lengths whole seconds. */
    data class FeedEpisode(
        /** `guid` when the feed gives one, else the audio address: what a mark is kept against. */
        val guid: String,
        val title: String,
        val description: String?,
        val audioUrl: String,
        val mimeType: String?,
        val lengthBytes: Long,
        val durationSeconds: Long,
        val publishedUtc: Long?,
        val imageUrl: String?,
        val season: Int?,
        val episode: Int?,
        val explicit: Boolean,
        val link: String?,
        val chaptersUrl: String?,
        val transcriptUrl: String?,
    )

    companion object {

        /** A feed of decades of daily episodes is still read, but only this many are kept. */
        const val MAX_EPISODES = 3000

        fun parse(body: ByteArray, charset: String? = null): Outcome<PodcastFeed> = try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            runCatching { parser.setFeature(FEATURE_RELAXED, true) }
            parser.setInput(ByteArrayInputStream(body), charset)
            read(parser)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Outcome.Err(AppError(AppError.Kind.ParseFailed, "podcast feed is not XML", t))
        }

        fun parse(raw: String): Outcome<PodcastFeed> = parse(raw.toByteArray(Charsets.UTF_8), "UTF-8")

        private fun read(parser: XmlPullParser): Outcome<PodcastFeed> {
            var event = parser.next()
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT || local(parser.name) != "rss") {
                val root = if (event == XmlPullParser.END_DOCUMENT) "nothing" else "<${local(parser.name)}>"
                return Outcome.Err(AppError(AppError.Kind.UnsupportedFormat, "podcast feed: $root is not RSS"))
            }

            var title: String? = null
            var author: String? = null
            var summary: String? = null
            var description: String? = null
            var itunesImage: String? = null
            var image: String? = null
            var link: String? = null
            var language: String? = null
            val categories = LinkedHashSet<String>()
            val episodes = mutableListOf<FeedEpisode>()
            var sawChannel = false

            // Depth below <channel>: only its direct children describe the show.
            var depth = 0
            while (true) {
                event = parser.next()
                when (event) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.END_TAG -> if (depth > 0) depth--
                    XmlPullParser.START_TAG -> {
                        val raw = parser.name.orEmpty().lowercase(Locale.ROOT)
                        val name = local(raw)
                        if (!sawChannel) {
                            if (name == "channel") sawChannel = true else skip(parser)
                            continue
                        }
                        if (depth > 0) {
                            depth++
                            continue
                        }
                        when {
                            name == "item" -> readItem(parser, episodes.size)?.let {
                                if (episodes.size < MAX_EPISODES) episodes += it
                            }
                            name == "title" && !raw.contains(':') && title == null -> title = NewsFeed.clean(readText(parser))
                            name == "author" && raw.startsWith("itunes") -> author = NewsFeed.clean(readText(parser))
                            name == "summary" && raw.startsWith("itunes") -> summary = readText(parser)
                            name == "description" && !raw.contains(':') -> description = readText(parser)
                            name == "link" && !raw.contains(':') -> link = readText(parser).trim().ifEmpty { null }
                            name == "language" -> language = readText(parser).trim().ifEmpty { null }
                            name == "image" && parser.getAttributeValue(null, "href") != null -> {
                                if (itunesImage == null) itunesImage = parser.getAttributeValue(null, "href")
                                skip(parser)
                            }
                            name == "image" -> image = readImageUrl(parser) ?: image
                            name == "category" && raw.startsWith("itunes") -> {
                                parser.getAttributeValue(null, "text")?.trim()?.takeIf { it.isNotEmpty() }?.let(categories::add)
                                // Sub-categories nest inside; they are read as their own entries.
                                depth++
                            }
                            else -> depth++
                        }
                    }
                }
            }
            if (!sawChannel) {
                return Outcome.Err(AppError(AppError.Kind.UnsupportedFormat, "podcast feed: RSS without a channel"))
            }
            val showTitle = title?.ifEmpty { null }
                ?: return Outcome.Err(AppError(AppError.Kind.ParseFailed, "podcast feed: the channel has no title"))
            return Outcome.Ok(
                PodcastFeed(
                    title = showTitle,
                    author = author?.ifEmpty { null },
                    description = cleanBody(summary ?: description),
                    imageUrl = (itunesImage ?: image)?.trim()?.ifEmpty { null },
                    link = link,
                    language = language?.lowercase(Locale.ROOT),
                    categories = categories.toList(),
                    episodes = episodes.distinctBy { it.guid },
                ),
            )
        }

        private fun readItem(parser: XmlPullParser, index: Int): FeedEpisode? {
            var title = ""
            var description: String? = null
            var encoded: String? = null
            var summary: String? = null
            var guid = ""
            var audioUrl: String? = null
            var mime: String? = null
            var length = 0L
            var duration = 0L
            var date: String? = null
            var image: String? = null
            var season: Int? = null
            var number: Int? = null
            var explicit = false
            var link: String? = null
            var chapters: String? = null
            var transcript: String? = null
            var transcriptRank = Int.MAX_VALUE
            var depth = 1
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.START_TAG -> {
                        val raw = parser.name.orEmpty().lowercase(Locale.ROOT)
                        val name = local(raw)
                        if (depth > 1) {
                            depth++
                            continue
                        }
                        when {
                            name == "title" && !raw.contains(':') -> if (title.isEmpty()) title = readText(parser) else skip(parser)
                            name == "title" -> skip(parser)
                            name == "description" -> description = readText(parser)
                            name == "encoded" -> encoded = readText(parser)
                            name == "summary" -> summary = readText(parser)
                            name == "guid" && !raw.contains(':') -> guid = readText(parser).trim()
                            name == "enclosure" -> {
                                val url = parser.getAttributeValue(null, "url")?.trim()
                                val type = parser.getAttributeValue(null, "type")?.trim()?.lowercase(Locale.ROOT)
                                // The first audio enclosure wins; a video one is never taken.
                                if (audioUrl == null && !url.isNullOrEmpty() && isAudio(type, url)) {
                                    audioUrl = url
                                    mime = type?.ifEmpty { null }
                                    length = parser.getAttributeValue(null, "length")?.trim()?.toLongOrNull() ?: 0L
                                }
                                skip(parser)
                            }
                            name == "duration" -> duration = parseDuration(readText(parser))
                            name == "pubdate" || name == "date" -> {
                                val text = readText(parser)
                                if (date == null) date = text
                            }
                            name == "image" -> {
                                val href = parser.getAttributeValue(null, "href")
                                if (image == null && !href.isNullOrBlank()) image = href.trim()
                                skip(parser)
                            }
                            name == "season" -> season = readText(parser).trim().toIntOrNull()
                            name == "episode" && raw.startsWith("itunes") -> number = readText(parser).trim().toIntOrNull()
                            name == "explicit" -> explicit = readText(parser).trim().lowercase(Locale.ROOT) in EXPLICIT
                            name == "link" && !raw.contains(':') -> link = readText(parser).trim().ifEmpty { null }
                            name == "chapters" -> {
                                if (chapters == null) chapters = parser.getAttributeValue(null, "url")?.trim()?.ifEmpty { null }
                                skip(parser)
                            }
                            name == "transcript" -> {
                                val url = parser.getAttributeValue(null, "url")?.trim()
                                val rank = TRANSCRIPT_PREFERENCE.indexOf(parser.getAttributeValue(null, "type")?.lowercase(Locale.ROOT))
                                    .let { if (it < 0) TRANSCRIPT_PREFERENCE.size else it }
                                if (!url.isNullOrEmpty() && rank < transcriptRank) {
                                    transcript = url
                                    transcriptRank = rank
                                }
                                skip(parser)
                            }
                            else -> depth++
                        }
                    }
                }
            }
            val url = audioUrl ?: return null
            val heading = NewsFeed.clean(title).ifEmpty { return null }
            return FeedEpisode(
                guid = guid.ifEmpty { url }.ifEmpty { "$index:$heading" },
                title = heading,
                description = cleanBody(encoded ?: description ?: summary),
                audioUrl = url,
                mimeType = mime,
                lengthBytes = length,
                durationSeconds = duration,
                publishedUtc = date?.let { NewsFeed.parseDate(it) },
                imageUrl = image,
                season = season,
                episode = number,
                explicit = explicit,
                link = link,
                chaptersUrl = chapters,
                transcriptUrl = transcript,
            )
        }

        /**
         * `itunes:duration` as feeds write it: seconds (`3245`), `mm:ss`, `hh:mm:ss`, sometimes with
         * a fraction (`3245.5`). Zero when it is none of those; the player learns the real length.
         */
        internal fun parseDuration(raw: String): Long {
            val s = raw.trim()
            if (s.isEmpty()) return 0
            if (':' !in s) return s.toDoubleOrNull()?.toLong()?.coerceAtLeast(0) ?: 0
            val parts = s.split(':').map { it.trim().toDoubleOrNull() ?: return 0 }
            if (parts.size > 3) return 0
            return parts.fold(0.0) { acc, v -> acc * 60 + v }.toLong().coerceAtLeast(0)
        }

        /**
         * A description for the screen: markup reduced to text with its paragraphs kept, entities
         * decoded. A show notes page can be long; the screen clamps it.
         */
        internal fun cleanBody(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val paragraphs = BREAK.replace(raw, "\n")
            return paragraphs.split('\n')
                .map { NewsFeed.clean(it) }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
                .take(MAX_DESCRIPTION)
                .ifEmpty { null }
        }

        private fun isAudio(type: String?, url: String): Boolean {
            if (!type.isNullOrEmpty()) return type.startsWith("audio/") || type == "application/octet-stream" && hasAudioExtension(url)
            return hasAudioExtension(url)
        }

        private fun hasAudioExtension(url: String): Boolean =
            url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO_EXTENSIONS

        /** `<image><url>…</url></image>`, RSS's own image, read to its end tag. */
        private fun readImageUrl(parser: XmlPullParser): String? {
            var url: String? = null
            var depth = 1
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> return url
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.START_TAG -> if (local(parser.name) == "url" && url == null) {
                        url = readText(parser).trim().ifEmpty { null }
                    } else {
                        depth++
                    }
                }
            }
            return url
        }

        private fun readText(parser: XmlPullParser): String {
            val out = StringBuilder()
            var depth = 1
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> out.append(parser.text)
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

        private fun local(name: String?): String = name.orEmpty().substringAfter(':').lowercase(Locale.ROOT)

        private const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
        private const val MAX_DESCRIPTION = 8000

        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mp4")
        private val EXPLICIT = setOf("yes", "true", "explicit")
        private val TRANSCRIPT_PREFERENCE = listOf("text/vtt", "application/x-subrip", "application/srt", "text/plain", "text/html")
        private val BREAK = Regex("""(?i)<\s*(br|/p|/li|/h[1-6]|/div)\s*/?>|\r?\n""")
    }
}
