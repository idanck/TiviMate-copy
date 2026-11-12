package com.idanck.shidur.ui.mobile

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.ui.tv.YouTubeSession
import com.idanck.shidur.ui.tv.YtPage
import com.idanck.shidur.data.builtin.IsraelRadioStations
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.Programme
import com.idanck.shidur.data.model.VodItem
import com.idanck.shidur.record.SERIES_LOOKAHEAD_MILLIS
import com.idanck.shidur.data.prefs.StartBehaviour
import com.idanck.shidur.ui.AppViewModel
import com.idanck.shidur.ui.NowPlayingInfo
import com.idanck.shidur.ui.SportSession
import com.idanck.shidur.ui.UiMessage
import com.idanck.shidur.ui.screens.AddSourceScreen
import com.idanck.shidur.ui.screens.ChannelListScreen
import com.idanck.shidur.ui.screens.ProgrammeDetailsDialog
import com.idanck.shidur.ui.screens.RecordingsScreen
import com.idanck.shidur.ui.common.HandoverPrompt
import com.idanck.shidur.ui.common.UpdatePrompt
import com.idanck.shidur.ui.screens.SearchScreen
import com.idanck.shidur.ui.screens.VodScreen
import com.idanck.shidur.ui.screens.VodScreenState
import com.idanck.shidur.ui.settings.SettingsScreen
import com.idanck.shidur.ui.settings.SettingsSection
import kotlinx.coroutines.delay
import com.idanck.shidur.ui.common.StudioIcons

/**
 * The phone's destinations. Live, Radio and Settings have a tab; the guide, YouTube and the
 * recordings sit behind the last tab, "עוד" ([MORE], ADR-066), which is a sheet rather than a
 * screen. [VOD] has none and is reached only from search, on an installation that has on-demand at
 * all (ADR-016).
 */
enum class MobileTab { LIVE, RADIO, GUIDE, VOD, RECORDINGS, SETTINGS, YOUTUBE, MORE, PODCASTS }

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
    panelsOpen: SportSession.Mode?,
    onPanelsOpenChange: (SportSession.Mode?) -> Unit,
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

    // Sport and News modes (ADR-041, ADR-042) open and close with their screen, as the
    // television's do with their overlays. Leaving through the screen's own Back has already
    // closed the session by the time this runs.
    LaunchedEffect(panelsOpen) {
        when (panelsOpen) {
            SportSession.Mode.SPORT -> vm.enterSport()
            SportSession.Mode.NEWS -> vm.enterNews()
            null -> vm.closeSport()
        }
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

    // YouTube (ADR-061, stage 4): one player for the tab and its player screen, made while either
    // is up and let go after. The full-screen player stands down while YouTube's plays.
    var ytPlayerOpen by remember { mutableStateOf(false) }
    val ytActive = tab == MobileTab.YOUTUBE || ytPlayerOpen
    val ytContext = LocalContext.current
    val ytSession = remember(ytActive) { if (ytActive) YouTubeSession(ytContext, ServiceLocator.youtube) else null }
    DisposableEffect(ytSession) { onDispose { ytSession?.release() } }
    LaunchedEffect(ytPlayerOpen) {
        if (ytPlayerOpen) vm.enterYouTube() else vm.leaveYouTube(restart = false)
    }
    // The other players take over from it.
    LaunchedEffect(playerOpen, panelsOpen) {
        if (playerOpen || panelsOpen != null) ytPlayerOpen = false
    }
    // A YouTube channel picked in the channel list or the guide plays on YouTube's player screen.
    val ytRequest by vm.youtubeRequest.collectAsState()
    LaunchedEffect(ytRequest) {
        if (ytRequest != null) {
            onPlayerOpenChange(false)
            ytPlayerOpen = true
        }
    }
    LaunchedEffect(ytSession, ytRequest) {
        val id = ytRequest ?: return@LaunchedEffect
        val session = ytSession ?: return@LaunchedEffect
        vm.consumeYoutubeRequest()
        session.playChannel(id)
    }
    val closeYouTubePlayer: () -> Unit = {
        ytSession?.let { session ->
            session.noteCurrent()
            session.player.stop()
        }
        ytPlayerOpen = false
    }

    // The add screen doubles as the first-run setup, and then it *is* the root: with no
    // playlist yet there is nothing behind it to go back to.
    val showAddSource = addingSource || (state.playlists.isEmpty() && !state.loading)
    val inSettingsSubSection = tab == MobileTab.SETTINGS && settingsSection != SettingsSection.ROOT
    // A show's page and the player step back inside the podcasts tab (ADR-067).
    val podcastPage by vm.podcastNav.page.collectAsState()
    val inPodcastPage = tab == MobileTab.PODCASTS && podcastPage !is com.idanck.shidur.ui.podcasts.PodcastPage.Home
    // Live is the phone's home tab, the same way the channel list is TV's — Back from any other
    // tab returns to it first, rather than falling straight through to the activity's exit
    // confirmation the moment there is nothing else on this composable's own stack to unwind.
    val canGoBack = (addingSource && state.playlists.isNotEmpty()) ||
        (!showAddSource && (searching || inSettingsSubSection || inPodcastPage || tab != MobileTab.LIVE))

    val goBack: () -> Unit = {
        when {
            // A channel's page or search over YouTube's shelves steps back to them first.
            tab == MobileTab.YOUTUBE && ytSession?.back() == true -> Unit
            addingSource -> addingSource = false
            searching -> searching = false
            inSettingsSubSection -> onSettingsSection(SettingsSection.ROOT)
            inPodcastPage -> vm.podcastNav.back()
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
    // chosen channel is never left audible behind a full-screen list (ADR-011). A YouTube channel
    // goes to YouTube's player screen instead (the request above).
    val openPlayer: () -> Unit = { if (vm.youtubeRequest.value == null) onPlayerOpenChange(true) }

    // Leaving Sport or News mode plays the channel being heard, and so shows it, as the television
    // carries it on full screen. With nothing chosen, whatever played before comes back as it was:
    // playing under the mini player, or paused there.
    val leavePanels: () -> Unit = {
        val chosen = vm.sport.focusedChannel()
        vm.exitSport(restartPaused = false)
        onPanelsOpenChange(null)
        if (chosen != null) openPlayer()
    }
    val openPanels: (SportSession.Mode) -> Unit = { mode ->
        onPlayerOpenChange(false)
        onPanelsOpenChange(mode)
    }

    // Registered from composition, so it takes precedence over the activity's exit handler for
    // exactly as long as there is somewhere to go back to. The player screen registers its own
    // handlers; composed after this one, they win while it is open.
    BackHandler(enabled = canGoBack, onBack = goBack)

    // "עוד" (ADR-066): open above the bar, and how tall the bar is, so the panel stops at it.
    var moreOpen by remember { mutableStateOf(false) }
    var navBarHeightPx by remember { mutableIntStateOf(0) }
    val moreTabs = listOf(
        PhoneTab(MobileTab.GUIDE, stringResource(R.string.nav_guide), StudioIcons.Guide),
        PhoneTab(MobileTab.YOUTUBE, stringResource(R.string.nav_youtube), StudioIcons.Stream),
        PhoneTab(MobileTab.RECORDINGS, stringResource(R.string.rec_title), StudioIcons.Record),
    )

    Box(modifier = modifier) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                // Touch has no D-pad Back, and a phone's gesture bar is easy to miss, so the way
                // out of a sub-screen is on the screen itself. The bar also carries search, which
                // otherwise has no entry point at all on the phone.
                val searchable = !showAddSource && !searching &&
                    tab in setOf(MobileTab.LIVE, MobileTab.RADIO, MobileTab.GUIDE, MobileTab.VOD)
                // Only the screens that draw no heading of their own take a title: recordings and
                // every settings section set theirs in the screen, and search is its field. The
                // add screen and on-demand title themselves on a television only; on a phone their
                // title is this bar's (`PAddSource`, `PVod`).
                val title = when {
                    showAddSource -> stringResource(R.string.setup_title)
                    searching -> null
                    tab == MobileTab.VOD -> stringResource(R.string.nav_vod)
                    tab == MobileTab.LIVE -> stringResource(R.string.nav_live)
                    tab == MobileTab.GUIDE -> stringResource(R.string.nav_guide)
                    tab == MobileTab.RADIO -> stringResource(R.string.nav_radio)
                    tab == MobileTab.YOUTUBE -> stringResource(R.string.nav_youtube)
                    tab == MobileTab.PODCASTS && !inPodcastPage -> stringResource(R.string.nav_podcasts)
                    else -> null
                }
                // YouTube's tab searches YouTube, on a page of its own (`PYouTubeSearch`).
                val youtubeSearch = !showAddSource && !searching && tab == MobileTab.YOUTUBE &&
                    ytSession?.page != YtPage.Search
                // Sport and News modes are offered where the television's channels are browsed, not
                // over radio.
                val sportable = !showAddSource && !searching && tab in setOf(MobileTab.LIVE, MobileTab.GUIDE)
                if (canGoBack || searchable || title != null) {
                    PhoneTopBar(
                        title = title,
                        onBack = if (canGoBack) goBack else null,
                        onSearch = when {
                            searchable -> ({ searching = true })
                            youtubeSearch -> ({ ytSession?.open(YtPage.Search) })
                            else -> null
                        },
                        onSport = if (sportable) ({ openPanels(SportSession.Mode.SPORT) }) else null,
                        onNews = if (sportable) ({ openPanels(SportSession.Mode.NEWS) }) else null,
                        // YouTube's account button (ADR-069), on the tab's own shelves.
                        youtubeAccount = !showAddSource && !searching && tab == MobileTab.YOUTUBE && ytSession?.page == null,
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
                            // A podcast opens its own player, in its tab (ADR-067); anything else
                            // the full-screen one.
                            onExpand = {
                                if (nowPlaying is NowPlayingInfo.Podcast) {
                                    selectTab(MobileTab.PODCASTS)
                                    vm.podcastNav.open(com.idanck.shidur.ui.podcasts.PodcastPage.Player)
                                } else {
                                    openPlayer()
                                }
                            },
                            onTogglePlay = { ServiceLocator.player.togglePlayPause() },
                        )
                    }
                    // The bar the owner chose on 2026-11-11 (ADR-066): Live, Radio, Podcasts and Settings, and
                    // "עוד" with the guide, YouTube and the recordings, so a new destination no
                    // longer squeezes seven labels into 390dp.
                    PhoneNavBar(
                        // The Radio tab leaves with the radio playlist, removed or turned off in Settings.
                        tabs = listOfNotNull(
                            PhoneTab(MobileTab.LIVE, stringResource(R.string.nav_live), StudioIcons.Tv),
                            PhoneTab(MobileTab.RADIO, stringResource(R.string.nav_radio), StudioIcons.Radio)
                                .takeIf { vm.isRadioShown(state.playlists) },
                            PhoneTab(MobileTab.PODCASTS, stringResource(R.string.nav_podcasts), StudioIcons.Podcast),
                            PhoneTab(MobileTab.SETTINGS, stringResource(R.string.nav_settings), StudioIcons.Settings),
                        ),
                        more = moreTabs,
                        moreOpen = moreOpen,
                        onMoreOpenChange = { moreOpen = it },
                        selected = tab.takeIf { !showAddSource && it != MobileTab.VOD },
                        onSelect = selectTab,
                        modifier = Modifier.onSizeChanged { navBarHeightPx = it.height },
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
                            onAddSource = { addingSource = true },
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
                        MobileTab.YOUTUBE -> ytSession?.let { session ->
                            PhoneYouTube(
                                session = session,
                                onOpenPlayer = {
                                    onPlayerOpenChange(false)
                                    ytPlayerOpen = true
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        MobileTab.SETTINGS -> Column(modifier = Modifier.fillMaxSize()) {
                            SettingsScreen(
                                vm = vm,
                                section = settingsSection,
                                onNavigate = onSettingsSection,
                                onAddSource = { addingSource = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        MobileTab.PODCASTS -> com.idanck.shidur.ui.podcasts.PodcastsScreen(
                            vm = vm,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // "עוד" is a sheet over the current tab, never a screen of its own.
                        MobileTab.MORE -> Unit
                    }
                }
            }
        }

        // `PNavOptMoreSheet`: over the screen and the mini player, under the tab bar, which stays
        // in view and in reach.
        PhoneMorePanel(
            open = moreOpen && !playerOpen && panelsOpen == null && !ytPlayerOpen,
            items = moreTabs,
            selected = tab,
            barHeight = with(LocalDensity.current) { navBarHeightPx.toDp() },
            onSelect = { moreOpen = false; selectTab(it) },
            onDismiss = { moreOpen = false },
        )

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

        // YouTube's player screen, over everything like the other player: nothing may cover it.
        if (ytPlayerOpen) {
            ytSession?.let { session ->
                PhoneYouTubePlayer(
                    session = session,
                    onClose = closeYouTubePlayer,
                    onOpenChannel = { id ->
                        closeYouTubePlayer()
                        selectTab(MobileTab.YOUTUBE)
                        session.open(YtPage.Channel(id))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Over the player screen too, though the two are never open together: opening Sport or
        // News mode closes it, and leaving opens it.
        if (panelsOpen != null) {
            PhoneSport(
                vm = vm,
                onLeave = leavePanels,
                modifier = Modifier.fillMaxSize(),
            )
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
        // ADR-060: the move to the new package, on whichever side of it this build is.
        HandoverPrompt()
    }
}

/** How long past a programme's end to wait before re-asking the guide, absorbing clock skew. */
private const val PROGRAMME_TURNOVER_GRACE_MS = 5_000L
