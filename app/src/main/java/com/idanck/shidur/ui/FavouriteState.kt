package com.idanck.shidur.ui

import com.idanck.shidur.data.model.Channel

/**
 * [this] with channel [channelId]'s favourite set to [favourite] in every copy of that channel the
 * state holds: the browsed list, the playing channel, the record of what is playing, and the
 * radio's full station list.
 *
 * Each of those is a snapshot taken when it was loaded, and a star is drawn from whichever one a
 * surface happens to read: the phone's player from the list, falling back to what is playing; the
 * television's radio screen from what is playing alone; the phone's radio from the radio list. The
 * database write followed by a reload of the browsed list refreshed only the first, so every
 * other star stayed as it was — and since each press sends the opposite of what the star shows, a
 * second press sent the same value again. The button read as dead in both directions (device
 * report, 2026-10-16). Applied before the write, so the star answers the press at once.
 */
internal fun AppUiState.withFavourite(channelId: Long, favourite: Boolean): AppUiState {
    fun Channel.marked(): Channel = if (this.id == channelId) copy(favourite = favourite) else this
    return copy(
        list = list.copy(channels = list.channels.map { it.marked() }),
        currentChannel = currentChannel?.marked(),
        nowPlaying = when (val playing = nowPlaying) {
            is NowPlayingInfo.Live -> playing.copy(channel = playing.channel.marked())
            is NowPlayingInfo.Catchup -> playing.copy(channel = playing.channel.marked())
            else -> playing
        },
        radioAllChannels = radioAllChannels.map { it.marked() },
    )
}
