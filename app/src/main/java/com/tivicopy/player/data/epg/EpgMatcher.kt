package com.tivicopy.player.data.epg

import com.tivicopy.player.core.TextNormalizer
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.EpgChannel

/** How a channel came to be bound to a guide entry; surfaced in the diagnostic view. */
enum class MatchKind { MANUAL, EXACT_ID, NORMALIZED_NAME, FUZZY, NONE }

data class MatchResult(
    val channelId: Long,
    val epgId: String?,
    val kind: MatchKind,
    val confidence: Double,
)

/**
 * Binds playlist channels to XMLTV entries (FR-EPG-07).
 *
 * The stages run in strict order and stop at the first hit, so a correct `tvg-id` is never
 * overruled by a lucky fuzzy score:
 *
 * 1. a manual binding the user made (FR-EPG-08) — always wins, and is never recomputed;
 * 2. exact `tvg-id` to XMLTV `channel id`;
 * 3. normalised display-name equality, against every `<display-name>` the guide offers;
 * 4. a scored fuzzy match above [fuzzyThreshold].
 */
class EpgMatcher(private val fuzzyThreshold: Double = 0.82) {

    fun match(channels: List<Channel>, epgChannels: List<EpgChannel>): List<MatchResult> {
        val byId = HashMap<String, String>(epgChannels.size * 2)
        val byName = HashMap<String, String>(epgChannels.size * 2)

        epgChannels.forEach { e ->
            byId[e.id.lowercase()] = e.id
            e.displayNames.forEach { n ->
                val key = TextNormalizer.canonical(n)
                // First guide entry wins, so a later duplicate name cannot steal the binding.
                if (key.isNotEmpty() && key !in byName) byName[key] = e.id
            }
            // The id itself is often a readable name such as `BBCOne.uk`.
            val idAsName = TextNormalizer.canonical(e.id.substringBeforeLast('.'))
            if (idAsName.isNotEmpty() && idAsName !in byName) byName[idAsName] = e.id
        }

        // Candidate list for the fuzzy stage: (canonical name, epg id).
        val fuzzyCandidates: List<Pair<String, String>> = epgChannels.flatMap { e ->
            val names = if (e.displayNames.isEmpty()) listOf(e.id) else e.displayNames
            names.mapNotNull { n ->
                val c = TextNormalizer.canonical(n)
                if (c.isEmpty()) null else c to e.id
            }
        }

        return channels.map { ch -> matchOne(ch, byId, byName, fuzzyCandidates) }
    }

    private fun matchOne(
        ch: Channel,
        byId: Map<String, String>,
        byName: Map<String, String>,
        fuzzyCandidates: List<Pair<String, String>>,
    ): MatchResult {
        ch.manualEpgId?.takeIf { it.isNotBlank() }?.let {
            return MatchResult(ch.id, it, MatchKind.MANUAL, 1.0)
        }

        ch.tvgId?.takeIf { it.isNotBlank() }?.let { tvg ->
            byId[tvg.lowercase()]?.let { return MatchResult(ch.id, it, MatchKind.EXACT_ID, 1.0) }
        }

        val candidateNames = listOfNotNull(ch.name, ch.customName, ch.tvgId)
        candidateNames.forEach { n ->
            val key = TextNormalizer.canonical(n)
            if (key.isNotEmpty()) {
                byName[key]?.let { return MatchResult(ch.id, it, MatchKind.NORMALIZED_NAME, 0.95) }
            }
        }

        var best: String? = null
        var bestScore = 0.0
        val probe = TextNormalizer.canonical(ch.displayName)
        if (probe.isNotEmpty()) {
            fuzzyCandidates.forEach { (name, epgId) ->
                val score = TextNormalizer.similarity(probe, name)
                if (score > bestScore) { bestScore = score; best = epgId }
            }
        }

        return if (best != null && bestScore >= fuzzyThreshold) {
            MatchResult(ch.id, best, MatchKind.FUZZY, bestScore)
        } else {
            MatchResult(ch.id, null, MatchKind.NONE, bestScore)
        }
    }
}
