package com.tivicopy.player.ui.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.Motion
import com.tivicopy.player.ui.common.filledContentColour
import com.tivicopy.player.ui.common.focusLift
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.common.ProgrammeProgress
import com.tivicopy.player.ui.common.SectionHeader
import com.tivicopy.player.ui.common.monogram
import com.tivicopy.player.ui.common.monogramColour
import com.tivicopy.player.ui.home.HomeCard
import com.tivicopy.player.ui.home.HomeRail
import com.tivicopy.player.ui.home.HomeRailId
import com.tivicopy.player.ui.home.HomeRails
import kotlinx.coroutines.delay

/**
 * The ten-foot home screen (FR-UI-24): the live picture as a full-bleed banner, an icon rail down
 * the inner edge, and rails of cards that scroll up over the picture.
 *
 * ADR-016 built this screen with the picture docked into a boxed hero beside its caption; ADR-020
 * replaced that with the banner described here, after the Cellcom TV teardown. The video is not
 * docked at all now — it fills the screen behind this overlay, and everything below the banner
 * paints its own backdrop over it, which is what keeps text on top of moving video readable.
 * `TvOverlay.HOME` therefore no longer sets `docksPlayer`, and `OverlayScaffold` draws neither a
 * scrim nor a content inset around it: a full-bleed banner cannot start 24dp from the edge.
 *
 * Everything is one `LazyColumn`, banner included, so pressing Down from the banner scrolls it
 * away and hands the whole panel to the rails — the same thing the benchmark does, and the reason
 * the rails are not squeezed into whatever is left under a pinned hero.
 *
 * Three rules from the rest of the shell are load-bearing here and are easy to undo by accident:
 *
 * - **No `focusable()` beside `clickable()`.** `clickable` is a focus target on its own, and
 *   pairing them costs the remote an extra press to leave every tile.
 * - **No text field.** On a D-pad surface a text field takes the first focus and pops the
 *   on-screen keyboard over the content. Search is a rail destination, not a box.
 * - **The side menu opens over the screen, never beside it.** It is drawn in a `Box` on top of
 *   the list and padded for at its closed strip's width only (`SideMenu.kt`), so focus crossing
 *   into it cannot reflow every card on the screen.
 */
// `Modifier.focusGroup` is `@ExperimentalFoundationApi` at this Compose version, as
// `combinedClickable` is in `ui/common/Components.kt`. It keeps a D-pad step between rails
// landing at the start of the next one rather than at whichever card happens to be nearest.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvHomeScreen(
    vm: AppViewModel,
    /**
     * Whether what is behind this screen is the radio backdrop rather than a picture. It draws a
     * station's identity and a clock of its own, so the banner drops both instead of doubling
     * them (`ui/common/StationBackdrop.kt`).
     */
    audioOnlyBackdrop: Boolean,
    onOpen: (TvOverlay) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val prefs by vm.settingsState.collectAsState()
    val featured by ServiceLocator.featured.state.collectAsState()
    val metrics = LocalTvMetrics.current

    var rails by remember { mutableStateOf(emptyList<HomeRail>()) }
    var tick by remember { mutableStateOf(0) }

    // What is on "now" moves on without anyone pressing anything, so the rails are rebuilt on a
    // slow tick as well as on a real change. Slow enough to be free, often enough that a caption
    // is never wrong for long.
    LaunchedEffect(Unit) {
        while (true) {
            delay(REBUILD_INTERVAL_MS)
            tick++
        }
    }
    LaunchedEffect(state.activePlaylist?.id, state.currentChannel?.id, featured, tick) {
        val data = vm.loadHomeData()
        rails = HomeRails.build(data, System.currentTimeMillis())
    }

    val apps = rememberHomeApps()

    /*
     * Rail titles and destination labels are resolved here, in the composable body.
     *
     * A `LazyListScope` lambda is not a `@Composable` scope — only the `item {}` / `items {}`
     * content blocks inside it are — so `stringResource` cannot be called while building the
     * list. The same reason `GroupColumn` and `GroupChips` hoist theirs.
     */
    val titles = mapOf(
        HomeRailId.FEATURED to stringResource(R.string.home_rail_featured),
        HomeRailId.CONTINUE to stringResource(R.string.home_rail_continue),
        HomeRailId.CHANNELS to stringResource(R.string.nav_live),
        HomeRailId.RADIO to stringResource(R.string.nav_radio),
        HomeRailId.FAVOURITES to stringResource(R.string.favourites),
        HomeRailId.STARTING_SOON to stringResource(R.string.home_rail_starting_soon),
    )
    // Studio's order (`SideMenu.dc.html`): the screens, then the tools under a hairline.
    val destinations = listOf(
        MenuDestination(stringResource(R.string.nav_home), Icons.Filled.Home, null),
        MenuDestination(stringResource(R.string.nav_live), Icons.Filled.LiveTv, TvOverlay.CHANNELS),
        MenuDestination(stringResource(R.string.nav_guide), Icons.Filled.DateRange, TvOverlay.GUIDE),
        MenuDestination(stringResource(R.string.nav_radio), Icons.Filled.Radio, TvOverlay.RADIO),
        MenuDestination(stringResource(R.string.nav_recordings), Icons.Filled.History, TvOverlay.RECORDINGS),
    )
    val tools = listOf(
        MenuDestination(stringResource(R.string.nav_search), Icons.Filled.Search, TvOverlay.SEARCH),
        MenuDestination(stringResource(R.string.nav_settings), Icons.Filled.Settings, TvOverlay.SETTINGS),
    )

    // OK on the channel already playing goes fullscreen; OK on any other card tunes it and stays
    // here, because the banner is what the live picture is for. One rule, and the banner's own
    // button says it out loud.
    val open: (HomeCard) -> Unit = { card ->
        val channelId = card.channelId
        when {
            channelId == null -> Unit
            channelId == state.currentChannel?.id -> onFullscreen()
            else -> vm.playChannelId(channelId)
        }
    }

    // Where the first press of the remote lands: the banner's own button while something is
    // playing behind it, and otherwise the first card, because a banner with no channel has no
    // button to focus.
    val heroFocus = remember { FocusRequester() }
    val firstCard = remember { FocusRequester() }
    var claimedFocus by remember { mutableStateOf(false) }
    val heroIsFocusable = state.currentChannel != null
    LaunchedEffect(heroIsFocusable, rails.isNotEmpty()) {
        if (claimedFocus || (!heroIsFocusable && rails.isEmpty())) return@LaunchedEffect
        claimedFocus = true
        // The target has to be composed before it can be focused; this frame is load-bearing.
        withFrameNanos { }
        runCatching { if (heroIsFocusable) heroFocus.requestFocus() else firstCard.requestFocus() }
    }

    // What everything below the banner is painted on, and what the banner's gradient fades into.
    // The viewer's own overlay-opacity setting (FR-UI-18), not a constant: every other surface
    // drawn over video in this shell obeys it, and this one is drawn over video at all times.
    val backdrop = MaterialTheme.colorScheme.background
    val scrim = backdrop.copy(alpha = prefs.overlayOpacity)

    // Room the side menu's closed strip needs at the leading edge, kept by every row's *content*
    // rather than by padding the list, so the rows stay full-bleed under the strip. The strip's
    // width and never the open menu's: the menu opens *over* the list, so nothing reflows as focus
    // crosses into it.
    val railGutter = sideMenuInset()

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusGroup(),
        ) {
            item(key = "hero") {
                HomeBanner(
                    channel = state.currentChannel,
                    now = state.now,
                    next = state.next,
                    height = metrics.homeHeroHeight,
                    showIdentity = !audioOnlyBackdrop,
                    backdrop = backdrop,
                    scrim = scrim,
                    startInset = railGutter,
                    watchFocus = heroFocus.takeIf { heroIsFocusable },
                    onFullscreen = onFullscreen,
                )
            }

            if (apps.isNotEmpty()) {
                item(key = "apps") {
                    HomeAppStrip(
                        apps = apps,
                        background = scrim,
                        startInset = railGutter,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            items(rails, key = { it.id.name }) { rail ->
                HomeCardRail(
                    title = titles[rail.id].orEmpty(),
                    cards = rail.cards,
                    playingChannelId = state.currentChannel?.id,
                    cardWidth = metrics.homeCardWidth,
                    background = scrim,
                    firstCardFocus = firstCard.takeIf { rail.id == rails.firstOrNull()?.id },
                    startInset = railGutter,
                    onOpen = open,
                )
            }

            // The backdrop has to reach the bottom of the panel when the list is scrolled to its
            // end; without this the last rail's cards sit against bare video.
            item(key = "tail") {
                Spacer(modifier = Modifier.fillMaxWidth().height(24.dp).background(scrim))
            }
        }

        // Pinned rather than scrolled with the banner: the clock stays in its corner however far
        // down the rails the viewer goes.
        StudioTopBar(
            startInset = railGutter,
            showClock = !audioOnlyBackdrop,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        SideMenu(
            destinations = destinations,
            tools = tools,
            onOpen = onOpen,
        )
    }
}

/**
 * The banner: the live picture, the channel it belongs to, and what is on it.
 *
 * The picture itself is not drawn here and never was — `TvRoot` owns the one `PlayerSurface` in
 * the app and, on this screen, leaves it filling the panel. What this composable adds is the
 * gradient that makes words legible over it (the benchmark's own device, and the reason its hero
 * text is readable over any frame), the channel's identity in the leading corner, the clock in
 * the trailing one, and the button that fills the screen with the picture.
 *
 * The caption reads the *playing* channel rather than the focused card, so the picture and the
 * words are never about two different channels.
 */
@Composable
private fun HomeBanner(
    channel: Channel?,
    now: Programme?,
    next: Programme?,
    height: Dp,
    /** False while the radio backdrop behind is already saying which station this is. */
    showIdentity: Boolean,
    backdrop: Color,
    scrim: Color,
    /** Room kept clear at the leading edge for the side menu's strip drawn over this. */
    startInset: Dp,
    /** Null when nothing is playing: there is no button to focus, so nothing asks for it. */
    watchFocus: FocusRequester?,
    onFullscreen: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().height(height)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to backdrop.copy(alpha = 0.30f),
                        1f to scrim,
                    ),
                ),
        )

        if (showIdentity) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    // Under the top bar, which now carries the clock this row used to end with.
                    .padding(start = startInset, end = TV_CONTENT_INSET, top = BANNER_IDENTITY_TOP, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (channel != null) {
                    ChannelLogo(channel = channel, sizeDp = 44)
                    Text(
                        text = channel.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = startInset, end = TV_CONTENT_INSET, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Crossed, not cut: the banner's title changes on its own when a programme ends or
            // the viewer zaps, and a caption that swaps instantly under a picture that dissolved
            // reads as two different screens. Only the title — the button below keeps focus, and
            // animating a focused control out from under the remote is how focus gets lost.
            Crossfade(
                targetState = now?.title ?: channel?.displayName ?: stringResource(R.string.no_guide_data),
                animationSpec = tween(Motion.STANDARD),
                label = "bannerTitle",
            ) { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                now?.let {
                    Text(
                        text = TimeModel.formatClockRange(it.startUtc, it.endUtc),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                next?.let {
                    Text(
                        // A bare clock beside Hebrew is isolated, or the layout reorders it.
                        text = "${TimeModel.ltr(TimeModel.formatClock(it.startUtc))} · ${it.title}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            now?.let {
                ProgrammeProgress(
                    startUtc = it.startUtc,
                    endUtc = it.endUtc,
                    modifier = Modifier.fillMaxWidth(BANNER_PROGRESS_FRACTION),
                )
            }
            if (channel != null) {
                WatchFullscreenButton(focusRequester = watchFocus, onClick = onFullscreen)
            }
        }
    }
}

/** The banner's one action, and the first thing the remote lands on. */
@Composable
private fun WatchFullscreenButton(focusRequester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LocalTvMetrics.current.controlCorner)
    val label = stringResource(R.string.home_watch_fullscreen)
    Row(
        modifier = Modifier
            .focusLift(focused, shape = shape)
            // Studio's button (`canvas/Button.dc.html`): a translucent control with a hairline
            // edge at rest, because it sits over live video where a plate with no edge bleeds into
            // a bright frame, and the cue when focused.
            .focusSurface(focused = focused, shape = shape, fill = FocusFill.FILLED)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val tint = filledContentColour(focused)
        Icon(
            imageVector = Icons.Filled.Fullscreen,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint,
        )
        Text(text = label, style = MaterialTheme.typography.titleSmall, color = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeCardRail(
    title: String,
    cards: List<HomeCard>,
    playingChannelId: Long?,
    cardWidth: Dp,
    background: Color,
    firstCardFocus: FocusRequester?,
    startInset: Dp,
    onOpen: (HomeCard) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(background)) {
        SectionHeader(
            text = title,
            topDivider = false,
            // Heavier than the app's default header: a rail title on a banner-led home screen is
            // read from across the room, not scanned down a settings list.
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            // Less the inset the header pads its own label by, so the heading starts on the same
            // line as the cards under it rather than 12dp past them.
            modifier = Modifier.padding(start = startInset - SECTION_HEADER_OWN_INSET),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            // The home screen is drawn without `OverlayScaffold`'s content inset — a full-bleed
            // banner cannot start 24dp from the edge — so the rails apply it themselves, plus
            // room for the navigation capsule at the leading edge and a focused card's ring.
            contentPadding = PaddingValues(
                start = startInset,
                end = TV_CONTENT_INSET,
                top = 8.dp,
                bottom = 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cards, key = { it.key }) { card ->
                HomeTile(
                    card = card,
                    playing = card.channelId != null && card.channelId == playingChannelId,
                    width = cardWidth,
                    onClick = { onOpen(card) },
                    modifier = if (firstCardFocus != null && card.key == cards.firstOrNull()?.key) {
                        Modifier.focusRequester(firstCardFocus)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeTile(
    card: HomeCard,
    playing: Boolean,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            // Grows on focus and is drawn over its neighbours while it does. Through a
            // `graphicsLayer`, so the row it sits in does not re-measure and the cards either
            // side of it do not move (`ui/common/Motion.kt`).
            .focusLift(focused, shape = tvCardShape())
            .width(width)
            // The shell's one card look, shared with the filmstrip (`TvSurfaces.kt`).
            .tvCard(focused = focused, playing = playing)
            .onFocusChanged { focused = it.isFocused }
            // `clickable` carries the focus target on its own; adding `focusable()` here would
            // cost the remote a second press to leave every tile.
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The card knows its own shape: a station is round wherever it lands, including on the
        // Continue and Favourites rails, which are built across every playlist (`HomeModels.kt`).
        HomeTileArt(card = card, circular = card.circular, modifier = Modifier.fillMaxWidth())
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The card for the channel that is actually playing says so, rather than leaving the
            // ring around it to carry that alone: on a rail scrolled past the focused card there
            // is no ring in view, and "which of these am I listening to" is the question a radio
            // rail gets asked most.
            if (playing) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Text(
                text = card.subtitle ?: stringResource(R.string.no_guide_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        when {
            card.hasProgress -> ProgrammeProgress(startUtc = card.startUtc, endUtc = card.endUtc)
            card.endUtc > card.startUtc -> Text(
                // A window still ahead: the clock, not a bar sitting at zero. Isolated because a
                // Latin time range inside a Hebrew layout reorders otherwise (FR-UI-22).
                text = TimeModel.ltr(TimeModel.formatClockRange(card.startUtc, card.endUtc)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The picture on a card: the source's own artwork, or a monogram tile in a colour derived from
 * the title, which is what [com.tivicopy.player.ui.common.ChannelLogo] already does for a
 * channel row. Written out here rather than reused because a card is not always a channel —
 * a promoted item carries a poster and a programme carries its own icon.
 *
 * Sixteen by nine, not the benchmark's 2:3 poster: every card this screen draws is a channel or
 * a programme on one, and a portrait poster of a channel logo is a tall box with a small mark in
 * the middle of it. The on-demand grid, which is where this app draws artwork for films and
 * series, does use 2:3 (`ui/screens/VodScreen.kt`).
 */
@Composable
private fun HomeTileArt(card: HomeCard, circular: Boolean, modifier: Modifier = Modifier) {
    var failed by remember(card.imageUrl) { mutableStateOf(false) }
    // The *outer* box keeps 16:9 whichever shape the mark inside it takes, so a rail of round
    // station marks is exactly as tall as a rail of channel stills and the two sit in one column
    // of rails without the row heights stepping.
    val artShape = if (circular) CircleShape else RoundedCornerShape(8.dp)
    val artSize = if (circular) {
        Modifier.fillMaxHeight().aspectRatio(1f)
    } else {
        Modifier.fillMaxSize()
    }
    Box(
        modifier = modifier.aspectRatio(16f / 9f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = artSize
                .clip(artShape)
                .background(monogramColour(card.title)),
            contentAlignment = Alignment.Center,
        ) {
            if (card.imageUrl.isNullOrBlank() || failed) {
                Text(
                    text = monogram(card.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            } else {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = card.title,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    onError = { failed = true },
                )
            }
        }
        card.number?.let { number ->
            Text(
                // A bare Latin numeral beside Hebrew: isolated, or the layout reorders it.
                text = TimeModel.ltr(number.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** How often the rails are rebuilt so a now-caption cannot sit wrong for long. */
private const val REBUILD_INTERVAL_MS = 5L * 60 * 1000

/** Where the banner's channel line starts: under the top bar (`StudioTopBar`, 64dp) and a gap. */
private val BANNER_IDENTITY_TOP = 72.dp

/**
 * How much [com.tivicopy.player.ui.common.SectionHeader] insets its own label.
 *
 * A rail heading and the cards under it are given their leading inset by two different mechanisms
 * — a modifier on the header, content padding on the row — and only one of them is the whole
 * distance. Subtracting this is what keeps the two on one line; before the rails were made
 * full-bleed the same alignment came out of a bare `12.dp` that quietly encoded it.
 */
internal val SECTION_HEADER_OWN_INSET = 12.dp

/** How much of the banner's width the progress bar takes. Long enough to read, short of a rule. */
private const val BANNER_PROGRESS_FRACTION = 0.45f
