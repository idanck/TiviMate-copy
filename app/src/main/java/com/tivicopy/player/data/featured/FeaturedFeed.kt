package com.tivicopy.player.data.featured

import com.tivicopy.player.core.AppError
import com.tivicopy.player.core.Outcome
import com.tivicopy.player.core.TextNormalizer
import com.tivicopy.player.data.model.Channel
import org.json.JSONObject

/**
 * One promoted entry on the home screen's featured rail (FR-UI-26).
 *
 * The app ships with no feed address at all: this is a mechanism, not a content decision. The
 * owner points [com.tivicopy.player.data.prefs.Settings.Snapshot.featuredFeedUrl] at whatever
 * he wants promoted — a hand-written JSON file, something generated on the Pi beside
 * `radio.m3u` (ADR-015), anything that serves the shape below over HTTPS — and the rail starts
 * rendering it. Until then there is no rail, not an empty one.
 */
data class FeaturedItem(
    /** Stable within one feed; used as the card's list key so a refresh does not re-animate. */
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    /**
     * Which channel this card tunes, as a *reference* rather than a database id: the feed is
     * written by hand against channels whose local ids it cannot know. Resolved by
     * [resolveChannel] against the tvg-id, the guide id, the name, then the channel number.
     *
     * Required in practice: an item that does not resolve to a channel this device has is
     * dropped when the rail is built, because a tile that swallows OK is indistinguishable from
     * a frozen screen on a remote. A card that opens something *other* than a channel — a web
     * address, say — is a deliberate later addition, and would need somewhere to open it.
     */
    val channelRef: String? = null,
)

/**
 * The document behind the featured rail.
 *
 * Parsing follows [com.tivicopy.player.data.update.UpdateManifest] deliberately: `org.json` and
 * an [Outcome]. It is less strict in two places, both on purpose, because that document decides
 * which binary is installed over the running app and this one decides which posters appear on a
 * shelf:
 *
 * - A malformed *item* is dropped while the rest of the feed still renders. One bad row in a
 *   promotional list is not a reason to blank the rail.
 * - A feed with no items is a valid, empty document. Publishing `{"items": []}` is how the owner
 *   turns the rail off without unsetting the address, and reporting that as a failure would keep
 *   the previous feed cached and on screen — the opposite of what was asked.
 *
 * Text that is not JSON, or a document naming a schema version this build does not know, still
 * fails outright.
 */
data class FeaturedFeed(val items: List<FeaturedItem>) {

    companion object {
        /**
         * A ceiling on how much of a feed the home screen will hold. The rail itself shows far
         * fewer; this only stops a runaway document from being kept in memory and in prefs.
         */
        const val MAX_ITEMS = 50

        /** Absent means 1. A document that names a version this build does not know is refused. */
        const val SCHEMA_VERSION = 1

        fun parse(raw: String): Outcome<FeaturedFeed> {
            val json = try {
                JSONObject(raw)
            } catch (t: Throwable) {
                return Outcome.Err(AppError(AppError.Kind.ParseFailed, "featured feed is not JSON", t))
            }

            val version = json.optInt("version", SCHEMA_VERSION)
            if (version != SCHEMA_VERSION) {
                return Outcome.Err(AppError(AppError.Kind.ParseFailed, "featured feed: version $version"))
            }

            // A feed with no items is how the owner turns the rail off without unsetting the
            // address — so it is a valid, empty document, not a failure. Treating it as an error
            // would leave the last good feed cached and the rail promoting stale content
            // precisely when the intent was to clear it.
            val array = json.optJSONArray("items") ?: return Outcome.Ok(FeaturedFeed(emptyList()))

            val items = buildList {
                for (index in 0 until array.length()) {
                    if (size >= MAX_ITEMS) break
                    val obj = array.optJSONObject(index) ?: continue
                    add(item(obj, index) ?: continue)
                }
            }
            return Outcome.Ok(FeaturedFeed(items.distinctBy { it.id }))
        }

        private fun item(obj: JSONObject, index: Int): FeaturedItem? {
            val title = obj.optString("title").trim()
            if (title.isEmpty()) return null

            return FeaturedItem(
                id = obj.optString("id").trim().ifEmpty { "$index:$title" },
                title = title,
                subtitle = obj.optString("subtitle").trim().ifEmpty { null },
                // Plain HTTP is refused for the same reason the update manifest refuses it: the
                // app should not be talking to anything on the path in the clear. An image that
                // fails this test costs a picture, not the card.
                imageUrl = obj.optString("image").trim().takeIf { it.isHttps() },
                channelRef = obj.optString("channel").trim().ifEmpty { null },
            )
        }

        private fun String.isHttps(): Boolean = startsWith("https://", ignoreCase = true)
    }
}

/**
 * Which channel a feed's `channel` reference means, against the channels this device actually has.
 *
 * Four passes in a fixed order, most specific first: the playlist's own `tvg-id`, the guide id the
 * matcher resolved, the canonical channel name, then the channel number. The feed is written by a
 * person, so all four are things a person would plausibly type, and the order is what keeps "11"
 * meaning the channel numbered 11 rather than a station with 11 somewhere in its name.
 *
 * Pure, and over a list rather than a query: the built-in Israeli playlists are a few dozen rows,
 * which is not worth a new index or a new statement in [com.tivicopy.player.data.db.Dao].
 */
fun resolveChannel(ref: String, channels: List<Channel>): Channel? {
    val needle = ref.trim()
    if (needle.isEmpty()) return null

    channels.firstOrNull { it.tvgId.equals(needle, ignoreCase = true) }?.let { return it }
    channels.firstOrNull { it.resolvedEpgId.equals(needle, ignoreCase = true) }?.let { return it }

    // The same normalisation the guide matcher uses, rather than a second one invented here:
    // a person writing this feed by hand types the channel's name the way they say it, and
    // TextNormalizer.canonical is what already turns that into something comparable.
    val canonical = TextNormalizer.canonical(needle)
    if (canonical.isNotEmpty()) {
        channels.firstOrNull { TextNormalizer.canonical(it.displayName) == canonical }?.let { return it }
    }

    val number = needle.toIntOrNull()
    if (number != null) channels.firstOrNull { it.number == number }?.let { return it }

    return null
}
