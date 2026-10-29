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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * Nothing here handles pointer input but the error panel's own buttons, so the playback gestures
 * on the surface below keep working through it everywhere else.
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
 * @param errorActions what a settled failure offers to do about it (ADR-034), or null for a panel
 *   that only says what went wrong — under a surface that owns the remote, say, where buttons
 *   behind it would be reachable by an arrow that has run off the end of that surface.
 */
@Composable
fun PlaybackStateOverlay(
    status: PlaybackStatus,
    modifier: Modifier = Modifier,
    showErrorText: Boolean = true,
    // Default matches `Settings.overlayOpacity`, for a caller with no preference to hand.
    opacity: Float = 0.85f,
    errorActions: PlaybackErrorActions? = null,
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
        // alert glyph in the error colour, the reason as its heading, and what can be done about
        // it under that (ADR-034).
        if (!tv) {
            // `PPlayError`: no plate inside a picture this small. The whole of it goes dark, and
            // the glyph, the reason and the one button sit in the middle of what is left.
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .background(studio.ground.copy(alpha = PHONE_ERROR_DIM))
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    imageVector = StudioIcons.Alert,
                    contentDescription = null,
                    tint = studio.error,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = message,
                    style = type.rowLabel.copy(fontWeight = FontWeight.SemiBold),
                    color = studio.ink,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                detail?.let { Text(text = it, style = type.meta, color = studio.ink2) }
                errorActions?.let { ErrorButtons(it, message) }
            }
            return
        }
        // With buttons the panel is the screen's, in the middle of it. Without them (`TvPlayErrorQuiet`)
        // another overlay owns the remote — the transport bar, the lower third — so the panel sits
        // above that, near the top, rather than across it.
        val quiet = errorActions == null
        Box(
            modifier = modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = if (quiet) QUIET_ERROR_TOP else 16.dp),
            contentAlignment = if (quiet) Alignment.TopCenter else Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .panelSurface(opacity.coerceAtLeast(MENU_MIN_OPACITY), MaterialTheme.shapes.large, GlassMaterial.THICK)
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = StudioIcons.Alert,
                        contentDescription = null,
                        tint = studio.error,
                        modifier = Modifier.size(20.dp),
                    )
                    if (status.title.isNotBlank()) {
                        Text(text = status.title, style = type.rowLabel, color = studio.ink, maxLines = 1)
                    }
                }
                // 40px at 1080p: the h1 step, larger, because the reason is the whole message.
                Text(
                    text = message,
                    style = type.h1.copy(fontSize = type.h1.fontSize * (20f / 17f)),
                    color = studio.ink,
                )
                detail?.let { Text(text = it, style = type.meta, color = studio.ink2) }
                Text(text = stringResource(R.string.playback_error_hint), style = type.meta, color = studio.ink2)
                errorActions?.let { ErrorButtons(it, message) }
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

/**
 * What a settled playback failure offers to do about it (`TvPlayError`, `PPlayError`, ADR-034).
 *
 * Until Studio stage 12 the panel only said what had gone wrong, and the way out of it was a
 * remote binding the viewer had to already know. The canvas puts the three ways out on the panel
 * itself. A null entry draws no button: the phone's panel has room for the retry alone.
 *
 * @param takeFocus whether the retry takes the remote's focus as the panel appears. Only while
 *   nothing else owns the D-pad; the arrows that do not move between the buttons still fall
 *   through to the shell, so up and down go on changing channel from here.
 */
class PlaybackErrorActions(
    val onRetry: () -> Unit,
    val onNetworkSettings: (() -> Unit)? = null,
    val onChannelList: (() -> Unit)? = null,
    val takeFocus: Boolean = false,
)

/** The error panel's buttons: retry first, where the focus lands, then the two ways out. */
@Composable
private fun ErrorButtons(actions: PlaybackErrorActions, message: String) {
    val tv = StudioTheme.television
    val retry = remember { FocusRequester() }
    // Re-asked for each new failure and each time the remote comes back to the bare screen: a
    // surface opened over the panel took the focus with it, and closing it hands nothing back.
    LaunchedEffect(actions.takeFocus, message) {
        if (!actions.takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { retry.requestFocus() }
    }
    Row(
        modifier = Modifier.padding(top = if (tv) 7.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StudioButton(
            label = stringResource(R.string.action_retry),
            onClick = actions.onRetry,
            icon = StudioIcons.Restart,
            // The phone's one action is the panel's primary; the television's three are equals.
            variant = if (tv) StudioButtonVariant.SECONDARY else StudioButtonVariant.PRIMARY,
            size = StudioButtonSize.MEDIUM,
            focusRequester = retry,
        )
        actions.onNetworkSettings?.let { open ->
            StudioButton(
                label = stringResource(R.string.playback_network_settings),
                onClick = open,
                icon = StudioIcons.Network,
                size = StudioButtonSize.MEDIUM,
            )
        }
        actions.onChannelList?.let { open ->
            StudioButton(
                label = stringResource(R.string.playback_channel_list),
                onClick = open,
                icon = StudioIcons.Tv,
                size = StudioButtonSize.MEDIUM,
            )
        }
    }
}

/** How dark a phone's picture goes behind a settled failure (`PPlayError`). */
private const val PHONE_ERROR_DIM = 0.94f

/** How far the last frame is dimmed behind the reconnecting loader (`TvReconnect`). */
private const val RECONNECT_DIM = 0.6f

/** How long buffering has to persist before it is worth drawing anything. */
private const val BUSY_GRACE_MILLIS = 350L

/** The quiet error's place: 150px from the top at 1080p, clear of the lower third under it. */
private val QUIET_ERROR_TOP = 75.dp
