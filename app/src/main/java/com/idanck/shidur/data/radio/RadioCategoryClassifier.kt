package com.idanck.shidur.data.radio

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
 *
 * Since ADR-050 the radio list's menu asks the same question of every station, not only the
 * regional ones ([RadioShelf]), and in the same order.
 */
enum class RadioRegion { NORTH, CENTER, JERUSALEM, SOUTH, UNKNOWN }

/**
 * One entry of the radio list's category menu, on the television and the phone alike (ADR-050).
 *
 * The menu is one flat list in two parts. The first places every station exactly once, by where it
 * is heard: [National], or the [Regional] shelf of the region its name names. The second is the
 * genres a name says ([Genre]), beside the first rather than instead of it — a Mizrahi station in
 * the north is on both shelves.
 *
 * Separate from [RadioCategory], which the car's folders and the radio screen's strip still sort
 * by: the feed's `group-title`s are genres now ("מוזיקה", "שיחות"…) with only a couple of stations
 * filed as regional, so a menu built from them was one shelf holding nearly every station.
 */
sealed interface RadioShelf {
    /** Heard across the country, as far as the name says: it names no region. */
    data object National : RadioShelf

    /**
     * Named after where it broadcasts. [RadioRegion.UNKNOWN] is the shelf for a station the feed
     * files as regional without its name saying which region — "other regions", never national.
     */
    data class Regional(val region: RadioRegion) : RadioShelf

    /** A genre the station's name says, as [RadioCategory.GENRE] has it. */
    data class Genre(val label: String) : RadioShelf
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

    /**
     * Station-name keywords for genre buckets, checked before the group-level split above.
     *
     * "יוון" as well as "יווני": the feed's Greek station is "רדיו כחול יוון", named after the
     * country rather than the music, and the country (final nun) is not inside the adjective.
     */
    private val GENRE_MARKERS: List<Pair<String, List<String>>> = listOf(
        "יווני" to listOf("יווני", "יוון", "greek"),
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
     *
     * ADR-050 added, from the feed's own names: "יזרעאל" and "גולן", both wholly in the north
     * ("רדיו קול יזרעאל 106FM", "רדיו קול הגולן"), and "רדיו תשעים", the feed's spelling of the
     * Radio 90 already placed in the centre — as a phrase, so a station for the nineties
     * ("התשעים") is not.
     */
    private val REGION_MARKERS: List<Pair<RadioRegion, List<String>>> = listOf(
        RadioRegion.NORTH to listOf(
            "צפון", "חיפה", "גליל", "כרמל", "כנרת", "טבריה", "נצרת", "עכו", "צפת", "קריות",
            "קול רגע", "יזרעאל", "גולן", "104.5",
            "north", "tzafon", "haifa", "galil", "galilee", "kinneret", "kol rega", "golan",
        ),
        RadioRegion.CENTER to listOf(
            "מרכז", "תל אביב", "ת\"א", "שרון", "לב המדינה", "גוש דן", "רמת גן", "פתח תקווה",
            "נתניה", "הרצליה", "רחובות", "מודיעין", "עמק חפר", "רדיו 90", "רדיו תשעים", "90fm", "90 fm",
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
        genreOf(stationName)?.let { return RadioCategory.GENRE(it) }
        if (filedAsRegional(groupName)) return RadioCategory.REGIONAL
        val group = groupName.orEmpty().lowercase()
        if (NATIONAL_GROUP_MARKERS.any { group.contains(it) }) return RadioCategory.NATIONAL
        return RadioCategory.OTHER
    }

    /** The genre a station's name says ([GENRE_MARKERS]), or null when it says none. */
    private fun genreOf(stationName: String): String? {
        val name = stationName.lowercase()
        return GENRE_MARKERS.firstOrNull { (_, markers) -> markers.any { name.contains(it) } }?.first
    }

    /** Whether the feed's own `group-title` files a station as regional. */
    private fun filedAsRegional(groupName: String?): Boolean {
        val group = groupName.orEmpty().lowercase()
        return REGIONAL_GROUP_MARKERS.any { group.contains(it) }
    }

    /**
     * The one shelf of the menu's first part a station is on (ADR-050): the region its name names;
     * failing that, "other regions" if the feed files it as regional; failing that, national.
     *
     * National is where a name that places a station nowhere goes because, in this feed, such a
     * station is either one of the country's own (Kan, Galgalatz, Galei Tzahal, 103FM…) or an
     * internet station, heard the same everywhere — which is what the menu means by national. A
     * station the feed calls regional is not moved there for lack of a place name.
     */
    fun regionShelf(groupName: String?, stationName: String): RadioShelf {
        val region = region(stationName)
        return when {
            region != RadioRegion.UNKNOWN -> RadioShelf.Regional(region)
            filedAsRegional(groupName) -> RadioShelf.Regional(RadioRegion.UNKNOWN)
            else -> RadioShelf.National
        }
    }

    /** Every shelf of the menu a station is on: its [regionShelf], and its genre's if it has one. */
    fun shelves(groupName: String?, stationName: String): List<RadioShelf> =
        listOfNotNull(regionShelf(groupName, stationName), genreOf(stationName)?.let { RadioShelf.Genre(it) })

    /**
     * The radio list's category menu (ADR-050), from every station's `group-title` and name: national,
     * then the regions in [RadioRegion]'s order — north, centre, Jerusalem, south, then "other
     * regions" — then the genres alphabetically. A shelf no station is on is left out.
     *
     * There is no "other" shelf: every station is on one of the first part's, so a shelf for those
     * on none would always be empty.
     */
    fun menu(stations: List<Pair<String?, String>>): List<RadioShelf> {
        val present = stations.flatMapTo(HashSet<RadioShelf>()) { (group, name) -> shelves(group, name) }
        val places = listOf<RadioShelf>(RadioShelf.National) + RadioRegion.entries.map { RadioShelf.Regional(it) }
        val genres = present.filterIsInstance<RadioShelf.Genre>().sortedBy { it.label }
        return places.filter { it in present } + genres
    }

    /**
     * Which [RadioRegion] a station's name places it in, or [RadioRegion.UNKNOWN] when nothing in
     * the name says. The car asks it only of stations [classify] put in [RadioCategory.REGIONAL];
     * the radio list's menu asks it of every station ([regionShelf], ADR-050).
     */
    fun region(stationName: String): RadioRegion {
        val name = stationName.lowercase()
        REGION_MARKERS.forEach { (region, markers) ->
            if (markers.any { name.contains(it) }) return region
        }
        return RadioRegion.UNKNOWN
    }
}
