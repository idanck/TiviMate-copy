package com.tivicopy.player.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Guide time handling.
 *
 * FR-EPG-10: everything is stored as epoch-millis UTC and only converted to a wall clock
 * at render time, using the device zone. FR-EPG-13 follows from that: a stored instant
 * cannot drift when the zone's DST rules change, because no local time is ever persisted.
 *
 * Offsets (FR-EPG-11/12) are applied to the *instant*, not to the wall clock, so a channel
 * whose provider publishes the guide an hour early stays aligned across a DST boundary.
 */
object TimeModel {

    const val OFFSET_STEP_MINUTES = 15
    val MAX_OFFSET_MINUTES = 24 * 60

    /** Applies the effective offset for a channel: per-channel override wins over global. */
    fun effectiveOffsetMinutes(globalMinutes: Int, perChannelMinutes: Int?): Int =
        perChannelMinutes ?: globalMinutes

    fun shift(utcMillis: Long, offsetMinutes: Int): Long =
        utcMillis + offsetMinutes * 60_000L

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun local(utcMillis: Long, zone: ZoneId = zone()): ZonedDateTime =
        Instant.ofEpochMilli(utcMillis).atZone(zone)

    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.ROOT)
    private val fileStamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm", Locale.ROOT)
    private const val FULL_DATE_PATTERN = "EEEE, d MMMM"
    private const val WEEKDAY_PATTERN = "EEEE"
    private const val SHORT_DATE_PATTERN = "EEEE dd.MM"

    fun formatClock(utcMillis: Long, zone: ZoneId = zone()): String =
        local(utcMillis, zone).format(hhmm)

    fun formatDay(utcMillis: Long, zone: ZoneId = zone()): String =
        local(utcMillis, zone).format(dayMonth)

    /**
     * Wraps [text] so it keeps its own direction inside a paragraph of the opposite one
     * (NFR-UX-08).
     *
     * Isolate characters, not marks: U+2066 opens a left-to-right isolate and U+2069 closes it,
     * which tells the bidirectional algorithm to resolve what is inside independently and to treat
     * the whole run as neutral from the outside. A mark would only nudge the characters next to it.
     */
    fun ltr(text: String): String = "⁦$text⁩"

    /**
     * A start–end clock range that still reads start-then-end in a Hebrew layout (FR-UI-21).
     *
     * `"$start – $end"` is three runs of strong left-to-right digits joined by a dash, and the dash
     * is bidirectionally neutral: in a right-to-left paragraph it takes the paragraph's direction
     * and the two times swap places on screen, so 14:00 – 15:30 is read as ending before it began.
     * Isolating the whole range fixes it once, here, rather than at each call site.
     */
    fun formatClockRange(startUtc: Long, endUtc: Long, zone: ZoneId = zone()): String =
        ltr("${formatClock(startUtc, zone)} – ${formatClock(endUtc, zone)}")

    /**
     * `Wednesday, 26 August` in [locale] — the date line under the radio backdrop's clock.
     *
     * Written as a pattern rather than [java.time.format.FormatStyle.FULL], which appends a year
     * nobody reading a clock needs, and built per call because the locale is the caller's: the
     * in-app language setting (FR-UI-23) means the interface language is not the platform's.
     */
    fun formatFullDate(
        utcMillis: Long,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = zone(),
    ): String = local(utcMillis, zone).format(DateTimeFormatter.ofPattern(FULL_DATE_PATTERN, locale))

    /**
     * `Wednesday` in [locale] — the day strip above the guide grid (ADR-020).
     *
     * Built per call for the same reason [formatFullDate] is: the in-app language setting means
     * the interface language is not necessarily the platform's, so the locale belongs to the
     * caller rather than to a formatter cached here.
     */
    fun formatWeekday(
        utcMillis: Long,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = zone(),
    ): String = local(utcMillis, zone).format(DateTimeFormatter.ofPattern(WEEKDAY_PATTERN, locale))

    /**
     * `Thursday 15.10` in [locale] — the date under the clock in the television's top bar
     * (ADR-027). Locale from the caller, for the reason [formatFullDate] gives.
     */
    fun formatShortDate(
        utcMillis: Long,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = zone(),
    ): String = local(utcMillis, zone).format(DateTimeFormatter.ofPattern(SHORT_DATE_PATTERN, locale))

    /** `dd/MM HH:mm`, isolated for the same reason as [formatClockRange]. */
    fun formatDayTime(utcMillis: Long, zone: ZoneId = zone()): String =
        ltr("${formatDay(utcMillis, zone)} ${formatClock(utcMillis, zone)}")

    fun formatForFilename(utcMillis: Long, zone: ZoneId = zone()): String =
        local(utcMillis, zone).format(fileStamp)

    /** Start of the local day containing [utcMillis], expressed back in UTC millis. */
    fun startOfLocalDay(utcMillis: Long, zone: ZoneId = zone()): Long =
        local(utcMillis, zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun clampOffset(minutes: Int): Int =
        minutes.coerceIn(-MAX_OFFSET_MINUTES, MAX_OFFSET_MINUTES)
            .let { (it / OFFSET_STEP_MINUTES) * OFFSET_STEP_MINUTES }

    /**
     * XMLTV timestamps look like `20260819143000 +0300`; the offset is optional and, when
     * absent, the specification says to assume the document is in the local zone of the
     * *producer*, which we cannot know — we treat it as UTC and let the user correct it
     * with a per-channel offset rather than guessing.
     */
    fun parseXmltvTime(raw: String): Long? {
        val s = raw.trim()
        if (s.length < 14) return null
        return try {
            val y = s.substring(0, 4).toInt()
            val mo = s.substring(4, 6).toInt()
            val d = s.substring(6, 8).toInt()
            val h = s.substring(8, 10).toInt()
            val mi = s.substring(10, 12).toInt()
            val se = s.substring(12, 14).toInt()
            val offsetPart = s.substring(14).trim()
            val offsetSeconds = parseOffsetSeconds(offsetPart)
            val ldt = java.time.LocalDateTime.of(y, mo, d, h, mi, se)
            ldt.toInstant(java.time.ZoneOffset.ofTotalSeconds(offsetSeconds)).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseOffsetSeconds(part: String): Int {
        if (part.isEmpty()) return 0
        val sign = when (part[0]) {
            '+' -> 1
            '-' -> -1
            else -> return 0
        }
        val digits = part.drop(1).filter { it.isDigit() }
        if (digits.length < 4) return 0
        val h = digits.substring(0, 2).toIntOrNull() ?: return 0
        val m = digits.substring(2, 4).toIntOrNull() ?: return 0
        return sign * (h * 3600 + m * 60)
    }

    /** Formats a duration as `1h 05m` / `45m`, for programme lengths and recording lengths. */
    fun formatDuration(millis: Long): String {
        val totalMinutes = (millis / 60_000L).coerceAtLeast(0)
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) String.format(Locale.ROOT, "%dh %02dm", h, m)
        else String.format(Locale.ROOT, "%dm", m)
    }
}
