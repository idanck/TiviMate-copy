package com.tivicopy.player.data.news

/**
 * A news source Settings offers to add with one press (FR-UI-33, ADR-038).
 *
 * [id] picks the name the settings screen shows, so the catalogue itself carries no language.
 * [flashes] says whether the feed is a newsroom's breaking-news list or its articles in general;
 * [timed] is false for a feed whose items this reader cannot date, so the row can say so before
 * the owner adds it rather than after the headlines arrive without a time.
 */
data class SuggestedNewsSource(
    val id: String,
    val url: String,
    val flashes: Boolean,
    val timed: Boolean = true,
)

/**
 * The suggestions, in the order Settings lists them: breaking news first.
 *
 * **Nothing here is switched on.** The list of active sources stays empty by default for the
 * reason ADR-037 gives — this app installs itself on other people's televisions, and which
 * newsroom scrolls past on one is its owner's to choose. These only spare that owner typing a
 * feed address with a remote.
 *
 * Every address was checked on 2026-10-16 to answer an HTTPS request from an Israeli connection,
 * sent with the app's own User-Agent, with a document [NewsFeed] reads. Kan (כאן) is not here: it
 * publishes no feed, and its news-flash page refuses any request that is not a full browser.
 */
object SuggestedNewsSources {
    val ALL: List<SuggestedNewsSource> = listOf(
        SuggestedNewsSource("ynet", "https://www.ynet.co.il/Integration/StoryRss1854.xml", flashes = true),
        SuggestedNewsSource("walla", "https://www.walla.co.il/rss/feed/news/breaking-news", flashes = true),
        SuggestedNewsSource("maariv", "https://www.maariv.co.il/Rss/RssFeedsMivzakiChadashot", flashes = true),
        // A bare JSON list whose items carry `title` and `startedAt`; the reader does not know
        // `startedAt` (ADR-038), so i24's headlines arrive without a time.
        SuggestedNewsSource("i24", "https://api.i24news.tv/v2/he/news", flashes = true, timed = false),
        SuggestedNewsSource("n12", "https://rcs.mako.co.il/rss/news-israel.xml", flashes = false),
        SuggestedNewsSource("israel_hayom", "https://www.israelhayom.co.il/rss.xml", flashes = false),
        SuggestedNewsSource("inn", "https://www.inn.co.il/Rss.aspx", flashes = false),
        SuggestedNewsSource("srugim", "https://www.srugim.co.il/feed", flashes = false),
    )

    /** The suggestions [current] does not already hold, in catalogue order. */
    fun available(current: List<NewsSource>): List<SuggestedNewsSource> {
        val taken = current.map { it.url.trim().lowercase() }.toSet()
        return ALL.filterNot { it.url.lowercase() in taken }
    }
}
