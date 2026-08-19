package com.tivicopy.player.core

import java.text.Normalizer
import java.util.Locale

/**
 * Name normalisation shared by the EPG matcher (FR-EPG-07) and by refresh identity
 * resolution (FR-SRC-11). Kept deliberately free of Android APIs (NFR-MAINT-01).
 */
object TextNormalizer {

    /** Quality/definition markers and separators that carry no identity information. */
    private val NOISE_TOKENS = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "1080", "1080p",
        "720", "720p", "576", "480", "raw", "backup", "alt", "vip", "plus", "hq", "lq",
        "hdr", "dolby", "atmos", "50fps", "60fps", "multi", "mux",
    )

    private val BRACKETED = Regex("[\\[(<{][^\\])>}]*[\\])>}]")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{Nd}]+")
    private val LEADING_NUMBER = Regex("^\\d{1,4}[.:)\\-]\\s*")

    /** Lowercase, de-accent, drop punctuation; keeps every meaningful character. */
    fun basic(input: String): String {
        val decomposed = Normalizer.normalize(input.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return decomposed.lowercase(Locale.ROOT).replace(NON_ALNUM, " ").trim()
    }

    /**
     * Aggressive form used for stage-2 matching: drops bracketed suffixes, a leading
     * channel number, and the quality tokens providers append inconsistently.
     */
    fun canonical(input: String): String {
        var s = input.trim()
        s = BRACKETED.replace(s, " ")
        s = LEADING_NUMBER.replace(s, "")
        s = basic(s)
        val kept = s.split(' ').filter { it.isNotBlank() && it !in NOISE_TOKENS }
        return kept.joinToString(" ")
    }

    /** Token set used by the fuzzy scorer. */
    fun tokens(input: String): Set<String> =
        canonical(input).split(' ').filter { it.isNotBlank() }.toSet()

    /**
     * Similarity in `0.0..1.0`. Combines Jaccard over tokens with a normalised
     * Levenshtein over the canonical strings, so `"BBC One HD"` and `"BBC 1"` still
     * score usefully while `"BBC One"` and `"CBS One"` do not.
     */
    fun similarity(a: String, b: String): Double {
        val ca = canonical(a)
        val cb = canonical(b)
        if (ca.isEmpty() || cb.isEmpty()) return 0.0
        if (ca == cb) return 1.0

        val ta = tokens(a)
        val tb = tokens(b)
        val intersection = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        val jaccard = if (union == 0.0) 0.0 else intersection / union

        val distance = levenshtein(ca, cb)
        val maxLen = maxOf(ca.length, cb.length).toDouble()
        val edit = if (maxLen == 0.0) 0.0 else 1.0 - (distance / maxLen)

        return (0.6 * jaccard + 0.4 * edit).coerceIn(0.0, 1.0)
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val swap = prev; prev = cur; cur = swap
        }
        return prev[b.length]
    }

    /** Strips credentials from a URL so it can be shown in diagnostics (NFR-SEC-02). */
    fun redactUrl(url: String): String {
        var out = url
        out = Regex("(?i)([?&](?:username|user|password|pass|token|auth|key)=)[^&\\s]*")
            .replace(out) { m -> m.groupValues[1] + "***" }
        out = Regex("//([^/@\\s:]+):([^/@\\s]+)@").replace(out, "//***:***@")
        // Xtream stream paths are /live/<user>/<pass>/<id>.ts
        out = Regex("(?i)/(live|movie|series|timeshift)/([^/]+)/([^/]+)/")
            .replace(out) { m -> "/${m.groupValues[1]}/***/***/" }
        return out
    }
}
