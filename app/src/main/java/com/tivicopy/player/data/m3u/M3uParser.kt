package com.tivicopy.player.data.m3u

import com.tivicopy.player.core.TextNormalizer
import com.tivicopy.player.data.model.CatchupMode
import java.io.BufferedReader

/** One entry of a parsed playlist, still free of database identity. */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val tvgLogo: String? = null,
    val tvgChno: Int? = null,
    val tvgShiftMinutes: Int = 0,
    val groupTitle: String? = null,
    val catchupMode: CatchupMode = CatchupMode.NONE,
    val catchupSource: String? = null,
    val catchupDays: Int = 0,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val licenseType: String? = null,
    val licenseKey: String? = null,
    /** Everything from the `#EXTINF` attribute list we did not map (FR-SRC-05). */
    val extraAttributes: Map<String, String> = emptyMap(),
) {
    /** FR-SRC-11 identity: tvg-id, then a stream id from the URL, then the normalised name. */
    val identity: String
        get() = tvgId?.takeIf { it.isNotBlank() }?.let { "tvg:$it" }
            ?: streamIdFromUrl(url)?.let { "sid:$it" }
            ?: "name:${TextNormalizer.canonical(name)}"

    companion object {
        private val XTREAM_PATH = Regex("/(?:live|movie|series)/[^/]+/[^/]+/(\\d+)(?:\\.[a-z0-9]+)?$", RegexOption.IGNORE_CASE)

        fun streamIdFromUrl(url: String): String? = XTREAM_PATH.find(url)?.groupValues?.get(1)
    }
}

data class M3uPlaylist(
    val entries: List<M3uEntry>,
    /** `url-tvg` / `x-tvg-url` from the `#EXTM3U` header (FR-EPG-03). */
    val epgUrl: String? = null,
    val globalUserAgent: String? = null,
    val malformedLines: Int = 0,
)

/**
 * Streaming M3U/M3U8 reader.
 *
 * Real provider playlists are routinely malformed — unquoted attributes, stray blank lines,
 * `#EXTINF` without a following URL, Windows line endings, BOMs. Nothing here throws on bad
 * input: an unusable entry is counted in [M3uPlaylist.malformedLines] and skipped, because a
 * single broken line must not cost the user their whole playlist.
 */
object M3uParser {

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    private val ATTRIBUTES = Regex("([A-Za-z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))")

    fun parse(reader: BufferedReader): M3uPlaylist {
        val entries = ArrayList<M3uEntry>(1024)
        var epgUrl: String? = null
        var globalUa: String? = null
        var malformed = 0

        var pending: PendingEntry? = null

        reader.forEachLine { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    epgUrl = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                    globalUa = attrs["user-agent"]
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    if (pending != null) malformed++ // previous #EXTINF never got a URL
                    pending = parseExtInf(line)
                }

                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                    pending?.applyVlcOpt(line.substringAfter(':', "").trim())
                }

                line.startsWith("#KODIPROP", ignoreCase = true) -> {
                    pending?.applyKodiProp(line.substringAfter(':', "").trim())
                }

                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    val g = line.substringAfter(':', "").trim()
                    if (g.isNotEmpty()) pending?.let { if (it.groupTitle.isNullOrBlank()) it.groupTitle = g }
                }

                line.startsWith("#") -> Unit // comment or a directive we do not use

                else -> {
                    val p = pending
                    if (p == null) {
                        // A bare URL with no #EXTINF is still a playable channel — but only a URL.
                        // A suspended account or a captive portal answers 200 with an HTML page,
                        // and taking its lines as channels made the refresh "succeed" and the
                        // merge delete every real channel the user had.
                        if (looksLikeAddress(line)) {
                            entries += M3uEntry(name = deriveName(line), url = line)
                        } else {
                            malformed++
                        }
                    } else {
                        entries += p.toEntry(line, globalUa)
                        pending = null
                    }
                }
            }
        }
        if (pending != null) malformed++

        return M3uPlaylist(entries, epgUrl?.takeIf { it.isNotBlank() }, globalUa, malformed)
    }

    /** A scheme (`http://`, `rtsp://`, `content://`…) or an absolute path on the device. */
    private fun looksLikeAddress(line: String): Boolean =
        SCHEME.containsMatchIn(line) || line.startsWith("/")

    private fun deriveName(url: String): String =
        url.substringAfterLast('/').substringBefore('?').ifBlank { url }

    private fun attributes(line: String): Map<String, String> =
        ATTRIBUTES.findAll(line).associate { m ->
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
            key to value
        }

    private fun parseExtInf(line: String): PendingEntry {
        // `#EXTINF:-1 tvg-id="a" group-title="b",Display Name`
        val afterColon = line.substringAfter(':', "")
        val commaIndex = firstAttributeSafeComma(afterColon)
        val attributePart = if (commaIndex >= 0) afterColon.substring(0, commaIndex) else afterColon
        val displayName = if (commaIndex >= 0) afterColon.substring(commaIndex + 1).trim() else ""
        val attrs = attributes(attributePart).toMutableMap()

        val known = setOf(
            "tvg-id", "tvg-name", "tvg-logo", "tvg-chno", "tvg-shift", "group-title",
            "catchup", "catchup-source", "catchup-days", "catchup-type", "timeshift",
            "user-agent", "channel-id", "tvg-rec",
        )
        val extras = attrs.filterKeys { it !in known }

        val catchupRaw = attrs["catchup"] ?: attrs["catchup-type"]
        val catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"] ?: attrs["tvg-rec"])
            ?.trim()?.toIntOrNull() ?: 0
        val mode = when {
            catchupRaw != null -> CatchupMode.from(catchupRaw)
            catchupDays > 0 -> CatchupMode.DEFAULT
            else -> CatchupMode.NONE
        }

        return PendingEntry(
            name = displayName.ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { "Unnamed" },
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
            tvgLogo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            tvgChno = attrs["tvg-chno"]?.trim()?.toIntOrNull(),
            tvgShiftMinutes = parseShiftMinutes(attrs["tvg-shift"]),
            groupTitle = attrs["group-title"]?.takeIf { it.isNotBlank() },
            catchupMode = if (catchupDays > 0 || catchupRaw != null) mode else CatchupMode.NONE,
            catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() },
            catchupDays = catchupDays,
            userAgent = attrs["user-agent"]?.takeIf { it.isNotBlank() },
            extraAttributes = extras,
        )
    }

    /**
     * `#EXTINF:<duration> <attributes>,<display name>` — the *first* comma outside a quoted
     * attribute value ends the attribute list, and everything after it is the name.
     *
     * It has to be the first and not the last: provider names such as `Channel 4, HD` contain
     * commas of their own, and splitting on the last one would truncate them to ` HD`.
     * Unquoted attribute values cannot contain a comma, so the first unquoted comma is
     * unambiguous.
     */
    private fun firstAttributeSafeComma(s: String): Int {
        var inQuote = false
        var quoteChar = ' '
        for (i in s.indices) {
            val c = s[i]
            when {
                inQuote && c == quoteChar -> inQuote = false
                !inQuote && (c == '"' || c == '\'') -> { inQuote = true; quoteChar = c }
                !inQuote && c == ',' -> return i
            }
        }
        return -1
    }

    /** `tvg-shift` is documented in hours and seen in the wild as `1`, `-2`, `1.5`, `+0.5`. */
    internal fun parseShiftMinutes(raw: String?): Int {
        val v = raw?.trim()?.removePrefix("+") ?: return 0
        if (v.isEmpty()) return 0
        val hours = v.toDoubleOrNull() ?: return 0
        return Math.round(hours * 60).toInt()
    }

    private class PendingEntry(
        val name: String,
        val tvgId: String?,
        val tvgName: String?,
        val tvgLogo: String?,
        val tvgChno: Int?,
        val tvgShiftMinutes: Int,
        var groupTitle: String?,
        val catchupMode: CatchupMode,
        val catchupSource: String?,
        val catchupDays: Int,
        var userAgent: String?,
        val extraAttributes: Map<String, String>,
    ) {
        val headers = LinkedHashMap<String, String>()
        var licenseType: String? = null
        var licenseKey: String? = null

        /** `http-user-agent=...`, `http-referrer=...`, `http-header=Name: value`. */
        fun applyVlcOpt(option: String) {
            val key = option.substringBefore('=').trim().lowercase()
            val value = option.substringAfter('=', "").trim()
            if (value.isEmpty()) return
            when (key) {
                "http-user-agent" -> userAgent = value
                "http-referrer", "http-referer" -> headers["Referer"] = value
                "http-origin" -> headers["Origin"] = value
                "http-cookie" -> headers["Cookie"] = value
                "http-header" -> {
                    val hName = value.substringBefore(':').trim()
                    val hValue = value.substringAfter(':', "").trim()
                    if (hName.isNotEmpty() && hValue.isNotEmpty()) headers[hName] = hValue
                }
            }
        }

        /** `inputstream.adaptive.license_type=...`, `...license_key=...`. */
        fun applyKodiProp(option: String) {
            val key = option.substringBefore('=').trim().lowercase()
            val value = option.substringAfter('=', "").trim()
            if (value.isEmpty()) return
            when {
                key.endsWith("license_type") -> licenseType = value
                key.endsWith("license_key") -> licenseKey = value
                key.endsWith("stream_headers") -> parseHeaderBlob(value).forEach { (k, v) -> headers[k] = v }
            }
        }

        private fun parseHeaderBlob(blob: String): Map<String, String> =
            blob.split('&').mapNotNull { pair ->
                val k = pair.substringBefore('=').trim()
                val v = pair.substringAfter('=', "").trim()
                if (k.isEmpty() || v.isEmpty()) null else k to java.net.URLDecoder.decode(v, "UTF-8")
            }.toMap()

        fun toEntry(url: String, globalUa: String?) = M3uEntry(
            name = name,
            url = url,
            tvgId = tvgId,
            tvgName = tvgName,
            tvgLogo = tvgLogo,
            tvgChno = tvgChno,
            tvgShiftMinutes = tvgShiftMinutes,
            groupTitle = groupTitle,
            catchupMode = catchupMode,
            catchupSource = catchupSource,
            catchupDays = catchupDays,
            userAgent = userAgent ?: globalUa,
            headers = headers.toMap(),
            licenseType = licenseType,
            licenseKey = licenseKey,
            extraAttributes = extraAttributes,
        )
    }
}
