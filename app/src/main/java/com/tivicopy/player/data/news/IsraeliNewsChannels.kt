package com.tivicopy.player.data.news

import com.tivicopy.player.data.sport.IsraeliSportChannels

/**
 * Which channels News mode offers (ADR-042): the Israeli news channels in the viewer's
 * playlists — Kan 11, Keshet 12, Reshet 13, Channel 14, i24NEWS — and nothing else.
 *
 * A heuristic over names, as Sport mode's is ([IsraeliSportChannels]), and the same first
 * question: is the channel Israeli? Then, is it news? It is if its name says so (`news`,
 * `חדשות`, `ניוז`, `i24`, the Knesset channel), if it is one of the general channels by number
 * (`CHANNEL 12`, `קשת 12`, `HOT Reshet 13`, and Channel 14 under its old number, `CHANNEL 20`),
 * or if its guide id is one of theirs.
 *
 * The owner's daily playlist names them in Hebrew (`כאן 11`, `ערוץ 14`, `איי 24 ניוז`) with
 * guide ids like `il.channel-12`; the provider's own list calls them `|IL| CHANNEL 12` and
 * `|IL| I24`. Both are covered. Channel 9, Channel 1 and the rest of the Israeli group are not
 * news channels and are left out.
 */
object IsraeliNewsChannels {

    private val NEWS_WORDS = listOf("news", "חדשות", "ניוז", "i24", "knesset", "כנסת")

    /** A general channel by its number, as a word of its own: `channel 12`, `קשת 12`, `כאן 11`. */
    private val BY_NUMBER = Regex(
        "(?:^|\\s)(?:channel|ערוץ|כאן|kan|קשת|keshet|רשת|reshet|now|עכשיו)\\s+(?:11|12|13|14|20)(?:\\s|$)",
    )

    /** The guide ids the owner's playlist gives them. */
    private val GUIDE_IDS = setOf(
        "il.channel-11", "il.channel-12", "il.channel-13", "il.channel-14", "il.channel-20",
        "il.i24", "il.hot-keshet-12", "il.hot-reshet-13",
    )

    /**
     * @param name the channel's name as the provider sent it
     * @param groupName the name of the group it is filed under, if any
     * @param tvgId the guide id the playlist gave it, if any
     */
    fun isIsraeliNews(name: String, groupName: String?, tvgId: String? = null): Boolean {
        if (!IsraeliSportChannels.isIsraeli(name, groupName, tvgId)) return false
        val guideId = tvgId?.trim()?.lowercase().orEmpty()
        val folded = IsraeliSportChannels.fold(name)
        return NEWS_WORDS.any { it in folded } ||
            BY_NUMBER.containsMatchIn(folded) ||
            guideId in GUIDE_IDS ||
            "news" in guideId
    }
}
