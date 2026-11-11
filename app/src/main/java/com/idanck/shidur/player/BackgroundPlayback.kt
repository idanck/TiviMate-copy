package com.idanck.shidur.player

import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the background player's card says and what its skip buttons do (ADR-068), handed from the
 * UI's state to [RadioBackgroundService].
 *
 * The service plays through the same [PlayerEngine] the UI drives, but knows nothing about
 * channels or playlists: which station is on, its logo, and which station is "next" all live in
 * the view model. So the view model publishes the card here while it exists, and lends the
 * service a way to change station; the service only reads.
 */
object BackgroundPlayback {

    /**
     * @param title the station's name, or the episode's
     * @param subtitle the line under it when the stream announces no song: the podcast's name
     * @param artworkUrl the station's logo, or the episode's picture
     * @param stations whether the previous/next buttons change station — true for a radio station,
     *   false for a podcast episode, whose buttons stay the player's own (back to the start, and on)
     */
    data class Card(
        val title: String,
        val subtitle: String?,
        val artworkUrl: String?,
        val stations: Boolean,
    )

    private val _card = MutableStateFlow<Card?>(null)

    /** Null when nothing the background player can carry is playing. */
    val card: StateFlow<Card?> = _card

    private var skipper: ((Int) -> Unit)? = null

    @MainThread
    fun publish(card: Card?) {
        _card.value = card
    }

    /** Lent by the view model for as long as it lives; [skip] goes nowhere without one. */
    @MainThread
    fun lendSkip(skip: (Int) -> Unit) {
        skipper = skip
    }

    /** Takes back what [lendSkip] lent, unless another holder has lent its own since. */
    @MainThread
    fun returnSkip(skip: (Int) -> Unit) {
        if (skipper === skip) skipper = null
    }

    /** Moves [delta] stations along the list; false when no one is there to do it. */
    @MainThread
    fun skip(delta: Int): Boolean {
        val target = skipper ?: return false
        target(delta)
        return true
    }
}
