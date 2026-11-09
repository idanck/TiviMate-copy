package com.idanck.shidur.data

import com.idanck.shidur.data.radio.RadioCategory
import com.idanck.shidur.data.radio.RadioCategoryClassifier
import com.idanck.shidur.data.radio.RadioRegion
import com.idanck.shidur.data.radio.RadioShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-015's two documented group-titles, plus the genre/Russian keyword heuristics. */
class RadioCategoryClassifierTest {

    @Test
    fun `national group-title classifies as national`() {
        assertEquals(RadioCategory.NATIONAL, RadioCategoryClassifier.classify("רדיו ישראלי", "גלגלצ"))
    }

    @Test
    fun `regional group-title classifies as regional`() {
        assertEquals(RadioCategory.REGIONAL, RadioCategoryClassifier.classify("רדיו אזורי", "רדיו דרום"))
    }

    @Test
    fun `station name genre keyword wins over the group-title`() {
        assertEquals(
            RadioCategory.GENRE("יווני"),
            RadioCategoryClassifier.classify("רדיו ישראלי", "רדיו יווני"),
        )
    }

    @Test
    fun `unrecognised group falls back to other rather than being dropped`() {
        assertEquals(RadioCategory.OTHER, RadioCategoryClassifier.classify("קבוצה חדשה", "תחנה כלשהי"))
    }

    @Test
    fun `russian keyword is flagged regardless of group`() {
        assertTrue(RadioCategoryClassifier.isRussian("Radio Russkoe", "רדיו ישראלי"))
        assertTrue(RadioCategoryClassifier.isRussian("רדיו רוסי", null))
    }

    @Test
    fun `cyrillic station name is flagged even without a keyword match`() {
        assertTrue(RadioCategoryClassifier.isRussian("Русское радио", null))
    }

    @Test
    fun `hebrew station name is not flagged as russian`() {
        assertFalse(RadioCategoryClassifier.isRussian("גלגלצ", "רדיו ישראלי"))
    }

    /**
     * The eight regional stations ADR-015 lists, each by the name the feed gives it: this is the
     * split the car's Regional folder is grouped and ordered by, so getting one of them wrong
     * puts a station under the wrong heading rather than merely mislabelling it.
     */
    @Test
    fun `each documented regional station lands in its own region`() {
        assertEquals(RadioRegion.NORTH, RadioCategoryClassifier.region("רדיו חיפה"))
        assertEquals(RadioRegion.NORTH, RadioCategoryClassifier.region("104.5 רדיו צפון"))
        assertEquals(RadioRegion.NORTH, RadioCategoryClassifier.region("קול רגע"))
        assertEquals(RadioRegion.CENTER, RadioCategoryClassifier.region("רדיו תל אביב"))
        assertEquals(RadioRegion.CENTER, RadioCategoryClassifier.region("רדיו לב המדינה"))
        assertEquals(RadioRegion.CENTER, RadioCategoryClassifier.region("רדיו 90"))
        assertEquals(RadioRegion.JERUSALEM, RadioCategoryClassifier.region("רדיו ירושלים"))
        assertEquals(RadioRegion.SOUTH, RadioCategoryClassifier.region("רדיו דרום"))
    }

    @Test
    fun `an unplaceable station name falls back to unknown rather than a guess`() {
        assertEquals(RadioRegion.UNKNOWN, RadioCategoryClassifier.region("תחנה כלשהי"))
    }

    /** North sorts before every other region, which is the order the car lists them in. */
    @Test
    fun `region order puts the north first and the unplaceable last`() {
        val shuffled = listOf(
            RadioRegion.SOUTH,
            RadioRegion.UNKNOWN,
            RadioRegion.NORTH,
            RadioRegion.JERUSALEM,
            RadioRegion.CENTER,
        )
        assertEquals(
            listOf(
                RadioRegion.NORTH,
                RadioRegion.CENTER,
                RadioRegion.JERUSALEM,
                RadioRegion.SOUTH,
                RadioRegion.UNKNOWN,
            ),
            shuffled.sortedBy { it.ordinal },
        )
    }

    /** A latin-script feed name has to place the same way its Hebrew equivalent does. */
    @Test
    fun `latin station names are placed too`() {
        assertEquals(RadioRegion.NORTH, RadioCategoryClassifier.region("Radio Haifa"))
        assertEquals(RadioRegion.SOUTH, RadioCategoryClassifier.region("Radio Darom"))
        assertEquals(RadioRegion.JERUSALEM, RadioCategoryClassifier.region("Radio Jerusalem"))
    }

    // ------------------------------------------------------------ the menu (ADR-050)

    /**
     * The names the feed actually carries that the first keyword lists missed: the Jezreel valley
     * and the Golan are wholly in the north, and "רדיו תשעים" is the feed's spelling of Radio 90.
     */
    @Test
    fun `the feed's own spellings of northern and central stations are placed`() {
        assertEquals(RadioRegion.NORTH, RadioCategoryClassifier.region("רדיו קול יזרעאל 106FM"))
        assertEquals(RadioRegion.NORTH, RadioCategoryClassifier.region("רדיו קול הגולן"))
        assertEquals(RadioRegion.CENTER, RadioCategoryClassifier.region("רדיו תשעים"))
        // Radio 90 as a phrase: a station for the nineties is not in the centre for it.
        assertEquals(RadioRegion.UNKNOWN, RadioCategoryClassifier.region("רדיו שנות התשעים"))
    }

    /** The feed's Greek station is named after the country, not the music. */
    @Test
    fun `a station named after Greece is on the Greek shelf`() {
        assertEquals(RadioCategory.GENRE("יווני"), RadioCategoryClassifier.classify("יווני", "רדיו כחול יוון"))
        assertEquals(RadioCategory.GENRE("יווני"), RadioCategoryClassifier.classify(null, "רדיו יווני"))
    }

    /**
     * Every station is on exactly one shelf of the menu's first part: the region its name names,
     * "other regions" when the feed files it as regional without saying where, and national when
     * the name names no place at all.
     */
    @Test
    fun `a station's place on the menu comes from its name`() {
        assertEquals(
            RadioShelf.Regional(RadioRegion.NORTH),
            RadioCategoryClassifier.regionShelf("מוזיקה", "רדיו צפון 104.5"),
        )
        assertEquals(
            RadioShelf.Regional(RadioRegion.JERUSALEM),
            RadioCategoryClassifier.regionShelf("אקטואליה", "רדיו ירושלים"),
        )
        assertEquals(RadioShelf.National, RadioCategoryClassifier.regionShelf("מוזיקה", "גלגלצ"))
        assertEquals(RadioShelf.National, RadioCategoryClassifier.regionShelf(null, "כאן ב"))
        assertEquals(
            RadioShelf.Regional(RadioRegion.UNKNOWN),
            RadioCategoryClassifier.regionShelf("אזורי", "רדיו קול גלים"),
        )
        // The name wins over the feed's group: a regional station that names its region is there.
        assertEquals(
            RadioShelf.Regional(RadioRegion.SOUTH),
            RadioCategoryClassifier.regionShelf("אזורי", "רדיו דרום"),
        )
    }

    /** A genre is a second shelf beside the station's place, never instead of it. */
    @Test
    fun `a station with a genre is on its place's shelf and its genre's`() {
        assertEquals(
            listOf(RadioShelf.National, RadioShelf.Genre("מזרחי")),
            RadioCategoryClassifier.shelves("ים תיכוני", "רדיו קצב מזרחית"),
        )
        assertEquals(
            listOf(RadioShelf.Regional(RadioRegion.NORTH), RadioShelf.Genre("מזרחי")),
            RadioCategoryClassifier.shelves("מוזיקה", "רדיו מזרחי חיפה"),
        )
        assertEquals(listOf(RadioShelf.National), RadioCategoryClassifier.shelves("מוזיקה", "כאן 88"))
    }

    /** The owner's order: national, the regions north to south, then the genres. */
    @Test
    fun `the menu lists national, the regions from the north, then the genres`() {
        val stations = listOf(
            "מוזיקה" to "רדיו מזרחי",
            "מוזיקה" to "רדיו דרום",
            "יווני" to "רדיו כחול יוון",
            "אזורי" to "רדיו קול השפלה",
            "אקטואליה" to "רדיו ירושלים",
            "מוזיקה" to "רדיו 102 תל אביב",
            "מוזיקה" to "רדיו חיפה",
            "מוזיקה" to "גלגלצ",
        )
        assertEquals(
            listOf(
                RadioShelf.National,
                RadioShelf.Regional(RadioRegion.NORTH),
                RadioShelf.Regional(RadioRegion.CENTER),
                RadioShelf.Regional(RadioRegion.JERUSALEM),
                RadioShelf.Regional(RadioRegion.SOUTH),
                RadioShelf.Regional(RadioRegion.UNKNOWN),
                RadioShelf.Genre("יווני"),
                RadioShelf.Genre("מזרחי"),
            ),
            RadioCategoryClassifier.menu(stations),
        )
    }

    /** A shelf nobody is on is not listed — no empty region, no empty genre, no "other". */
    @Test
    fun `empty shelves are left out of the menu`() {
        assertEquals(
            listOf(RadioShelf.National, RadioShelf.Regional(RadioRegion.SOUTH)),
            RadioCategoryClassifier.menu(listOf("מוזיקה" to "גלגלצ", "מוזיקה" to "רדיו אילת 102FM")),
        )
        // Only regional stations: no national shelf.
        assertEquals(
            listOf(RadioShelf.Regional(RadioRegion.NORTH)),
            RadioCategoryClassifier.menu(listOf("מוזיקה" to "רדיו קריות")),
        )
        assertEquals(emptyList<RadioShelf>(), RadioCategoryClassifier.menu(emptyList()))
    }

    /**
     * The regional stations in the feed as it stood on 2026-10-30 (`radio.m3u`, 98 stations), each
     * on the shelf ADR-050 reports for it.
     */
    @Test
    fun `the feed's regional stations land on their shelves`() {
        fun shelf(name: String, group: String = "מוזיקה") = RadioCategoryClassifier.regionShelf(group, name)
        val north = RadioShelf.Regional(RadioRegion.NORTH)
        val center = RadioShelf.Regional(RadioRegion.CENTER)
        val south = RadioShelf.Regional(RadioRegion.SOUTH)
        listOf(
            "רדיו חיפה", "רדיו קול רגע", "רדיו קול יזרעאל 106FM", "רדיו קול הכנרת",
            "רדיו קול הגליל העליון 105.3FM", "רדיו צפון 104.5", "רדיו קול הגולן", "רדיו קריות",
        ).forEach { assertEquals(it, north, shelf(it)) }
        listOf(
            "רדיו תשעים", "רדיו לב המדינה", "רדיו 102 תל אביב", "רדיו קול נתניה 106FM",
            "קול רמת השרון", "רדיו קול המרכז",
        ).forEach { assertEquals(it, center, shelf(it)) }
        assertEquals(RadioShelf.Regional(RadioRegion.JERUSALEM), shelf("רדיו ירושלים", "אקטואליה"))
        listOf("רדיו דרום", "רדיו אילת 102FM", "רדיו דרום 101.5").forEach { assertEquals(it, south, shelf(it)) }
        listOf("רדיו קול השפלה", "רדיו קול גלים").forEach {
            assertEquals(it, RadioShelf.Regional(RadioRegion.UNKNOWN), shelf(it, "אזורי"))
        }
        listOf("כאן 88", "גלגלצ", "גלי צהל", "רדיו 103fm", "רדיוס 100FM", "רדיו 99FM").forEach {
            assertEquals(it, RadioShelf.National, shelf(it))
        }
    }
}
