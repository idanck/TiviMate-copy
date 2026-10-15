package com.tivicopy.player.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.builtin.IsraelRadioStations
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.model.VodItem
import com.tivicopy.player.record.SERIES_LOOKAHEAD_MILLIS
import com.tivicopy.player.data.prefs.StartBehaviour
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.NowPlayingInfo
import com.tivicopy.player.ui.UiMessage
import com.tivicopy.player.ui.screens.AddSourceScreen
import com.tivicopy.player.ui.screens.ChannelListScreen
import com.tivicopy.player.ui.screens.ProgrammeDetailsDialog
import com.tivicopy.player.ui.screens.RecordingsScreen
import com.tivicopy.player.ui.common.UpdatePrompt
import com.tivicopy.player.ui.screens.SearchScreen
import com.tivicopy.player.ui.screens.VodScreen
import com.tivicopy.player.ui.screens.VodScreenState
import com.tivicopy.player.ui.settings.SettingsScreen
import com.tivicopy.player.ui.settings.SettingsSection
import kotlinx.coroutines.delay
import com.tivicopy.player.ui.common.StudioIcons

/**
 * The phone's destinations. Five have a tab (ADR-032); [VOD] has none and is reached only from
 * search, on an installation that has on-demand at all (ADR-016).
 */
enum class MobileTab { LIVE, RADIO, GUIDE, VOD, RECORDINGS, SETTINGS }

/**
 * The touch shell.
 *
 * This is the deliberate divergence from the benchmark (docs/00 section 0.3, docs/08 row 1):
 * the phone is a first-class target, not a scaled-down TV layout. Browsing is full screen —
 * the channel list, guide and on-demand grid own the whole display — and playing something
 * opens the [PlayerScreen] over everything, the way a music app expands its now-playing bar.
 * While the player is collapsed, [PhoneMiniPlayer] above the navigation bar keeps what is
 * playing one tap away (ADR-011). The chrome is Studio's (`PhoneChrome.kt`, ADR-027).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileRoot(
    vm: AppViewModel,
    settingsSection: SettingsSection,
    onSettingsSection: (SettingsSection) -> Unit,
    playerOpen: Boolean,
    onPlayerOpenChange: (Boolean) -> Unit,
    inPip: Boolean,
    numericEntry: String,
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val prefs by vm.settingsState.collectAsState()
    val playback by vm.playbackStatus.collectAsState()
    // Saveable: a language change recreates the activity, and plain `remember` dumped the
    // user who did it from Settings back onto the Live tab.
    var tab by rememberSaveable { mutableStateOf(MobileTab.LIVE) }
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
    // The bottom bar is already at its five tabs, so search is a destination the top bar opens
    // over the current tab rather than a sixth tab (FR-UI-09).
    var searching by remember { mutableStateOf(false) }
    // A series picked out of search, handed to the on-demand tab so it opens on that series
    // rather than on the grid the user would then have to search again.
    var pendingSeries by remember { mutableStateOf<VodItem?>(null) }
    // Held here, above the tab switch and the search overlay, so leaving the on-demand tab and
    // coming back restores the search box, the filter, the open series and the scroll position.
    val vodState = remember { VodScreenState() }

    val configuration = LocalConfiguration.current
    // The smallest width, not the current one: a tall phone lying down is ~800dp wide and is
    // still a phone, not a tablet wanting three panes.
    val twoPane = configuration.smallestScreenWidthDp >= 600

    // FR-SET-04: on the TV shell the guide and channel-list start behaviours open an overlay.
    // Here the channel list already is the Live tab, so only the guide needs moving; a resumed
    // start channel opens the player screen instead, and MainActivity owns that flag. One-shot
    // across recreation, or a language change would re-force the guide tab.
    var startTabApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // On-demand no longer has a tab (ADR-032); only search reaches it, so a value saved by an
        // earlier build's `rememberSaveable` is sent to Live rather than restored without a tab.
        if (tab == MobileTab.VOD) tab = MobileTab.LIVE
        if (!startTabApplied) {
            startTabApplied = true
            // Only the guide is a tab here. HOME (the television's new root, ADR-016) and the
            // channel list both mean "start where you browse", which on the phone is Live —
            // the tab this already is. The bottom bar is at Material's five-item limit, so a
            // sixth Home destination would have to displace one of the five that earn it.
            if (prefs.startBehaviour == StartBehaviour.GUIDE) tab = MobileTab.GUIDE
        }
    }

    // Re-query now/next when the running programme ends, so neither the mini bar nor the player
    // screen captions the channel with a programme that is over. It lives here, not in
    // PlayerScreen, because collapsing the player must not stop it — the collapsed bar is
    // exactly where a stale title would sit unnoticed. Keyed on the end time: a zap replaces
    // `now` and re-arms it.
    LaunchedEffect(state.now?.endUtc) {
        val end = state.now?.endUtc ?: return@LaunchedEffect
        val wait = end - System.currentTimeMillis() + PROGRAMME_TURNOVER_GRACE_MS
        if (wait > 0) delay(wait)
        vm.refreshNowNext()
    }

    // While the player screen is closed and the app is not in PiP, nothing anywhere shows the
    // video track — only the mini bar's audio matters — so decoding it is pure battery and
    // bandwidth. The engine re-applies this over instance rebuilds.
    LaunchedEffect(playerOpen, inPip) {
        ServiceLocator.player.setVideoOutputWanted(playerOpen || inPip)
    }

    // In the PiP window the app is its video and nothing else: the browse chrome would be
    // unreadable at that size, and Android snapshots whatever happens to be composed here.
    if (inPip) {
        PlayerScreen(
            vm = vm,
            onClose = {},
            onOpenGuide = {},
            onBrightness = onBrightness,
            onVolume = onVolume,
            twoPane = twoPane,
            inPip = true,
            modifier = modifier,
        )
        return
    }

    // The add screen doubles as the first-run setup, and then it *is* the root: with no
    // playlist yet there is nothing behind it to go back to.
    val showAddSource = addingSource || (state.playlists.isEmpty() && !state.loading)
    val inSettingsSubSection = tab == MobileTab.SETTINGS && settingsSection != SettingsSection.ROOT
    // Live is the phone's home tab, the same way the channel list is TV's — Back from any other
    // tab returns to it first, rather than falling straight through to the activity's exit
    // confirmation the moment there is nothing else on this composable's own stack to unwind.
    val canGoBack = (addingSource && state.playlists.isNotEmpty()) ||
        (!showAddSource && (searching || inSettingsSubSection || tab != MobileTab.LIVE))

    val goBack: () -> Unit = {
        when {
            addingSource -> addingSource = false
            searching -> searching = false
            inSettingsSubSection -> onSettingsSection(SettingsSection.ROOT)
            tab != MobileTab.LIVE -> tab = MobileTab.LIVE
        }
    }

    // Picking a tab also abandons the add screen; otherwise the flag stays latched and the tab
    // bar looks broken, moving its selection while the content underneath never changes.
    val selectTab: (MobileTab) -> Unit = {
        addingSource = false
        searching = false
        // The screen drops the request itself once it has taken it; this only covers the request
        // that is never taken, because another tab was chosen before it composed.
        pendingSeries = null
        tab = it
    }

    // Radio borrows the same channel-list machinery as Live, pointed at the bundled radio
    // playlist instead of the active one (see AppViewModel.openRadio). Watching `tab` itself,
    // rather than hooking this into selectTab, catches every way it can change — including the
    // search screen jumping straight to the VOD tab on an opened series — so Live can never be
    // left showing radio stations.
    var wasRadio by remember { mutableStateOf(false) }
    LaunchedEffect(tab) {
        if (tab == MobileTab.RADIO) {
            vm.openRadio()
            wasRadio = true
        } else if (wasRadio) {
            vm.closeRadio()
            wasRadio = false
        }
    }

    // Every play path is "play and show it": starting something opens the player screen, so a
    // chosen channel is never left audible behind a full-screen list (ADR-011).
    val openPlayer: () -> Unit = { onPlayerOpenChange(true) }

    // Registered from composition, so it takes precedence over the activity's exit handler for
    // exactly as long as there is somewhere to go back to. The player screen registers its own
    // handlers; composed after this one, they win while it is open.
    BackHandler(enabled = canGoBack, onBack = goBack)

    Box(modifier = modifier) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                // Touch has no D-pad Back, and a phone's gesture bar is easy to miss, so the way
                // out of a sub-screen is on the screen itself. The bar also carries search, which
                // otherwise has no entry point at all on the phone.
                val searchable = !showAddSource && !searching &&
                    tab in setOf(MobileTab.LIVE, MobileTab.RADIO, MobileTab.GUIDE, MobileTab.VOD)
                // Only the tabs that draw no heading of their own take a title: recordings and
                // every settings section set theirs in the screen, and the add screen and search
                // do too, so a title here would be the same words twice.
                val title = when {
                    showAddSource || searching -> null
                    tab == MobileTab.LIVE -> stringResource(R.string.nav_live)
                    tab == MobileTab.GUIDE -> stringResource(R.string.nav_guide)
                    tab == MobileTab.RADIO -> stringResource(R.string.nav_radio)
                    else -> null
                }
                if (canGoBack || searchable || title != null) {
                    PhoneTopBar(
                        title = title,
                        onBack = if (canGoBack) goBack else null,
                        onSearch = if (searchable) ({ searching = true }) else null,
                    )
                }
            },
            bottomBar = {
                // A column, not two bars: the Scaffold then accounts for both in its content
                // padding, so no list ever scrolls to an item the bar is covering.
                Column {
                    val nowPlaying = state.nowPlaying
                    if (nowPlaying != null && !showAddSource) {
                        val radioPlaylistId = state.playlists.firstOrNull { it.url == IsraelRadioStations.M3U_URL }?.id
                        PhoneMiniPlayer(
                            nowPlaying = nowPlaying,
                            now = state.now,
                            radio = radioPlaylistId != null &&
                                (nowPlaying as? NowPlayingInfo.Live)?.channel?.playlistId == radioPlaylistId,
                            wantsPlay = playback.playWhenReady,
                            onExpand = openPlayer,
                            onTogglePlay = { ServiceLocator.player.togglePlayPause() },
                        )
                    }
                    // Studio's five tabs, in the canvas's order (ADR-032): recordings has the
                    // place on-demand held, since this installation carries no on-demand (ADR-016).
                    PhoneNavBar(
                        tabs = listOf(
                            PhoneTab(MobileTab.LIVE, stringResource(R.string.nav_live), StudioIcons.Tv),
                            PhoneTab(MobileTab.GUIDE, stringResource(R.string.nav_guide), StudioIcons.Guide),
                            PhoneTab(MobileTab.RADIO, stringResource(R.string.nav_radio), StudioIcons.Radio),
                            PhoneTab(MobileTab.RECORDINGS, stringResource(R.string.rec_title), StudioIcons.Record),
                            PhoneTab(MobileTab.SETTINGS, stringResource(R.string.nav_settings), StudioIcons.Settings),
                        ),
                        selected = tab.takeIf { !showAddSource && it != MobileTab.VOD },
                        onSelect = selectTab,
                    )
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
                if (searching) {
                    SearchScreen(
                        vm = vm,
                        onPlay = { channel -> vm.play(channel); searching = false; openPlayer() },
                        onOpenProgramme = { channel, programme -> details = channel to programme },
                        // Only on success: a series container or a non-Xtream playlist posts a
                        // message instead, and the player screen must not open over nothing.
                        onPlayVod = { item -> if (vm.playVod(item)) { searching = false; openPlayer() } },
                        onOpenSeries = { item ->
                            pendingSeries = item
                            searching = false
                            tab = MobileTab.VOD
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    when (tab) {
                        MobileTab.LIVE -> ChannelListScreen(
                            vm = vm,
                            onPlay = { channel -> vm.play(channel); openPlayer() },
                            compact = !twoPane,
                            showNowNext = twoPane,
                            // A tablet is still touch, so it gets the filter box even though
                            // it is wide enough to drop the compact layout.
                            showFilter = true,
                            onSearch = { searching = true },
                            scrollToCurrent = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                        MobileTab.RADIO -> ChannelListScreen(
                            vm = vm,
                            onPlay = { channel -> vm.play(channel); openPlayer() },
                            compact = !twoPane,
                            showNowNext = twoPane,
                            showFilter = true,
                            onSearch = { searching = true },
                            scrollToCurrent = true,
                            isRadio = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // The grid with Studio's programme sheet under it (`PGuide`).
                        MobileTab.GUIDE -> PhoneGuide(
                            vm = vm,
                            onPlay = { channel -> vm.play(channel); openPlayer() },
                            onCatchup = { channel, programme -> if (vm.playCatchup(channel, programme)) openPlayer() },
                            onOpenProgramme = { channel, programme -> details = channel to programme },
                            modifier = Modifier.fillMaxSize(),
                        )
                        MobileTab.VOD -> VodScreen(
                            vm = vm,
                            onPlay = { item -> if (vm.playVod(item)) openPlayer() },
                            modifier = Modifier.fillMaxSize(),
                            openSeriesRequest = pendingSeries,
                            onSeriesRequestConsumed = { pendingSeries = null },
                            state = vodState,
                        )
                        MobileTab.RECORDINGS -> RecordingsScreen(
                            onPlayRecording = { recording -> if (vm.playRecording(recording)) openPlayer() },
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

        if (playerOpen) {
            PlayerScreen(
                vm = vm,
                onClose = { onPlayerOpenChange(false) },
                onOpenGuide = { onPlayerOpenChange(false); selectTab(MobileTab.GUIDE) },
                onBrightness = onBrightness,
                onVolume = onVolume,
                twoPane = twoPane,
                modifier = Modifier.fillMaxSize(),
            )
            // Digits from a Bluetooth remote or a keyboard, as on the television (QA-09).
            if (numericEntry.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.channel_number_entry, numericEntry),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .safeDrawingPadding()
                        .padding(16.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = prefs.overlayOpacity))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        details?.let { (channel, programme) ->
            ProgrammeDetailsDialog(
                channel = channel,
                programme = programme,
                onDismiss = { details = null },
                onWatch = { vm.play(channel); openPlayer() },
                onCatchup = { if (vm.playCatchup(channel, programme)) openPlayer() },
                onFavourite = { favourite -> vm.setFavourite(channel, favourite) },
                onMessage = { text -> vm.post(UiMessage(text = text)) },
                upcoming = upcoming,
            )
        }

        // FR-UPD-03: offered here rather than inside a screen, so it survives tab changes and
        // is absent in picture-in-picture, which returns above this point.
        UpdatePrompt()
    }
}

/** How long past a programme's end to wait before re-asking the guide, absorbing clock skew. */
private const val PROGRAMME_TURNOVER_GRACE_MS = 5_000L
