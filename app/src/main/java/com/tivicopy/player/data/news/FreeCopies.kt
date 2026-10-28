package com.tivicopy.player.data.news

import com.tivicopy.player.data.model.Channel

/**
 * The free-to-air copy of a channel before the provider's (ADR-042).
 *
 * The bundled playlist (ADR-013) carries Kan 11, Keshet 12, Reshet 13, Channel 14, i24NEWS and the
 * Knesset channel straight from the broadcasters, and the owner's daily playlist carries the same
 * stations again through the provider — under the same names. The panels' picker offered both,
 * side by side and indistinguishable, and a panel on the provider's copy spends one of the
 * account's connections. The owner's account allows one (device report, 2026-10-28): four panels
 * of what looked like public channels, and the provider closing all but one of them.
 *
 * So where the free playlist has a station ([IsraeliNewsChannels.station]), its copy is the one
 * offered and the provider's is left out; a provider channel the free playlist does not carry
 * stays. A grid remembered with a provider's copy gets the free one in its place ([replacedBy]).
 */
object FreeCopies {

    /**
     * @param choices what the panels may show, playlist by playlist, in their order
     * @param replacedBy the free copy, by the id of each provider copy left out for it
     */
    class Result(val choices: List<Channel>, val replacedBy: Map<Long, Channel>)

    /** @param free the ids of the bundled free-to-air playlist (in practice, one) */
    fun prefer(channels: List<Channel>, free: Set<Long>): Result {
        val stations = HashMap<String, Channel>()
        channels.filter { it.playlistId in free }.forEach { channel ->
            station(channel)?.let { stations.putIfAbsent(it, channel) }
        }
        if (stations.isEmpty()) return Result(channels, emptyMap())
        val replacedBy = HashMap<Long, Channel>()
        val kept = channels.filter { channel ->
            if (channel.playlistId in free) return@filter true
            val copy = station(channel)?.let(stations::get) ?: return@filter true
            replacedBy[channel.id] = copy
            false
        }
        return Result(kept, replacedBy)
    }

    private fun station(channel: Channel): String? =
        listOfNotNull(channel.name, channel.customName)
            .firstNotNullOfOrNull { IsraeliNewsChannels.station(it, channel.tvgId) }
}
