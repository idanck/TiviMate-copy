package com.tivicopy.player.data

import com.tivicopy.player.data.radio.RadioCategory
import com.tivicopy.player.data.radio.RadioCategoryClassifier
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
}
