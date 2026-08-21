package com.tivicopy.player.data.builtin

/**
 * The one deliberate exception to "no bundled provider" (docs/00-scope-and-platforms.md §0.1):
 * a fixed playlist of Israel's free-to-air public channels, personal/non-store use only.
 *
 * Curation, stream URLs, EPG and logos all live outside this repo, on their own host — this
 * object only points at them. That host is deliberately *not* the one serving the app's own
 * update manifest: assuming the two were the same is what shipped 0.4.4 pointing at a URL that
 * had never been created (ADR-013).
 */
object IsraelPublicChannels {
    const val NAME = "ערוצים פתוחים בישראל"
    const val M3U_URL = "https://il-epg.idanck.com/channels.m3u"
    const val EPG_URL = "https://il-epg.idanck.com/guide.xml"

    /**
     * Playlist URLs earlier builds seeded. A row at one of these is the same built-in playlist
     * under an address that no longer serves it, so bootstrap corrects it in place rather than
     * leaving the dead row behind and inserting a second one beside it.
     */
    val LEGACY_M3U_URLS = listOf("https://updates.idanck.com/il-epg/channels.m3u")
}
