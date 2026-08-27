package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.model.VodItem
import com.tivicopy.player.record.SERIES_LOOKAHEAD_MILLIS
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.AudioOnlyNowPlaying
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.tvMetricsFor
import com.tivicopy.player.ui.common.describe
import com.tivicopy.player.ui.common.PlaybackStateOverlay
import com.tivicopy.player.ui.common.PlayerSurface
import com.tivicopy.player.ui.common.audioOnlyBackdropVisible
import com.tivicopy.player.ui.common.audioOnlyChannel
import com.tivicopy.player.ui.common.ProgrammeProgress
import com.tivicopy.player.ui.screens.AddSourceScreen
import com.tivicopy.player.ui.screens.ChannelListScreen
import com.tivicopy.player.ui.screens.NavDestination
import com.tivicopy.player.ui.screens.ProgrammeDetailsDialog
import com.tivicopy.player.ui.screens.RecordingsScreen
import com.tivicopy.player.ui.screens.SearchScreen
import com.tivicopy.player.ui.screens.VodScreen
import com.tivicopy.player.ui.screens.VodScreenState
import com.tivicopy.player.ui.settings.SettingsScreen
import com.tivicopy.player.ui.settings.SettingsSection
import com.tivicopy.player.ui.common.UpdatePrompt

/** Which overlay, if any, sits on top of the video. Nothing here ever stops playback. */
enum class TvOverlay {
    NONE, HOME, CHANNELS, RADIO, MINI_GUIDE, GUIDE, SEARCH, RECORDINGS, VOD, SETTINGS, ADD_SOURCE, INFO,

    /** The controls OK brings up over playing video. */
    TRANSPORT,

    /** Everything the transport bar is too small for, on a long OK. */
    PLAYBACK_MENU;

    /**
     * Whether the video is still the screen while this overlay is up.
     *
     * Four separate places used to spell this as `!= NONE && != INFO`, which was fine while the
     * info card was the only thing drawn over playing video. It stopped being fine the moment
     * other surfaces joined it: an overlay the remote should still be able to zap and type channel
     * numbers underneath is not the same thing as one that owns the D-pad, and a comparison
     * repeated in four files cannot say which is which. Naming it puts the answer in one place.
     */
    val isPassthrough: Boolean
        get() = this == NONE || this == INFO || this == MINI_GUIDE ||
            this == TRANSPORT || this == PLAYBACK_MENU

    /**
     * Whether this overlay owns the D-pad.
     *
     * A passthrough overlay still draws focusable controls — the filmstrip and the transport bar
     * both do — so "the video is still the screen" and "the remote's arrows still zap" are two
     * different questions. Only the bare screen and the auto-dismissing info card leave the arrows
     * to the activity.
     */
    val leavesDpadToShell: Boolean
        get() = this == NONE || this == INFO

    /**
     * Whether the live picture shrinks into a preview slot rather than filling the screen.
     *
     * Two surfaces now do this: the guide docks it beside the grid, and the home screen makes it
     * the hero. Both then paint their own panes around a picture that never stops playing, which
     * is also why [OverlayScaffold] draws no scrim for either.
     */
    val docksPlayer: Boolean
        get() = this == GUIDE || this == HOME
}

/**
 * The TV shell: fullscreen video is the root screen (FR-UI-02), and every other surface is an
 * overlay drawn over a stream that keeps playing (FR-UI-06, NFR-PERF-04).
 */
@Composable
fun TvRoot(
    vm: AppViewModel,
    overlay: TvOverlay,
    settingsSection: SettingsSection,
    onOverlayChange: (TvOverlay) -> Unit,
    onSettingsSection: (SettingsSection) -> Unit,
    numericEntry: String,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val status by vm.playbackStatus.collectAsState()
    val prefs by vm.settingsState.collectAsState()
    var details by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }
    // Loaded when the details dialog opens so a series recording can claim the episodes the
    // guide already lists, without querying on every recomposition.
    var upcoming by remember { mutableStateOf<List<Programme>>(emptyList()) }
    // A series picked out of search, handed to the on-demand overlay so it opens on that series
    // rather than on the grid the user would then have to search again.
    var pendingSeries by remember { mutableStateOf<VodItem?>(null) }
    // Held here, above the overlay switch, so closing and reopening the on-demand overlay
    // restores the search box, the filter, the open series and the scroll position.
    val vodState = remember { VodScreenState() }

    // The screen drops the request itself once it has taken it; this only covers the request
    // that is never taken, because on demand was left before it composed.
    LaunchedEffect(overlay) { if (overlay != TvOverlay.VOD) pendingSeries = null }

    // Radio borrows the same channel-list machinery as Channels, pointed at the bundled radio
    // playlist instead of the active one (see AppViewModel.openRadio); leaving it hands the list
    // straight back so Channels never ends up showing radio stations.
    var wasRadio by remember { mutableStateOf(false) }
    LaunchedEffect(overlay) {
        if (overlay == TvOverlay.RADIO) {
            vm.openRadio()
            wasRadio = true
        } else if (wasRadio) {
            vm.closeRadio()
            wasRadio = false
        }
    }

    LaunchedEffect(details?.first?.id) {
        val channel = details?.first
        upcoming = if (channel == null) emptyList() else {
            val now = System.currentTimeMillis()
            vm.programmesFor(channel, now, now + SERIES_LOOKAHEAD_MILLIS)
        }
    }

    // Where the guide wants the live picture drawn, in root coordinates, and where this box
    // itself sits so that rectangle can be made relative to it.
    var dockBounds by remember { mutableStateOf<Rect?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val docked = overlay.docksPlayer
    // Forget the slot whenever the overlay changes, not merely when docking stops. Keyed on
    // `docked` alone, a move between the two docking surfaces — home to guide — kept the old
    // rectangle, and the picture sat in the home screen's hero position until the guide got
    // round to reporting its own.
    LaunchedEffect(overlay) { dockBounds = null }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .onGloballyPositioned { rootOrigin = it.positionInRoot() },
    ) {
        // One surface, two sizes. It is never moved to another call site and never re-created:
        // Compose measures the same node smaller, so the stream is not interrupted and nothing
        // re-tunes. Reparenting it — or scaling it with a graphics layer, which does not move a
        // SurfaceView's punched hole at all — would both have cost a black frame or worse.
        val dock = dockBounds.takeIf { docked }
        // Whatever stands in for the picture goes where the picture goes — the surface itself,
        // and the radio backdrop that replaces it when there is no picture to draw.
        val pictureBounds = if (dock == null) {
            Modifier.fillMaxSize()
        } else {
            with(LocalDensity.current) {
                Modifier
                    .offset {
                        IntOffset(
                            (dock.left - rootOrigin.x).roundToInt(),
                            (dock.top - rootOrigin.y).roundToInt(),
                        )
                    }
                    .size(dock.width.toDp(), dock.height.toDp())
            }
        }
        PlayerSurface(
            engine = ServiceLocator.player,
            aspectMode = prefs.aspectMode,
            subtitleScale = prefs.subtitleTextScale,
            docked = dock != null,
            modifier = pictureBounds,
        )

        // Radio (and any other audio-only stream) never produces a video frame, so the surface
        // above is a steady black rectangle with sound and nothing to look at — indistinguishable
        // from broken. Detected the same way PlaybackStatus already reports it (no video track),
        // not a new "isRadio" flag, so this also covers a TV channel that happens to be audio-only.
        //
        // Whether it is on screen depends on where it *was* as well as on the status, so that
        // pausing a station does not take the screen — and its own pause button — away with it;
        // the reasoning is in `audioOnlyBackdropVisible`. Keyed on what is playing rather than
        // on the channel, which deliberately outlives an on-demand session: starting a film
        // straight after a station has to begin the question again, not inherit its answer.
        var wasAudioOnly by remember(state.nowPlaying) { mutableStateOf(false) }
        val audioOnly = audioOnlyBackdropVisible(status, wasAudioOnly)
        SideEffect { wasAudioOnly = audioOnly }
        if (audioOnly) {
            AudioOnlyNowPlaying(
                channel = state.nowPlaying.audioOnlyChannel(),
                title = status.title,
                playWhenReady = status.playWhenReady,
                onTogglePlay = { ServiceLocator.player.togglePlayPause() },
                // Only while nothing else owns the arrows: an overlay draws its own focusable
                // controls over this one, and two claims on the D-pad is one too many.
                takeDpadFocus = overlay.leavesDpadToShell,
                modifier = pictureBounds,
            )
        }

        // Zapping on a remote gives no other feedback: without this a channel that is still
        // tuning is a black screen, identical to one that failed. The settled-failure text is
        // left to PlaybackErrorBanner below, which is where a 10-foot layout wants it.
        PlaybackStateOverlay(
            status = status,
            showErrorText = false,
            opacity = prefs.overlayOpacity,
            modifier = Modifier.fillMaxSize(),
        )

        // The info card auto-dismisses; everything else waits for Back.
        if (overlay == TvOverlay.INFO) {
            ChannelInfoCard(
                channel = state.currentChannel,
                now = state.now,
                next = state.next,
                modifier = Modifier.align(Alignment.BottomStart).padding(TV_CONTENT_INSET),
            )
        }

        if (numericEntry.isNotEmpty()) {
            Text(
                text = stringResource(R.string.channel_number_entry, numericEntry),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(TV_CONTENT_INSET)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = prefs.overlayOpacity))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        status.error?.let { error ->
            PlaybackErrorBanner(
                error = error,
                modifier = Modifier.align(Alignment.TopStart).padding(TV_CONTENT_INSET),
            )
        }

        // The filmstrip is drawn over playing video with no scrim of its own, which is what makes
        // it a way to change channel rather than a screen to visit.
        if (overlay == TvOverlay.MINI_GUIDE) {
            ChannelFilmstrip(
                vm = vm,
                onPlay = { channel -> vm.play(channel) },
                opacity = prefs.overlayOpacity,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }

        if (overlay == TvOverlay.TRANSPORT) {
            TransportBar(
                vm = vm,
                onOpenGuide = { onOverlayChange(TvOverlay.GUIDE) },
                onOpenMenu = { onOverlayChange(TvOverlay.PLAYBACK_MENU) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (overlay == TvOverlay.PLAYBACK_MENU) {
            PlaybackMenu(
                vm = vm,
                onDismiss = { onOverlayChange(TvOverlay.NONE) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(TV_CONTENT_INSET),
            )
        }

        if (!overlay.isPassthrough) {
            OverlayScaffold(
                opacity = prefs.overlayOpacity,
                // The guide paints its own panes and leaves the preview slot bare. A scrim here
                // would be drawn straight over the live picture the whole layout exists to keep
                // visible.
                scrim = !overlay.docksPlayer,
            ) {
                when (overlay) {
                    // The television's root surface (ADR-016). Added here in the same edit as the
                    // enum value on purpose: the `else -> Unit` at the bottom of this `when` means
                    // a forgotten branch compiles and renders an empty scaffold over the video.
                    TvOverlay.HOME -> TvHomeScreen(
                        vm = vm,
                        onDockBounds = { dockBounds = it },
                        onOpen = onOverlayChange,
                        onFullscreen = { onOverlayChange(TvOverlay.NONE) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    TvOverlay.CHANNELS -> ChannelListScreen(
                        vm = vm,
                        onPlay = { channel -> vm.play(channel); onOverlayChange(TvOverlay.NONE) },
                        onSearch = { onOverlayChange(TvOverlay.SEARCH) },
                        // The ten-foot equivalent of the phone's bottom bar. On demand had no
                        // entry point at all before this — it could only be opened by searching
                        // for a series and picking it (NFR-UX-01).
                        destinations = listOf(
                            NavDestination(stringResource(R.string.nav_home)) {
                                onOverlayChange(TvOverlay.HOME)
                            },
                            NavDestination(stringResource(R.string.nav_radio)) {
                                onOverlayChange(TvOverlay.RADIO)
                            },
                            NavDestination(stringResource(R.string.nav_guide)) {
                                onOverlayChange(TvOverlay.GUIDE)
                            },
                            NavDestination(stringResource(R.string.nav_vod)) {
                                onOverlayChange(TvOverlay.VOD)
                            },
                            NavDestination(stringResource(R.string.nav_recordings)) {
                                onOverlayChange(TvOverlay.RECORDINGS)
                            },
                            NavDestination(stringResource(R.string.nav_settings)) {
                                onOverlayChange(TvOverlay.SETTINGS)
                            },
                        ),
                        // Opening the list lands on the playing channel, focused, so zapping
                        // onward is one press instead of a scroll from the top.
                        scrollToCurrent = true,
                        focusCurrent = true,
                        // As on the phone: each row says what that channel is showing now. The
                        // query behind it was always windowed and debounced; the ten-foot shell
                        // was simply the one caller that never asked for it.
                        showNowLine = true,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Same screen as CHANNELS above, browsing the bundled radio playlist instead
                    // of the active one (FR: radio is its own destination, not a group inside
                    // Channels) — see the openRadio/closeRadio effect above.
                    TvOverlay.RADIO -> ChannelListScreen(
                        vm = vm,
                        onPlay = { channel -> vm.play(channel); onOverlayChange(TvOverlay.NONE) },
                        onSearch = { onOverlayChange(TvOverlay.SEARCH) },
                        destinations = listOf(
                            NavDestination(stringResource(R.string.nav_home)) {
                                onOverlayChange(TvOverlay.HOME)
                            },
                            NavDestination(stringResource(R.string.nav_live)) {
                                onOverlayChange(TvOverlay.CHANNELS)
                            },
                            NavDestination(stringResource(R.string.nav_guide)) {
                                onOverlayChange(TvOverlay.GUIDE)
                            },
                            NavDestination(stringResource(R.string.nav_vod)) {
                                onOverlayChange(TvOverlay.VOD)
                            },
                            NavDestination(stringResource(R.string.nav_recordings)) {
                                onOverlayChange(TvOverlay.RECORDINGS)
                            },
                            NavDestination(stringResource(R.string.nav_settings)) {
                                onOverlayChange(TvOverlay.SETTINGS)
                            },
                        ),
                        scrollToCurrent = true,
                        focusCurrent = true,
                        showNowLine = true,
                        isRadio = true,
                        modifier = Modifier.fillMaxSize(),
                    )

                    TvOverlay.GUIDE -> PictureInGuide(
                        vm = vm,
                        onDockBounds = { dockBounds = it },
                        onPlay = { channel -> vm.play(channel) },
                        onOpenProgramme = { channel, programme -> details = channel to programme },
                        opacity = prefs.overlayOpacity,
                        modifier = Modifier.fillMaxSize(),
                    )

                    TvOverlay.SEARCH -> SearchScreen(
                        vm = vm,
                        onPlay = { channel -> vm.play(channel); onOverlayChange(TvOverlay.NONE) },
                        onOpenProgramme = { channel, programme -> details = channel to programme },
                        onPlayVod = { item -> vm.playVod(item); onOverlayChange(TvOverlay.NONE) },
                        onOpenSeries = { item ->
                            pendingSeries = item
                            onOverlayChange(TvOverlay.VOD)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    TvOverlay.RECORDINGS -> RecordingsScreen(
                        onPlayRecording = { recording ->
                            vm.playRecording(recording)
                            onOverlayChange(TvOverlay.NONE)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    TvOverlay.VOD -> VodScreen(
                        vm = vm,
                        onPlay = { item ->
                            vm.playVod(item)
                            onOverlayChange(TvOverlay.NONE)
                        },
                        modifier = Modifier.fillMaxSize(),
                        openSeriesRequest = pendingSeries,
                        onSeriesRequestConsumed = { pendingSeries = null },
                        state = vodState,
                    )

                    TvOverlay.SETTINGS -> SettingsScreen(
                        vm = vm,
                        section = settingsSection,
                        onNavigate = onSettingsSection,
                        onAddSource = { onOverlayChange(TvOverlay.ADD_SOURCE) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    TvOverlay.ADD_SOURCE -> AddSourceScreen(
                        vm = vm,
                        onDone = { onOverlayChange(TvOverlay.NONE) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> Unit
                }
            }
        }

        details?.let { (channel, programme) ->
            ProgrammeDetailsDialog(
                channel = channel,
                programme = programme,
                onDismiss = { details = null },
                onWatch = { vm.play(channel); onOverlayChange(TvOverlay.NONE) },
                onCatchup = { vm.playCatchup(channel, programme); onOverlayChange(TvOverlay.NONE) },
                onFavourite = { favourite -> vm.setFavourite(channel, favourite) },
                onMessage = { text -> vm.post(com.tivicopy.player.ui.UiMessage(text = text)) },
                upcoming = upcoming,
            )
        }

        // FR-UPD-03: offered here rather than inside a screen, so it survives tab changes and
        // is absent in picture-in-picture, which returns above this point.
        UpdatePrompt()
    }
}

/**
 * The container every browse overlay is drawn in.
 *
 * The background is painted on the full-size box and the inset is applied *inside* it. It used to
 * be the other way round, which drew the scrim within a 32dp/24dp margin and left every surface —
 * the channel list, the guide, settings — floating in a frame of raw video. That was the second of
 * the three reasons the interface was reported as not filling the screen, and the most visible.
 *
 * The inset itself is kept, as content padding rather than a frame: dropping it altogether would
 * put settings rows and on-demand titles flush against the edge of the panel, and a television
 * that still overscans would eat them. [TV_CONTENT_INSET] is what remains of the old overscan
 * margin — the surface reaches the edge, the words do not.
 */
@Composable
private fun OverlayScaffold(
    opacity: Float,
    /**
     * Whether to dim the video behind. The guide says no: it draws its own panes around a live
     * preview, and a scrim here would tint the one thing it is trying to keep watchable.
     */
    scrim: Boolean = true,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (scrim) {
                    Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = opacity))
                } else {
                    Modifier
                },
            )
            .padding(TV_CONTENT_INSET),
    ) {
        // Measured here rather than read from the Configuration: this box is what the screens
        // actually get, insets and all, and it is the only honest input to how wide an hour of
        // guide should be.
        val metrics = tvMetricsFor(maxWidth.value, maxHeight.value)
        CompositionLocalProvider(LocalTvMetrics provides metrics) { content() }
    }
}

/**
 * How far the ten-foot shell keeps text from the edge of the panel.
 *
 * Not a safe area — the surfaces themselves are full-bleed by decision (docs/03 section 3.1). This
 * is only the breathing room any readable layout wants, and it happens to absorb the small crop an
 * older overscanning television applies.
 */
internal val TV_CONTENT_INSET = 24.dp

/** The zapping info card (FR-PLB-18). */
@Composable
fun ChannelInfoCard(
    channel: Channel?,
    now: Programme?,
    next: Programme?,
    modifier: Modifier = Modifier,
) {
    if (channel == null) return
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChannelLogo(channel = channel, sizeDp = 56)
            Column {
                Text(
                    text = "${channel.number.takeIf { it > 0 } ?: ""} ${channel.displayName}".trim(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = now?.title ?: stringResource(R.string.no_guide_data),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        now?.let {
            Text(
                text = TimeModel.formatClockRange(it.startUtc, it.endUtc),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProgrammeProgress(it.startUtc, it.endUtc, modifier = Modifier.fillMaxWidth(0.5f))
        }
        next?.let {
            Text(
                text = "${TimeModel.ltr(TimeModel.formatClock(it.startUtc))} · ${it.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlaybackErrorBanner(error: com.tivicopy.player.core.AppError, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp),
    ) {
        Text(
            text = error.describe(context),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
