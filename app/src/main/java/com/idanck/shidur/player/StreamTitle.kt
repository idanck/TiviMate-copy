package com.idanck.shidur.player

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
     * The raw `StreamTitle` of the first ICY entry in [metadata] that has one, or null when the
     * batch carries none. Null and "" mean different things to a caller: no title says nothing
     * about the song — a block can carry only a `StreamUrl` — while an empty title says the
     * station has stopped announcing one.
     */
    fun icyTitle(metadata: Metadata): String? {
        for (i in 0 until metadata.length()) {
            val title = (metadata.get(i) as? IcyInfo)?.title
            if (title != null) return title
        }
        return null
    }

    /** [raw] as a title worth showing, or null when it is empty, broken or a placeholder. */
    fun clean(raw: String?, stationName: String? = null): String? {
        if (raw == null) return null
        // A replacement character means the bytes were already lost upstream; there is nothing
        // to repair and a row of diamonds is not a song.
        if (raw.contains('\uFFFD')) return null
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
     * Windows-1255 Hebrew that was read as Latin-1 or Windows-1252, read back as Windows-1255;
     * anything else unchanged.
     *
     * Decided per word, not per string: a title is often half English ("Static & Ben El - קן של
     * אהבה"), so the test is whether any word of two or more letters is made *entirely* of the
     * characters Windows-1255 puts the Hebrew alphabet at. No real Latin-script word is — French
     * and German put the odd accented letter in that range, always beside plain ASCII ones, so
     * "Café del Mar" is left alone. The string must also already contain no Hebrew, and every
     * character must map back to a single byte, or the round trip would not be lossless.
     */
    internal fun repairHebrew(text: String): String {
        if (text.any { it in HEBREW_BLOCK }) return text
        val looksHebrew = text.split(WHITESPACE).any { word ->
            val letters = word.filter { it.isLetter() || it.code in HEBREW_IN_1255 }
            letters.length >= 2 && letters.all { it.code in HEBREW_IN_1255 }
        }
        if (!looksHebrew) return text
        val bytes = singleByteEncoding(text) ?: return text
        val windows1255 = runCatching { Charset.forName("windows-1255") }.getOrNull() ?: return text
        return String(bytes, windows1255)
    }

    /**
     * The bytes [text] was decoded from, when every character came from one byte: Latin-1 for
     * U+0000–U+00FF, and Windows-1252 for the punctuation it puts at 0x80–0x9F (a Windows server
     * turns a Windows-1255 dash or quote into U+2013 or U+2019 that way). Null otherwise.
     */
    private fun singleByteEncoding(text: String): ByteArray? {
        val windows1252 = runCatching { Charset.forName("windows-1252").newEncoder() }.getOrNull()
        val out = ByteArray(text.length)
        for ((i, c) in text.withIndex()) {
            out[i] = when {
                c.code <= 0xFF -> c.code.toByte()
                windows1252 != null && windows1252.canEncode(c) -> c.toString().toByteArray(windows1252.charset())[0]
                else -> return null
            }
        }
        return out
    }

    /**
     * Where Windows-1255 keeps א–ת: 0xE0–0xFA. One of them, ק (0xF7), is the division sign in
     * Latin-1 rather than a letter, which is why the word test counts this range explicitly.
     */
    private val HEBREW_IN_1255 = 0xE0..0xFA

    private val HEBREW_BLOCK = '\u0590'..'\u05FF'

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
