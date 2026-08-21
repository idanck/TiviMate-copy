package com.tivicopy.player.data.builtin

/**
 * The one deliberate exception to "no bundled provider" (docs/00-scope-and-platforms.md §0.1):
 * a fixed playlist of Israel's free-to-air public channels, personal/non-store use only.
 *
 * Curation, stream URLs, EPG and logos all live outside this repo, on the same host that
 * already serves the app's own self-update manifest — this object only points at them.
 */
object IsraelPublicChannels {
    const val NAME = "ערוצים פתוחים בישראל"
    const val M3U_URL = "https://updates.idanck.com/il-epg/channels.m3u"
    const val EPG_URL = "https://updates.idanck.com/il-epg/guide.xml"
}
