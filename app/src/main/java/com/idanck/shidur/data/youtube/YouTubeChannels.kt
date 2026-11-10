package com.idanck.shidur.data.youtube

import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.Programme

/**
 * YouTube channels in Shidur's channel list and guide (ADR-061 stage 3, `TvYouTubeInList`).
 *
 * A YouTube channel the viewer added to the list ("להוסיף לרשימת הערוצים" on its page) is drawn as
 * a channel like any other: a number from 900, so it never takes a provider's, a group of its own,
 * a row in the guide carrying what it streams live. It is not a row in the database. The list of
 * them is the YouTube repository's, and the [Channel]s here are made from it each time the channel
 * list is read, so nothing about playlists, refreshes, backups or the hand-over changes, and the
 * web and webOS versions never meet a source they cannot read.
 *
 * Playing one opens YouTube's own player (the IFrame API), never ExoPlayer: [youtubeChannelId]
 * is how [com.idanck.shidur.ui.AppViewModel.play] knows to hand it over.
 */
object YouTubeChannels {

    /** The URL scheme a made-up channel carries: `youtube:UC…`. ExoPlayer never sees it. */
    const val SCHEME = "youtube:"

    /** The first number a YouTube channel takes in the list. */
    const val FIRST_NUMBER = 900

    /**
     * Ids for the made-up rows, below every real one: real rows are SQLite rowids, never negative.
     * Offset by the channel's number, so one YouTube channel keeps one id for as long as it keeps
     * its number.
     */
    private const val ID_BASE = -1_000_000L

    fun idFor(number: Int): Long = ID_BASE - number

    /** The channel list's rows for [listed], as part of the playlist [playlistId] that is browsed. */
    fun channels(listed: List<YtListed>, playlistId: Long): List<Channel> = listed.map { entry ->
        Channel(
            id = idFor(entry.number),
            playlistId = playlistId,
            identity = "$SCHEME${entry.id}",
            name = entry.title,
            logoUrl = entry.thumb,
            number = entry.number,
            urls = listOf("$SCHEME${entry.id}"),
        )
    }

    /**
     * What the guide shows on a YouTube channel: its live stream, from when it started until a
     * while ahead, and a stream announced for later. Nothing when it is off air.
     */
    fun programmes(channel: Channel, state: YtChannelState?, now: Long): List<Programme> {
        state ?: return emptyList()
        val epgId = channel.identity
        val live = state.live.firstOrNull()?.let { v ->
            val start = v.startedAt ?: (now - LIVE_LOOKBACK_MS)
            Programme(epgChannelId = epgId, startUtc = start, endUtc = maxOf(now + LIVE_AHEAD_MS, start + LIVE_AHEAD_MS), title = v.title, description = v.channelTitle)
        }
        val upcoming = state.upcoming.mapNotNull { v ->
            val at = v.scheduledAt ?: return@mapNotNull null
            if (live != null && at < live.endUtc) return@mapNotNull null
            Programme(epgChannelId = epgId, startUtc = at, endUtc = at + UPCOMING_LENGTH_MS, title = v.title, description = v.channelTitle)
        }
        return listOfNotNull(live) + upcoming
    }

    private const val LIVE_LOOKBACK_MS = 30 * 60 * 1000L
    private const val LIVE_AHEAD_MS = 2 * 60 * 60 * 1000L
    private const val UPCOMING_LENGTH_MS = 60 * 60 * 1000L
}

/** A YouTube channel in the channel list: kept with its title and picture so the list draws offline. */
data class YtListed(val id: String, val number: Int, val title: String, val thumb: String?)

/** The YouTube channel a row of the channel list stands for, or null for an ordinary channel. */
val Channel.youtubeChannelId: String?
    get() = urls.firstOrNull()?.takeIf { it.startsWith(YouTubeChannels.SCHEME) }?.removePrefix(YouTubeChannels.SCHEME)
