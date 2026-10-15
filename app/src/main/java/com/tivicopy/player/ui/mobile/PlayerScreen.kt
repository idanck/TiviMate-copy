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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.tivicopy.player.ui.common.LiveBadge
import com.tivicopy.player.ui.common.PlaybackStateOverlay
import com.tivicopy.player.ui.common.PlayerSurface
import com.tivicopy.player.ui.common.StudioProgress
import com.tivicopy.player.ui.common.audioOnlyBackdropVisible
import com.tivicopy.player.ui.common.audioOnlyChannel
import com.tivicopy.player.ui.common.isLive
import com.tivicopy.player.ui.common.labelRes
import com.tivicopy.player.ui.common.panelSurface
import com.tivicopy.player.ui.screens.ChannelListScreen
import com.tivicopy.player.ui.theme.StudioTheme
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
                        // Studio's tally along the top edge of a picture that is on air.
                        if (state.nowPlaying is NowPlayingInfo.Live) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .background(StudioTheme.colors.tally),
                            )
                        }
                        // The one piece of chrome on the video: the way back down, on a scrim so
                        // it reads over any frame. Landscape stays clean — the system Back gesture
                        // does the same job there.
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(StudioTheme.colors.scrim),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.action_collapse_player),
                                tint = StudioTheme.colors.ink,
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
 * The metadata half of the portrait player screen, Studio's `PNowPlaying`, keyed off what is
 * actually playing. A live channel gets the live badge and its name, the programme title in
 * Karantina, its time with progress and minutes left, zapping around play/pause, favourite /
 * guide / view, and what comes next in a panel in the "next" blue. Catch-up, on-demand and
 * recordings get their title and what little else is known, play/pause and the view action.
 * Seeking stays on the surface's gestures (FR-UI-14) — there is no scrubber yet, and docs/10
 * says so.
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
    val studio = StudioTheme.colors
    // Re-read from the current list so the star reflects an edit made after playback started;
    // the snapshot inside NowPlayingInfo never updates.
    val live = state.nowPlaying as? NowPlayingInfo.Live
    val liveChannel = live?.let { l ->
        state.list.channels.firstOrNull { it.id == l.channel.id } ?: l.channel
    }
    // The progress and the minutes left are read at composition; nothing else here changes once
    // a minute, so a slow tick keeps them honest without recomposing more often.
    var minute by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS)
            minute = System.currentTimeMillis()
        }
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val playing = state.nowPlaying) {
            is NowPlayingInfo.Live -> {
                val channel = liveChannel ?: playing.channel
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LiveBadge()
                    Text(
                        text = channel.displayName,
                        style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = studio.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (channel.number > 0) {
                        Text(
                            text = stringResource(R.string.channel_number_entry, channel.number.toString()),
                            style = StudioTheme.type.caption,
                            color = studio.ink2,
                            maxLines = 1,
                        )
                    }
                }
                PlayerTitle(state.now?.title ?: channel.displayName)
                state.now?.let { now ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val span = (now.endUtc - now.startUtc).coerceAtLeast(1)
                        val fraction = ((minute - now.startUtc).toFloat() / span).coerceIn(0f, 1f)
                        val left = ((now.endUtc - minute) / MINUTE_MS).coerceAtLeast(0)
                        Text(
                            text = TimeModel.formatClockRange(now.startUtc, now.endUtc),
                            style = StudioTheme.type.caption,
                            color = studio.ink2,
                        )
                        StudioProgress(fraction = fraction, modifier = Modifier.weight(1f), height = 5.dp)
                        Text(
                            text = stringResource(R.string.home_minutes_left, left.toInt()),
                            style = StudioTheme.type.caption,
                            color = studio.ink2,
                        )
                    }
                }
            }

            is NowPlayingInfo.Catchup -> {
                Text(
                    text = playing.channel.displayName,
                    style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = studio.ink,
                )
                PlayerTitle(playing.programme.title)
                Text(
                    text = TimeModel.formatClockRange(playing.programme.startUtc, playing.programme.endUtc),
                    style = StudioTheme.type.caption,
                    color = studio.ink2,
                )
                playing.programme.description?.let { PlayerDescription(it) }
            }

            is NowPlayingInfo.OnDemand -> {
                PlayerTitle(playing.item.name)
                val meta = listOfNotNull(playing.item.year, playing.item.rating).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(text = meta, style = StudioTheme.type.caption, color = studio.ink2)
                }
                playing.item.plot?.let { PlayerDescription(it) }
            }

            is NowPlayingInfo.RecordingFile -> {
                Text(
                    text = playing.recording.channelName,
                    style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = studio.ink,
                )
                PlayerTitle(playing.recording.title)
                Text(
                    text = TimeModel.formatDayTime(playing.recording.startUtc),
                    style = StudioTheme.type.caption,
                    color = studio.ink2,
                )
            }

            // Still tuning: the playback overlay on the surface above already says so.
            null -> Unit
        }

        if (state.nowPlaying != null) {
            Spacer(modifier = Modifier.height(16.dp))
            TransportRow(
                wantsPlay = playback.playWhenReady,
                showZap = live != null,
                onTogglePlay = { ServiceLocator.player.togglePlayPause() },
                onZapPrevious = { vm.zap(-1) },
                onZapNext = { vm.zap(1) },
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (liveChannel != null) {
                    PhoneButton(
                        label = stringResource(R.string.player_favourite),
                        icon = if (liveChannel.favourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        size = PhoneButtonSize.M,
                        onClick = { vm.setFavourite(liveChannel, !liveChannel.favourite) },
                        modifier = Modifier.weight(1f),
                    )
                    PhoneButton(
                        label = stringResource(R.string.nav_guide),
                        icon = Icons.Filled.DateRange,
                        size = PhoneButtonSize.M,
                        onClick = onOpenGuide,
                        modifier = Modifier.weight(1f),
                    )
                }
                PhoneButton(
                    label = stringResource(R.string.player_view),
                    icon = Icons.Filled.AspectRatio,
                    size = PhoneButtonSize.M,
                    onClick = onCycleAspect,
                    modifier = Modifier.weight(1f),
                )
            }
            QualityLine(status = playback)
        }
        val next = state.next
        if (liveChannel != null && next != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .panelSurface(opacity = 1f)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.info_next) + " · " + TimeModel.ltr(TimeModel.formatClock(next.startUtc)),
                    style = StudioTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = studio.next,
                )
                Text(
                    text = next.title,
                    style = StudioTheme.type.body.copy(fontSize = StudioTheme.type.body.fontSize * (18f / 16f), fontWeight = FontWeight.SemiBold),
                    color = studio.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = TimeModel.formatClockRange(next.startUtc, next.endUtc),
                    style = StudioTheme.type.caption,
                    color = studio.ink2,
                )
            }
        }
    }
}

/** The programme's title as Studio sets it on the phone: Karantina, 44sp, two lines at most. */
@Composable
private fun PlayerTitle(text: String) {
    val hero = StudioTheme.type.hero
    Text(
        text = text,
        style = hero.copy(fontSize = hero.fontSize * (44f / 48f), lineHeight = hero.fontSize * (44f / 48f) * 0.95f),
        color = StudioTheme.colors.ink,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PlayerDescription(text: String) {
    Text(
        text = text,
        style = StudioTheme.type.meta,
        color = StudioTheme.colors.ink2,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Play/pause front and centre — the one control every kind of playback needs (device report) —
 * in the cue, with zapping either side of it for live. The arrows are deliberately not mirrored
 * in RTL: like a seek bar, "next" keeps its direction regardless of the reading order.
 */
@Composable
private fun TransportRow(
    /** The intent flag, not `isPlaying` — a rebuffering stream must still offer Pause. */
    wantsPlay: Boolean,
    showZap: Boolean,
    onTogglePlay: () -> Unit,
    onZapPrevious: () -> Unit,
    onZapNext: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (showZap) {
            PhoneButton(
                label = stringResource(R.string.channel_previous),
                icon = Icons.Filled.KeyboardArrowUp,
                iconOnly = true,
                size = PhoneButtonSize.L,
                onClick = onZapPrevious,
            )
        }
        PhoneButton(
            label = stringResource(if (wantsPlay) R.string.action_pause else R.string.action_play),
            icon = if (wantsPlay) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            iconOnly = true,
            variant = PhoneButtonVariant.PRIMARY,
            size = PhoneButtonSize.XL,
            onClick = onTogglePlay,
        )
        if (showZap) {
            PhoneButton(
                label = stringResource(R.string.channel_next),
                icon = Icons.Filled.KeyboardArrowDown,
                iconOnly = true,
                size = PhoneButtonSize.L,
                onClick = onZapNext,
            )
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

/** A minute, for the details' clock. */
private const val MINUTE_MS = 60_000L

/** A full-width horizontal sweep seeks this far. */
private const val SEEK_SWEEP_MS = 120_000f
