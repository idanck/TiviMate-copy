package com.idanck.shidur.data

import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.news.FreeCopies
import com.idanck.shidur.data.news.IsraeliNewsChannels
import com.idanck.shidur.data.xtream.XtreamStreamUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** News mode offers Israeli news channels and nothing else (ADR-042). */
class IsraeliNewsChannelsTest {

    private fun news(name: String, group: String?, tvgId: String? = null) =
        IsraeliNewsChannels.isIsraeliNews(name, group, tvgId)

    @Test
    fun `the provider's own names for the news channels are news`() {
        listOf("|IL| CHANNEL 11", "|IL| CHANNEL 12", "|IL| CHANNEL 13", "|IL| CHANNEL 20", "|IL| I24",
            "|IL| HOT Keshet 12", "|IL| HOT Reshet 13").forEach {
            assertTrue(it, news(it, "ISRAËL| HEBREEW"))
        }
    }

    @Test
    fun `the owner's Hebrew names for them are news`() {
        listOf("כאן 11", "קשת 12", "ערוץ 13", "ערוץ 14", "איי 24 ניוז", "הוט קשת 12", "הוט רשת 13").forEach {
            assertTrue(it, news(it, "ישראל"))
        }
    }

    @Test
    fun `a guide id of theirs is enough`() {
        assertTrue(news("Something", "ישראל", "il.channel-12"))
        assertTrue(news("Something", null, "il.i24"))
    }

    @Test
    fun `the bundled free-to-air playlist's news channels are news, with no group to go by`() {
        assertTrue(news("i24NEWS", null, "i24NEWSHebrew.il@SD"))
        assertTrue(news("קשת 12", null, "Keshet12.il@SD"))
        assertTrue(news("ערוץ הכנסת", null, "KnessetChannel.il@SD"))
        assertFalse(news("ערוץ 16", null, "Channel16.il@SD"))
        assertFalse(news("כאן חינוכית", null, "KanEducational.il@SD"))
    }

    @Test
    fun `the rest of the Israeli group is not news`() {
        listOf("|IL| CHANNEL 9", "|IL| CHANNEL 1 HD", "|IL| CHANNEL 8", "ערוץ 9", "ערוץ 1 HD", "ספורט 1",
            "קשת 120", "|IL| HOT Channel 9", "יס סרטים דרמה HD").forEach {
            assertFalse(it, news(it, "ישראל"))
        }
    }

    @Test
    fun `foreign news is not Israeli`() {
        assertFalse(news("CNN International", "USA | NEWS"))
        assertFalse(news("BBC News", "UK"))
        assertFalse(news("Channel 12", "ספורט | אנגליה"))
    }

    private fun station(name: String, tvgId: String? = null) = IsraeliNewsChannels.station(name, tvgId)

    @Test
    fun `the free playlist's copy and the provider's are the same station`() {
        assertEquals("14", station("ערוץ 14", "Now14.il@SD"))
        assertEquals("14", station("ערוץ 14", "il.channel-20"))
        assertEquals("14", station("|IL| CHANNEL 20"))
        assertEquals("i24", station("i24NEWS", "i24NEWSHebrew.il@SD"))
        assertEquals("i24", station("איי 24 ניוז", "il.i24"))
        assertEquals("i24", station("|IL| I24"))
        assertEquals("11", station("כאן 11", "Kan11.il@SD"))
        assertEquals("11", station("כאן 11", "il.channel-11"))
        assertEquals("12", station("קשת 12", "Keshet12.il@SD"))
        assertEquals("13", station("ערוץ 13", "Channel13.il@SD"))
        assertEquals("13", station("|IL| CHANNEL 13"))
        assertEquals("knesset", station("ערוץ הכנסת", "KnessetChannel.il@SD"))
    }

    @Test
    fun `another feed of a station, or another station, is not it`() {
        assertNull(station("הוט קשת 12", "il.hot-keshet-12"))
        assertNull(station("|IL| HOT Reshet 13"))
        assertNull(station("i24 News English"))
        assertNull(station("קשת 120"))
        assertNull(station("ערוץ 9"))
        assertNull(station("ערוץ 16", "Channel16.il@SD"))
    }

    private fun channel(id: Long, playlist: Long, name: String, tvgId: String? = null) =
        Channel(id = id, playlistId = playlist, identity = "$id", name = name, tvgId = tvgId)

    @Test
    fun `a station the free playlist carries is offered from there only`() {
        val provider = listOf(
            channel(1, 1, "כאן 11", "il.channel-11"),
            channel(2, 1, "ערוץ 14", "il.channel-20"),
            channel(3, 1, "איי 24 ניוז", "il.i24"),
            channel(4, 1, "הוט קשת 12", "il.hot-keshet-12"),
        )
        val free = listOf(
            channel(10, 2, "ערוץ 14", "Now14.il@SD"),
            channel(11, 2, "i24NEWS", "i24NEWSHebrew.il@SD"),
        )
        val result = FreeCopies.prefer(provider + free, free = setOf(2L))
        assertEquals(listOf(1L, 4L, 10L, 11L), result.choices.map { it.id })
        assertEquals(10L, result.replacedBy[2L]?.id)
        assertEquals(11L, result.replacedBy[3L]?.id)
        assertNull(result.replacedBy[1L])
    }

    @Test
    fun `without the free playlist nothing is left out`() {
        val provider = listOf(channel(1, 1, "ערוץ 14", "il.channel-20"), channel(2, 1, "כאן 11"))
        val result = FreeCopies.prefer(provider, free = emptySet())
        assertEquals(provider, result.choices)
        assertTrue(result.replacedBy.isEmpty())
    }

    @Test
    fun `a panel's stream address names its Xtream account`() {
        val plain = XtreamStreamUrl.parse("http://line.example.org:80/user1/pa%2Bss/9905")
        assertEquals("http://line.example.org:80", plain?.server)
        assertEquals("user1", plain?.username)
        assertEquals("pa+ss", plain?.password)
        val live = XtreamStreamUrl.parse("http://host.example/live/u/p/123.ts")
        assertEquals("u", live?.username)
        assertEquals("p", live?.password)
        assertNull(XtreamStreamUrl.parse("http://stream.example.net/378/index.m3u8"))
        assertNull(XtreamStreamUrl.parse("https://cdn.example/livehls/oil/ch14/live/ch14/live.livx/playlist.m3u8"))
        assertNull(XtreamStreamUrl.parse("http://host.example/movie/u/p/123.mp4"))
    }
}
