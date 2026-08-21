package com.tivicopy.player.data.builtin

/**
 * A second, narrow exception to "no bundled provider" (docs/00-scope-and-platforms.md §0.1),
 * alongside [IsraelPublicChannels]: a fixed playlist of Israeli radio stations, personal/
 * non-store use only (ADR-015).
 *
 * Curation, stream URLs and logos all live outside this repo, on the same host as the TV
 * channels — this object only points at them. No EPG: radio stations have no programme guide
 * in scope.
 */
object IsraelRadioStations {
    const val NAME = "רדיו ישראלי"
    const val M3U_URL = "https://il-epg.idanck.com/radio.m3u"

    /**
     * Playlist URLs earlier builds seeded. A row at one of these is the same built-in playlist
     * under an address that no longer serves it, so bootstrap corrects it in place rather than
     * leaving the dead row behind and inserting a second one beside it.
     */
    val LEGACY_M3U_URLS: List<String> = emptyList()
}
