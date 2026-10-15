package com.tivicopy.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.player.PlaybackStatus
import kotlinx.coroutines.delay

/**
 * Says what the player is doing over the video surface.
 *
 * A tuned channel that has not produced a frame yet looks exactly like a channel that failed:
 * both are a black rectangle. FR-PLB-17 already retries and fails over underneath, and
 * NFR-REL-06 asks for plain language, but neither is worth anything if none of it reaches the
 * screen — so every non-playing state is named here, and the retry counter is shown while the
 * engine is still working through its attempts.
 *
 * Nothing here handles pointer input, so the playback gestures on the surface below keep
 * working through it.
 *
 * A live stream dips into buffering for a fraction of a second fairly often, and a card that
 * appeared for each of those would be worse than no card at all, so the busy state has to hold
 * for [BUSY_GRACE_MILLIS] before anything is drawn. A channel that tunes faster than that never
 * needed to be reported on.
 *
 * @param showErrorText whether a settled failure is spelled out here. The TV shell already
 *   banners it at the edge of the screen, where a 10-foot layout wants it, and would otherwise
 *   say the same thing twice; a still-retrying failure keeps its spinner either way, because
 *   that is progress rather than a verdict.
 * @param opacity the user's overlay opacity (FR-UI-16), so this card thins out with every other
 *   overlay rather than staying the most solid thing on screen.
 */
@Composable
fun PlaybackStateOverlay(
    status: PlaybackStatus,
    modifier: Modifier = Modifier,
    showErrorText: Boolean = true,
    // Default matches `Settings.overlayOpacity`, for a caller with no preference to hand.
    opacity: Float = 0.85f,
) {
    val context = LocalContext.current
    val error = status.error
    // `errorDetail` is filled in only once the engine has stopped trying, which is what
    // separates "reconnecting" from "gave up" (see PlayerEngine.handleError).
    val retrying = error != null && status.errorDetail == null
    val buffering = error == null && status.buffering

    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(buffering) {
        if (!buffering) {
            busy = false
            return@LaunchedEffect
        }
        delay(BUSY_GRACE_MILLIS)
        busy = true
    }

    if (error == null && !busy) return
    // A settled failure the caller has chosen to render elsewhere leaves nothing to say.
    if (error != null && !retrying && !showErrorText) return

    val message = when {
        error != null && showErrorText -> error.describe(context)
        else -> stringResource(R.string.playback_connecting)
    }
    val detail = when {
        retrying && status.retriesUsed > 0 ->
            stringResource(R.string.playback_reconnecting, status.retriesUsed)
        // A channel that declares several URLs says which one is being tried (FR-PLB-17).
        status.sourceCount > 1 -> "${status.sourceIndex + 1}/${status.sourceCount}"
        else -> null
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = opacity.coerceIn(0.1f, 1f)))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            if (busy || retrying) {
                // In the text colour, not the cue: loading is never where the remote is.
                Loader(size = 36.dp)
            }
            if (status.title.isNotBlank()) {
                Text(
                    text = status.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null && !retrying && showErrorText) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** How long buffering has to persist before it is worth drawing anything. */
private const val BUSY_GRACE_MILLIS = 350L
