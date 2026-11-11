package com.idanck.shidur.data

import android.app.Application
import com.idanck.shidur.core.Outcome
import com.idanck.shidur.data.podcast.PodcastFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a podcast feed gives the app (ADR-067): the show, its episodes and what each one can play.
 *
 * Robolectric, like `NewsFeedTest`: the XML pull parser is a platform class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PodcastFeedTest {

    private fun ok(raw: String): PodcastFeed = requireNotNull(PodcastFeed.parse(raw).getOrNull()) {
        "did not parse: ${PodcastFeed.parse(raw)}"
    }

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
             xmlns:content="http://purl.org/rss/1.0/modules/content/"
             xmlns:podcast="https://podcastindex.org/namespace/1.0">
          <channel>
            <title>הסכת לדוגמה</title>
            <link>https://example.com/show</link>
            <language>he</language>
            <itunes:author>רשת לדוגמה</itunes:author>
            <itunes:summary><![CDATA[<p>על <b>הכול</b> &amp; עוד</p><p>שורה שנייה</p>]]></itunes:summary>
            <description>plain description</description>
            <itunes:image href="https://example.com/cover.jpg"/>
            <image><url>https://example.com/rss-image.jpg</url><title>ignored</title></image>
            <itunes:category text="Society &amp; Culture"><itunes:category text="Documentary"/></itunes:category>
            <itunes:category text="History"/>
            <item>
              <title>פרק 2</title>
              <itunes:title>should not replace</itunes:title>
              <guid isPermaLink="false">ep-2</guid>
              <pubDate>Mon, 09 Nov 2026 06:00:00 +0200</pubDate>
              <enclosure url="https://cdn.example.com/2.mp3" type="audio/mpeg" length="48000000"/>
              <itunes:duration>1:02:40</itunes:duration>
              <itunes:season>3</itunes:season>
              <itunes:episode>114</itunes:episode>
              <itunes:explicit>no</itunes:explicit>
              <content:encoded><![CDATA[<p>notes</p><br/>more]]></content:encoded>
              <podcast:chapters url="https://example.com/2.json" type="application/json+chapters"/>
              <podcast:transcript url="https://example.com/2.html" type="text/html"/>
              <podcast:transcript url="https://example.com/2.vtt" type="text/vtt"/>
            </item>
            <item>
              <title>פרק 1</title>
              <enclosure url="https://cdn.example.com/1.m4a?x=1" length="0"/>
              <itunes:duration>3245</itunes:duration>
              <itunes:image href="https://example.com/ep1.jpg"/>
            </item>
            <item>
              <title>video only</title>
              <enclosure url="https://cdn.example.com/v.mp4" type="video/mp4"/>
            </item>
            <item>
              <title>no enclosure at all</title>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `reads the show from its channel`() {
        val f = ok(feed)
        assertEquals("הסכת לדוגמה", f.title)
        assertEquals("רשת לדוגמה", f.author)
        // itunes:summary is preferred, and keeps its paragraphs.
        assertEquals("על הכול & עוד\nשורה שנייה", f.description)
        // itunes:image beats RSS's own image.
        assertEquals("https://example.com/cover.jpg", f.imageUrl)
        assertEquals("he", f.language)
        assertEquals(listOf("Society & Culture", "History"), f.categories)
    }

    @Test
    fun `keeps only items with audio, with their details`() {
        val f = ok(feed)
        assertEquals(listOf("פרק 2", "פרק 1"), f.episodes.map { it.title })
        val two = f.episodes[0]
        assertEquals("ep-2", two.guid)
        assertEquals("https://cdn.example.com/2.mp3", two.audioUrl)
        assertEquals("audio/mpeg", two.mimeType)
        assertEquals(48_000_000L, two.lengthBytes)
        assertEquals(3760L, two.durationSeconds)
        assertEquals(3, two.season)
        assertEquals(114, two.episode)
        assertEquals("notes\nmore", two.description)
        assertEquals("https://example.com/2.json", two.chaptersUrl)
        // VTT is preferred over HTML, whatever the order.
        assertEquals("https://example.com/2.vtt", two.transcriptUrl)
        assertEquals(1_794_196_800_000L, two.publishedUtc)
    }

    @Test
    fun `an item without a guid is keyed by its audio address`() {
        val one = ok(feed).episodes[1]
        assertEquals("https://cdn.example.com/1.m4a?x=1", one.guid)
        // No type, but an audio extension before the query string.
        assertEquals("https://cdn.example.com/1.m4a?x=1", one.audioUrl)
        assertEquals(3245L, one.durationSeconds)
        assertEquals("https://example.com/ep1.jpg", one.imageUrl)
        assertNull(one.publishedUtc)
    }

    @Test
    fun `durations in every form feeds write them`() {
        assertEquals(3245L, PodcastFeed.parseDuration("3245"))
        assertEquals(3245L, PodcastFeed.parseDuration("3245.6"))
        assertEquals(125L, PodcastFeed.parseDuration("2:05"))
        assertEquals(3760L, PodcastFeed.parseDuration("01:02:40"))
        assertEquals(0L, PodcastFeed.parseDuration(""))
        assertEquals(0L, PodcastFeed.parseDuration("about an hour"))
    }

    @Test
    fun `an HTML page or Atom is not a podcast feed`() {
        assertTrue(PodcastFeed.parse("<html><body>hi</body></html>") is Outcome.Err)
        assertTrue(PodcastFeed.parse("<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>x</title></feed>") is Outcome.Err)
    }

    @Test
    fun `a channel without a title is refused`() {
        assertTrue(PodcastFeed.parse("<rss><channel><item><title>a</title></item></channel></rss>") is Outcome.Err)
    }
}
