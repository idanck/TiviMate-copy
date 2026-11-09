package com.idanck.shidur.data

import com.idanck.shidur.data.sport.IsraeliSportChannels
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sport mode offers Israeli sports channels and nothing else (ADR-041). */
class IsraeliSportChannelsTest {

    private fun sport(name: String, group: String?, tvgId: String? = null) =
        IsraeliSportChannels.isIsraeliSport(name, group, tvgId)

    @Test
    fun `the Israeli group's sports channels are offered`() {
        assertTrue(sport("|IL| SPORT 1", "ישראל"))
        assertTrue(sport("|IL| SPORT 5 PLUS", "ישראל"))
        assertTrue(sport("|IL| EUROSPORT 1", "ישראל"))
        assertTrue(sport("ספורט 5", "ישראל"))
    }

    @Test
    fun `the provider's raw Israeli group counts as Israeli`() {
        assertTrue(sport("SPORT 2 HD", "ISRAËL| HEBREEW"))
        assertTrue(sport("ספורט 1", "ISRAËL| HEBREEW"))
    }

    @Test
    fun `ONE is sport, and ZONE and Channel One are not`() {
        assertTrue(sport("|IL| ONE HD", "ישראל"))
        assertTrue(sport("|IL| ONE 2", "ישראל"))
        assertFalse(sport("|IL| ZONE HD", "ישראל"))
        assertFalse(sport("|IL| CHANNEL ONE", "ישראל"))
    }

    @Test
    fun `Sport 5's channels that leave the word out are sport`() {
        assertTrue(sport("|IL| 5 GOLD", "ישראל"))
        assertTrue(sport("|IL| 5+ LIVE", "ישראל"))
        assertTrue(sport("|IL| 5 STARS HD", "ישראל"))
        assertFalse(sport("|IL| HOT 3", "ישראל"))
        assertFalse(sport("|IL| CHANNEL 12", "ישראל"))
    }

    @Test
    fun `foreign sports are not offered, however they are named`() {
        assertFalse(sport("סקיי ספורטס האירוע המרכזי HD", "ספורט | אנגליה"))
        assertFalse(sport("Sky Sports Main Event", "UK | Sports"))
        assertFalse(sport("|UK| SKY SPORTS F1", "ספורט | אנגליה", "SkySportsF1.uk"))
    }

    @Test
    fun `a country tag or a guide id makes a channel Israeli without a group`() {
        assertTrue(sport("|IL| SPORT 3", null))
        assertTrue(sport("IL: Sport 4", "Sports"))
        assertTrue(sport("Sport 5", null, "sport5.il"))
        assertTrue(sport("5 Channel", null, "5sport.il"))
        assertFalse(sport("Sport 5", null))
        assertFalse(sport("ILLUMINATE SPORTS", null))
    }

    @Test
    fun `every channel in an Israeli sports group is offered`() {
        assertTrue(sport("Charlton", "IL | Sports"))
        assertTrue(sport("וואן", "ספורט ישראלי"))
    }

    @Test
    fun `decorated names are folded before they are read`() {
        assertTrue(sport("|IL| ˢᴾᴼᴿᵀ 1 ᴴᴰ", "ישראל"))
    }
}
