package com.tivicopy.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.player.PlaybackStatus

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
 */
@Composable
fun PlaybackStateOverlay(status: PlaybackStatus, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val error = status.error
    // `errorDetail` is filled in only once the engine has stopped trying, which is what
    // separates "reconnecting" from "gave up" (see PlayerEngine.handleError).
    val retrying = error != null && status.errorDetail == null
    val busy = error == null && status.buffering

    if (error == null && !busy) return

    val message = when {
        error != null -> error.describe(context)
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
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            if (busy || retrying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
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
                color = if (error != null && !retrying) {
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
