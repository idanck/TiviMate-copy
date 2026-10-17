package com.tivicopy.player.data.sport

import com.tivicopy.player.core.TextNormalizer
import java.text.Normalizer

/**
 * Which channels Sport mode offers (ADR-041): the Israeli sports channels in the viewer's
 * playlists, and nothing else — the owner's call, 2026-10-17.
 *
 * A heuristic over names, as the radio's shelves are (`RadioCategoryClassifier`), because no
 * playlist says "Israeli" or "sport" in a field of its own. Two questions, and both must say yes:
 *
 * - **Is it Israeli?** Its group is the Israeli one — `ישראל` in the owner's daily playlist, the
 *   provider's raw `ISRAËL| HEBREEW`, or any other group naming Israel or Hebrew — or the channel
 *   carries the provider's country tag (`|IL| SPORT 5`) or an Israeli guide id (`sport5.il`).
 * - **Is it sport?** Its name says so (`sport`, `ספורט`, which also catches Eurosport and
 *   `5SPORT`), it is ONE (`ONE`, `ONE 2`, `וואן`), it is one of Sport 5's channels that leave the
 *   word out (`5 LIVE`, `5 GOLD`, `5 STARS`, `5+`), its guide id names sport, or its Israeli group
 *   is itself a sports group.
 *
 * The first question is the one that keeps the foreign sports out. The owner's playlist files
 * those under `ספורט | אנגליה`, `ספורט | גרמניה` and so on: sport, named in Hebrew, and not
 * Israeli — none of them carries an Israeli marker.
 *
 * Extend the lists as real names turn out to need it. What a playlist contains, and what its
 * groups are called, is decided upstream, not here.
 */
object IsraeliSportChannels {

    /** Group names that hold Israeli channels, matched against the folded group name. */
    private val ISRAELI_GROUP_MARKERS = listOf("ישראל", "isra", "hebrew", "hebreew", "עברית")

    /**
     * The provider's country tag at the very start of a name or a group: `|IL|`, `IL:`, `IL |`,
     * `[IL]`, `IL-`. Only punctuation may come before it and a separator must follow it, so
     * `ILLUMINATE` and a Hebrew name that happens to contain the two letters are not Israeli.
     */
    private val ISRAELI_TAG = Regex("^[\\s|:\\-\\[(]*il(?=[\\s|:\\-\\])]|$)", RegexOption.IGNORE_CASE)

    private val SPORT_WORDS = listOf("sport", "ספורט")

    /** ONE, the sports channel, as a word of its own: not the end of `ZONE`. */
    private val ONE = Regex("(?:^|\\s)(?:one|וואן)(?:\\s|$)")

    /** The other channels named one: not sport. */
    private val NOT_ONE = listOf("channel one", "ערוץ וואן")

    /**
     * Sport 5's channels that leave the word out. Read off the raw name, because `+` is
     * punctuation to [TextNormalizer.basic] and would be gone from the folded one.
     */
    private val SPORT_FIVE =
        Regex("(?<![\\d.])5\\s*(?:\\+|plus\\b|live\\b|gold\\b|stars?\\b|4k\\b)", RegexOption.IGNORE_CASE)

    /**
     * @param name the channel's name as the provider sent it
     * @param groupName the name of the group it is filed under, if any
     * @param tvgId the guide id the playlist gave it, if any
     */
    fun isIsraeliSport(name: String, groupName: String?, tvgId: String? = null): Boolean {
        val group = groupName?.let(::fold).orEmpty()
        val guideId = tvgId?.trim()?.lowercase().orEmpty()
        val israeliGroup = ISRAELI_GROUP_MARKERS.any { it in group } ||
            ISRAELI_TAG.containsMatchIn(groupName.orEmpty())
        val israeli = israeliGroup || ISRAELI_TAG.containsMatchIn(name) || guideId.endsWith(".il")
        if (!israeli) return false

        val folded = fold(name)
        return SPORT_WORDS.any { it in folded } ||
            (ONE.containsMatchIn(folded) && NOT_ONE.none { it in folded }) ||
            SPORT_FIVE.containsMatchIn(name) ||
            "sport" in guideId ||
            (israeliGroup && SPORT_WORDS.any { it in group })
    }

    /**
     * Lower case, accents off, punctuation to spaces — and compatibility forms folded first, so
     * the superscript `ˢᴾᴼᴿᵀ` some providers decorate names with reads as `sport`.
     */
    private fun fold(text: String): String =
        TextNormalizer.basic(Normalizer.normalize(text, Normalizer.Form.NFKC))
}
