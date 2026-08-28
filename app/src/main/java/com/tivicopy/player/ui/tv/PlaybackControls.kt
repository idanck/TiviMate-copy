package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.player.TrackOption
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.NowPlayingInfo
import com.tivicopy.player.ui.common.LiveBadge
import com.tivicopy.player.ui.common.ProgrammeProgress
import com.tivicopy.player.ui.common.isLive
import com.tivicopy.player.ui.common.labelRes
import com.tivicopy.player.ui.common.nowPlayingLabels
import com.tivicopy.player.ui.common.qualitySummary
import com.tivicopy.player.ui.common.rememberAspectCycler

/**
 * The controls the ten-foot shell never had.
 *
 * The phone has carried a transport row, a quality readout, a favourite toggle and a now/next pane
 * inside its player screen since ADR-011. The television had none of them: bare video, a channel
 * info card that auto-dismissed, and a remote. Play and pause existed only as a key that some
 * remotes do not have; changing the aspect ratio meant leaving playback for the settings screen;
 * and audio or subtitle tracks could not be chosen at all in either shell, though the engine has
 * offered them the whole time (`PlayerEngine.audioTracks`, with no callers anywhere).
 *
 * Two surfaces rather than one, because a remote wants two different things at two different
 * moments. [TransportBar] is what OK gives you — the four or five actions worth a single press,
 * over video that keeps playing. [PlaybackMenu] is what a long OK gives you: everything else,
 * including the track pickers, in a list a D-pad can walk.
 */
@Composable
fun TransportBar(
    vm: AppViewModel,
    onOpenGuide: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val playback by vm.playbackStatus.collectAsState()
    val prefs by vm.settingsState.collectAsState()
    val cycleAspect = rememberAspectCycler(vm, prefs.aspectMode)
    val focusPlay = remember { FocusRequester() }

    // Focus lands on play/pause, the one control every kind of playback needs (device report),
    // so OK-then-OK is pause and nothing has to be aimed at first.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusPlay.requestFocus() }
    }

    // Re-read from the current list, so the star reflects an edit made after playback started;
    // the snapshot inside NowPlayingInfo never updates.
    val live = state.nowPlaying as? NowPlayingInfo.Live
    val liveChannel = live?.let { l ->
        state.list.channels.firstOrNull { it.id == l.channel.id } ?: l.channel
    }
    val labels = nowPlayingLabels(state.nowPlaying, state.now?.title) ?: return

    Column(
        // A floating plate, inset from the edges, rather than a band the picture stops at —
        // the same surface the filmstrip and the navigation capsule are drawn from
        // (`TvSurfaces.kt`).
        modifier = modifier
            .fillMaxWidth()
            .padding(TV_CONTENT_INSET)
            .tvGlassPanel(prefs.overlayOpacity)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Live *and* not paused, the same rule the station backdrop applies to the same badge:
        // this bar is the one place a viewer can stop a live stream, so it is the last place that
        // should go on calling it live afterwards.
        //
        // Hidden rather than removed, because this bar is anchored to the foot of the screen and
        // grows upwards: dropping a line would slide the focused play/pause button down at the
        // exact moment it is being pressed, and back up on the next press. `alpha` keeps the row
        // measured at any font scale; the semantics go with the pixels, so a screen reader is not
        // told about a badge nobody can see.
        if (live != null) {
            val onAir = playback.playWhenReady
            LiveBadge(
                modifier = Modifier
                    .alpha(if (onAir) 1f else 0f)
                    .then(if (onAir) Modifier else Modifier.clearAndSetSemantics {}),
            )
        }
        Text(
            text = labels.first,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        labels.second?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Only live has a programme to be part-way through. Catch-up and on-demand have a
        // position, but this app has no scrubber yet and docs/10 says so rather than drawing one
        // that cannot be dragged.
        if (live != null) {
            state.now?.let { now ->
                Text(
                    text = TimeModel.ltr(TimeModel.formatClockRange(now.startUtc, now.endUtc)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProgrammeProgress(now.startUtc, now.endUtc, modifier = Modifier.fillMaxWidth())
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.nowPlaying.isLive()) {
                TransportButton(
                    icon = Icons.Filled.SkipPrevious,
                    description = stringResource(R.string.channel_previous),
                    onClick = { vm.zap(-1) },
                )
            }
            TransportButton(
                icon = if (playback.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                description = stringResource(
                    if (playback.playWhenReady) R.string.action_pause else R.string.action_play,
                ),
                primary = true,
                onClick = { ServiceLocator.player.togglePlayPause() },
                modifier = Modifier.focusRequester(focusPlay),
            )
            if (state.nowPlaying.isLive()) {
                TransportButton(
                    icon = Icons.Filled.SkipNext,
                    description = stringResource(R.string.channel_next),
                    onClick = { vm.zap(1) },
                )
            }
            if (liveChannel != null) {
                TransportButton(
                    icon = if (liveChannel.favourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    description = stringResource(
                        if (liveChannel.favourite) R.string.action_unfavourite
                        else R.string.action_favourite,
                    ),
                    onClick = { vm.setFavourite(liveChannel, !liveChannel.favourite) },
                )
                TransportButton(
                    icon = Icons.Filled.DateRange,
                    description = stringResource(R.string.nav_guide),
                    onClick = onOpenGuide,
                )
            }
            TransportButton(
                icon = Icons.Filled.AspectRatio,
                description = stringResource(R.string.aspect_ratio),
                onClick = cycleAspect,
            )

            Box(modifier = Modifier.weight(1f))

            qualitySummary(playback)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = stringResource(R.string.playback_menu_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onOpenMenu),
        )
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val size = if (primary) 64.dp else 52.dp
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primary
                    primary -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (focused) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.size(if (primary) 32.dp else 24.dp),
        )
    }
}

/**
 * Everything the transport bar is too small for, in a list a D-pad can walk.
 *
 * The audio and subtitle pickers here are the first callers `PlayerEngine.tracksOfType` and
 * `selectTrack` have ever had. Both shells could set a *preferred* language in settings and
 * neither could choose a track on the stream in front of you — which on a multi-audio channel is
 * the difference between watching it and not.
 */
@Composable
fun PlaybackMenu(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val playback by vm.playbackStatus.collectAsState()
    val prefs by vm.settingsState.collectAsState()
    val cycleAspect = rememberAspectCycler(vm, prefs.aspectMode)

    // Read once per opening rather than per recomposition: `currentTracks` is a snapshot off the
    // player thread and the menu is short-lived.
    var audio by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    var text by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload, playback.width, playback.height) {
        audio = ServiceLocator.player.audioTracks()
        text = ServiceLocator.player.textTracks()
    }

    val labels = nowPlayingLabels(state.nowPlaying, state.now?.title)

    Column(
        // Rounded and hairlined like every other floating surface in this shell — but with a
        // floor under the viewer's opacity setting rather than obeying it outright. This menu is
        // a list of track names and settings being *read*, several lines of small text at once,
        // and the setting goes down to 0.3: at that value every other surface is a caption
        // glanced at over video, and this one would be unreadable.
        modifier = modifier
            .fillMaxWidth(0.5f)
            .tvGlassPanel(prefs.overlayOpacity.coerceAtLeast(MENU_MIN_OPACITY))
            .padding(TV_CONTENT_INSET),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels?.let {
            Text(
                text = it.first,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        qualitySummary(playback)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                MenuEntry(
                    title = stringResource(
                        if (playback.playWhenReady) R.string.action_pause else R.string.action_play,
                    ),
                    onClick = { ServiceLocator.player.togglePlayPause(); onDismiss() },
                )
            }
            item {
                MenuEntry(
                    title = stringResource(R.string.aspect_ratio),
                    detail = stringResource(prefs.aspectMode.labelRes()),
                    onClick = cycleAspect,
                )
            }

            if (audio.isNotEmpty()) {
                item { MenuHeading(stringResource(R.string.audio_track)) }
                items(audio) { track ->
                    MenuEntry(
                        title = track.label,
                        selected = track.selected,
                        onClick = {
                            ServiceLocator.player.selectTrack(track)
                            reload++
                        },
                    )
                }
            }

            if (text.isNotEmpty()) {
                item { MenuHeading(stringResource(R.string.subtitle_track)) }
                item {
                    MenuEntry(
                        title = stringResource(R.string.subtitle_off),
                        selected = text.none { it.selected },
                        onClick = {
                            ServiceLocator.player.disableTextTracks()
                            reload++
                        },
                    )
                }
                items(text) { track ->
                    MenuEntry(
                        title = track.label,
                        selected = track.selected,
                        onClick = {
                            ServiceLocator.player.selectTrack(track)
                            reload++
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 12.dp),
    )
}

@Composable
private fun MenuEntry(
    title: String,
    onClick: () -> Unit,
    detail: String? = null,
    selected: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (focused) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                } else {
                    Color.Transparent
                },
            )
            .border(
                width = 2.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // A tick rather than a radio button: this is a list of what is on, not a form.
        Box(modifier = Modifier.width(20.dp)) {
            if (selected) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The least opaque the playback menu is ever drawn, whatever the overlay-opacity setting says.
 *
 * Every other surface in this shell obeys that setting outright (FR-UI-18), because every other
 * surface is glanced at. This one is a list of track names read line by line, and below about
 * this it stops being legible over moving video — which is not a preference anyone is expressing
 * when they turn overlays down.
 */
private const val MENU_MIN_OPACITY = 0.92f
