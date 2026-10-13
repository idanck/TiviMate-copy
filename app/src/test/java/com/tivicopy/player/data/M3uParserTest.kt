package com.tivicopy.player.data

import com.tivicopy.player.data.m3u.M3uParser
import com.tivicopy.player.data.model.CatchupMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NFR-MAINT-02: the parser is tested against the malformed shapes real provider playlists take.
 * Every sample here is synthetic — no provider URL or credential appears in the corpus.
 */
class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(text.reader().buffered())

    @Test
    fun `reads the documented attributes`() {
        val result = parse(
            """
            #EXTM3U url-tvg="http://guide.example/xmltv.xml"
            #EXTINF:-1 tvg-id="one.example" tvg-name="One" tvg-logo="http://logo/1.png" tvg-chno="12" group-title="News",Channel One HD
            http://stream.example/1.ts
            """.trimIndent(),
        )
        assertEquals("http://guide.example/xmltv.xml", result.epgUrl)
        assertEquals(1, result.entries.size)
        val entry = result.entries[0]
        assertEquals("one.example", entry.tvgId)
        assertEquals("One", entry.tvgName)
        assertEquals(12, entry.tvgChno)
        assertEquals("News", entry.groupTitle)
        assertEquals("Channel One HD", entry.name)
        assertEquals("http://stream.example/1.ts", entry.url)
    }

    @Test
    fun `keeps a comma inside the display name`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="General",Channel 4, HD
            http://stream.example/4.ts
            """.trimIndent(),
        )
        assertEquals("Channel 4, HD", result.entries[0].name)
    }

    @Test
    fun `preserves attributes it does not understand`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" some-future-thing="42" another="x",A
            http://stream.example/a.ts
            """.trimIndent(),
        )
        assertEquals("42", result.entries[0].extraAttributes["some-future-thing"])
        assertEquals("x", result.entries[0].extraAttributes["another"])
    }

    @Test
    fun `reads EXTVLCOPT and KODIPROP into headers and licence hints`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",A
            #EXTVLCOPT:http-user-agent=CustomUA/2.0
            #EXTVLCOPT:http-referrer=http://ref.example/
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=http://license.example/key
            http://stream.example/a.mpd
            """.trimIndent(),
        )
        val entry = result.entries[0]
        assertEquals("CustomUA/2.0", entry.userAgent)
        assertEquals("http://ref.example/", entry.headers["Referer"])
        assertEquals("com.widevine.alpha", entry.licenseType)
        assertEquals("http://license.example/key", entry.licenseKey)
    }

    @Test
    fun `survives an EXTINF with no URL`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1,Orphan
            #EXTINF:-1,Real
            http://stream.example/real.ts
            """.trimIndent(),
        )
        assertEquals(1, result.entries.size)
        assertEquals("Real", result.entries[0].name)
        assertEquals(1, result.malformedLines)
    }

    @Test
    fun `accepts a bare URL with no EXTINF`() {
        val result = parse("http://stream.example/bare.ts")
        assertEquals(1, result.entries.size)
        assertEquals("bare.ts", result.entries[0].name)
    }

    @Test
    fun `an HTML error page is not a playlist`() {
        // What a panel sends, with a 200, for a suspended account. Each line used to become a
        // "bare URL" channel, so the refresh succeeded and the merge deleted every real channel.
        val result = parse(
            """
            <html>
            <body><h1>Account suspended</h1></body>
            </html>
            """.trimIndent(),
        )
        assertTrue(result.entries.isEmpty())
        assertEquals(3, result.malformedLines)
    }

    @Test
    fun `accepts a bare absolute path`() {
        val result = parse("/storage/emulated/0/Movies/local.ts")
        assertEquals(1, result.entries.size)
        assertEquals("local.ts", result.entries[0].name)
    }

    @Test
    fun `handles a byte order mark and CRLF endings`() {
        val result = parse("﻿#EXTM3U\r\n#EXTINF:-1,A\r\nhttp://stream.example/a.ts\r\n")
        assertEquals(1, result.entries.size)
        assertEquals("A", result.entries[0].name)
    }

    @Test
    fun `derives catch-up mode and window`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1 catchup="flussonic" catchup-days="7" catchup-source="http://a/{start}-{duration}.ts",A
            http://stream.example/a.ts
            """.trimIndent(),
        )
        assertEquals(CatchupMode.FLUSSONIC, result.entries[0].catchupMode)
        assertEquals(7, result.entries[0].catchupDays)
    }

    @Test
    fun `treats an archive window with no explicit mode as the default mode`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1 catchup-days="3",A
            http://stream.example/a.ts
            """.trimIndent(),
        )
        assertEquals(CatchupMode.DEFAULT, result.entries[0].catchupMode)
    }

    @Test
    fun `parses fractional and signed tvg-shift as minutes`() {
        assertEquals(60, M3uParser.parseShiftMinutes("1"))
        assertEquals(-120, M3uParser.parseShiftMinutes("-2"))
        assertEquals(30, M3uParser.parseShiftMinutes("0.5"))
        assertEquals(90, M3uParser.parseShiftMinutes("+1.5"))
        assertEquals(0, M3uParser.parseShiftMinutes(null))
        assertEquals(0, M3uParser.parseShiftMinutes("nonsense"))
    }

    @Test
    fun `identity prefers tvg-id then stream id then name`() {
        val result = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="has.id",With id
            http://stream.example/live/u/p/55.ts
            #EXTINF:-1,No id
            http://stream.example/live/u/p/77.ts
            #EXTINF:-1,Plain Name HD
            http://stream.example/whatever
            """.trimIndent(),
        )
        assertEquals("tvg:has.id", result.entries[0].identity)
        assertEquals("sid:77", result.entries[1].identity)
        assertEquals("name:plain name", result.entries[2].identity)
        assertNull(result.entries[2].tvgId)
        assertTrue(result.entries.all { it.url.isNotBlank() })
    }

    /**
     * Shaped like the hosted `il-epg/channels.m3u` (docs/00-scope-and-platforms.md §0.1): Hebrew
     * display names, iptv-org-style tvg-id, and a self-hosted tvg-logo per entry.
     */
    @Test
    fun `reads a built-in-playlist-shaped entry with Hebrew name and hosted logo`() {
        val result = parse(
            """
            #EXTM3U url-tvg="https://il-epg.idanck.com/guide.xml"
            #EXTINF:-1 tvg-id="Kan11.il@SD" tvg-logo="https://il-epg.idanck.com/logos/kan11.png" group-title="ישראל",כאן 11
            https://kancdn.medonecdn.net/livehls/oil/kancdn-live/live/kan11/live.livx/playlist.m3u8
            #EXTINF:-1 tvg-id="KnessetChannel.il@SD" tvg-logo="https://il-epg.idanck.com/logos/knesset.png" group-title="ישראל",ערוץ הכנסת
            http://stream.mcquack.net/48/index.m3u8
            """.trimIndent(),
        )
        assertEquals("https://il-epg.idanck.com/guide.xml", result.epgUrl)
        assertEquals(2, result.entries.size)
        val kan11 = result.entries[0]
        assertEquals("Kan11.il@SD", kan11.tvgId)
        assertEquals("https://il-epg.idanck.com/logos/kan11.png", kan11.tvgLogo)
        assertEquals("כאן 11", kan11.name)
        val knesset = result.entries[1]
        assertEquals("KnessetChannel.il@SD", knesset.tvgId)
        assertEquals("ערוץ הכנסת", knesset.name)
    }
}
