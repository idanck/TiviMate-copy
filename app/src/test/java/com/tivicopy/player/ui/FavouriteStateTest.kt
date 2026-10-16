package com.tivicopy.player.ui

import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A favourite set from any surface reaches every copy of the channel the state holds
 * (`withFavourite`). Each surface draws its star from one of those copies, and a copy the change
 * missed left that star stuck, so every press sent the same value again (device report,
 * 2026-10-16).
 *
 * Plain JUnit: the state classes are data classes with no Android in them.
 */
class FavouriteStateTest {

    private fun channel(id: Long, favourite: Boolean = false) = Channel(
        id = id,
        playlistId = 1,
        identity = "ch$id",
        name = "Channel $id",
        favourite = favourite,
    )

    private val playing = channel(2)
    private val other = channel(3)

    private fun state(nowPlaying: NowPlayingInfo?) = AppUiState(
        list = ChannelListState(channels = listOf(channel(1), playing, other)),
        currentChannel = playing,
        nowPlaying = nowPlaying,
        radioAllChannels = listOf(playing, other),
    )

    @Test
    fun `every copy of the channel takes the new favourite`() {
        val after = state(NowPlayingInfo.Live(playing)).withFavourite(playing.id, true)

        assertTrue(after.list.channels.first { it.id == playing.id }.favourite)
        assertTrue(after.currentChannel!!.favourite)
        assertTrue((after.nowPlaying as NowPlayingInfo.Live).channel.favourite)
        assertTrue(after.radioAllChannels.first { it.id == playing.id }.favourite)
    }

    @Test
    fun `other channels are left alone`() {
        val after = state(NowPlayingInfo.Live(playing)).withFavourite(playing.id, true)

        assertFalse(after.list.channels.first { it.id == other.id }.favourite)
        assertFalse(after.radioAllChannels.first { it.id == other.id }.favourite)
    }

    @Test
    fun `a second press takes the favourite away again`() {
        val once = state(NowPlayingInfo.Live(playing)).withFavourite(playing.id, true)
        // What the star shows is what the next press sends the opposite of.
        val shown = (once.nowPlaying as NowPlayingInfo.Live).channel.favourite
        val twice = once.withFavourite(playing.id, !shown)

        assertFalse((twice.nowPlaying as NowPlayingInfo.Live).channel.favourite)
        assertFalse(twice.currentChannel!!.favourite)
        assertFalse(twice.list.channels.first { it.id == playing.id }.favourite)
    }

    @Test
    fun `a catch-up session's channel is updated too`() {
        val programme = Programme(
            epgChannelId = "ch2",
            startUtc = 0,
            endUtc = 1,
            title = "Programme",
        )
        val after = state(NowPlayingInfo.Catchup(playing, programme)).withFavourite(playing.id, true)

        val catchup = after.nowPlaying as NowPlayingInfo.Catchup
        assertTrue(catchup.channel.favourite)
        assertEquals(programme, catchup.programme)
    }

    @Test
    fun `nothing playing is fine`() {
        val before = state(nowPlaying = null)
        val after = before.withFavourite(playing.id, true)

        assertNull(after.nowPlaying)
        assertTrue(after.currentChannel!!.favourite)
    }
}
