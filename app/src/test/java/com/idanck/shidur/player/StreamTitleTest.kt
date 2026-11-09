package com.idanck.shidur.player

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The strings here are what the bundled stations actually sent, captured off their streams —
 * including the broken ones, which are the reason [StreamTitle] exists.
 */
class StreamTitleTest {

    @Test
    fun `plain titles pass through`() {
        assertEquals("Sting - Fields Of Gold", StreamTitle.clean("Sting - Fields Of Gold"))
        assertEquals("קובי פרץ - אני ואת", StreamTitle.clean("קובי פרץ - אני ואת"))
        assertEquals("Destiny's Child - Independent Women", StreamTitle.clean("Destiny's Child - Independent Women"))
    }

    @Test
    fun `windows-1255 hebrew read as latin-1 is repaired`() {
        assertEquals(
            "אתניקס ואייל גולן - קן של אהבה",
            StreamTitle.clean("àúðé÷ñ åàééì âåìï - ÷ï ùì àäáä"),
        )
        assertEquals("שעה 4", StreamTitle.clean("ùòä 4"))
    }

    @Test
    fun `hebrew beside an english artist is repaired too`() {
        assertEquals(
            "Static & Ben El Tavori - קן של אהבה",
            StreamTitle.clean("Static & Ben El Tavori - ÷ï ùì àäáä"),
        )
    }

    @Test
    fun `windows-1255 read as windows-1252 is repaired, punctuation and all`() {
        // 0x96 is an en dash in both code pages, so a Windows server hands it over as U+2013.
        assertEquals("אהבה – שיר", StreamTitle.clean("àäáä \u2013 ùéø"))
    }

    @Test
    fun `latin titles with the odd accent are left alone`() {
        assertEquals("Café del Mar - Energy 52", StreamTitle.clean("Café del Mar - Energy 52"))
        val bach = "Carl Philipp Emanuel Bach - Sinfonie C-Dur"
        assertEquals(bach, StreamTitle.clean(bach))
    }

    @Test
    fun `placeholders and empties are not songs`() {
        assertNull(StreamTitle.clean(""))
        assertNull(StreamTitle.clean("   "))
        assertNull(StreamTitle.clean("kz"))
        assertNull(StreamTitle.clean("CDN - Powered By Multix Technologies"))
        assertNull(StreamTitle.clean("Streaming Powered By Multix"))
        assertNull(StreamTitle.clean("AzuraCast.com - AzuraCast is Live!"))
        assertNull(StreamTitle.clean("Adobe Audition CC 2024-11-06 162408 DESKTOP-QH05J21"))
        assertNull(StreamTitle.clean(null))
    }

    @Test
    fun `bytes already lost upstream are dropped rather than shown`() {
        assertNull(StreamTitle.clean("��� ��� - ��"))
    }

    @Test
    fun `the station's own name is not a song`() {
        assertNull(StreamTitle.clean("RADIO ONE", stationName = "Radio One"))
        assertEquals("RADIO ONE", StreamTitle.clean("RADIO ONE", stationName = "רדיו 1"))
    }

    @Test
    fun `whitespace and dangling dashes are tidied`() {
        assertEquals("Walter Smith III - Circus", StreamTitle.clean("  Walter Smith III  -  Circus "))
        assertEquals("Andy Gibb", StreamTitle.clean("Andy Gibb - "))
    }

    @Test
    fun `icy title tells no title apart from an empty one`() {
        assertNull(StreamTitle.icyTitle(Metadata()))
        // A block carrying only a StreamUrl says nothing about the song.
        assertNull(StreamTitle.icyTitle(Metadata(IcyInfo(ByteArray(0), null, "http://x/art.jpg"))))
        assertEquals("", StreamTitle.icyTitle(Metadata(IcyInfo(ByteArray(0), "", null))))
        assertEquals("A - B", StreamTitle.icyTitle(Metadata(IcyInfo(ByteArray(0), "A - B", null))))
    }
}
