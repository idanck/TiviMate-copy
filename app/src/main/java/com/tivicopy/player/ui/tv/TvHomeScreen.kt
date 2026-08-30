package com.tivicopy.player.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.tivicopy.player.ui.common.Motion
import com.tivicopy.player.ui.common.animatedColour
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

    // Room the capsule needs at the leading edge, kept by every row's *content* rather than by
    // padding the list. Padding the list is what the rail used to rely on, and it stopped working
    // the moment the rail stopped being full height: a scrim that halts 64dp short of the panel
    // leaves a column of bare video running down the side of every rail, above and below the
    // capsule. Full-bleed rows with inset content give the capsule something to float on.
    //
    // The collapsed width, and never the expanded one: the rail grows *over* the list, so nothing
    // reflows as focus crosses into it.
    val railGutter = metrics.homeNavRailCollapsedWidth + NAV_RAIL_MARGIN * 2

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

        HomeNavRail(
            destinations = destinations,
            onOpen = onOpen,
            opacity = prefs.overlayOpacity,
            modifier = Modifier.align(Alignment.CenterStart).padding(NAV_RAIL_MARGIN),
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
 * It is a floating capsule rather than a full-height column: wrapped to its own content, centred
 * vertically, rounded and inset from the edge, so the rails and the banner read as one surface
 * with the rail resting on top of them rather than as a screen split into two panes. That is the
 * one piece both explored directions agreed on, and it is why the background here is drawn on a
 * clipped, rounded node instead of a bare rectangle.
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
    /** The viewer's overlay-opacity setting (FR-UI-18), which this obeys like every other panel. */
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalTvMetrics.current
    var expanded by remember { mutableStateOf(false) }
    // The rail slides open rather than snapping. It is drawn over the list, so this width is the
    // one animation in the shell that does re-measure per frame — seven rows of it, and only
    // while focus is crossing in or out.
    val railWidth by animateDpAsState(
        targetValue = if (expanded) metrics.homeNavRailWidth else metrics.homeNavRailCollapsedWidth,
        animationSpec = tween(Motion.STANDARD),
        label = "railWidth",
    )
    Column(
        modifier = modifier
            // Before `focusGroup`, so it observes the group's own focus state: `hasFocus` is true
            // while any item inside is focused, which is exactly when the labels are wanted.
            .onFocusChanged { expanded = it.hasFocus }
            .focusGroup()
            .width(railWidth)
            // The shell's floating-panel look (`TvSurfaces.kt`), reaching for opacity as focus
            // arrives: a rail whose labels are open is being read, and is worth more than the
            // frame of video behind it; a rail merely being passed is not.
            //
            // Both ends are the viewer's own setting rather than constants — this capsule obeys
            // FR-UI-18 like every other panel — with a floor under the focused end alone, which
            // is the only moment there are words on it to read.
            .tvGlassPanel(
                animateFloatAsState(
                    targetValue = if (expanded) {
                        opacity.coerceAtLeast(RAIL_FOCUSED_MIN_OPACITY)
                    } else {
                        opacity
                    },
                    animationSpec = tween(Motion.STANDARD),
                    label = "railOpacity",
                ).value,
            )
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RailHeader(expanded = expanded)
        destinations.forEach { destination ->
            NavRailItem(
                destination = destination,
                expanded = expanded,
                onClick = { destination.overlay?.let(onOpen) },
            )
        }
    }
}

/**
 * The app's name at the top of the rail, drawn only once the rail is open.
 *
 * Its own composable, and not just a `Box` inside the rail's `Column`, for a reason the compiler
 * insists on: inside that `Box` the enclosing `ColumnScope` is still an implicit receiver, so
 * `AnimatedVisibility` resolves to `ColumnScope.AnimatedVisibility` — an extension whose receiver
 * is no longer the innermost one, which does not compile. Here the only scope in sight is the
 * `Box`'s, and the plain overload is what gets called.
 *
 * The height is reserved whether or not the name is drawn. Without that, every destination below
 * would jump down the screen the moment focus entered the rail.
 */
@Composable
private fun RailHeader(expanded: Boolean) {
    Box(
        modifier = Modifier.height(RAIL_HEADER_HEIGHT).padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(Motion.FAST)),
            exit = fadeOut(tween(Motion.FAST)),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            // The app's one focus treatment (`ui/common/Focus.kt`). The pill answers the same two
            // questions every row in every list answers — is this where focus is, is this the one
            // you are on — and used to answer them with its own tint and its own 2dp ring.
            .focusSurface(focused = focused, shape = shape, selected = selected)
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
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(Motion.FAST)),
            exit = fadeOut(tween(Motion.FAST)),
        ) {
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
    /** Room kept clear at the leading edge for the navigation capsule drawn over this. */
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
                    .padding(start = startInset, end = TV_CONTENT_INSET, top = 16.dp, bottom = 16.dp),
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
    val shape = RoundedCornerShape(24.dp)
    val label = stringResource(R.string.home_watch_fullscreen)
    Row(
        modifier = Modifier
            .focusLift(focused, BUTTON_FOCUS_SCALE)
            .clip(shape)
            .background(
                animatedColour(
                    if (focused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                    },
                ),
            )
            // One width, crossing colour. This is the shell's one outlined button rather than a
            // focusable surface — it keeps an edge at rest because it sits over live video, where
            // a plate with no edge bleeds into a bright frame — but focus is carried by the fill
            // going solid, so the ring has no reason to widen and shift the label inside it.
            .border(
                width = 1.dp,
                color = animatedColour(
                    if (focused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                ),
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
            .focusLift(focused)
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

/** Room kept at the top of the navigation rail for the app's name, drawn only when it is open. */
private val RAIL_HEADER_HEIGHT = 44.dp

/** How far the floating navigation capsule sits from the edge of the panel. */
private val NAV_RAIL_MARGIN = 12.dp

/**
 * The least opaque the capsule is drawn while its labels are open.
 *
 * Only the focused end has a floor. Collapsed, the rail is a strip of icons glanced past and the
 * viewer's setting is the whole answer; open, it carries seven words that have to be read off
 * moving video.
 */
private const val RAIL_FOCUSED_MIN_OPACITY = 0.9f

/**
 * How much [com.tivicopy.player.ui.common.SectionHeader] insets its own label.
 *
 * A rail heading and the cards under it are given their leading inset by two different mechanisms
 * — a modifier on the header, content padding on the row — and only one of them is the whole
 * distance. Subtracting this is what keeps the two on one line; before the rails were made
 * full-bleed the same alignment came out of a bare `12.dp` that quietly encoded it.
 */
internal val SECTION_HEADER_OWN_INSET = 12.dp

/** The banner's button lifts less than a card does; it is already the largest control on screen. */
private const val BUTTON_FOCUS_SCALE = 1.04f

/** How much of the banner's width the progress bar takes. Long enough to read, short of a rule. */
private const val BANNER_PROGRESS_FRACTION = 0.45f
