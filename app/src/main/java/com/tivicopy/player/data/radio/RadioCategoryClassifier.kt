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
 * Which part of the country a [RadioCategory.REGIONAL] station broadcasts to.
 *
 * The regional category is a single `group-title` in the feed ("רדיו אזורי" — ADR-015), so this
 * is a second, finer heuristic over the station names inside it, used only to *present* that one
 * category as separated, ordered shelves rather than one undifferentiated list. Declaration order
 * is display order, north first: [NORTH], [CENTER], [JERUSALEM], [SOUTH], then [UNKNOWN] for a
 * station whose name says nothing about where it broadcasts, which sorts last rather than being
 * dropped.
 */
enum class RadioRegion { NORTH, CENTER, JERUSALEM, SOUTH, UNKNOWN }

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

    /**
     * Station-name markers per [RadioRegion], checked in declaration order.
     *
     * Deliberately keyed on whole station or place names rather than short fragments: the eight
     * regional stations ADR-015 lists are named after where they broadcast ("רדיו חיפה",
     * "104.5 רדיו צפון", "רדיו לב המדינה", "רדיו 90"…), so the names themselves carry the answer,
     * while a fragment like "עמק" would put Emek Hefer (central) in the north and "90" alone would
     * match any frequency that happens to contain it.
     */
    private val REGION_MARKERS: List<Pair<RadioRegion, List<String>>> = listOf(
        RadioRegion.NORTH to listOf(
            "צפון", "חיפה", "גליל", "כרמל", "כנרת", "טבריה", "נצרת", "עכו", "צפת", "קריות",
            "קול רגע", "עמק יזרעאל", "104.5",
            "north", "tzafon", "haifa", "galil", "galilee", "kinneret", "kol rega",
        ),
        RadioRegion.CENTER to listOf(
            "מרכז", "תל אביב", "ת\"א", "שרון", "לב המדינה", "גוש דן", "רמת גן", "פתח תקווה",
            "נתניה", "הרצליה", "רחובות", "מודיעין", "עמק חפר", "רדיו 90", "90fm", "90 fm",
            "center", "central", "tel aviv", "sharon", "merkaz", "hefer",
        ),
        RadioRegion.JERUSALEM to listOf("ירושלים", "jerusalem", "yerushalayim"),
        RadioRegion.SOUTH to listOf(
            "דרום", "נגב", "באר שבע", "אילת", "אשקלון", "אשדוד", "שדרות", "ערד", "דימונה",
            "south", "darom", "negev", "eilat", "beer sheva", "ashdod", "ashkelon",
        ),
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

    /**
     * Which [RadioRegion] a regional station's name places it in, or [RadioRegion.UNKNOWN] when
     * nothing in the name says. Only meaningful for a station [classify] put in
     * [RadioCategory.REGIONAL]; callers decide that first rather than this second-guessing it.
     */
    fun region(stationName: String): RadioRegion {
        val name = stationName.lowercase()
        REGION_MARKERS.forEach { (region, markers) ->
            if (markers.any { name.contains(it) }) return region
        }
        return RadioRegion.UNKNOWN
    }
}
