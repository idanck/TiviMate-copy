package com.tivicopy.player.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.prefs.AspectMode
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.NowPlayingInfo
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.PlaybackStateOverlay
import com.tivicopy.player.ui.common.PlayerSurface
import com.tivicopy.player.ui.screens.ChannelListScreen
import com.tivicopy.player.ui.screens.NowNextPane
import kotlinx.coroutines.delay

/**
 * The phone's Now Playing screen (ADR-011): a full-screen surface over the browse shell, the
 * way a music app expands its mini bar. Portrait pins the video on top with the channel's
 * identity, now/next and quick actions below it; landscape is the video alone, with the
 * channel list one tap away as a sheet. Back collapses it to [MiniPlayerBar].
 *
 * The playback gestures (FR-UI-14) live on the video box here — the browse screens no longer
 * host any video for them to act on.
 */
@Composable
fun PlayerScreen(
    vm: AppViewModel,
    onClose: () -> Unit,
    onOpenGuide: () -> Unit,
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    twoPane: Boolean,
    inPip: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val prefs by vm.settingsState.collectAsState()
    val playback by vm.playbackStatus.collectAsState()

    if (inPip) {
        Box(modifier = modifier.background(Color.Black)) {
            PlayerSurface(
                engine = ServiceLocator.player,
                aspectMode = prefs.aspectMode,
                subtitleScale = prefs.subtitleTextScale,
                modifier = Modifier.fillMaxSize(),
            )
            PlaybackStateOverlay(
                status = playback,
                opacity = prefs.overlayOpacity,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    var channelSheet by remember { mutableStateOf(false) }

    // Close is registered first so the sheet's handler, registered after, wins while the sheet
    // is open: Back then dismisses the list before it collapses the screen.
    BackHandler(onBack = onClose)
    BackHandler(enabled = landscape && channelSheet) { channelSheet = false }

    // Re-query now/next when the running programme ends, so the titles under the video do not
    // go stale mid-viewing. Keyed on the end time: a zap replaces `now` and re-arms this.
    LaunchedEffect(state.now?.endUtc) {
        val end = state.now?.endUtc ?: return@LaunchedEffect
        val wait = end - System.currentTimeMillis() + PROGRAMME_TURNOVER_GRACE_MS
        if (wait > 0) delay(wait)
        vm.refreshNowNext()
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                val videoModifier = if (landscape) {
                    Modifier.fillMaxWidth().weight(1f)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                }

                Box(
                    modifier = videoModifier.background(Color.Black).gestureControls(
                        onBrightness = onBrightness,
                        onVolume = onVolume,
                        onSeek = { deltaMs ->
                            val exo = ServiceLocator.player.exoPlayer()
                            if (exo.isCurrentMediaItemSeekable) {
                                exo.seekTo((exo.currentPosition + deltaMs).coerceAtLeast(0))
                            }
                        },
                        onDoubleTapLeft = { ServiceLocator.player.exoPlayer().seekBack() },
                        onDoubleTapRight = { ServiceLocator.player.exoPlayer().seekForward() },
                        onCycleAspect = {
                            val order = AspectMode.entries
                            val next = order[(order.indexOf(prefs.aspectMode) + 1) % order.size]
                            ServiceLocator.settings.setAspectMode(next)
                        },
                        onTap = { if (landscape) channelSheet = !channelSheet },
                    ),
                ) {
                    PlayerSurface(
                        engine = ServiceLocator.player,
                        aspectMode = prefs.aspectMode,
                        subtitleScale = prefs.subtitleTextScale,
                        modifier = Modifier.fillMaxSize(),
                    )
                    PlaybackStateOverlay(
                        status = playback,
                        opacity = prefs.overlayOpacity,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (!landscape) {
                        // The one piece of chrome on the video: the way back down. Landscape
                        // stays clean — the system Back gesture does the same job there.
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.action_collapse_player),
                                tint = Color.White,
                            )
                        }
                    }
                }

                if (!landscape) {
                    NowPlayingDetails(
                        vm = vm,
                        onOpenGuide = onOpenGuide,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }

            // The landscape channel list: a sheet over the video, toggled by a tap on it, so
            // fullscreen viewing and browsing stay one gesture apart.
            if (landscape && channelSheet) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f)),
                ) {
                    ChannelListScreen(
                        vm = vm,
                        onPlay = { channel -> vm.play(channel); channelSheet = false },
                        compact = !twoPane,
                        showNowNext = twoPane,
                        showFilter = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * The metadata half of the portrait player screen, keyed off what is actually playing: a live
 * channel gets its identity, zap arrows and now/next; catch-up, on-demand and recordings get
 * the title and what little else is known. Deliberately no transport bar — seeking is the
 * horizontal drag and double-tap the surface already carries (FR-UI-14).
 */
@Composable
private fun NowPlayingDetails(
    vm: AppViewModel,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val playing = state.nowPlaying) {
            is NowPlayingInfo.Live -> {
                // Re-read from the current list so the star reflects a change made after playback
                // started; the snapshot in `playing` never updates.
                val channel = state.list.channels.firstOrNull { it.id == playing.channel.id }
                    ?: playing.channel
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ChannelLogo(channel = channel, sizeDp = 56)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = channel.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val group = state.list.groups.firstOrNull { it.id == channel.groupId }?.name
                        val subtitle = listOfNotNull(
                            channel.number.takeIf { it > 0 }?.toString(),
                            group,
                        ).joinToString(" · ")
                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { vm.setFavourite(channel, !channel.favourite) }) {
                        Icon(
                            imageVector = if (channel.favourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = stringResource(
                                if (channel.favourite) R.string.action_unfavourite else R.string.action_favourite,
                            ),
                            tint = if (channel.favourite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Transport-style arrows, deliberately not mirrored in RTL: like a seek bar,
                    // "next" keeps its direction regardless of the reading order.
                    FilledTonalIconButton(onClick = { vm.zap(-1) }) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = stringResource(R.string.channel_previous),
                        )
                    }
                    TextButton(onClick = onOpenGuide, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.DateRange, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.nav_guide))
                    }
                    FilledTonalIconButton(onClick = { vm.zap(1) }) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = stringResource(R.string.channel_next),
                        )
                    }
                }
                HorizontalDivider()
                NowNextPane(
                    channel = channel,
                    now = state.now,
                    next = state.next,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is NowPlayingInfo.Catchup -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ChannelLogo(channel = playing.channel, sizeDp = 56)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playing.programme.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = playing.channel.displayName + " · " +
                                TimeModel.formatClockRange(
                                    playing.programme.startUtc,
                                    playing.programme.endUtc,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                playing.programme.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is NowPlayingInfo.OnDemand -> {
                Text(
                    text = playing.item.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(playing.item.year, playing.item.rating).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                playing.item.plot?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is NowPlayingInfo.RecordingFile -> {
                Text(
                    text = playing.recording.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = playing.recording.channelName + " · " +
                        TimeModel.formatDayTime(playing.recording.startUtc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Still tuning: the playback overlay on the surface above already says so.
            null -> Unit
        }
    }
}

/**
 * FR-UI-14 gestures. Brightness and volume are reported to the host activity, because both
 * belong to the window and the audio manager rather than to the player.
 */
private fun Modifier.gestureControls(
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit,
    onCycleAspect: () -> Unit,
    onTap: () -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        val width = size.width
        detectVerticalDragGestures { change, dragAmount ->
            change.consume()
            // Dragging up must increase, so the sign is inverted against screen coordinates.
            val delta = -dragAmount / size.height.toFloat()
            if (change.position.x < width / 2f) onBrightness(delta) else onVolume(delta)
        }
    }
    .pointerInput(Unit) {
        detectHorizontalDragGestures { change, dragAmount ->
            change.consume()
            onSeek((dragAmount / size.width.toFloat() * SEEK_SWEEP_MS).toLong())
        }
    }
    .pointerInput(Unit) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { offset ->
                if (offset.x < size.width / 2f) onDoubleTapLeft() else onDoubleTapRight()
            },
            // Aspect cycling is bound to a long press rather than a pinch: a pinch detector on
            // this surface would contend with the vertical and horizontal drag detectors above
            // and make both less reliable, which is a bad trade for a rarely used control.
            onLongPress = { onCycleAspect() },
        )
    }

/** A full-width horizontal sweep seeks this far. */
private const val SEEK_SWEEP_MS = 120_000f

/** How long past a programme's end to wait before re-asking the guide, absorbing clock skew. */
private const val PROGRAMME_TURNOVER_GRACE_MS = 5_000L
