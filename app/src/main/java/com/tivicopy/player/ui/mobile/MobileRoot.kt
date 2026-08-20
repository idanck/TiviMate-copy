package com.tivicopy.player.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.model.VodItem
import com.tivicopy.player.record.SERIES_LOOKAHEAD_MILLIS
import com.tivicopy.player.data.prefs.AspectMode
import com.tivicopy.player.data.prefs.StartBehaviour
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.UiMessage
import com.tivicopy.player.ui.common.PlaybackStateOverlay
import com.tivicopy.player.ui.common.PlayerSurface
import com.tivicopy.player.ui.screens.AddSourceScreen
import com.tivicopy.player.ui.screens.ChannelListScreen
import com.tivicopy.player.ui.screens.GuideScreen
import com.tivicopy.player.ui.screens.ProgrammeDetailsDialog
import com.tivicopy.player.ui.screens.RecordingsScreen
import com.tivicopy.player.ui.screens.SearchScreen
import com.tivicopy.player.ui.screens.VodScreen
import com.tivicopy.player.ui.settings.SettingsScreen
import com.tivicopy.player.ui.settings.SettingsSection

enum class MobileTab { LIVE, GUIDE, VOD, RECORDINGS, SETTINGS }

/**
 * The touch shell.
 *
 * This is the deliberate divergence from the benchmark (docs/00 section 0.3, docs/08 row 1):
 * the phone is a first-class target, not a scaled-down TV layout. Portrait pins the video to
 * the top with the list below; landscape gives the video the whole screen with sheets over it;
 * and the playback gestures FR-UI-14 asks for are wired to the surface itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileRoot(
    vm: AppViewModel,
    settingsSection: SettingsSection,
    onSettingsSection: (SettingsSection) -> Unit,
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val prefs by vm.settingsState.collectAsState()
    var tab by remember { mutableStateOf(MobileTab.LIVE) }
    var details by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }
    // Loaded when the details dialog opens so a series recording can claim the episodes the
    // guide already lists, without querying on every recomposition.
    var upcoming by remember { mutableStateOf<List<Programme>>(emptyList()) }

    LaunchedEffect(details?.first?.id) {
        val channel = details?.first
        upcoming = if (channel == null) emptyList() else {
            val now = System.currentTimeMillis()
            vm.programmesFor(channel, now, now + SERIES_LOOKAHEAD_MILLIS)
        }
    }
    var addingSource by remember { mutableStateOf(false) }
    // The bottom bar is already at Material's five-item limit, so search is a destination the top
    // bar opens over the current tab rather than a sixth tab (FR-UI-09).
    var searching by remember { mutableStateOf(false) }
    // A series picked out of search, handed to the on-demand tab so it opens on that series
    // rather than on the grid the user would then have to search again.
    var pendingSeries by remember { mutableStateOf<VodItem?>(null) }
    // Landscape hides the bottom bar, so a tap on the video brings the channel list back.
    var landscapeOverlay by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val twoPane = configuration.screenWidthDp >= 720

    val playback by vm.playbackStatus.collectAsState()

    // FR-SET-04: on the TV shell the guide and channel-list start behaviours open an overlay,
    // and `resumeStartChannel` deliberately plays nothing for them. The phone has no overlays,
    // so the equivalent is the tab that opens first; without this both settings do nothing at
    // all here and the user is left on Live with a black surface. The channel list already is
    // the Live tab, so only the guide needs moving.
    LaunchedEffect(Unit) {
        if (prefs.startBehaviour == StartBehaviour.GUIDE) tab = MobileTab.GUIDE
    }

    // The add screen doubles as the first-run setup, and then it *is* the root: with no
    // playlist yet there is nothing behind it to go back to.
    val showAddSource = addingSource || (state.playlists.isEmpty() && !state.loading)
    val inSettingsSubSection =
        !landscape && tab == MobileTab.SETTINGS && settingsSection != SettingsSection.ROOT
    val canGoBack = (addingSource && state.playlists.isNotEmpty()) ||
        (!showAddSource && (searching || inSettingsSubSection))

    val goBack: () -> Unit = {
        when {
            addingSource -> addingSource = false
            searching -> searching = false
            else -> onSettingsSection(SettingsSection.ROOT)
        }
    }

    // Picking a tab also abandons the add screen; otherwise the flag stays latched and the tab
    // bar looks broken, moving its selection while the content underneath never changes.
    val selectTab: (MobileTab) -> Unit = {
        addingSource = false
        searching = false
        // Dropped on any deliberate move, so coming back to on demand later opens the grid
        // rather than reopening a series chosen some time ago.
        pendingSeries = null
        tab = it
    }

    // Registered from composition, so it takes precedence over the activity's exit handler for
    // exactly as long as there is somewhere to go back to.
    BackHandler(enabled = canGoBack, onBack = goBack)
    // The landscape channel list is a sheet over the video with nothing to dismiss it but a
    // tap on the video behind it. Without this, Back falls through to the activity and the
    // second press quits the app with the list still on screen.
    BackHandler(enabled = landscape && landscapeOverlay && !showAddSource) { landscapeOverlay = false }

    Scaffold(
        modifier = modifier,
        topBar = {
            // Touch has no D-pad Back, and a phone's gesture bar is easy to miss, so the way
            // out of a sub-screen is on the screen itself. The bar also carries search, which
            // otherwise has no entry point at all on the phone.
            val searchable = !landscape && !showAddSource && !searching &&
                tab in setOf(MobileTab.LIVE, MobileTab.GUIDE, MobileTab.VOD)
            if (canGoBack || searchable) {
                TopAppBar(
                    // Every destination behind this bar draws its own heading — the add screen
                    // and each settings section alike — so the bar is the back arrow on its
                    // own rather than the same words twice, one above the other.
                    title = {},
                    navigationIcon = {
                        if (canGoBack) {
                            IconButton(onClick = goBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                )
                            }
                        }
                    },
                    actions = {
                        if (searchable) {
                            IconButton(onClick = { searching = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.nav_search),
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!landscape) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == MobileTab.LIVE,
                        onClick = { selectTab(MobileTab.LIVE) },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_live)) },
                    )
                    NavigationBarItem(
                        selected = tab == MobileTab.GUIDE,
                        onClick = { selectTab(MobileTab.GUIDE) },
                        icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_guide)) },
                    )
                    NavigationBarItem(
                        selected = tab == MobileTab.VOD,
                        onClick = { selectTab(MobileTab.VOD) },
                        icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_vod)) },
                    )
                    NavigationBarItem(
                        selected = tab == MobileTab.RECORDINGS,
                        onClick = { selectTab(MobileTab.RECORDINGS) },
                        icon = { Icon(Icons.Filled.List, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_recordings)) },
                    )
                    NavigationBarItem(
                        selected = tab == MobileTab.SETTINGS,
                        onClick = { selectTab(MobileTab.SETTINGS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_settings)) },
                    )
                }
            }
        },
    ) { padding ->
        if (showAddSource) {
            AddSourceScreen(
                vm = vm,
                onDone = { addingSource = false },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                val videoModifier = if (landscape) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                }

                Box(modifier = videoModifier.background(Color.Black).gestureControls(
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
                    onTap = { if (landscape) landscapeOverlay = !landscapeOverlay },
                )) {
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

                if (!landscape) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (searching) {
                            SearchScreen(
                                vm = vm,
                                onPlay = { channel -> vm.play(channel); searching = false },
                                onOpenProgramme = { channel, programme -> details = channel to programme },
                                onPlayVod = { item -> vm.playVod(item); searching = false },
                                onOpenSeries = { item ->
                                    pendingSeries = item
                                    searching = false
                                    tab = MobileTab.VOD
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            return@Box
                        }
                        when (tab) {
                            MobileTab.LIVE -> ChannelListScreen(
                                vm = vm,
                                onPlay = vm::play,
                                compact = !twoPane,
                                showNowNext = twoPane,
                                // A tablet is still touch, so it gets the filter box even though
                                // it is wide enough to drop the compact layout.
                                showFilter = true,
                                onSearch = { searching = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                            MobileTab.GUIDE -> GuideScreen(
                                vm = vm,
                                onPlay = vm::play,
                                onOpenProgramme = { channel, programme -> details = channel to programme },
                                modifier = Modifier.fillMaxSize(),
                            )
                            MobileTab.VOD -> VodScreen(
                                vm = vm,
                                onPlay = vm::playVod,
                                modifier = Modifier.fillMaxSize(),
                                openSeriesRequest = pendingSeries,
                            )
                            MobileTab.RECORDINGS -> RecordingsScreen(
                                onPlayRecording = vm::playRecording,
                                modifier = Modifier.fillMaxSize(),
                            )
                            MobileTab.SETTINGS -> Column(modifier = Modifier.fillMaxSize()) {
                                SettingsScreen(
                                    vm = vm,
                                    section = settingsSection,
                                    onNavigate = onSettingsSection,
                                    onAddSource = { addingSource = true },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            if (landscape && landscapeOverlay) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f)),
                ) {
                    ChannelListScreen(
                        vm = vm,
                        onPlay = { channel -> vm.play(channel); landscapeOverlay = false },
                        compact = !twoPane,
                        showNowNext = twoPane,
                        showFilter = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        details?.let { (channel, programme) ->
            ProgrammeDetailsDialog(
                channel = channel,
                programme = programme,
                onDismiss = { details = null },
                onWatch = { vm.play(channel) },
                onCatchup = { vm.playCatchup(channel, programme) },
                onFavourite = { favourite -> vm.setFavourite(channel, favourite) },
                onMessage = { text -> vm.post(UiMessage(text = text)) },
                upcoming = upcoming,
            )
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
