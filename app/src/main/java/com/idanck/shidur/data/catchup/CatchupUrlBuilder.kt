package com.idanck.shidur.data.catchup

import com.idanck.shidur.data.model.CatchupMode
import com.idanck.shidur.data.model.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the archive URL for a past programme (FR-CU-03).
 *
 * Providers use a handful of incompatible conventions. Each supported mode is implemented
 * exactly as the corresponding playlist convention documents it, with both UTC and local
 * variants of every time token, because the same template appears with either.
 */
object CatchupUrlBuilder {

    /**
     * @param startUtc programme start, epoch millis.
     * @param endUtc programme end, epoch millis.
     * @param nowUtc current time, used for the `shift`/`offset` styles.
     * @return a playable URL, or null when the channel declares no usable archive.
     */
    fun build(channel: Channel, startUtc: Long, endUtc: Long, nowUtc: Long): String? {
        val base = channel.urls.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val durationSeconds = ((endUtc - startUtc) / 1000L).coerceAtLeast(1)
        val startSeconds = startUtc / 1000L
        val offsetSeconds = ((nowUtc - startUtc) / 1000L).coerceAtLeast(0)

        val template = channel.catchupSource?.takeIf { it.isNotBlank() }

        return when (channel.catchupMode) {
            CatchupMode.NONE -> null

            // An explicit template always wins, whatever the declared mode.
            CatchupMode.DEFAULT, CatchupMode.APPEND, CatchupMode.SHIFT,
            CatchupMode.FLUSSONIC, CatchupMode.XC,
            -> when {
                template != null && template.containsAnyToken() ->
                    substitute(template, startUtc, endUtc, durationSeconds, offsetSeconds)

                template != null && channel.catchupMode == CatchupMode.APPEND ->
                    base.appendQuery(substitute(template, startUtc, endUtc, durationSeconds, offsetSeconds).trimStart('?', '&'))

                channel.catchupMode == CatchupMode.APPEND ->
                    base.appendQuery("utc=$startSeconds&lutc=${nowUtc / 1000L}")

                channel.catchupMode == CatchupMode.SHIFT ->
                    base.appendQuery("utc=$startSeconds&lutc=${nowUtc / 1000L}")

                channel.catchupMode == CatchupMode.FLUSSONIC -> flussonic(base, startSeconds, durationSeconds)

                channel.catchupMode == CatchupMode.XC -> xtream(base, startUtc, durationSeconds)

                // `default` with no template: the widely-used `?utc=&lutc=` convention.
                else -> base.appendQuery("utc=$startSeconds&lutc=${nowUtc / 1000L}")
            }
        }
    }

    /** Whether the archive window still covers [startUtc] (FR-CU-05). */
    fun isWithinWindow(channel: Channel, startUtc: Long, nowUtc: Long): Boolean {
        if (channel.catchupDays <= 0) return false
        val windowStart = nowUtc - channel.catchupDays * 24L * 60 * 60 * 1000
        return startUtc in windowStart..nowUtc
    }

    private fun String.containsAnyToken(): Boolean =
        contains("\${") || contains("{") && contains("}")

    private fun String.appendQuery(query: String): String =
        if (query.isEmpty()) this
        else if (contains('?')) "$this&$query" else "$this?$query"

    /** Flussonic: `.../<name>/index.m3u8` becomes `.../<name>/<start>-<duration>.m3u8`. */
    private fun flussonic(base: String, startSeconds: Long, durationSeconds: Long): String {
        val query = base.substringAfter('?', "")
        val path = base.substringBefore('?')
        val replaced = when {
            path.endsWith("/index.m3u8") ->
                path.removeSuffix("/index.m3u8") + "/$startSeconds-$durationSeconds.m3u8"
            path.endsWith("/video.m3u8") ->
                path.removeSuffix("/video.m3u8") + "/$startSeconds-$durationSeconds.m3u8"
            path.endsWith(".m3u8") ->
                path.substringBeforeLast('/') + "/$startSeconds-$durationSeconds.m3u8"
            path.endsWith("/mpegts") ->
                path.removeSuffix("/mpegts") + "/timeshift_abs-$startSeconds.ts"
            else -> path.trimEnd('/') + "/$startSeconds-$durationSeconds.ts"
        }
        return if (query.isEmpty()) replaced else "$replaced?$query"
    }

    /**
     * Xtream Codes: `http://host:port/live/user/pass/123.ts` becomes
     * `http://host:port/streaming/timeshift.php?username=..&password=..&stream=123&start=..&duration=..`
     * with `start` in the provider's local wall-clock form `Y-m-d:H-i` and duration in minutes.
     */
    private fun xtream(base: String, startUtc: Long, durationSeconds: Long): String {
        val m = Regex("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(?:\\.[a-z0-9]+)?(?:\\?.*)?$", RegexOption.IGNORE_CASE)
            .find(base) ?: return base
        val (host, user, pass, streamId) = m.destructured
        val minutes = ((durationSeconds + 59) / 60).coerceAtLeast(1)
        val start = formatXtreamStart(startUtc)
        return "$host/streaming/timeshift.php?username=$user&password=$pass" +
            "&stream=$streamId&start=$start&duration=$minutes"
    }

    /**
     * Token substitution for `catchup-source` templates.
     * Supports the `${...}` and bare `{...}` spellings, UTC and local variants, and the
     * `strftime`-style `${start:Y-m-d}` form used by several panels.
     */
    internal fun substitute(
        template: String,
        startUtc: Long,
        endUtc: Long,
        durationSeconds: Long,
        offsetSeconds: Long,
    ): String {
        var out = template
        val startSec = startUtc / 1000L
        val endSec = endUtc / 1000L

        val simple = mapOf(
            "start" to startSec.toString(),
            "utc" to startSec.toString(),
            "start_timestamp" to startSec.toString(),
            "end" to endSec.toString(),
            "utcend" to endSec.toString(),
            "stop" to endSec.toString(),
            "duration" to durationSeconds.toString(),
            "durmin" to ((durationSeconds + 59) / 60).toString(),
            "offset" to offsetSeconds.toString(),
            "timestamp" to startSec.toString(),
            "lutc" to (System.currentTimeMillis() / 1000L).toString(),
            "now" to (System.currentTimeMillis() / 1000L).toString(),
        )
        simple.forEach { (k, v) ->
            out = out.replace("\${$k}", v, ignoreCase = true).replace("{$k}", v, ignoreCase = true)
        }

        // Date-pattern tokens: ${start:Y-m-d:H-i} / {utc:Y-m-d H:i:s}
        out = Regex("\\$?\\{(start|utc|end|utcend)\\s*:\\s*([^}]+)}", RegexOption.IGNORE_CASE)
            .replace(out) { m ->
                val instant = if (m.groupValues[1].lowercase().startsWith("utc") &&
                    m.groupValues[1].lowercase() != "utcend"
                ) startUtc else if (m.groupValues[1].lowercase().contains("end")) endUtc else startUtc
                formatStrftime(instant, m.groupValues[2])
            }
        return out
    }

    /** Maps the PHP-style letters providers use into a [SimpleDateFormat] pattern. */
    private fun formatStrftime(instantMillis: Long, phpPattern: String): String {
        val sb = StringBuilder()
        phpPattern.forEach { c ->
            when (c) {
                'Y' -> sb.append("yyyy")
                'y' -> sb.append("yy")
                'm' -> sb.append("MM")
                'd' -> sb.append("dd")
                'H' -> sb.append("HH")
                'i' -> sb.append("mm")
                's' -> sb.append("ss")
                'M' -> sb.append("MMM")
                else -> if (c.isLetter()) sb.append('\'').append(c).append('\'') else sb.append(c)
            }
        }
        val fmt = SimpleDateFormat(sb.toString(), Locale.ROOT)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(instantMillis))
    }

    /**
     * Xtream panels expect the start in the *server's* wall clock, which in practice means
     * the device's local time for a subscriber in the provider's region. SimpleDateFormat is
     * not thread-safe, so a fresh instance is built per call rather than shared.
     */
    private fun formatXtreamStart(startUtc: Long): String =
        SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.ROOT).format(Date(startUtc))
}
