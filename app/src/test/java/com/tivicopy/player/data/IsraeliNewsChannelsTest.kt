package com.tivicopy.player.data

import com.tivicopy.player.data.news.IsraeliNewsChannels
import org.junit.Assert.assertFalse
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
}
