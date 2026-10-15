package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.player.TrackOption
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.NowPlayingInfo
import com.tivicopy.player.ui.UiMessage
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.LiveBadge
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.MENU_MIN_OPACITY
import com.tivicopy.player.ui.common.ProgrammeProgress
import com.tivicopy.player.ui.common.filledContentColour
import com.tivicopy.player.ui.common.focusLift
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.common.isLive
import com.tivicopy.player.ui.common.labelRes
import com.tivicopy.player.ui.common.nowPlayingLabels
import com.tivicopy.player.ui.common.qualitySummary
import com.tivicopy.player.ui.common.rememberAspectCycler
import com.tivicopy.player.ui.common.ListRow
import com.tivicopy.player.ui.common.RowTrail
import com.tivicopy.player.ui.common.StudioButton
import com.tivicopy.player.ui.common.StudioButtonSize
import com.tivicopy.player.ui.screens.scheduleRecording
import com.tivicopy.player.ui.theme.StudioTheme
import com.tivicopy.player.ui.common.StudioIcons

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
    /** The programme sheet for what is on air: watch, catch-up, record, series (ADR-034). */
    onOpenDetails: (Channel, Programme) -> Unit = { _, _ -> },
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

    // `PlayerTransport`: no plate. A lower third over the canvas's bottom scrim, the controls
    // under it as square buttons, and the hint for the long press under those. The scrim follows
    // the viewer's overlay opacity (FR-UI-18), with a floor so the text over it stays legible.
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val scrim = prefs.overlayOpacity.coerceAtLeast(0.5f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to studio.ground.copy(alpha = 0f),
                    0.3f to studio.ground.copy(alpha = 0.55f * scrim),
                    0.6f to studio.ground.copy(alpha = 0.95f * scrim),
                    1f to studio.ground.copy(alpha = scrim),
                ),
            )
            .padding(start = 48.dp, end = 48.dp, top = 72.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
        labels.second?.let {
            Text(
                text = it,
                style = type.rowLabel,
                color = studio.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = labels.first,
            // The lower third's title: Karantina, between the small title step and the title.
            style = type.title.copy(fontSize = type.title.fontSize * LOWER_THIRD_TITLE_SCALE),
            color = studio.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Only live has a programme to be part-way through. Catch-up and on-demand have a
        // position, but this app has no scrubber yet and docs/10 says so rather than drawing one
        // that cannot be dragged.
        if (live != null) {
            state.now?.let { now ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = TimeModel.ltr(TimeModel.formatClockRange(now.startUtc, now.endUtc)),
                        style = type.meta,
                        color = studio.ink2,
                    )
                    ProgrammeProgress(now.startUtc, now.endUtc, modifier = Modifier.width(220.dp))
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.nowPlaying.isLive()) {
                TransportButton(
                    icon = StudioIcons.ChevronUp,
                    description = stringResource(R.string.channel_previous),
                    onClick = { vm.zap(-1) },
                )
            }
            TransportButton(
                icon = if (playback.playWhenReady) StudioIcons.Pause else StudioIcons.Play,
                description = stringResource(
                    if (playback.playWhenReady) R.string.action_pause else R.string.action_play,
                ),
                onClick = { ServiceLocator.player.togglePlayPause() },
                focusRequester = focusPlay,
            )
            if (state.nowPlaying.isLive()) {
                TransportButton(
                    icon = StudioIcons.ChevronDown,
                    description = stringResource(R.string.channel_next),
                    onClick = { vm.zap(1) },
                )
            }
            // The canvas's rule between moving through the stream and everything else.
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .width(1.dp)
                    .height(22.dp)
                    .background(studio.hairline),
            )
            // `PlayerTransport`'s two labelled actions on what is on air now (ADR-034). Without a
            // known programme there is nothing for either to act on, and neither is drawn.
            val onAir = state.now?.takeIf { liveChannel != null }
            if (liveChannel != null && onAir != null) {
                TransportButton(
                    icon = StudioIcons.Info,
                    description = stringResource(R.string.transport_details),
                    onClick = { onOpenDetails(liveChannel, onAir) },
                    labelled = true,
                )
                TransportButton(
                    icon = StudioIcons.Record,
                    description = stringResource(R.string.action_record),
                    onClick = { vm.post(UiMessage(text = scheduleRecording(liveChannel, onAir))) },
                    labelled = true,
                )
            }
            if (liveChannel != null) {
                TransportButton(
                    icon = StudioIcons.Guide,
                    description = stringResource(R.string.nav_guide),
                    onClick = onOpenGuide,
                    labelled = true,
                )
                TransportButton(
                    icon = if (liveChannel.favourite) StudioIcons.StarOn else StudioIcons.Star,
                    description = stringResource(
                        if (liveChannel.favourite) R.string.action_unfavourite
                        else R.string.action_favourite,
                    ),
                    onClick = { vm.setFavourite(liveChannel, !liveChannel.favourite) },
                )
            }
            TransportButton(
                icon = StudioIcons.Aspect,
                description = stringResource(R.string.aspect_ratio),
                onClick = cycleAspect,
            )

            Box(modifier = Modifier.weight(1f))

            qualitySummary(playback)?.let {
                Text(text = it, style = type.meta, color = studio.ink2)
            }
        }

        Text(
            text = stringResource(R.string.playback_menu_hint),
            style = type.caption,
            color = studio.ink2,
            modifier = Modifier.clickable(onClick = onOpenMenu),
        )
    }
}

/**
 * One control on the transport bar: Studio's medium button (`PlayerTransport`), square when it is
 * a glyph alone, becoming the cue when the remote is on it. Every one is the same weight; the
 * canvas gives play and pause no plate of their own.
 *
 * @param labelled the canvas's details, record and guide, which carry their name beside the glyph
 */
@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    labelled: Boolean = false,
) {
    StudioButton(
        label = description,
        onClick = onClick,
        icon = icon,
        iconOnly = !labelled,
        size = StudioButtonSize.MEDIUM,
        focusRequester = focusRequester,
    )
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
    /** The programme sheet for what is on air, from the menu's first row (ADR-034). */
    onOpenDetails: (Channel, Programme) -> Unit = { _, _ -> },
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
        //
        // `PlayerMenu`: a drawer the full height of the screen against its end edge, as the
        // channel menu is — the picture stays in view beside it.
        modifier = modifier
            .width(310.dp)
            .fillMaxHeight()
            .background(StudioTheme.colors.drawer.copy(alpha = prefs.overlayOpacity.coerceAtLeast(MENU_MIN_OPACITY)))
            .padding(start = 28.dp, end = 48.dp, top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = stringResource(R.string.playback_menu),
            style = StudioTheme.type.titleSmall.copy(fontSize = StudioTheme.type.titleSmall.fontSize * 0.75f),
            color = StudioTheme.colors.ink,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        labels?.let {
            Text(
                text = listOfNotNull(it.first, qualitySummary(playback)).joinToString(" · "),
                style = StudioTheme.type.caption,
                color = StudioTheme.colors.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
            )
        }

        // `PlayerMenu`'s first row, on a live channel whose programme is known.
        val live = (state.nowPlaying as? NowPlayingInfo.Live)?.channel
        val onAir = state.now?.takeIf { live != null }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (live != null && onAir != null) {
                item {
                    MenuEntry(
                        title = stringResource(R.string.menu_programme_details),
                        icon = StudioIcons.Info,
                        chevron = true,
                        onClick = { onOpenDetails(live, onAir) },
                    )
                }
            }
            item {
                MenuEntry(
                    title = stringResource(
                        if (playback.playWhenReady) R.string.action_pause else R.string.action_play,
                    ),
                    icon = if (playback.playWhenReady) StudioIcons.Pause else StudioIcons.Play,
                    onClick = { ServiceLocator.player.togglePlayPause(); onDismiss() },
                )
            }
            item {
                MenuEntry(
                    title = stringResource(R.string.aspect_ratio),
                    detail = stringResource(prefs.aspectMode.labelRes()),
                    icon = StudioIcons.Aspect,
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
        style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
        color = StudioTheme.colors.ink2,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp, start = 12.dp),
    )
}

/** One entry in the playback menu: Studio's list row, with its glyph, value or check. */
@Composable
private fun MenuEntry(
    title: String,
    onClick: () -> Unit,
    detail: String? = null,
    selected: Boolean = false,
    icon: ImageVector? = null,
    /** Opens something further in. */
    chevron: Boolean = false,
) {
    ListRow(
        label = title,
        onClick = onClick,
        icon = icon,
        trail = when {
            chevron -> RowTrail.CHEVRON
            detail != null -> RowTrail.VALUE
            selected -> RowTrail.CHECK
            else -> RowTrail.NONE
        },
        value = detail,
        selected = selected,
    )
}

/** The lower third's programme title: 96px at 1080p against the title step's 108. */
private const val LOWER_THIRD_TITLE_SCALE = 96f / 108f
