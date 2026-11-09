package com.idanck.shidur.data.youtube

/**
 * YouTube inside Shidur (ADR-061): what the app draws, as the Cloud Functions send it
 * (`firebase/functions/src/youtube/model.ts`). The app holds no YouTube key and no token; it sends
 * channel ids and gets back these.
 */

/** A video, a live stream, or a stream announced for later. */
data class YtVideo(
    val id: String,
    val title: String,
    val channelId: String,
    val channelTitle: String,
    val thumb: String?,
    /** Epoch ms. */
    val publishedAt: Long?,
    /** Seconds; null for a live stream. */
    val duration: Int?,
    val live: Boolean,
    val upcoming: Boolean,
    val startedAt: Long?,
    val scheduledAt: Long?,
    val viewers: Long?,
    /** False when the owner allows it to play on YouTube alone; the app then offers the YouTube app. */
    val embeddable: Boolean,
)

data class YtChannel(
    val id: String,
    val title: String,
    val handle: String?,
    val thumb: String?,
    val subscribers: Long?,
    val videos: Long?,
)

/** A channel with what is on it now. */
data class YtChannelState(
    val channel: YtChannel,
    val live: List<YtVideo>,
    val upcoming: List<YtVideo>,
    val recent: List<YtVideo>,
)

/** A video watched here, for "continue watching": kept on the device only. */
data class YtWatched(
    val video: YtVideo,
    /** Seconds reached. */
    val position: Int,
    val watchedAt: Long,
) {
    /** How far in, 0..1, or null for a live stream or a video of unknown length. */
    val progress: Float?
        get() = video.duration?.takeIf { it > 0 }?.let { (position.toFloat() / it).coerceIn(0f, 1f) }
}

/** Why the YouTube screen has nothing to show, so it can say so plainly (`TvYouTubeStates`). */
enum class YtProblem {
    /** This build has no Firebase project, so there is no server to ask. */
    UNAVAILABLE,

    /** The network is down or the server did not answer. */
    OFFLINE,

    /** The day's YouTube quota is gone; it comes back at midnight Pacific time. */
    QUOTA,

    /** The day's searches are gone. */
    SEARCH_QUOTA,

    /** What was typed is not a channel, or no such channel exists. */
    NOT_FOUND,

    OTHER,
}

/** Reading the callables' answers: plain maps, lists, strings, numbers and booleans. */
internal object YtParse {

    private fun Map<*, *>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }

    private fun Map<*, *>.long(key: String): Long? = (this[key] as? Number)?.toLong()

    private fun Map<*, *>.bool(key: String): Boolean = this[key] as? Boolean ?: false

    fun video(raw: Any?): YtVideo? {
        val m = raw as? Map<*, *> ?: return null
        val id = m.str("id") ?: return null
        return YtVideo(
            id = id,
            title = m.str("title").orEmpty(),
            channelId = m.str("channelId").orEmpty(),
            channelTitle = m.str("channelTitle").orEmpty(),
            thumb = m.str("thumb"),
            publishedAt = m.long("publishedAt"),
            duration = m.long("duration")?.toInt(),
            live = m.bool("live"),
            upcoming = m.bool("upcoming"),
            startedAt = m.long("startedAt"),
            scheduledAt = m.long("scheduledAt"),
            viewers = m.long("viewers"),
            // Missing is read as embeddable, as the server does: the player says so if it is not.
            embeddable = m["embeddable"] as? Boolean ?: true,
        )
    }

    fun channel(raw: Any?): YtChannel? {
        val m = raw as? Map<*, *> ?: return null
        val id = m.str("id") ?: return null
        return YtChannel(
            id = id,
            title = m.str("title").orEmpty(),
            handle = m.str("handle"),
            thumb = m.str("thumb"),
            subscribers = m.long("subscribers"),
            videos = m.long("videos"),
        )
    }

    fun videos(raw: Any?): List<YtVideo> = (raw as? List<*>).orEmpty().mapNotNull(::video)

    fun state(raw: Any?): YtChannelState? {
        val m = raw as? Map<*, *> ?: return null
        val channel = channel(m["channel"]) ?: return null
        return YtChannelState(channel, videos(m["live"]), videos(m["upcoming"]), videos(m["recent"]))
    }

    fun states(raw: Any?): List<YtChannelState> = (raw as? List<*>).orEmpty().mapNotNull(::state)
}
