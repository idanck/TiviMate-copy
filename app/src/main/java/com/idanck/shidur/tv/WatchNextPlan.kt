package com.idanck.shidur.tv

import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.Programme

/**
 * One row this app wants in Google TV's "Continue watching" (FR-TVI-02).
 *
 * Free of every Android type, in its own file, so [WatchNextPlan] can be tested on a plain JVM.
 * The `ContentResolver` half is `WatchNextSync.kt`, and it does no thinking of its own.
 */
data class WatchNextEntry(
    val channelId: Long,
    val title: String,
    val description: String?,
    val posterUri: String?,
    val lastEngagementUtc: Long,
    val deepLink: String,
)

/**
 * What the launcher row should contain, and what should be taken out of it.
 *
 * "Continue watching" means something slightly different for live television than it does for a
 * film: there is no position to resume, so the promise a card makes is "you were watching this,
 * here it is again". That is exactly what `channels.last_watched_utc` already records
 * (`Dao.recordWatch`), so the row is the recently-watched list with artwork — and radio stations
 * belong in it on the same terms, because the bundled radio playlist is watch history too
 * (ADR-015).
 */
object WatchNextPlan {

    /** How many rows to keep. The launcher shows a handful; the rest is invisible upkeep. */
    const val MAX_ENTRIES = 10

    /**
     * How long a channel stays in the row without being watched again.
     *
     * Nothing expires a Watch Next entry on its own. Without a ceiling, a channel opened once in
     * March would still be offering itself as "continue watching" in September.
     */
    const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    /** The deep-link scheme, matched by the `ACTION_VIEW` filter in `AndroidManifest.xml`. */
    const val SCHEME = "shidur"
    const val HOST_CHANNEL = "channel"

    fun deepLink(channelId: Long): String = "$SCHEME://$HOST_CHANNEL/$channelId"

    /**
     * The channel a launcher card names, or null for anything else.
     *
     * Parsed by hand rather than with `android.net.Uri` so it is testable without a device, and
     * strict on purpose: this arrives through an exported `ACTION_VIEW` filter, so anything that
     * is not plainly one of ours is refused rather than coerced into something.
     */
    fun channelIdFromDeepLink(raw: String?): Long? {
        val uri = raw?.trim().orEmpty()
        val prefix = "$SCHEME://$HOST_CHANNEL/"
        if (!uri.startsWith(prefix, ignoreCase = true)) return null
        val rest = uri.substring(prefix.length).substringBefore('?').substringBefore('#')
        // A trailing slash is tolerated; a further path segment is not — `channel/1/2` names
        // nothing this app understands, and guessing at it is how an exported entry point grows
        // behaviour nobody designed.
        val id = rest.removeSuffix("/")
        if (id.isEmpty() || id.any { !it.isDigit() }) return null
        return id.toLongOrNull()?.takeIf { it > 0 }
    }

    /**
     * The rows to publish for [recent], newest engagement first.
     *
     * Channels never watched, hidden, or last watched longer ago than [STALE_AFTER_MS] are left
     * out — a row claiming you were watching something you never opened, or opened once last
     * spring, is worse than a shorter row.
     */
    fun entries(
        recent: List<Channel>,
        nowByChannel: Map<Long, Programme?> = emptyMap(),
        nowUtc: Long = 0,
        max: Int = MAX_ENTRIES,
    ): List<WatchNextEntry> =
        recent.asSequence()
            .filter { it.lastWatchedUtc > 0 && !it.hidden }
            .filter { nowUtc <= 0 || nowUtc - it.lastWatchedUtc < STALE_AFTER_MS }
            .distinctBy { it.id }
            .sortedByDescending { it.lastWatchedUtc }
            .take(max.coerceAtLeast(0))
            .map { channel ->
                WatchNextEntry(
                    channelId = channel.id,
                    title = channel.displayName,
                    // What is on right now, where the guide knows. It is the one line on a card
                    // that tells the viewer something they did not already know.
                    description = nowByChannel[channel.id]?.title,
                    posterUri = channel.displayLogo,
                    lastEngagementUtc = channel.lastWatchedUtc,
                    deepLink = deepLink(channel.id),
                )
            }
            .toList()

    /**
     * Channel ids the launcher still holds that this plan no longer wants.
     *
     * A channel that fell off the end of the list, that went stale, or that a playlist refresh
     * deleted has to be taken out explicitly: nothing expires a Watch Next row on its own, so
     * without this the row would only ever grow — and would keep offering channels that no
     * longer exist.
     */
    fun stale(existing: Collection<Long>, wanted: Collection<Long>): List<Long> {
        val keep = wanted.toSet()
        return existing.filter { it !in keep }.distinct()
    }
}
