package com.tivicopy.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * FR-EPG-10..13. The DST cases are the point of this file: the benchmark's guide is reported to
 * shift by an hour twice a year, and these tests pin the behaviour that prevents it.
 */
class TimeModelTest {

    private val london: ZoneId = ZoneId.of("Europe/London")
    private val jerusalem: ZoneId = ZoneId.of("Asia/Jerusalem")

    /**
     * The radio backdrop's date line. Pinned in the device zone the app is written for and in a
     * fixed locale, so this says what it means rather than what the runner's defaults happen to be.
     */
    @Test
    fun `formats a full date in the caller's locale`() {
        val noon = java.time.OffsetDateTime.parse("2026-08-26T09:00:00Z").toInstant().toEpochMilli()

        assertEquals(
            "Wednesday, 26 August",
            TimeModel.formatFullDate(noon, java.util.Locale.ENGLISH, jerusalem),
        )
    }

    /** The day name has to follow the interface language, not the platform's (FR-UI-23). */
    @Test
    fun `a Hebrew locale gets a Hebrew day name`() {
        val noon = java.time.OffsetDateTime.parse("2026-08-26T09:00:00Z").toInstant().toEpochMilli()

        val hebrew = TimeModel.formatFullDate(noon, java.util.Locale.forLanguageTag("he"), jerusalem)

        assertTrue(hebrew, hebrew.any { it in '\u0590'..'\u05FF' })
    }

    @Test
    fun `parses an XMLTV timestamp with an explicit offset`() {
        // 2026-08-19 14:30:00 +0300 is 11:30:00 UTC.
        val expected = java.time.OffsetDateTime.parse("2026-08-19T11:30:00Z").toInstant().toEpochMilli()
        assertEquals(expected, TimeModel.parseXmltvTime("20260819143000 +0300"))
    }

    @Test
    fun `an explicit offset round-trips to the same instant`() {
        val a = TimeModel.parseXmltvTime("20260819143000 +0300")!!
        val b = TimeModel.parseXmltvTime("20260819113000 +0000")!!
        assertEquals(a, b)
    }

    @Test
    fun `a missing offset is read as UTC`() {
        val withOffset = TimeModel.parseXmltvTime("20260819113000 +0000")!!
        val without = TimeModel.parseXmltvTime("20260819113000")!!
        assertEquals(withOffset, without)
    }

    @Test
    fun `rejects a malformed timestamp instead of guessing`() {
        assertNull(TimeModel.parseXmltvTime("nonsense"))
        assertNull(TimeModel.parseXmltvTime("2026"))
    }

    @Test
    fun `an instant renders at the same wall clock either side of a DST change`() {
        // 2026-03-29 is the UK's spring-forward date. A programme published as 20:00 local on
        // each side must still render as 20:00 local, which only holds if the stored value is
        // an instant with its own offset rather than a local time.
        val beforeDst = TimeModel.parseXmltvTime("20260328200000 +0000")!!
        val afterDst = TimeModel.parseXmltvTime("20260330200000 +0100")!!
        assertEquals("20:00", TimeModel.formatClock(beforeDst, london))
        assertEquals("20:00", TimeModel.formatClock(afterDst, london))
    }

    @Test
    fun `an offset shifts the instant, not the wall clock, across a DST boundary`() {
        val beforeDst = TimeModel.parseXmltvTime("20260328200000 +0000")!!
        val afterDst = TimeModel.parseXmltvTime("20260330200000 +0100")!!
        val shiftedBefore = TimeModel.shift(beforeDst, 60)
        val shiftedAfter = TimeModel.shift(afterDst, 60)
        assertEquals("21:00", TimeModel.formatClock(shiftedBefore, london))
        assertEquals("21:00", TimeModel.formatClock(shiftedAfter, london))
    }

    @Test
    fun `offsets are clamped to quarter-hour steps`() {
        assertEquals(15, TimeModel.clampOffset(20))
        assertEquals(-30, TimeModel.clampOffset(-38))
        assertEquals(0, TimeModel.clampOffset(7))
        assertEquals(1440, TimeModel.clampOffset(99999))
        assertEquals(-1440, TimeModel.clampOffset(-99999))
    }

    @Test
    fun `effective offset prefers the per-channel override`() {
        assertEquals(45, TimeModel.effectiveOffsetMinutes(globalMinutes = 15, perChannelMinutes = 45))
        assertEquals(15, TimeModel.effectiveOffsetMinutes(globalMinutes = 15, perChannelMinutes = null))
        // A per-channel zero is a real choice and must not fall back to the global value.
        assertEquals(0, TimeModel.effectiveOffsetMinutes(globalMinutes = 15, perChannelMinutes = 0))
    }

    @Test
    fun `start of the local day respects the rendering zone`() {
        val instant = TimeModel.parseXmltvTime("20260819230000 +0000")!!
        val startInJerusalem = TimeModel.startOfLocalDay(instant, jerusalem)
        assertEquals("00:00", TimeModel.formatClock(startInJerusalem, jerusalem))
    }

    @Test
    fun `formats durations`() {
        assertEquals("45m", TimeModel.formatDuration(45 * 60_000L))
        assertEquals("1h 05m", TimeModel.formatDuration(65 * 60_000L))
        assertEquals("0m", TimeModel.formatDuration(-5))
    }

    /**
     * FR-UI-21. Without the isolate, the dash between two runs of digits is neutral and takes the
     * paragraph's direction, so a Hebrew layout draws the range end-first.
     */
    @Test
    fun `a clock range is isolated so it survives a right-to-left layout`() {
        val start = TimeModel.parseXmltvTime("20260819140000 +0000")!!
        val end = TimeModel.parseXmltvTime("20260819153000 +0000")!!
        val range = TimeModel.formatClockRange(start, end, london)

        assertTrue(range.startsWith("⁦"))
        assertTrue(range.endsWith("⁩"))
        assertEquals("15:00 – 16:30", range.trim('⁦', '⁩'))
    }

    @Test
    fun `a day and time is isolated the same way`() {
        val at = TimeModel.parseXmltvTime("20260819140000 +0000")!!
        assertEquals("19/08 15:00", TimeModel.formatDayTime(at, london).trim('⁦', '⁩'))
    }

    @Test
    fun `isolating leaves the text itself untouched`() {
        assertEquals("-1d", TimeModel.ltr("-1d").trim('⁦', '⁩'))
    }
}
