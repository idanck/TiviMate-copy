package com.idanck.shidur.data.podcast

/**
 * A podcast the app knows (ADR-067): one the viewer keeps in the library ([inLibrary]), or one
 * known only because an episode of it is a favourite, in the history or in the queue.
 *
 * The show's own settings travel with it ([PodcastPrefs]) and win over the general defaults.
 */
data class Podcast(
    val id: Long = 0,
    val feedUrl: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val link: String? = null,
    val language: String? = null,
    val categories: List<String> = emptyList(),
    /** Podcast Index's id for the feed, when it was found there (ADR-067). */
    val indexId: Long? = null,
    val inLibrary: Boolean = true,
    val addedUtc: Long = 0,
    val lastRefreshUtc: Long = 0,
    val lastRefreshOk: Boolean = true,
    val lastRefreshMessage: String? = null,
    val prefs: PodcastPrefs = PodcastPrefs(),
    /** When the viewer last changed this show's choices — in the library or not, its settings — for the sync. */
    val choicesUtc: Long = 0,
    /** Episodes neither started nor played, published since the show was added. */
    val newCount: Int = 0,
    /** The newest episode's date, for "פרק אחרון" sorting. */
    val latestUtc: Long = 0,
)

/** A show's own settings (`TvPodcastShowSettings`). Zero or null means "the general default". */
data class PodcastPrefs(
    /** 0 = the general speed. */
    val speed: Float = 0f,
    val skipIntroSeconds: Int = 0,
    val skipOutroSeconds: Int = 0,
    val notifyNew: Boolean = false,
    val oldestFirst: Boolean = false,
)

/**
 * An episode, with the viewer's mark on it joined in: where they stopped, whether it was played,
 * starred, when it was last heard and on which device.
 *
 * The mark is kept against the feed's address and the episode's guid, not against a row id, so a
 * backup, a re-added podcast and another device all find the same mark (ADR-067).
 */
data class PodcastEpisode(
    val id: Long,
    val podcastId: Long,
    val feedUrl: String,
    val podcastTitle: String,
    val podcastImageUrl: String?,
    val guid: String,
    val title: String,
    val description: String?,
    val audioUrl: String,
    val mimeType: String?,
    val durationSeconds: Long,
    val publishedUtc: Long?,
    val imageUrl: String?,
    val season: Int?,
    val episode: Int?,
    val chaptersUrl: String?,
    val transcriptUrl: String?,
    val link: String?,
    val mark: EpisodeMark = EpisodeMark(),
) {
    val key: EpisodeKey get() = EpisodeKey(feedUrl, guid)

    /** The cover to show: the episode's own, else the show's. */
    val coverUrl: String? get() = imageUrl ?: podcastImageUrl

    /** The best known length: what the player last saw, else what the feed said. */
    val lengthMs: Long get() = mark.durationMs.takeIf { it > 0 } ?: durationSeconds * 1000

    val status: EpisodeStatus
        get() = when {
            mark.played -> EpisodeStatus.PLAYED
            mark.positionMs > 0 -> EpisodeStatus.STARTED
            else -> EpisodeStatus.NEW
        }

    /** Milliseconds left, when both the length and a position are known. */
    val remainingMs: Long? get() = lengthMs.takeIf { it > 0 && mark.positionMs > 0 }?.let { (it - mark.positionMs).coerceAtLeast(0) }
}

enum class EpisodeStatus { NEW, STARTED, PLAYED }

/** What identifies an episode across devices and reinstalls. */
data class EpisodeKey(val feedUrl: String, val guid: String)

/**
 * The viewer's state for one episode. Every field changes on its own, so each carries the time it
 * changed in [updatedUtc]: the newer side wins when two devices disagree (ADR-067).
 */
data class EpisodeMark(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val played: Boolean = false,
    val favourite: Boolean = false,
    val favouriteUtc: Long = 0,
    /** In the history since; 0 once removed from it. Clearing the history keeps the position. */
    val historyUtc: Long = 0,
    val lastPlayedUtc: Long = 0,
    /** "tv" or "phone": the history says where it was heard. */
    val device: String? = null,
    val updatedUtc: Long = 0,
)
