package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.tivicopy.player.ui.common.ClockText
import com.tivicopy.player.ui.common.LocalTvMetrics
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
 * - **The navigation rail expands over the screen, never beside it.** It is drawn in a `Box` on
 *   top of the list and padded for at its collapsed width only, so focus crossing into it cannot
 *   reflow every card on the screen.
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
    val destinations = listOf(
        HomeDestination(stringResource(R.string.nav_home), Icons.Filled.Home, null),
        HomeDestination(stringResource(R.string.nav_search), Icons.Filled.Search, TvOverlay.SEARCH),
        HomeDestination(stringResource(R.string.nav_live), Icons.Filled.LiveTv, TvOverlay.CHANNELS),
        HomeDestination(stringResource(R.string.nav_radio), Icons.Filled.Radio, TvOverlay.RADIO),
        HomeDestination(stringResource(R.string.nav_guide), Icons.Filled.DateRange, TvOverlay.GUIDE),
        HomeDestination(stringResource(R.string.nav_recordings), Icons.Filled.History, TvOverlay.RECORDINGS),
        HomeDestination(stringResource(R.string.nav_settings), Icons.Filled.Settings, TvOverlay.SETTINGS),
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

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                // The collapsed rail's width, and never the expanded one: the rail grows over
                // this list rather than pushing it.
                .padding(start = metrics.homeNavRailCollapsedWidth)
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
                    watchFocus = heroFocus.takeIf { heroIsFocusable },
                    onFullscreen = onFullscreen,
                )
            }

            if (apps.isNotEmpty()) {
                item(key = "apps") {
                    HomeAppStrip(apps = apps, background = scrim, modifier = Modifier.fillMaxWidth())
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
                    onOpen = open,
                )
            }

            // The backdrop has to reach the bottom of the panel when the list is scrolled to its
            // end; without this the last rail's cards sit against bare video.
            item(key = "tail") {
                Spacer(modifier = Modifier.fillMaxWidth().height(24.dp).background(scrim))
            }
        }

        HomeNavRail(
            destinations = destinations,
            onOpen = onOpen,
            modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight(),
        )
    }
}

/** One row of the navigation rail. A null [overlay] is the row for the screen you are on. */
private data class HomeDestination(
    val label: String,
    val icon: ImageVector,
    val overlay: TvOverlay?,
)

/**
 * The vertical navigation rail: icons only, until focus enters it.
 *
 * The teardown found the benchmark's rail collapsed to a strip of icons with the content still
 * visible behind it, which is the pattern this now follows — a ten-foot screen has no room to
 * spend a sixth of its width on words nobody is reading while they browse. Two things stop that
 * from being the accessibility regression the same report warned about:
 *
 * - every item carries its label as a `contentDescription` whether or not it is drawn, so
 *   TalkBack and the D-pad see a named destination at all times (NFR-UX-05);
 * - the labels appear the moment focus enters the rail, which is the only moment they are
 *   being read.
 *
 * It expands *over* the screen, not beside it. An earlier version of this rail kept its labels
 * permanently for exactly one reason — a rail that changes width reflows every card on the
 * screen each time focus crosses into it — and drawing it in a `Box` above the list, with the
 * list padded only for its collapsed width, keeps that property while letting it grow.
 *
 * On-demand is deliberately absent. This build carries the two bundled Israeli playlists and
 * nothing else (ADR-013, ADR-015), and a destination that opens an empty catalogue is worse
 * than no destination.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeNavRail(
    destinations: List<HomeDestination>,
    onOpen: (TvOverlay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalTvMetrics.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            // Before `focusGroup`, so it observes the group's own focus state: `hasFocus` is true
            // while any item inside is focused, which is exactly when the labels are wanted.
            .onFocusChanged { expanded = it.hasFocus }
            .focusGroup()
            .width(if (expanded) metrics.homeNavRailWidth else metrics.homeNavRailCollapsedWidth)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (expanded) 0.96f else 0.55f))
            .padding(vertical = 16.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (expanded) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            )
        }
        destinations.forEach { destination ->
            NavRailItem(
                destination = destination,
                expanded = expanded,
                onClick = { destination.overlay?.let(onOpen) },
            )
        }
    }
}

@Composable
private fun NavRailItem(
    destination: HomeDestination,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val selected = destination.overlay == null
    // A pill at the expanded width, a circle at the collapsed one — the "light round background"
    // the benchmark marks its focused item with.
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                    selected -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color.Transparent
                },
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            // `clickable` carries the focus target on its own; `focusable()` beside it would cost
            // the remote a second press to pass every destination.
            .clickable(onClick = onClick)
            // The label is spoken and searched for whether or not it is drawn, which is what
            // keeps the collapsed rail navigable (NFR-UX-05).
            .semantics { contentDescription = destination.label }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (focused || selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (expanded) {
            Text(
                text = destination.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
                    .padding(horizontal = TV_CONTENT_INSET, vertical = 16.dp),
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
                Spacer(modifier = Modifier.weight(1f))
                ClockText()
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = TV_CONTENT_INSET, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = now?.title ?: channel?.displayName ?: stringResource(R.string.no_guide_data),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    val shape = RoundedCornerShape(24.dp)
    val label = stringResource(R.string.home_watch_fullscreen)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(
                if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                },
            )
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = shape,
            )
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
        val tint = if (focused) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
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
            modifier = Modifier.padding(start = 12.dp),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            // The home screen is drawn without `OverlayScaffold`'s content inset — a full-bleed
            // banner cannot start 24dp from the edge — so the rails apply it themselves, plus
            // room for a focused card's ring.
            contentPadding = PaddingValues(horizontal = TV_CONTENT_INSET, vertical = 8.dp),
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
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .width(width)
            .clip(shape)
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.surfaceVariant
                    playing -> MaterialTheme.colorScheme.surface
                    else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                },
            )
            .border(
                // Thicker than the app's usual ring, as on the filmstrip: this one is read
                // across a room, against moving video rather than a settled surface (NFR-UX-02).
                width = if (focused) 3.dp else 1.dp,
                color = when {
                    focused -> MaterialTheme.colorScheme.primary
                    playing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    else -> Color.Transparent
                },
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            // `clickable` carries the focus target on its own; adding `focusable()` here would
            // cost the remote a second press to leave every tile.
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HomeTileArt(card = card, modifier = Modifier.fillMaxWidth())
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = card.subtitle ?: stringResource(R.string.no_guide_data),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
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
private fun HomeTileArt(card: HomeCard, modifier: Modifier = Modifier) {
    var failed by remember(card.imageUrl) { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
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

/** How much of the banner's width the progress bar takes. Long enough to read, short of a rule. */
private const val BANNER_PROGRESS_FRACTION = 0.45f
