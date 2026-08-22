package com.tivicopy.player.data.radio

/**
 * Which shelf a radio station belongs on in the dedicated radio browse UI (TV, phone, Android
 * Auto) — National, Regional, one bucket per detected genre, or [OTHER] for anything neither
 * matches.
 */
sealed interface RadioCategory {
    data object NATIONAL : RadioCategory
    data object REGIONAL : RadioCategory
    data class GENRE(val label: String) : RadioCategory
    data object OTHER : RadioCategory
}

/**
 * A heuristic classifier over the bundled radio playlist's `group-title` and station names.
 *
 * This repo has no access to `build_radio_m3u.py` (the script on the Pi that actually builds
 * `radio.m3u`, outside this repo — see ADR-015's Consequences) or to the live feed it publishes,
 * so the category boundaries here are inferred from what ADR-015 documents today (two
 * `group-title`s: "רדיו ישראלי" national, "רדיו אזורי" regional) plus keyword matching on station
 * names for genres and for Russian-language stations, which no `group-title` currently separates
 * out at all. Treat the keyword lists below as a starting point to extend as real station names
 * turn out to need it — this is presentation-layer classification, not the source of truth for
 * what the feed actually contains; the authoritative fix for miscategorized or unwanted stations
 * is still in the upstream script.
 */
object RadioCategoryClassifier {

    /** group-title keywords for the two categories ADR-015 documents. */
    private val REGIONAL_GROUP_MARKERS = listOf("אזורי", "regional")
    private val NATIONAL_GROUP_MARKERS = listOf("ארצי", "ישראלי", "national")

    /** Station-name keywords for genre buckets, checked before the group-level split above. */
    private val GENRE_MARKERS: List<Pair<String, List<String>>> = listOf(
        "יווני" to listOf("יווני", "greek"),
        "מזרחי" to listOf("מזרחי", "mizrahi"),
    )

    /** Station-name (or group-title) markers for Russian-language content, excluded outright. */
    private val RUSSIAN_MARKERS = listOf("רוסי", "russian", "russkoe", "русск")

    /** Cyrillic script anywhere in the name is a Russian station regardless of a matching word. */
    private val CYRILLIC_RANGE = 'Ѐ'..'ӿ'

    fun isRussian(stationName: String, groupName: String?): Boolean {
        val haystack = "${stationName.lowercase()} ${groupName.orEmpty().lowercase()}"
        if (RUSSIAN_MARKERS.any { haystack.contains(it) }) return true
        return stationName.any { it in CYRILLIC_RANGE }
    }

    fun classify(groupName: String?, stationName: String): RadioCategory {
        val name = stationName.lowercase()
        GENRE_MARKERS.forEach { (label, markers) ->
            if (markers.any { name.contains(it) }) return RadioCategory.GENRE(label)
        }
        val group = groupName.orEmpty().lowercase()
        if (REGIONAL_GROUP_MARKERS.any { group.contains(it) }) return RadioCategory.REGIONAL
        if (NATIONAL_GROUP_MARKERS.any { group.contains(it) }) return RadioCategory.NATIONAL
        return RadioCategory.OTHER
    }
}
