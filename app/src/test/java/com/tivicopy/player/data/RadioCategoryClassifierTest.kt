package com.tivicopy.player.data

import com.tivicopy.player.data.radio.RadioCategory
import com.tivicopy.player.data.radio.RadioCategoryClassifier
import com.tivicopy.player.data.radio.RadioRegion
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
}
