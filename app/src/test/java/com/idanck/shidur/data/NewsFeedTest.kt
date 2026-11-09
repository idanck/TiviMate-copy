package com.idanck.shidur.data

import android.app.Application
import com.idanck.shidur.data.news.NewsDigest
import com.idanck.shidur.data.news.NewsDigests
import com.idanck.shidur.data.news.NewsFeed
import com.idanck.shidur.data.news.NewsFlash
import com.idanck.shidur.data.news.NewsSource
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the screensaver's headlines accept, and how several sources become one column
 * (FR-UI-33, ADR-037).
 *
 * The formats are the feature: "how do I get the news onto the television" has to be answered by
 * pasting the RSS address a news site already publishes, so RSS, Atom and JSON Feed are all read
 * as they are actually written — in windows-1255, with double-escaped entities, with a day name
 * the site got wrong.
 *
 * Robolectric for the reason [TaskFeedTest] gives — `org.json` and the XML pull parser are
 * platform classes, and the stubbed `android.jar` would let every assertion here pass without
 * parsing anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NewsFeedTest {

    private fun ok(raw: String): NewsFeed = requireNotNull(NewsFeed.parse(raw).getOrNull())

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    // --------------------------------------------------------------------- rss

    @Test
    fun `an rss channel names the source and its items are the headlines`() {
        val feed = ok(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>ynet - חדשות, כלכלה, ספורט ובריאות</title>
                <image><title>not the name</title></image>
                <item>
                  <title><![CDATA[ראש הממשלה נפגש עם השר]]></title>
                  <link>https://example.com/1</link>
                  <guid>a1</guid>
                  <pubDate>Thu, 15 Oct 2026 20:51:00 +0300</pubDate>
                </item>
                <item>
                  <title>Second headline</title>
                  <pubDate>Thu, 15 Oct 2026 17:44:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )
        assertEquals("ynet - חדשות, כלכלה, ספורט ובריאות", feed.title)
        assertEquals(listOf("ראש הממשלה נפגש עם השר", "Second headline"), feed.items.map { it.title })
        assertEquals("a1", feed.items[0].id)
        assertEquals(utc(2026, 10, 15, 17, 51), feed.items[0].publishedUtc)
        assertEquals(utc(2026, 10, 15, 17, 44), feed.items[1].publishedUtc)
    }

    /** Israeli sites still serve windows-1255, and say so only in the XML declaration. */
    @Test
    fun `the encoding the declaration names is the one the document is read in`() {
        val xml = """<?xml version="1.0" encoding="windows-1255"?>
            <rss version="2.0"><channel><title>מקור</title>
            <item><title>מבזק בעברית</title></item></channel></rss>"""
        val feed = requireNotNull(NewsFeed.parse(xml.toByteArray(charset("windows-1255"))).getOrNull())
        assertEquals("מקור", feed.title)
        assertEquals("מבזק בעברית", feed.items.single().title)
    }

    @Test
    fun `markup and double-escaped entities are not printed at the viewer`() {
        val feed = ok(
            """
            <rss><channel><title>X</title>
            <item><title>&lt;b&gt;Rates&lt;/b&gt; rise &amp;amp; inflation &lt; 3%</title></item>
            </channel></rss>
            """.trimIndent(),
        )
        assertEquals("Rates rise & inflation < 3%", feed.items.single().title)
    }

    @Test
    fun `an rss 1 document is read too`() {
        val feed = ok(
            """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel><title>Old style</title></channel>
              <item><title>One</title><dc:date>2026-10-15T20:00:00+03:00</dc:date></item>
            </rdf:RDF>
            """.trimIndent(),
        )
        assertEquals("Old style", feed.title)
        assertEquals(utc(2026, 10, 15, 17, 0), feed.items.single().publishedUtc)
    }

    // -------------------------------------------------------------------- atom

    @Test
    fun `an atom feed's entries are headlines, and its link is an attribute`() {
        val feed = ok(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom source</title>
              <entry>
                <title type="html">A &amp;amp; B</title>
                <link rel="alternate" href="https://example.com/a"/>
                <published>2026-10-15T17:30:00Z</published>
                <updated>2026-10-15T18:00:00Z</updated>
                <source><title>Somebody else</title></source>
              </entry>
            </feed>
            """.trimIndent(),
        )
        assertEquals("Atom source", feed.title)
        val entry = feed.items.single()
        assertEquals("A & B", entry.title)
        assertEquals("https://example.com/a", entry.id)
        // When it broke, not when somebody corrected it.
        assertEquals(utc(2026, 10, 15, 17, 30), entry.publishedUtc)
    }

    // -------------------------------------------------------------------- json

    @Test
    fun `a json feed is read`() {
        val feed = ok(
            """
            {"version":"https://jsonfeed.org/version/1.1","title":"JSON source","items":[
              {"id":"1","title":"First","date_published":"2026-10-15T17:51:00Z"},
              {"id":"2","content_text":"No title, only text"}
            ]}
            """.trimIndent(),
        )
        assertEquals("JSON source", feed.title)
        assertEquals(listOf("First", "No title, only text"), feed.items.map { it.title })
        assertEquals(utc(2026, 10, 15, 17, 51), feed.items[0].publishedUtc)
        assertNull(feed.items[1].publishedUtc)
    }

    @Test
    fun `a bare json array of headlines with unix times is read`() {
        val feed = ok("""[{"headline":"Seconds","timestamp":1792000000},{"text":"Millis","time":1792000000000}]""")
        assertEquals(listOf("Seconds", "Millis"), feed.items.map { it.title })
        assertEquals(1_792_000_000_000L, feed.items[0].publishedUtc)
        assertEquals(1_792_000_000_000L, feed.items[1].publishedUtc)
    }

    // ----------------------------------------------------------------- failure

    /** The usual mistake: a site's home page pasted where its feed's address belongs. */
    @Test
    fun `an html page is refused rather than read as an empty feed`() {
        val outcome = NewsFeed.parse("<!DOCTYPE html><html><head><title>Site</title></head><body></body></html>")
        assertFalse(outcome.isOk)
    }

    @Test
    fun `an empty document is an empty source, not an error`() {
        assertTrue(ok("   ").items.isEmpty())
    }

    @Test
    fun `an item with nothing to print is dropped and the rest still show`() {
        val feed = ok("<rss><channel><item><title> </title></item><item><title>Kept</title></item></channel></rss>")
        assertEquals(listOf("Kept"), feed.items.map { it.title })
    }

    // ------------------------------------------------------------------- dates

    @Test
    fun `rss dates are read in their many spellings`() {
        val expected = utc(2026, 10, 15, 17, 51)
        assertEquals(expected, NewsFeed.parseDate("Thu, 15 Oct 2026 20:51:00 +0300"))
        assertEquals(expected, NewsFeed.parseDate("15 Oct 2026 17:51 GMT"))
        assertEquals(expected, NewsFeed.parseDate("Thurs, 15 OCT 2026 13:51:00 EDT"))
        assertEquals(expected, NewsFeed.parseDate("2026-10-15T17:51:00Z"))
        assertEquals(expected, NewsFeed.parseDate("2026-10-15T20:51:00+03:00"))
    }

    @Test
    fun `a time with a zone this reader does not know is read as local time`() {
        val zone = ZoneId.of("Asia/Jerusalem")
        assertEquals(
            ZonedDateTime.of(2026, 10, 15, 20, 51, 0, 0, zone).toInstant().toEpochMilli(),
            NewsFeed.parseDate("Thu, 15 Oct 2026 20:51:00 IST", zone),
        )
    }

    @Test
    fun `a bare clock time is the most recent time the clock read it`() {
        val zone = ZoneId.of("Asia/Jerusalem")
        val now = ZonedDateTime.of(2026, 10, 16, 0, 10, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(
            ZonedDateTime.of(2026, 10, 15, 23, 50, 0, 0, zone).toInstant().toEpochMilli(),
            NewsFeed.parseDate("23:50", zone, now),
        )
        assertEquals(
            ZonedDateTime.of(2026, 10, 16, 0, 5, 0, 0, zone).toInstant().toEpochMilli(),
            NewsFeed.parseDate("00:05", zone, now),
        )
    }

    @Test
    fun `nonsense is no date rather than a wrong one`() {
        assertNull(NewsFeed.parseDate("yesterday-ish"))
        assertNull(NewsFeed.parseDate(""))
    }

    // ----------------------------------------------------------------- sources

    @Test
    fun `the source list survives the round trip through preferences`() {
        val sources = listOf(NewsSource("https://a.example/rss"), NewsSource("https://b.example/feed.json", enabled = false))
        assertEquals(sources, NewsSource.decode(NewsSource.encode(sources)))
        assertTrue(NewsSource.decode("not json").isEmpty())
        assertTrue(NewsSource.decode(null).isEmpty())
    }

    @Test
    fun `only an https address with a host is a source`() {
        assertTrue(NewsSource.acceptable("https://www.ynet.co.il/Integration/StoryRss2.xml"))
        assertFalse(NewsSource.acceptable("http://example.com/rss"))
        assertFalse(NewsSource.acceptable("https://"))
        assertFalse(NewsSource.acceptable("example.com/rss"))
    }

    @Test
    fun `the parsed headlines survive the round trip through preferences`() {
        val digests = mapOf(
            "https://a.example/rss" to NewsDigest("A", listOf(NewsFlash("1", "One", 5_000L), NewsFlash("2", "Two", null)), 9_000L),
        )
        assertEquals(digests, NewsDigests.decode(NewsDigests.encode(digests)))
        assertTrue(NewsDigests.decode("{").isEmpty())
    }

    // ------------------------------------------------------------------- merge

    private val now = utc(2026, 10, 15, 18, 0)
    private fun minutesAgo(m: Int) = now - m * 60_000L

    @Test
    fun `every source's headlines become one column, newest first, each with its source's colour`() {
        val sources = listOf(NewsSource("https://a.example"), NewsSource("https://b.example"))
        val digests = mapOf(
            "https://a.example" to NewsDigest("Alpha", listOf(NewsFlash("a1", "A one", minutesAgo(5)), NewsFlash("a2", "A two", minutesAgo(30))), now),
            "https://b.example" to NewsDigest("Beta", listOf(NewsFlash("b1", "B one", minutesAgo(10))), now),
        )
        val merged = NewsDigests.merge(sources, digests, now)
        assertEquals(listOf("A one", "B one", "A two"), merged.map { it.flash.title })
        assertEquals(listOf(0, 1, 0), merged.map { it.colourIndex })
        assertEquals(listOf("Alpha", "Beta", "Alpha"), merged.map { it.source })
    }

    @Test
    fun `a switched-off source, a stale headline and a repeated story are left out`() {
        val sources = listOf(NewsSource("https://a.example"), NewsSource("https://b.example", enabled = false), NewsSource("https://c.example"))
        val digests = mapOf(
            "https://a.example" to NewsDigest(null, listOf(NewsFlash("a1", "Same story", minutesAgo(5)), NewsFlash("a2", "Last week", now - 7 * 24 * 3_600_000L)), now),
            "https://b.example" to NewsDigest(null, listOf(NewsFlash("b1", "Hidden", minutesAgo(1))), now),
            "https://c.example" to NewsDigest(null, listOf(NewsFlash("c1", "same STORY", minutesAgo(8))), now),
        )
        val merged = NewsDigests.merge(sources, digests, now)
        assertEquals(listOf("Same story"), merged.map { it.flash.title })
        // The third source keeps the third colour even though the second is switched off.
        assertEquals(2, NewsDigests.merge(sources.take(1).map { it.copy(enabled = false) } + sources.drop(1), digests, now).single().colourIndex)
    }

    @Test
    fun `one busy source cannot push the others off the screen`() {
        val sources = listOf(NewsSource("https://busy.example"), NewsSource("https://quiet.example"))
        val digests = mapOf(
            "https://busy.example" to NewsDigest(null, (1..20).map { NewsFlash("b$it", "Busy $it", minutesAgo(it)) }, now),
            "https://quiet.example" to NewsDigest(null, listOf(NewsFlash("q", "Quiet", minutesAgo(50))), now),
        )
        val merged = NewsDigests.merge(sources, digests, now, perSource = 8)
        assertEquals(8, merged.count { it.colourIndex == 0 })
        assertNotNull(merged.firstOrNull { it.flash.title == "Quiet" })
    }

    @Test
    fun `a source is called by its own name without the slogan, or by its host`() {
        assertEquals("ynet", NewsDigests.label(NewsSource("https://www.ynet.co.il/rss"), NewsDigest("ynet - חדשות, כלכלה, ספורט", emptyList(), 0)))
        assertEquals("Haaretz", NewsDigests.label(NewsSource("https://x.example"), NewsDigest("Haaretz | News", emptyList(), 0)))
        assertEquals("ynet.co.il", NewsDigests.label(NewsSource("https://www.ynet.co.il/rss"), null))
    }
}
