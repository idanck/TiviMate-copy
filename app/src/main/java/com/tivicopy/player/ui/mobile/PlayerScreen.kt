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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.prefs.AspectMode
import com.tivicopy.player.player.PlaybackStatus
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.NowPlayingInfo
import com.tivicopy.player.ui.UiMessage
import com.tivicopy.player.ui.common.AudioOnlyNowPlaying
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.PlaybackStateOverlay
import com.tivicopy.player.ui.common.PlayerSurface
import com.tivicopy.player.ui.common.audioOnlyBackdropVisible
import com.tivicopy.player.ui.common.audioOnlyChannel
import com.tivicopy.player.ui.common.isLive
import com.tivicopy.player.ui.common.labelRes
import com.tivicopy.player.ui.screens.ChannelListScreen
import com.tivicopy.player.ui.screens.NowNextPane
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The phone's Now Playing screen (ADR-011): a full-screen surface over the browse shell, the
 * way a music app expands its mini bar. Portrait pins the video on top with the channel's
 * identity, now/next and quick actions below it; landscape is the video alone, with the
 * channel list one tap away as a sheet. Back collapses it to [PhoneMiniPlayer].
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
    val prefs by vm.settingsState.collectAsState()
    val playback by vm.playbackStatus.collectAsState()
    val state by vm.state.collectAsState()

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

    // The sheet is a landscape construct; composition survives rotation (the activity handles
    // it), so without this the flag stayed latched through portrait and the sheet popped back
    // open, unasked, on the next rotate.
    LaunchedEffect(landscape) {
        if (!landscape) channelSheet = false
    }

    // One aspect action for both routes into it — the long press on the video and the button
    // in the details below. It always announces what it just did: the long press is easy to
    // fire by accident, and a silently persisted Zoom reads as "the picture is suddenly
    // cropped" (device report), with nothing on screen to explain or undo it.
    val aspectChanged = stringResource(R.string.aspect_changed)
    val aspectLabels = AspectMode.entries.associateWith { stringResource(it.labelRes()) }
    val cycleAspect: () -> Unit = {
        val order = AspectMode.entries
        val next = order[(order.indexOf(prefs.aspectMode) + 1) % order.size]
        ServiceLocator.settings.setAspectMode(next)
        vm.post(UiMessage(text = aspectChanged.format(aspectLabels.getValue(next))))
    }

    // Close is registered first so the sheet's handler, registered after, wins while the sheet
    // is open: Back then dismisses the list before it collapses the screen.
    // The programme-turnover refresh lives in MobileRoot, which is always composed — here it
    // would stop the moment the user collapsed the screen, exactly when the mini bar needs it.
    BackHandler(onBack = onClose)
    BackHandler(enabled = landscape && channelSheet) { channelSheet = false }

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
                        key = landscape,
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
                        onCycleAspect = cycleAspect,
                        onTap = { if (landscape) channelSheet = !channelSheet },
                    ),
                ) {
                    PlayerSurface(
                        engine = ServiceLocator.player,
                        aspectMode = prefs.aspectMode,
                        subtitleScale = prefs.subtitleTextScale,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // A radio station has no picture, so the box above it is a black rectangle
                    // for the whole session. The television shell has replaced that with the
                    // station's own backdrop since 0.6.4; the phone gets the same one, measured
                    // down to the size of this box. Play/pause is offered here only in landscape,
                    // where the transport row below is not on screen to carry it.
                    var wasAudioOnly by remember(state.nowPlaying) { mutableStateOf(false) }
                    val audioOnly = audioOnlyBackdropVisible(playback, wasAudioOnly)
                    SideEffect { wasAudioOnly = audioOnly }
                    if (audioOnly) {
                        AudioOnlyNowPlaying(
                            channel = state.nowPlaying.audioOnlyChannel(),
                            title = playback.title,
                            playWhenReady = playback.playWhenReady,
                            song = playback.streamTitle,
                            onTogglePlay = if (landscape) {
                                { ServiceLocator.player.togglePlayPause() }
                            } else {
                                null
                            },
                            live = state.nowPlaying.isLive(),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
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
                        onCycleAspect = cycleAspect,
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
                        scrollToCurrent = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * The metadata half of the portrait player screen, keyed off what is actually playing: a live
 * channel gets its identity, zapping and now/next; catch-up, on-demand and recordings get the
 * title and what little else is known. Every kind gets the transport row (play/pause front and
 * centre) and the quality readout. Seeking stays on the surface's gestures (FR-UI-14) — there
 * is no scrubber yet, and docs/10 says so.
 */
@Composable
private fun NowPlayingDetails(
    vm: AppViewModel,
    onOpenGuide: () -> Unit,
    onCycleAspect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val playback by vm.playbackStatus.collectAsState()
    // Re-read from the current list so the star and the pane reflect an edit made after
    // playback started; the snapshot inside NowPlayingInfo never updates.
    val live = state.nowPlaying as? NowPlayingInfo.Live
    val liveChannel = live?.let { l ->
        state.list.channels.firstOrNull { it.id == l.channel.id } ?: l.channel
    }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val playing = state.nowPlaying) {
            is NowPlayingInfo.Live -> {
                val channel = liveChannel ?: playing.channel
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

        if (state.nowPlaying != null) {
            TransportRow(
                wantsPlay = playback.playWhenReady,
                showZap = live != null,
                showGuide = live != null,
                onTogglePlay = { ServiceLocator.player.togglePlayPause() },
                onZapPrevious = { vm.zap(-1) },
                onZapNext = { vm.zap(1) },
                onOpenGuide = onOpenGuide,
                onCycleAspect = onCycleAspect,
            )
            QualityLine(status = playback)
        }
        if (liveChannel != null) {
            HorizontalDivider()
            NowNextPane(
                channel = liveChannel,
                now = state.now,
                next = state.next,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Play/pause front and centre — the one control every kind of playback needs (device report) —
 * with zapping around it for live, and the guide and aspect actions on a second line. The
 * arrows are deliberately not mirrored in RTL: like a seek bar, "next" keeps its direction
 * regardless of the reading order.
 */
@Composable
private fun TransportRow(
    /** The intent flag, not `isPlaying` — a rebuffering stream must still offer Pause. */
    wantsPlay: Boolean,
    showZap: Boolean,
    showGuide: Boolean,
    onTogglePlay: () -> Unit,
    onZapPrevious: () -> Unit,
    onZapNext: () -> Unit,
    onOpenGuide: () -> Unit,
    onCycleAspect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (showZap) {
            FilledTonalIconButton(onClick = onZapPrevious) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.channel_previous),
                )
            }
        }
        FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(64.dp)) {
            Icon(
                imageVector = if (wantsPlay) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (wantsPlay) R.string.action_pause else R.string.action_play,
                ),
                modifier = Modifier.size(32.dp),
            )
        }
        if (showZap) {
            FilledTonalIconButton(onClick = onZapNext) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.channel_next),
                )
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (showGuide) {
            TextButton(onClick = onOpenGuide) {
                Icon(Icons.Filled.DateRange, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.nav_guide))
            }
        }
        TextButton(onClick = onCycleAspect) {
            Icon(Icons.Filled.AspectRatio, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.aspect_ratio))
        }
    }
}

/**
 * What the stream is actually delivering (device report asked to see it): resolution, frame
 * rate, bitrate and codec, each shown only once known. Isolated LTR so the mixed run cannot
 * reorder inside a Hebrew layout.
 */
@Composable
private fun QualityLine(status: PlaybackStatus) {
    val parts = buildList {
        if (status.height > 0) add("${status.height}p")
        if (status.frameRate > 0f) add("${status.frameRate.roundToInt()} fps")
        if (status.bitrate > 0) {
            add(String.format(Locale.ROOT, "%.1f Mbps", status.bitrate / 1_000_000f))
        }
        status.videoCodec?.takeIf { it.isNotBlank() }?.let { add(it.removePrefix("video/")) }
    }
    if (parts.isEmpty()) return
    Text(
        text = TimeModel.ltr(parts.joinToString(" · ")),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * FR-UI-14 gestures. Brightness and volume are reported to the host activity, because both
 * belong to the window and the audio manager rather than to the player.
 *
 * [key] restarts the pointer handlers so their captured lambdas stay current. The activity
 * handles rotation itself (`configChanges`), so `pointerInput(Unit)` would keep the closures
 * from the first composition for the life of the screen — with the orientation baked into
 * `onTap`, the landscape tap-for-channel-list would be dead after rotating mid-playback.
 */
private fun Modifier.gestureControls(
    key: Any?,
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit,
    onCycleAspect: () -> Unit,
    onTap: () -> Unit,
): Modifier = this
    .pointerInput(key) {
        detectVerticalDragGestures { change, dragAmount ->
            change.consume()
            // Dragging up must increase, so the sign is inverted against screen coordinates.
            val delta = -dragAmount / size.height.toFloat()
            // `size` is read per event, never cached: it changes under a live composition when
            // the window resizes without this block restarting.
            if (change.position.x < size.width / 2f) onBrightness(delta) else onVolume(delta)
        }
    }
    .pointerInput(key) {
        detectHorizontalDragGestures { change, dragAmount ->
            change.consume()
            onSeek((dragAmount / size.width.toFloat() * SEEK_SWEEP_MS).toLong())
        }
    }
    .pointerInput(key) {
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
