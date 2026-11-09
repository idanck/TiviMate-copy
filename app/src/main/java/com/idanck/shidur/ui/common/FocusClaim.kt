package com.idanck.shidur.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager

/*
 * A screen's first focus on the television: the playing channel's row, the guide's programme on
 * air, home's first stop, the PIN pad's middle key.
 *
 * Each of these used to be one `requestFocus` a frame after the screen composed, and nothing
 * checked that it landed. Two things make it miss. A window in touch mode — after a tap, or an air
 * mouse on a television box — has no focus to give a clickable at all, since Compose's `clickable`
 * is focusable only out of touch mode; the request does nothing and the screen opens with nothing
 * holding the remote. And a target laid out a frame late is not there to take the request yet. The
 * emulator QA met both (issue #152: QA-22's PIN pad, QA-24, QA-25, QA-26).
 *
 * [claim] asks until the screen says the remote has arrived, for a few frames; [remoteDriving]
 * lets a screen hold its claim back while the window is in touch mode and make it the moment the
 * remote is back, instead of spending its one try on a request nothing can take.
 */

/**
 * Whether a remote or a keyboard is driving: false while the window is in touch mode. Read in
 * composition; a change recomposes the reader, so it can key an effect.
 */
@Composable
fun remoteDriving(): Boolean = LocalInputModeManager.current.inputMode == InputMode.Keyboard

/**
 * Asks for the remote, and asks again a frame at a time until [landed] says it is here, for up to
 * [frames] frames; true once it is. The first ask comes a frame after the call, so a target
 * composed in the same frame as the caller has been laid out and carries this requester by then,
 * and it is always made, as the single request this replaces was: [landed] only decides whether
 * another is needed, so a default focus somewhere else on the screen does not stand in for it.
 *
 * A requester whose target has left the composition throws rather than failing quietly; that is
 * one more frame of waiting here, never a crash.
 */
suspend fun FocusRequester.claim(frames: Int = CLAIM_FRAMES, landed: () -> Boolean): Boolean {
    repeat(frames) { attempt ->
        withFrameNanos { }
        if (attempt > 0 && landed()) return true
        runCatching { requestFocus() }
    }
    withFrameNanos { }
    return landed()
}

/** About 130ms at 60Hz: long enough for a row a frame late, too short to pull back a quick press. */
private const val CLAIM_FRAMES = 8
