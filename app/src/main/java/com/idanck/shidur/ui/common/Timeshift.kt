package com.idanck.shidur.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.player.LiveWindow
import kotlinx.coroutines.delay

/**
 * Where buffered live playback stands (ADR-065), read again twice a second while [active]: the
 * player says nothing when the position moves, so a surface showing it asks.
 */
@Composable
fun rememberLiveWindow(active: Boolean): LiveWindow? {
    val window by produceState<LiveWindow?>(initialValue = null, active) {
        value = null
        while (active) {
            value = ServiceLocator.player.liveWindow()
            delay(LIVE_WINDOW_POLL_MS)
        }
    }
    return window
}

/** "−12:34", or "−1:02:03" past the hour, isolated so it reads the same beside Hebrew. */
fun formatBehind(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val clock = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    return TimeModel.ltr("−$clock")
}

/** "−12:34 behind live", for a picture that is not at the live edge. */
@Composable
fun behindLiveText(window: LiveWindow): String = stringResource(R.string.timeshift_behind, formatBehind(window.behindMs))

/** The buffer as a bar: how much of what it holds has played. */
@Composable
fun TimeshiftTrack(window: LiveWindow, modifier: Modifier = Modifier) {
    val fraction = if (window.durationMs > 0) window.positionMs.toFloat() / window.durationMs else 1f
    StudioProgress(fraction = fraction.coerceIn(0f, 1f), modifier = modifier)
}

private const val LIVE_WINDOW_POLL_MS = 500L

/** How far one press of rewind or fast-forward moves through the buffer. */
const val TIMESHIFT_STEP_MS = 30_000L
