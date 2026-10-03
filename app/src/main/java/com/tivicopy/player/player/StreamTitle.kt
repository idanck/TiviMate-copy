package com.tivicopy.player.player

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import java.nio.charset.Charset

/**
 * The song a radio stream says it is playing, as something fit to put on a screen.
 *
 * Shoutcast/Icecast streams interleave a `StreamTitle='…';` block with the audio when asked to
 * (`Icy-MetaData: 1`, which ExoPlayer sends on its own for every progressive stream), and
 * ExoPlayer hands it out as an [IcyInfo]. No request of our own and no per-station API: the
 * title rides inside the stream the player is already reading. What arrives is whatever the
 * station's playout software typed, though, so it is cleaned rather than trusted:
 *
 * - **Hebrew that arrives as Latin gibberish is repaired.** A number of Israeli stations send
 *   Windows-1255 bytes, either raw or re-encoded as UTF-8 by a server that took them for
 *   Latin-1. Either way the player ends up with Latin-1 letters where Hebrew ones should be
 *   ("àäáä" for "אהבה"), and those bytes read back as Windows-1255 are exactly the original.
 * - **Placeholders are dropped.** Several CDNs fill the field with their own name ("CDN -
 *   Powered By Multix"), a server default ("AzuraCast is Live!"), or the station's name; none of
 *   that is a song, and printing it under the station's name would be noise dressed as a feature.
 *
 * Pure, so the arithmetic of it is pinned by `StreamTitleTest`; whether a given station sends
 * anything at all is a fact about that station, not about this code.
 */
object StreamTitle {

    /**
     * The raw `StreamTitle` of the first ICY entry in [metadata], or null when the batch carries
     * none. Null and "" mean different things to a caller: no ICY entry says nothing about the
     * song, an empty title says the station has stopped announcing one.
     */
    fun icyTitle(metadata: Metadata): String? {
        for (i in 0 until metadata.length()) {
            val entry = metadata.get(i)
            if (entry is IcyInfo) return entry.title ?: ""
        }
        return null
    }

    /** [raw] as a title worth showing, or null when it is empty, broken or a placeholder. */
    fun clean(raw: String?, stationName: String? = null): String? {
        if (raw == null) return null
        // A replacement character means the bytes were already lost upstream; there is nothing
        // to repair and a row of diamonds is not a song.
        if (raw.contains('�')) return null
        val text = repairHebrew(raw)
            .replace(WHITESPACE, " ")
            .trim()
            .removePrefix("-").removeSuffix("-")
            .trim()
        if (text.length < MIN_LENGTH) return null
        if (PLACEHOLDERS.any { text.contains(it, ignoreCase = true) }) return null
        if (stationName != null && text.equals(stationName.trim(), ignoreCase = true)) return null
        return text
    }

    /**
     * Windows-1255 Hebrew that was read as Latin-1, read back as Windows-1255; anything else
     * unchanged.
     *
     * Only when the string could be nothing else: no real Hebrew in it already, nothing outside
     * Latin-1 (so the round trip is lossless), and at least half its letters in the Latin-1 range
     * Windows-1255 puts the Hebrew alphabet at. A French or German title has the odd accented
     * letter in that range and keeps it — "Café del Mar" is not Hebrew.
     */
    internal fun repairHebrew(text: String): String {
        if (text.any { it in HEBREW_BLOCK }) return text
        if (text.any { it.code > 0xFF }) return text
        val hebrewShaped = text.count { it.code in HEBREW_IN_1255 }
        if (hebrewShaped < 2) return text
        // One of the 27 letters, ק (0xF7), is the division sign in Latin-1, so it is counted as
        // Hebrew-shaped but would not be counted as a letter.
        val letters = text.count { it.isLetter() || it.code in HEBREW_IN_1255 }
        if (hebrewShaped * 2 < letters) return text
        val windows1255 = runCatching { Charset.forName("windows-1255") }.getOrNull() ?: return text
        return String(text.toByteArray(Charsets.ISO_8859_1), windows1255)
    }

    /** Where Windows-1255 keeps א–ת: 0xE0–0xFA. */
    private val HEBREW_IN_1255 = 0xE0..0xFA

    private val HEBREW_BLOCK = '֐'..'׿'

    private val WHITESPACE = Regex("\\s+")

    /** "kz", "-", a stray letter: nothing shorter than this is a song title. */
    private const val MIN_LENGTH = 3

    /** Text a server or CDN puts in the field when the station itself puts nothing there. */
    private val PLACEHOLDERS = listOf(
        "powered by",
        "azuracast",
        "adobe audition",
    )
}
