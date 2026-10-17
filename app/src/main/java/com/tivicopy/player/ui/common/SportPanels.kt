package com.tivicopy.player.ui.common

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tivicopy.player.R
import com.tivicopy.player.core.AppError
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.player.PanelStatus
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.delay

/*
 * The parts of a Sport mode panel both shells draw the same way (ADR-041): the picture, an empty
 * place, a channel that will not play, the caption along the foot, and a row of the channel
 * picker. The television's grid (`ui/tv/SportScreen.kt`) and the phone's (`ui/mobile/PhoneSport.kt`)
 * differ in how a panel is reached — the remote's focus, or a finger — and in nothing drawn here.
 */

/** Still reaching the stream, or trying again after it dropped. */
internal fun panelConnecting(status: PanelStatus): Boolean =
    !status.playing && (status.buffering || status.error != null)

/**
 * The panel's picture. Media3's own view, as the full-screen player uses (`PlayerSurface`), but
 * always fitted and never captioned: subtitles sized for a quarter of the screen are not readable.
 */
@Composable
internal fun PanelVideo(player: ExoPlayer?, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                subtitleView?.visibility = android.view.View.GONE
                // Decoration, never a control: the panel around it is what takes the remote or
                // the finger.
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
        onRelease = { view -> view.player = null },
    )
}

/** An empty place in the grid, saying what [hint] says picks a channel for it. */
@Composable
internal fun EmptyPanel(hint: String, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = StudioIcons.Plus,
            contentDescription = null,
            tint = studio.ink2,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = stringResource(R.string.sport_add_channel),
            style = StudioTheme.type.titleSmall,
            color = studio.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = hint,
            style = StudioTheme.type.caption,
            color = studio.ink2,
            textAlign = TextAlign.Center,
        )
    }
}

/** A panel whose channel will not play, and why, with [hint] saying how to try again. */
@Composable
internal fun PanelFailure(status: PanelStatus, hint: String, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    val context = LocalContext.current
    val error = status.error
    val message = when {
        // The provider closed the stream: on an account with fewer connections than panels, that
        // is what happens to all but the newest (ADR-041). The channel itself is very likely fine.
        status.closed -> stringResource(R.string.sport_panel_stream_closed)
        // Almost always the device's limit on pictures decoded at once (ADR-041), and that is
        // the thing worth saying: the channel itself is very likely fine.
        error?.kind == AppError.Kind.DecoderFailed -> stringResource(R.string.sport_panel_device_limit)
        else -> (error ?: AppError(AppError.Kind.Unknown)).describe(context)
    }
    Column(
        modifier = modifier
            .background(studio.ground.copy(alpha = FAILURE_PLATE_ALPHA))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = StudioIcons.Alert,
            contentDescription = null,
            tint = studio.error,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = message,
            style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = studio.ink,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = hint,
            style = StudioTheme.type.caption,
            color = studio.ink2,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Along the foot of a panel: the channel, the programme on air if the guide knows it, and the
 * sound mark on the panel being heard. Full screen it shows for a moment and then leaves the
 * picture alone, as the info card does.
 */
@Composable
internal fun PanelCaption(
    vm: AppViewModel,
    channel: Channel,
    audible: Boolean,
    paused: Boolean,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val onAir = rememberOnAir(vm, channel)
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(expanded, channel.id) {
        visible = true
        if (expanded) {
            delay(CAPTION_HOLD_MS)
            visible = false
        }
    }
    if (!visible) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(studio.ground.copy(alpha = 0f), studio.ground.copy(alpha = CAPTION_SCRIM_ALPHA))),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChannelLogo(channel, sizeDp = 32)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.displayName,
                style = StudioTheme.type.rowLabel.copy(fontWeight = FontWeight.SemiBold),
                color = studio.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onAir != null) {
                Text(
                    text = onAir,
                    style = StudioTheme.type.caption,
                    color = studio.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (paused) {
            Icon(
                imageVector = StudioIcons.Pause,
                contentDescription = null,
                tint = studio.ink,
                modifier = Modifier.size(20.dp),
            )
        }
        if (audible) {
            Icon(
                imageVector = StudioIcons.Audio,
                contentDescription = stringResource(R.string.sport_sound_here),
                tint = studio.cue,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * The title of what is on air on [channel], from the guide, looked at again as each programme
 * ends; null when the guide has nothing for it — which, for several Israeli sports channels, is
 * most of the time.
 */
@Composable
private fun rememberOnAir(vm: AppViewModel, channel: Channel): String? {
    var title by remember(channel.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(channel.id) {
        while (true) {
            val now = System.currentTimeMillis()
            val programme = runCatching { vm.programmesFor(channel, now, now + 1) }
                .getOrNull()
                ?.firstOrNull { it.startUtc <= now && it.endUtc > now }
            title = programme?.title
            val until = programme?.endUtc ?: (now + ON_AIR_RECHECK_MS)
            delay((until - now).coerceIn(ON_AIR_MIN_WAIT_MS, ON_AIR_RECHECK_MS))
        }
    }
    return title
}

/** One channel in the picker: its mark, its name, and whether it is already in another panel. */
@Composable
internal fun SportPickerRow(
    channel: Channel,
    selected: Boolean,
    onScreenElsewhere: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val studio = StudioTheme.colors
    FocusableRow(
        onClick = onClick,
        selected = selected,
        contentDescription = channel.displayName,
        focusRequester = focusRequester,
    ) {
        ChannelLogo(channel, sizeDp = 28)
        Text(
            text = channel.displayName,
            style = StudioTheme.type.rowLabel,
            color = studio.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onScreenElsewhere) {
            Text(
                text = stringResource(R.string.sport_pick_on_screen),
                style = StudioTheme.type.caption,
                color = studio.ink2,
                maxLines = 1,
            )
        }
    }
}

private const val CAPTION_SCRIM_ALPHA = 0.85f
private const val FAILURE_PLATE_ALPHA = 0.8f

/** How long the caption stays over a panel that fills the screen. */
private const val CAPTION_HOLD_MS = 4_000L

/** How often to look at the guide again when it says nothing about the hour. */
private const val ON_AIR_RECHECK_MS = 5 * 60_000L
private const val ON_AIR_MIN_WAIT_MS = 30_000L
