package com.idanck.shidur.data.youtube

/**
 * YouTube Music inside Shidur (ADR-069): what the music tab draws, as the Cloud Functions send it
 * (`firebase/functions/src/youtube/musicModel.ts`), and what Shidur keeps for the account itself —
 * the songs liked in Shidur, the albums saved, and what was played. There is no official YouTube
 * Music API; all of this is the YouTube Data API, read for music.
 */

/** A song: a YouTube video, read as a track. */
data class YtSong(
    val id: String,
    val title: String,
    /** The channel, without the " - Topic" YouTube adds to an artist's automatic channel. */
    val artist: String,
    val channelId: String,
    val thumb: String?,
    /** Seconds, or null when YouTube gives none. */
    val duration: Int?,
    val embeddable: Boolean,
) {
    /** The same video for YouTube's player, which plays every song. */
    fun asVideo(): YtVideo = YtVideo(
        id = id,
        title = title,
        channelId = channelId,
        channelTitle = artist,
        thumb = thumb,
        publishedAt = null,
        duration = duration,
        live = false,
        upcoming = false,
        startedAt = null,
        scheduledAt = null,
        viewers = null,
        embeddable = embeddable,
    )
}

/** A playlist or an album. The API does not tell them apart; [album] is the server's rule of thumb. */
data class YtMusicList(
    val id: String,
    val title: String,
    val owner: String,
    val thumb: String?,
    /** How many items YouTube says it holds, or null. */
    val count: Int?,
    val album: Boolean,
)

/** An artist: a YouTube channel. */
data class YtArtist(val id: String, val title: String, val thumb: String?)

/** A song liked in Shidur, kept in the account (`users/{uid}/musicLikes/{videoId}`). */
data class LikedSong(val song: YtSong, val likedAt: Long)

/** An album saved in Shidur (`users/{uid}/musicAlbums/{listId}`). */
data class SavedAlbum(val list: YtMusicList, val savedAt: Long)

/** A song played in Shidur (`users/{uid}/musicHistory/{playedAt-videoId}`). */
data class PlayedSong(
    val song: YtSong,
    val playedAt: Long,
    /** "tv" or "phone". */
    val device: String,
    /** The playlist or album it was played from, or null. */
    val context: String?,
) {
    val docId: String get() = "$playedAt-${song.id}"
}

/** The parts of the music tab: the canvas's chips. */
enum class MusicSection { HOME, LIBRARY, SEARCH }

/** What the library shows: the canvas's filter chips. */
enum class LibraryFilter { PLAYLISTS, LIKED, ARTISTS, ALBUMS, HISTORY }

/** Reading the music callables' answers: plain maps, lists, strings, numbers and booleans. */
internal object YtMusicParse {

    private fun Map<*, *>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }

    private fun Map<*, *>.int(key: String): Int? = (this[key] as? Number)?.toInt()

    fun song(raw: Any?): YtSong? {
        val m = raw as? Map<*, *> ?: return null
        val id = m.str("id") ?: return null
        return YtSong(
            id = id,
            title = m.str("title").orEmpty(),
            artist = m.str("artist").orEmpty(),
            channelId = m.str("channelId").orEmpty(),
            thumb = m.str("thumb"),
            duration = m.int("duration"),
            embeddable = m["embeddable"] as? Boolean ?: true,
        )
    }

    fun songs(raw: Any?): List<YtSong> = (raw as? List<*>).orEmpty().mapNotNull(::song).distinctBy { it.id }

    fun list(raw: Any?): YtMusicList? {
        val m = raw as? Map<*, *> ?: return null
        val id = m.str("id") ?: return null
        return YtMusicList(
            id = id,
            title = m.str("title").orEmpty(),
            owner = m.str("owner").orEmpty(),
            thumb = m.str("thumb"),
            count = m.int("count"),
            album = m["album"] as? Boolean ?: false,
        )
    }

    fun lists(raw: Any?): List<YtMusicList> = (raw as? List<*>).orEmpty().mapNotNull(::list)

    fun artist(raw: Any?): YtArtist? {
        val m = raw as? Map<*, *> ?: return null
        val id = m.str("id") ?: return null
        return YtArtist(id, m.str("title").orEmpty(), m.str("thumb"))
    }

    fun artists(raw: Any?): List<YtArtist> = (raw as? List<*>).orEmpty().mapNotNull(::artist)
}

/**
 * Where a song is in a list of [songs]: the next one to play after [current], or null at the end.
 * [repeat] goes back to the first.
 */
fun nextSong(songs: List<YtSong>, current: String?, repeat: Boolean): YtSong? {
    if (songs.isEmpty()) return null
    val at = songs.indexOfFirst { it.id == current }
    return when {
        at < 0 -> songs.first()
        at + 1 < songs.size -> songs[at + 1]
        repeat -> songs.first()
        else -> null
    }
}

/** The one before [current], or the first when [current] is first or unknown. */
fun previousSong(songs: List<YtSong>, current: String?): YtSong? {
    if (songs.isEmpty()) return null
    val at = songs.indexOfFirst { it.id == current }
    return if (at <= 0) songs.first() else songs[at - 1]
}

/**
 * The liked songs as the library shows them (ADR-069): Shidur's likes, newest first, then YouTube's
 * that Shidur does not already have, in YouTube's order.
 */
fun mergeLiked(shidur: List<LikedSong>, youtube: List<YtSong>): List<YtSong> {
    val own = shidur.sortedByDescending { it.likedAt }.map { it.song }
    val ids = own.map { it.id }.toHashSet()
    return own + youtube.filterNot { it.id in ids }
}

/**
 * "בחירה מהירה" (ADR-069), made by Shidur since the API has no recommendations: songs played most
 * often lately first, then liked songs not played lately, without repeats, at most [size].
 */
fun quickPicks(history: List<PlayedSong>, liked: List<YtSong>, size: Int = 20): List<YtSong> {
    val counts = history.groupingBy { it.song.id }.eachCount()
    val latest = history.groupBy { it.song.id }.mapValues { (_, p) -> p.maxOf { it.playedAt } }
    val played = history.distinctBy { it.song.id }
        .sortedWith(compareByDescending<PlayedSong> { counts[it.song.id] ?: 0 }.thenByDescending { latest[it.song.id] ?: 0L })
        .map { it.song }
    val ids = played.map { it.id }.toHashSet()
    return (played + liked.filterNot { it.id in ids }).take(size)
}
