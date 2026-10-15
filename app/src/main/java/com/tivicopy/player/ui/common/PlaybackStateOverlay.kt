package com.tivicopy.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.player.PlaybackStatus
import com.tivicopy.player.ui.theme.StudioTheme
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
 * @param showErrorText whether a settled failure is spelled out here, as Studio's error panel.
 *   Both shells do since Studio stage 10a, which retired the television's red banner at the edge
 *   of the screen for the canvas's panel (`TvPlayError`); a caller drawing the failure some other
 *   way can still turn it off. A still-retrying failure keeps its loader either way, because that
 *   is progress rather than a verdict.
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

    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val tv = StudioTheme.television
    if (error != null && !retrying) {
        // A settled failure (`TvPlayError`, `PPlayError`): a panel naming the channel with the
        // alert glyph in the error colour, and the reason as its heading. The canvas's three
        // buttons under it are a behaviour change (MIGRATION.md round 2 §5) and are not drawn.
        Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = if (tv) 500.dp else 342.dp)
                    .panelSurface(opacity.coerceAtLeast(MENU_MIN_OPACITY), MaterialTheme.shapes.large)
                    .padding(horizontal = if (tv) 28.dp else 20.dp, vertical = if (tv) 24.dp else 18.dp),
                verticalArrangement = Arrangement.spacedBy(if (tv) 9.dp else 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = StudioIcons.Alert,
                        contentDescription = null,
                        tint = studio.error,
                        modifier = Modifier.size(if (tv) 20.dp else 22.dp),
                    )
                    if (status.title.isNotBlank()) {
                        Text(text = status.title, style = type.rowLabel, color = studio.ink, maxLines = 1)
                    }
                }
                // 40px at 1080p: the h1 step, larger, because the reason is the whole message.
                Text(
                    text = message,
                    style = if (tv) type.h1.copy(fontSize = type.h1.fontSize * (20f / 17f)) else type.h1,
                    color = studio.ink,
                )
                detail?.let { Text(text = it, style = type.meta, color = studio.ink2) }
                if (tv) Text(text = stringResource(R.string.playback_error_hint), style = type.meta, color = studio.ink2)
            }
        }
        return
    }

    // Connecting or reconnecting (`TvReconnect`, `PReconnect`): no plate, the picture dimmed
    // behind a loader, what is happening, and which channel it is happening to.
    Box(
        modifier = modifier.fillMaxSize().background(studio.ground.copy(alpha = RECONNECT_DIM)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // In the text colour, not the cue: loading is never where the remote is.
            Loader(size = 36.dp)
            Text(
                text = detail ?: message,
                // 34px at 1080p: the television's h1 step; a phone's row label.
                style = (if (tv) type.h1 else type.rowLabel).copy(fontWeight = FontWeight.SemiBold),
                color = studio.ink,
                textAlign = TextAlign.Center,
            )
            if (status.title.isNotBlank()) {
                Text(text = status.title, style = type.meta, color = studio.ink2, textAlign = TextAlign.Center)
            }
        }
    }
}

/** How far the last frame is dimmed behind the reconnecting loader (`TvReconnect`). */
private const val RECONNECT_DIM = 0.6f

/** How long buffering has to persist before it is worth drawing anything. */
private const val BUSY_GRACE_MILLIS = 350L
