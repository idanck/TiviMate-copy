package com.idanck.shidur.ui

import com.idanck.shidur.data.model.Channel

/** Something that waits for the PIN before it happens (FR-PRF-04). */
sealed interface PinRequest {
    val channel: Channel

    /** Playing a locked channel. */
    data class Play(override val channel: Channel) : PinRequest

    /** Taking the lock off a channel. */
    data class Unlock(override val channel: Channel) : PinRequest
}

/** What became of one PIN entry. */
sealed interface PinResult {
    data object Accepted : PinResult
    data object Wrong : PinResult

    /** Too many wrong entries in a row (FR-PRF-07): nothing is checked for [seconds]. */
    data class LockedOut(val seconds: Int) : PinResult
}
