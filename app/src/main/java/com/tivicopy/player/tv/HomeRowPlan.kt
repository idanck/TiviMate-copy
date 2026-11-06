package com.tivicopy.player.tv

import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme

/**
 * One card in this app's own row on the Google TV home screen (FR-TVI-03).
 *
 * Free of every Android type, in its own file, so [HomeRowPlan] can be tested on a plain JVM —
 * the same split as [WatchNextPlan]. The `ContentResolver` half is `HomeRowSync.kt`.
 */
data class HomeRowEntry(
    val channelId: Long,
    val title: String,
    val description: String?,
    val posterUri: String?,
    /** Higher first. The launcher orders a preview channel's cards by weight, not by insertion. */
    val weight: Int,
    val deepLink: String,
)

/**
 * What the "שידור" row on the home screen should hold, and what should be taken out of it.
 *
 * Favourites first, in the order the viewer keeps them in the channel list, then recently
 * watched channels that are not already favourites, newest first. A channel is a card once:
 * a favourite watched five minutes ago stays in its favourite slot rather than appearing twice.
 *
 * Unlike "Continue watching", this row is the viewer's own shelf rather than a claim about what
 * they were in the middle of, so favourites never expire. Recents do, on the same thirty days as
 * [WatchNextPlan], for the same reason: a channel opened once in March should not still be
 * offering itself in September.
 */
object HomeRowPlan {

    /** How many cards to keep. A launcher row scrolls, but past this it is upkeep nobody sees. */
    const val MAX_ENTRIES = 20

    /** How many of [MAX_ENTRIES] recents may take when there are more favourites than fit. */
    const val MIN_RECENTS = 5

    /** The preview channel's own id among this app's channels, so it is found again rather than duplicated. */
    const val CHANNEL_PROVIDER_ID = "shidur-home"

    fun entries(
        favourites: List<Channel>,
        recent: List<Channel>,
        nowByChannel: Map<Long, Programme?> = emptyMap(),
        nowUtc: Long = 0,
        max: Int = MAX_ENTRIES,
    ): List<HomeRowEntry> {
        val cap = max.coerceAtLeast(0)
        val recents = recent.asSequence()
            .filter { it.lastWatchedUtc > 0 && !it.hidden && !it.favourite }
            .filter { nowUtc <= 0 || nowUtc - it.lastWatchedUtc < WatchNextPlan.STALE_AFTER_MS }
            .distinctBy { it.id }
            .sortedByDescending { it.lastWatchedUtc }
            .toList()
        val favs = favourites.asSequence()
            .filter { !it.hidden }
            .distinctBy { it.id }
            .toList()
        // Favourites lead, but a long favourites list must not push every recent off the end:
        // "what was I just watching" is half of what this row was asked to answer. The
        // reservation never takes more than half the row, so a short row still leads with
        // favourites.
        val reserved = minOf(MIN_RECENTS, recents.size, cap / 2)
        val favCap = (cap - reserved).coerceAtLeast(0)
        val chosen = (favs.take(favCap) + recents).distinctBy { it.id }.take(cap)
        return chosen.mapIndexed { index, channel ->
            // What is on right now, where the guide knows — which, in practice, is television
            // and not radio.
            val onNow = nowByChannel[channel.id]?.title?.trim()?.takeIf { it.isNotEmpty() }
            HomeRowEntry(
                channelId = channel.id,
                // Google TV draws a card's title and nothing else of ours: the line under it is
                // the launcher's own (a date, season and episode, a duration), never the
                // description. So the programme on now *is* the title, and the channel is the
                // logo on the card. A channel the guide says nothing about keeps its own name.
                title = onNow ?: channel.displayName,
                description = onNow?.let { channel.displayName },
                posterUri = channel.displayLogo,
                weight = chosen.size - index,
                deepLink = WatchNextPlan.deepLink(channel.id),
            )
        }
    }

    /**
     * Channel ids the row still holds that this plan no longer wants: a favourite removed, a
     * recent gone stale, or a channel a playlist refresh deleted. Nothing takes a preview program
     * out on its own, so without this the row would only ever grow.
     */
    fun stale(existing: Collection<Long>, wanted: Collection<Long>): List<Long> =
        WatchNextPlan.stale(existing, wanted)
}
