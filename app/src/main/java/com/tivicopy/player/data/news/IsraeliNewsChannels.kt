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

    /**
     * Which broadcaster a channel carries — `11`, `12`, `13`, `14`, `i24` or `knesset` — so the
     * same station from two playlists can be told apart from two stations: the bundled free-to-air
     * playlist's `ערוץ 14` (`Now14.il@SD`) and the provider's (`il.channel-20`) are one channel.
     * Null for anything else, and for a copy that is not plainly the same picture: HOT's own
     * Keshet 12 and Reshet 13, and i24's English, French and Arabic services.
     *
     * The guide id decides when it is one of the known ones; the name only when it is not.
     */
    fun station(name: String, tvgId: String?): String? {
        val guideId = tvgId?.trim()?.lowercase().orEmpty().substringBefore('@')
        STATION_GUIDE_IDS[guideId]?.let { return it }
        val folded = IsraeliSportChannels.fold(name)
        val words = folded.split(' ')
        if (NOT_THE_SAME_PICTURE.any { it in words }) return null
        if ("i24" in folded || "איי 24" in folded) return "i24"
        if ("knesset" in folded || "כנסת" in folded) return "knesset"
        val number = BY_NUMBER_STATION.find(folded)?.groupValues?.get(1) ?: return null
        // Channel 14 went on air as Channel 20, and some lists still call it that.
        return if (number == "20") "14" else number
    }

    /** [BY_NUMBER], with the number captured. */
    private val BY_NUMBER_STATION = Regex(
        "(?:^|\\s)(?:channel|ערוץ|כאן|kan|קשת|keshet|רשת|reshet|now|עכשיו)\\s+(11|12|13|14|20)(?:\\s|$)",
    )

    /** Words that make a channel a different feed of the station, not the station itself. */
    private val NOT_THE_SAME_PICTURE = setOf(
        "hot", "הוט", "english", "אנגלית", "french", "francais", "צרפתית", "arabic", "ערבית",
    )

    /** The guide ids of the bundled playlist (iptv-org's, `@` feed dropped) and of the owner's. */
    private val STATION_GUIDE_IDS = mapOf(
        "kan11.il" to "11", "il.channel-11" to "11",
        "keshet12.il" to "12", "il.channel-12" to "12",
        "channel13.il" to "13", "reshet13.il" to "13", "il.channel-13" to "13",
        "now14.il" to "14", "channel14.il" to "14", "il.channel-14" to "14", "il.channel-20" to "14",
        "i24newshebrew.il" to "i24", "il.i24" to "i24",
        "knessetchannel.il" to "knesset", "il.knesset" to "knesset",
    )
}
