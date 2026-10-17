package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.tivicopy.player.ui.common.PlaybackErrorActions
import com.tivicopy.player.ui.common.PlaybackStateOverlay
import com.tivicopy.player.ui.common.PlayerSurface
import com.tivicopy.player.ui.common.audioOnlyBackdropVisible
import com.tivicopy.player.ui.common.audioOnlyChannel
import com.tivicopy.player.ui.common.isLive
import com.tivicopy.player.ui.common.LiveBadge
import com.tivicopy.player.ui.common.ProgrammeProgress
import com.tivicopy.player.ui.common.screenEntrance
import com.tivicopy.player.ui.screens.AddSourceScreen
import com.tivicopy.player.ui.screens.ChannelListScreen
import com.tivicopy.player.ui.screens.ProgrammeDetailsDialog
import com.tivicopy.player.ui.screens.RecordingsScreen
import com.tivicopy.player.ui.screens.SearchScreen
import com.tivicopy.player.ui.screens.VodScreen
import com.tivicopy.player.ui.screens.VodScreenState
import com.tivicopy.player.ui.settings.SettingsScreen
import com.tivicopy.player.ui.settings.SettingsSection
import com.tivicopy.player.ui.common.UpdatePrompt
import com.tivicopy.player.ui.theme.StudioTheme

/** Which overlay, if any, sits on top of the video. Nothing here ever stops playback. */
enum class TvOverlay {
    NONE, HOME, CHANNELS, RADIO, MINI_GUIDE, GUIDE, SEARCH, RECORDINGS, VOD, SETTINGS, ADD_SOURCE, INFO,

    /** The controls OK brings up over playing video. */
    TRANSPORT,

    /** Everything the transport bar is too small for, on a long OK. */
    PLAYBACK_MENU,

    /**
     * Sport mode (ADR-041): two channels at once, each in a panel of its own. Not drawn
     * over the video at all — the full-screen player stands down while it is up — so it is neither
     * passthrough nor a browse screen, and [TvRoot] draws it in place of both.
     */
    SPORT,

    /**
     * News mode (ADR-042): two Israeli news channels, or one and the headlines. Sport mode's
     * screen and session in every respect but what they offer; see [isPanels].
     */
    NEWS;

    /** Whether this is one of the modes of several panels at once, which own playback while up. */
    val isPanels: Boolean
        get() = this == SPORT || this == NEWS

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
     * Only the guide, which docks it beside the grid. The home screen used to as well — ADR-016
     * gave it a boxed hero — and ADR-020 took that back: its banner is the picture at full size
     * with the screen's furniture drawn over it, so there is nothing to shrink into.
     */
    val docksPlayer: Boolean
        get() = this == GUIDE

    /**
     * Whether this overlay draws its own backdrop over the live picture, and so wants no scrim
     * from [OverlayScaffold].
     *
     * The guide paints panes around a preview it is deliberately keeping watchable; the home
     * screen paints a gradient and a set of opaque rails over a picture that fills the panel.
     * A scrim under either would tint the one thing the layout exists to keep visible.
     */
    val paintsOwnBackdrop: Boolean
        get() = this == GUIDE || this == HOME

    /**
     * Whether the live picture is what the screen *is* while this overlay is up.
     *
     * Not the same question as [isPassthrough], which is about the remote, even though the two
     * answers coincide: the picture is the screen only on the bare video and under the bars that
     * ride over it. The guide shrinks it into a preview beside a grid that does not move for
     * hours, and the home screen draws its top bar, caption and rails over it — still screens
     * with a moving stamp on them, which wear into a panel like any other (ADR-039, ADR-040).
     * Read by [com.tivicopy.player.ui.common.idleScene].
     */
    val videoFillsPanel: Boolean
        get() = isPassthrough

    /**
     * Whether this overlay reaches the edges of the panel itself, rather than sitting inside
     * [TV_CONTENT_INSET]. A full-bleed banner cannot start 24dp from the edge, so the home screen
     * takes the inset over as its own content padding (ADR-020).
     */
    val fullBleed: Boolean
        get() = this == HOME

    /**
     * Whether this overlay draws Studio's top bar inside its own layout, so [OverlayScaffold]
     * must not draw a second one. Home pins it over its banner; the guide heads the column beside
     * its channel rail with it (`canvas/Guide.dc.html`). Every other browse screen takes the
     * scaffold's, at the same place and size as home's (ADR-035).
     */
    val drawsOwnTopBar: Boolean
        get() = this == HOME || this == GUIDE
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
    /** Moves to another overlay. Whatever opens this way was not opened from the video (ADR-036). */
    onOverlayChange: (TvOverlay) -> Unit,
    /**
     * Opens a browse surface from the fullscreen video — here, the transport bar's guide button —
     * so that Back from it returns to the video rather than rising to home (ADR-036).
     */
    onOpenFromVideo: (TvOverlay) -> Unit,
    /** The overlay on screen, if it was opened from the fullscreen video; null otherwise. */
    openedFromVideo: TvOverlay?,
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
    // The channel the channel list's preview opened the guide for, which the guide lands on.
    // Forgotten as soon as the guide is left, so the next guide opens as it always has.
    var guideFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(overlay) { if (overlay != TvOverlay.GUIDE) guideFor = null }
    // Where home last had the remote, for the side menu to hand it back to; see HomeFocusReturn.
    val homeFocus = remember { HomeFocusReturn() }

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

    // Sport and News modes own playback while they are up (ADR-041, ADR-042): the full-screen
    // player stands down as one opens, and the panels let go as it closes, whichever way it was
    // left. Closed on every other overlay rather than tracked with a flag like the radio's above:
    // a recreation composes this afresh with the panels still running in the view model, and they
    // must not play on unseen.
    LaunchedEffect(overlay) {
        when (overlay) {
            TvOverlay.SPORT -> vm.enterSport()
            TvOverlay.NEWS -> vm.enterNews()
            else -> vm.closeSport()
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
        // Sport mode is the one exception (ADR-041): its panels are video surfaces of their own,
        // and two surfaces covering the same pixels have no defined order — this one's last frame
        // could sit on top of the panels. The full-screen player is silent while it is up, and is
        // attached again as it is left.
        if (!overlay.isPanels) {
            PlayerSurface(
                engine = ServiceLocator.player,
                aspectMode = prefs.aspectMode,
                subtitleScale = prefs.subtitleTextScale,
                docked = dock != null,
                modifier = pictureBounds,
            )
        }

        // A settled failure puts its own buttons on screen (ADR-034), and the retry among them
        // takes the remote; the station backdrop's play button must not claim it at the same time.
        val failed = status.error != null && status.errorDetail != null

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
        // Not under Sport mode (ADR-041), whose opaque grid would hide it anyway: its play button
        // is focusable, and the remote's search for the next panel goes by position, not by what
        // is drawn on top — an arrow could land on a button nobody can see.
        val sport = overlay.isPanels
        if (audioOnly && !sport) {
            AudioOnlyNowPlaying(
                channel = state.nowPlaying.audioOnlyChannel(),
                title = status.title,
                playWhenReady = status.playWhenReady,
                song = status.streamTitle,
                onTogglePlay = { ServiceLocator.player.togglePlayPause() },
                // Only while nothing else owns the arrows: an overlay draws its own focusable
                // controls over this one, and two claims on the D-pad is one too many.
                takeDpadFocus = overlay.leavesDpadToShell && !failed,
                // Catch-up off a radio station reaches this screen too, and is not live.
                live = state.nowPlaying.isLive(),
                onFavourite = state.nowPlaying.audioOnlyChannel()?.let { station ->
                    { vm.setFavourite(station, !station.favourite) }
                },
                // The bundled radio's stations along the bottom, while the remote is the shell's.
                stationStrip = state.nowPlaying.audioOnlyChannel()
                    ?.takeIf { overlay.leavesDpadToShell && isBundledRadio(state, it) }
                    ?.let { station ->
                        val strip: @Composable (Modifier) -> Unit = { stripModifier ->
                            TvStationStrip(vm = vm, current = station, modifier = stripModifier)
                        }
                        strip
                    },
                modifier = pictureBounds,
            )
        }

        // Zapping on a remote gives no other feedback: without this a channel that is still
        // tuning is a black screen, identical to one that failed. A settled failure is Studio's
        // error panel in the middle of the screen (`TvPlayError`), drawn by the same overlay.
        // The full-screen player's loader and error panel are its own; Sport mode's panels draw
        // their own, and this one's error buttons would be another place for an arrow to land.
        if (!sport) {
            PlaybackStateOverlay(
                status = status,
                opacity = prefs.overlayOpacity,
                // Only while nothing else owns the remote: under the channel list or the transport
                // bar, buttons here would be reachable by an arrow that ran off the end of those.
                errorActions = if (overlay.leavesDpadToShell) {
                    PlaybackErrorActions(
                        onRetry = vm::retryPlayback,
                        onNetworkSettings = {
                            onSettingsSection(SettingsSection.NETWORK)
                            onOverlayChange(TvOverlay.SETTINGS)
                        },
                        onChannelList = { onOverlayChange(TvOverlay.CHANNELS) },
                        takeFocus = true,
                    )
                } else {
                    null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // The info card auto-dismisses; everything else waits for Back.
        if (overlay == TvOverlay.INFO) {
            ChannelInfoCard(
                channel = state.currentChannel,
                now = state.now,
                next = state.next,
                // Live *and* running: pausing does not stop this card being on screen, and a
                // paused stream is exactly what the badge must not go on calling live.
                live = state.nowPlaying.isLive() && status.playWhenReady,
                // An audio-only stream is a station, and a station's mark is round wherever it
                // is drawn (ADR-022) — including here, over its own backdrop.
                circularLogo = audioOnly,
                // …but that backdrop is *already* drawing the station's mark, its name and a
                // badge of its own, so the card drops all three rather than printing a second
                // set of them a few centimetres below. The same rule the home banner follows.
                showIdentity = !audioOnly,
                opacity = prefs.overlayOpacity,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // Full width and flush to the foot of the screen: the card is a lower third
                    // now, and its own gradient is what holds the text, so an inset around it
                    // would put a band of untouched video under the words.
                    .fillMaxWidth()
                    // Everything that arrives over playing video fades and rises into place
                    // rather than appearing between two frames (`ui/common/Motion.kt`).
                    .screenEntrance(Unit),
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

        // The filmstrip is drawn over playing video with no scrim of its own, which is what makes
        // it a way to change channel rather than a screen to visit.
        if (overlay == TvOverlay.MINI_GUIDE) {
            ChannelFilmstrip(
                vm = vm,
                onPlay = { channel -> vm.play(channel) },
                opacity = prefs.overlayOpacity,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .screenEntrance(Unit),
            )
        }

        if (overlay == TvOverlay.TRANSPORT) {
            TransportBar(
                vm = vm,
                onOpenGuide = { onOpenFromVideo(TvOverlay.GUIDE) },
                onOpenMenu = { onOverlayChange(TvOverlay.PLAYBACK_MENU) },
                // The sheet is a dialog over the bare picture: the bar goes, and closing the sheet
                // leaves the viewer watching rather than back on a bar they did not ask for.
                onOpenDetails = { channel, programme ->
                    details = channel to programme
                    onOverlayChange(TvOverlay.NONE)
                },
                modifier = Modifier.align(Alignment.BottomCenter).screenEntrance(Unit),
            )
        }

        if (overlay == TvOverlay.PLAYBACK_MENU) {
            PlaybackMenu(
                vm = vm,
                onDismiss = { onOverlayChange(TvOverlay.NONE) },
                onOpenDetails = { channel, programme ->
                    details = channel to programme
                    onOverlayChange(TvOverlay.NONE)
                },
                // `PlayerMenu`: a drawer against the end edge of the screen, full height.
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .screenEntrance(Unit),
            )
        }

        if (overlay.isPanels) {
            // Opaque and full-bleed, over everything above: the panels are the whole screen.
            SportScreen(vm = vm, modifier = Modifier.fillMaxSize())
        } else if (!overlay.isPassthrough) {
            OverlayScaffold(
                opacity = prefs.overlayOpacity,
                // The guide paints its own panes and leaves the preview slot bare; the home
                // screen paints a gradient over a full-screen picture. A scrim under either would
                // be drawn straight over the live picture the whole layout exists to keep visible.
                scrim = !overlay.paintsOwnBackdrop,
                inset = !overlay.fullBleed,
                // Replayed whenever the overlay changes, which is what makes one browse surface
                // replacing another read as a transition. Deliberately not a cross-fade: that
                // would keep both screens composed, and two channel lists querying, two guides
                // fetching and two screens asking for focus is a worse bargain than a fade-in.
                entranceKey = overlay,
                topBar = !overlay.drawsOwnTopBar,
                // One menu for every browse screen, the one home has always had (ADR-035). It is
                // drawn by the scaffold rather than by each screen so it is the same instance from
                // one screen to the next: it does not fade out and back in with the screen under
                // it, and only which entry is lit changes. Every choice in it, the lit entry's
                // included, reaches `onOverlayChange`, so a screen chosen from the menu rises to
                // home on Back even if it had been opened from the video (ADR-036).
                // On home, a press towards the content is handed back explicitly: the D-pad cannot
                // find home's full-bleed rails from the menu by itself. Passed only while home is
                // the screen, which is exactly while its controls are noting themselves.
                sideMenu = {
                    SideMenu(
                        current = overlay,
                        onOpen = onOverlayChange,
                        onTowardContent = if (overlay == TvOverlay.HOME) homeFocus::restore else null,
                    )
                },
            ) {
                when (overlay) {
                    // The television's root surface (ADR-016). Added here in the same edit as the
                    // enum value on purpose: the `else -> Unit` at the bottom of this `when` means
                    // a forgotten branch compiles and renders an empty scaffold over the video.
                    TvOverlay.HOME -> TvHomeScreen(
                        vm = vm,
                        // The radio backdrop fills the panel behind the home screen exactly as
                        // video does, and it draws its own clock and station identity in the
                        // same corners the banner would. The banner drops its own rather than
                        // printing a second clock a centimetre from the first.
                        audioOnlyBackdrop = audioOnly,
                        onOpen = onOverlayChange,
                        onFullscreen = { onOverlayChange(TvOverlay.NONE) },
                        focusReturn = homeFocus,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Where else to go is the side menu's, as on every other screen (ADR-035):
                    // the group column holds the list's own filters and groups and nothing else.
                    TvOverlay.CHANNELS -> ChannelListScreen(
                        vm = vm,
                        onPlay = { channel -> vm.play(channel); onOverlayChange(TvOverlay.NONE) },
                        onAddSource = { onOverlayChange(TvOverlay.ADD_SOURCE) },
                        // The preview's "schedule": the guide, landed on that channel's programme
                        // on air. Not the radio's, whose stations carry no guide.
                        onOpenGuide = { channel ->
                            guideFor = channel.id
                            onOverlayChange(TvOverlay.GUIDE)
                        },
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
                        scrollToCurrent = true,
                        focusCurrent = true,
                        showNowLine = true,
                        isRadio = true,
                        modifier = Modifier.fillMaxSize(),
                    )

                    TvOverlay.GUIDE -> PictureInGuide(
                        vm = vm,
                        onDockBounds = { dockBounds = it },
                        // The guide stays up, with the new channel in its picture. Re-asserting
                        // it is what counts the OK as a move made here: Back then rises to home
                        // like any browse surface, even if the guide came from the video (ADR-036).
                        onPlay = { channel ->
                            vm.play(channel)
                            onOverlayChange(TvOverlay.GUIDE)
                        },
                        onOpenProgramme = { channel, programme -> details = channel to programme },
                        opacity = prefs.overlayOpacity,
                        modifier = Modifier.fillMaxSize(),
                        // From the video, the guide opens where the viewer already is: on the
                        // programme on air on the channel playing (`canvas/GuideFromVideo.dc.html`).
                        // From the channel list's preview, on the channel it was showing.
                        landOn = if (openedFromVideo == TvOverlay.GUIDE) state.currentChannel?.id else guideFor,
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
                        twoPane = true,
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
                        twoPane = true,
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
 *
 * Every screen in it shares one frame (ADR-035): the side menu's strip along the start edge, with
 * the content kept clear of it by the same room home keeps ([sideMenuInset]), and Studio's top bar
 * across the top, at the size and place home draws it. Before this, home was the only screen with
 * the side menu; the channel list carried its own list of destinations in a different order with
 * on demand in it, recordings and settings had the top bar but no menu, and search, on demand and
 * adding a source had neither — so every screen the viewer chose was navigated differently.
 */
@Composable
private fun OverlayScaffold(
    opacity: Float,
    /**
     * Whether to dim the video behind. The guide says no: it draws its own panes around a live
     * preview, and a scrim here would tint the one thing it is trying to keep watchable.
     */
    scrim: Boolean = true,
    /**
     * Whether to hold the content inside [TV_CONTENT_INSET]. The home screen says no: its banner
     * is full-bleed, and it applies the same inset to its own text instead (ADR-020).
     */
    inset: Boolean = true,
    /** What the arrival animation is keyed on; see [com.tivicopy.player.ui.common.screenEntrance]. */
    entranceKey: Any? = Unit,
    /** Whether to head the screen with Studio's top bar; off where the screen draws its own. */
    topBar: Boolean = false,
    /** The side menu, drawn over everything at the start edge; null for none. */
    sideMenu: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The panel's own measure, for what belongs to the panel rather than to the screen: the
        // side menu, the room kept for its strip and the top bar. Taken before any inset, as home
        // takes its own, so none of the three changes size from one screen to the next.
        val panelMetrics = tvMetricsFor(maxWidth.value, maxHeight.value)
        val startRoom = if (sideMenu != null) sideMenuInset(panelMetrics) else TV_CONTENT_INSET
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Outermost, so the scrim and the surface fade in together rather than the scrim
                // snapping on under a fading screen.
                .screenEntrance(entranceKey)
                .then(
                    if (scrim) {
                        Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = opacity))
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (topBar) {
                CompositionLocalProvider(LocalTvMetrics provides panelMetrics) {
                    // No scrim of its own: the screen's backdrop is already under it.
                    StudioTopBar(startInset = startRoom, showClock = true, scrim = false)
                }
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(
                        if (inset) {
                            Modifier.padding(
                                start = startRoom,
                                end = TV_CONTENT_INSET,
                                // The top bar is the room at the top when there is one.
                                top = if (topBar) 0.dp else TV_CONTENT_INSET,
                                bottom = TV_CONTENT_INSET,
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                // Measured here rather than read from the Configuration: this box is what the
                // screens actually get, insets and all, and it is the only honest input to how
                // wide an hour of guide should be.
                val metrics = tvMetricsFor(maxWidth.value, maxHeight.value)
                CompositionLocalProvider(LocalTvMetrics provides metrics) { content() }
            }
        }
        if (sideMenu != null) {
            CompositionLocalProvider(LocalTvMetrics provides panelMetrics) { sideMenu() }
        }
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

/**
 * The zapping info card (FR-PLB-18) — the whole of what the shell says while you are simply
 * watching.
 *
 * It used to be a small opaque box in the corner: a plate of `surface` at 0.9, a logo, a name and
 * three lines of grey text, laid out as a list rather than as something read from a sofa. That is
 * the surface a viewer sees more than any other in this app, and it was the one surface the
 * redesign had not touched — so it looked like a different app to the home screen it was one
 * button away from.
 *
 * This is the lower third the design calls for instead. Full width, no box: a gradient that
 * fades the picture into legibility from about half way down, the way the home banner already
 * does, so nothing is hidden that does not have to be. Above the fold of text sits the channel's
 * identity and, when the stream is live, the shared [LiveBadge] — the same badge the station
 * backdrop draws, because they are answering the same question about the same stream.
 *
 * The programme title carries the weight here rather than the channel name. Zapping asks "what is
 * on this one", and the answer was previously set two steps down the type ramp from the channel's
 * own name.
 */
@Composable
fun ChannelInfoCard(
    channel: Channel?,
    now: Programme?,
    next: Programme?,
    /** Whether the stream is arriving live *and* running; catch-up and a pause are neither. */
    live: Boolean,
    /** Whether the channel's mark is a station's disc rather than a channel's plate. */
    circularLogo: Boolean,
    /**
     * Whether to draw the channel's own mark, name and live badge.
     *
     * False while the audio-only backdrop is behind this card: it draws all three already, and
     * two of everything a few centimetres apart is worse than one.
     */
    showIdentity: Boolean,
    /** The viewer's overlay-opacity setting: how far the gradient darkens the picture. */
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    if (channel == null) return
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val backdrop = studio.ground
    // The setting, with a floor under it. This card is the most-seen surface in the app — it is
    // what every channel change puts on screen — and "turn overlays down" is not a request to make
    // the zapping readout unreadable.
    val floor = opacity.coerceAtLeast(INFO_MIN_OPACITY)
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The transparent lead-in is short and the ramp is steep: everything from the number
            // tile down is inside the dense part of it (`.st-scrim-bottom`).
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    INFO_GRADIENT_KNEE to backdrop.copy(alpha = floor * INFO_GRADIENT_KNEE_ALPHA),
                    1f to backdrop.copy(alpha = floor),
                ),
            )
            .padding(start = INFO_SAFE_X, end = INFO_SAFE_X, top = 72.dp, bottom = TV_CONTENT_INSET),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // The channel's number as a broadcast sets it: a tile with the tally across its top.
        if (showIdentity) {
            val tileShape = RoundedCornerShape(LocalTvMetrics.current.controlCorner)
            Box(
                modifier = Modifier
                    .size(INFO_TILE)
                    .clip(tileShape)
                    .background(studio.scrim)
                    .border(1.dp, studio.hairline, tileShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(studio.tally),
                )
                if (channel.number > 0) {
                    Text(
                        text = channel.number.toString(),
                        style = type.hero.copy(fontSize = type.hero.fontSize * INFO_NUMBER_SCALE),
                        color = studio.ink,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 9.dp),
                    )
                } else {
                    ChannelLogo(channel = channel, sizeDp = 56, circular = circularLogo)
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (showIdentity) Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    text = channel.displayName,
                    style = type.action.copy(
                        fontSize = type.action.fontSize * INFO_CHANNEL_SCALE,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = studio.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (live) LiveBadge()
            }

            // The programme title carries the weight, set the way a channel sets it on air.
            Text(
                text = now?.title ?: stringResource(R.string.no_guide_data),
                style = type.title.copy(fontSize = type.title.fontSize * INFO_TITLE_SCALE),
                color = studio.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            now?.let {
                val meta = type.body.copy(fontSize = type.body.fontSize * INFO_META_SCALE)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = TimeModel.formatClockRange(it.startUtc, it.endUtc),
                        style = meta,
                        color = studio.ink2,
                    )
                    ProgrammeProgress(
                        it.startUtc,
                        it.endUtc,
                        modifier = Modifier.width(INFO_PROGRESS_WIDTH),
                    )
                    val left = ((it.endUtc - System.currentTimeMillis()) / 60_000L).toInt()
                    if (left > 0) {
                        Text(
                            text = stringResource(R.string.home_minutes_left, left),
                            style = meta,
                            color = studio.ink2,
                        )
                    }
                }
            }
        }

        // What comes next, on a panel of its own, in Studio's "next" blue.
        next?.let {
            val panelShape = RoundedCornerShape(LocalTvMetrics.current.panelCorner)
            Column(
                modifier = Modifier
                    .width(INFO_NEXT_WIDTH)
                    .clip(panelShape)
                    .background(studio.panel)
                    .border(1.dp, studio.hairline, panelShape)
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.info_next),
                    style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = studio.next,
                )
                Text(
                    text = it.title,
                    style = type.h1.copy(fontWeight = FontWeight.SemiBold),
                    color = studio.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = TimeModel.formatClockRange(it.startUtc, it.endUtc),
                    style = type.meta,
                    color = studio.ink2,
                )
            }
        }
    }
}

/**
 * Where the info card's gradient stops fading and starts holding, and how dark it already is
 * there relative to its foot.
 *
 * The card's top padding puts the first line of text below this knee, which is the whole point:
 * above it the picture is barely touched, below it there is enough scrim to read grey text on.
 */
private const val INFO_GRADIENT_KNEE = 0.28f
private const val INFO_GRADIENT_KNEE_ALPHA = 0.82f

/** The least dense the info card's gradient is drawn, whatever the opacity setting says. */
private const val INFO_MIN_OPACITY = 0.8f

/*
 * The lower third's measurements, from `canvas/LowerThird.dc.html` (1080p pixels halved).
 */
private val INFO_SAFE_X = 48.dp
private val INFO_TILE = 88.dp
private val INFO_PROGRESS_WIDTH = 280.dp
private val INFO_NEXT_WIDTH = 220.dp

/** The number is 150px against the hero's 140; the title 124px against the title step's 108. */
private const val INFO_NUMBER_SCALE = 150f / 140f
private const val INFO_TITLE_SCALE = 124f / 108f

/** The channel name is 32px against the action step's 30; the time line 26px against the body's 24. */
private const val INFO_CHANNEL_SCALE = 32f / 30f
private const val INFO_META_SCALE = 26f / 24f

/** Whether [channel] is one of the bundled Israeli radio stations (ADR-015). */
private fun isBundledRadio(state: com.tivicopy.player.ui.AppUiState, channel: com.tivicopy.player.data.model.Channel): Boolean {
    val radioId = state.playlists.firstOrNull { it.url == com.tivicopy.player.data.builtin.IsraelRadioStations.M3U_URL }?.id
    return radioId != null && channel.playlistId == radioId
}
