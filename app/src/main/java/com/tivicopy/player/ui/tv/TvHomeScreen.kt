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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.text.font.FontWeight
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
import com.tivicopy.player.ui.common.LiveBadge
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.Motion
import com.tivicopy.player.ui.common.focusLift
import com.tivicopy.player.ui.common.ProgrammeProgress
import com.tivicopy.player.ui.common.SectionHeader
import com.tivicopy.player.ui.common.StudioButton
import com.tivicopy.player.ui.common.isLive
import com.tivicopy.player.ui.common.monogram
import com.tivicopy.player.ui.common.monogramColour
import com.tivicopy.player.ui.home.HomeCard
import com.tivicopy.player.ui.home.HomeRail
import com.tivicopy.player.ui.home.HomeRailId
import com.tivicopy.player.ui.home.HomeRails
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.delay
import com.tivicopy.player.ui.common.StudioIcons

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
 * - **The side menu opens over the screen, never beside it.** `TvRoot` draws it on top of the
 *   list, and the list pads for its closed strip's width only (`SideMenu.kt`), so focus crossing
 *   into it cannot reflow every card on the screen.
 */
// `Modifier.focusGroup` is `@ExperimentalFoundationApi` at this Compose version, as
// `combinedClickable` is in `ui/common/Components.kt`. It keeps a D-pad step between rails
// landing at the start of the next one rather than at whichever card happens to be nearest.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvHomeScreen(
    vm: AppViewModel,
    onOpen: (TvOverlay) -> Unit,
    onFullscreen: () -> Unit,
    /**
     * Where the side menu hands the remote back to this screen: whatever it was on when it left
     * for the menu. Every control here notes itself in it as it takes the remote; see
     * [HomeFocusReturn] for why the menu cannot find this screen by itself.
     */
    focusReturn: HomeFocusReturn,
    modifier: Modifier = Modifier,
    /**
     * Whether this is the screen on show. False while it waits, composed but not drawn, under the
     * video it was left for (ADR-048).
     */
    active: Boolean = true,
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
    LaunchedEffect(state.activePlaylist?.id, state.currentChannel?.id, featured, tick, active) {
        // Waiting under the video (ADR-048), a change is left to settle before the rails are built
        // again: zapping through ten channels rebuilds them once, after the last, rather than at
        // every press while the zap card is moving. Coming back, they are brought up to date at
        // once, and rails that come out the same change nothing on screen.
        if (!active) delay(HIDDEN_REBUILD_DELAY_MS)
        val data = vm.loadHomeData()
        rails = HomeRails.build(data, System.currentTimeMillis())
    }

    // Off in Settings (the default) skips the `PackageManager` lookups too, not only the row: an
    // empty list is the same "no strip at all" a device with none of the catalogue installed gets.
    val apps = if (prefs.showHomeApps) rememberHomeApps() else emptyList<HomeAppTile>()

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
    // The menu's fallback when the control the remote left from is no longer drawn: the same
    // first stop the screen opens on. Both are forgotten with the screen, so a later visit never
    // hands the remote to a control that has gone.
    SideEffect { focusReturn.fallback = if (heroIsFocusable) heroFocus else firstCard }
    DisposableEffect(focusReturn) { onDispose { focusReturn.forget() } }

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
                    live = state.nowPlaying.isLive(),
                    height = metrics.homeHeroHeight,
                    backdrop = backdrop,
                    scrim = scrim,
                    startInset = railGutter,
                    watchFocus = heroFocus.takeIf { heroIsFocusable },
                    focusReturn = focusReturn,
                    onFullscreen = onFullscreen,
                    onGuide = { onOpen(TvOverlay.GUIDE) },
                    onFavourite = { vm.setFavourite(it, !it.favourite) },
                )
            }

            if (apps.isNotEmpty()) {
                item(key = "apps") {
                    HomeAppStrip(
                        apps = apps,
                        background = scrim,
                        startInset = railGutter,
                        focusReturn = focusReturn,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            itemsIndexed(rails, key = { _, rail -> rail.id.name }) { index, rail ->
                HomeCardRail(
                    title = titles[rail.id].orEmpty(),
                    // The rail's place in the list: under the banner, and under the apps when
                    // there are any.
                    row = index + if (apps.isEmpty()) 1 else 2,
                    cards = rail.cards,
                    playingChannelId = state.currentChannel?.id,
                    cardWidth = metrics.homeCardWidth,
                    background = scrim,
                    firstCardFocus = firstCard.takeIf { rail.id == rails.firstOrNull()?.id },
                    startInset = railGutter,
                    focusReturn = focusReturn,
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
            showClock = true,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        // The side menu over all of this is `TvRoot`'s, the same one every browse screen has
        // (ADR-035); this screen only keeps its strip's room clear.
    }
}

/**
 * The banner: the live picture, the channel it belongs to, and what is on it — Studio's hero
 * (`docs/design/studio/canvas/Main.dc.html`).
 *
 * The picture itself is not drawn here and never was — `TvRoot` owns the one `PlayerSurface` in
 * the app and, on this screen, leaves it filling the panel. What this composable adds is the
 * gradient that makes words legible over it, and over that, from the top: the live badge with the
 * channel's name and number, the programme's title as a broadcast would set it (the display weight), its
 * time with a progress bar and the minutes left, and a row of actions — watch full screen, the
 * guide, and the channel as a favourite — with what comes next beside them, in Studio's "next"
 * blue.
 *
 * The caption reads the *playing* channel rather than the focused card, so the picture and the
 * words are never about two different channels.
 */
@Composable
private fun HomeBanner(
    channel: Channel?,
    now: Programme?,
    next: Programme?,
    /** Whether the stream is arriving live (rather than catch-up or a recording). */
    live: Boolean,
    height: Dp,
    backdrop: Color,
    scrim: Color,
    /** Room kept clear at the leading edge for the side menu's strip drawn over this. */
    startInset: Dp,
    /** Null when nothing is playing: there is no button to focus, so nothing asks for it. */
    watchFocus: FocusRequester?,
    focusReturn: HomeFocusReturn,
    onFullscreen: () -> Unit,
    onGuide: () -> Unit,
    onFavourite: (Channel) -> Unit,
) {
    val studio = StudioTheme.colors
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

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = startInset, end = TV_CONTENT_INSET, top = BANNER_IDENTITY_TOP, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            if (channel != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    if (live) LiveBadge()
                    Text(
                        text = channel.displayName,
                        style = StudioTheme.type.action.copy(fontWeight = FontWeight.SemiBold),
                        color = studio.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (channel.number > 0) {
                        Text(
                            text = TimeModel.ltr(channel.number.toString()),
                            style = StudioTheme.type.body,
                            color = studio.ink2,
                        )
                    }
                }
            }
            // Crossed, not cut: the banner's title changes on its own when a programme ends or
            // the viewer zaps, and a caption that swaps instantly under a picture that dissolved
            // reads as two different screens. Only the title — the buttons below keep focus, and
            // animating a focused control out from under the remote is how focus gets lost.
            Crossfade(
                targetState = now?.title ?: channel?.displayName ?: stringResource(R.string.no_guide_data),
                animationSpec = tween(Motion.STANDARD, easing = Motion.Ease),
                label = "bannerTitle",
            ) { title ->
                Text(
                    text = title,
                    style = StudioTheme.type.hero,
                    color = studio.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            now?.let {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val meta = StudioTheme.type.body.copy(fontSize = StudioTheme.type.body.fontSize * BANNER_META_SCALE)
                    Text(
                        text = TimeModel.formatClockRange(it.startUtc, it.endUtc),
                        style = meta,
                        color = studio.ink2,
                    )
                    ProgrammeProgress(
                        startUtc = it.startUtc,
                        endUtc = it.endUtc,
                        modifier = Modifier.width(BANNER_PROGRESS_WIDTH),
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
            if (channel != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 7.dp),
                ) {
                    StudioButton(
                        label = stringResource(R.string.home_watch_fullscreen),
                        icon = StudioIcons.Fullscreen,
                        onClick = onFullscreen,
                        modifier = Modifier.notesFocus(focusReturn),
                        focusRequester = watchFocus,
                    )
                    StudioButton(
                        label = stringResource(R.string.nav_guide),
                        icon = StudioIcons.Guide,
                        onClick = onGuide,
                        modifier = Modifier.notesFocus(focusReturn),
                    )
                    StudioButton(
                        label = stringResource(
                            if (channel.favourite) R.string.action_unfavourite else R.string.action_favourite,
                        ),
                        icon = if (channel.favourite) StudioIcons.StarOn else StudioIcons.Star,
                        iconOnly = true,
                        onClick = { onFavourite(channel) },
                        modifier = Modifier.notesFocus(focusReturn),
                    )
                    next?.let {
                        Text(
                            // A bare clock beside Hebrew is isolated, or the layout reorders it.
                            text = stringResource(
                                R.string.home_next_at,
                                TimeModel.ltr(TimeModel.formatClock(it.startUtc)),
                                it.title,
                            ),
                            style = StudioTheme.type.body,
                            color = studio.next,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeCardRail(
    title: String,
    /** The rail's place in the home list; see [HomeTile]. */
    row: Int,
    cards: List<HomeCard>,
    playingChannelId: Long?,
    cardWidth: Dp,
    background: Color,
    firstCardFocus: FocusRequester?,
    startInset: Dp,
    focusReturn: HomeFocusReturn,
    onOpen: (HomeCard) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(background)) {
        SectionHeader(
            text = title,
            topDivider = false,
            // `HomeRails`: the h1 step in ink. A rail title on a banner-led home screen is read
            // from across the room, not scanned down a settings list.
            style = StudioTheme.type.h1,
            color = StudioTheme.colors.ink,
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
            // room for the side menu's closed strip at the leading edge and a focused card's
            // ring.
            contentPadding = PaddingValues(
                start = startInset,
                end = TV_CONTENT_INSET,
                top = 8.dp,
                bottom = 8.dp,
            ),
            // `--st-gap-rail`: 32px at 1080p.
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(cards, key = { _, card -> card.key }) { index, card ->
                HomeTile(
                    card = card,
                    playing = card.channelId != null && card.channelId == playingChannelId,
                    width = cardWidth,
                    row = row,
                    index = index,
                    focusReturn = focusReturn,
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

// `BringIntoViewRequester` is `@ExperimentalFoundationApi` at this Compose version.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeTile(
    card: HomeCard,
    playing: Boolean,
    width: Dp,
    /** The rail's place in the home list, and the card's in its rail. */
    row: Int,
    index: Int,
    focusReturn: HomeFocusReturn,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    // A rebuild can move the card the remote is on: OK on a card puts its channel first under
    // "continue watching" (and in any rail sorted by what was watched last), and the first channel
    // ever played adds that rail above the rest. The lists keep their scroll through it, so the
    // card went off screen with the remote still on it, and the screen showed no focus at all.
    // Wherever the card lands, the lists follow it.
    LaunchedEffect(focused, row, index) { if (focused) bringIntoView.bringIntoView() }
    // Studio's channel card (`HomeRails`): the tile is the card, the programme and its time are
    // captions under it. A window still ahead gets its clock rather than a bar sitting at zero.
    val now = System.currentTimeMillis()
    StudioChannelCard(
        label = card.title,
        imageUrl = card.imageUrl,
        number = card.number,
        now = card.subtitle,
        time = if (card.endUtc > card.startUtc) TimeModel.formatClockRange(card.startUtc, card.endUtc) else null,
        progress = if (card.hasProgress) {
            ((now - card.startUtc).toFloat() / (card.endUtc - card.startUtc).coerceAtLeast(1)).coerceIn(0f, 1f)
        } else {
            null
        },
        onScreen = playing,
        circular = card.circular,
        width = width,
        onClick = onClick,
        modifier = modifier
            .notesFocus(focusReturn)
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { focused = it.isFocused },
    )
}

/**
 * Where on home the remote last was, so the side menu can hand it back there (ADR-035).
 *
 * Home is drawn full-bleed under the menu's strip, so to the D-pad nothing on it lies beside the
 * menu, and a press towards the content finds nothing by itself; `SideMenu` asks this instead.
 * Every control on home notes itself here as it takes the remote ([notesFocus]).
 *
 * 0.14.24 used Compose's own `focusRestorer` for this. At this Compose version (1.7) a restorer
 * remembers only the focus target directly under it, and under a lazy list's `focusGroup` that is
 * the list's own scroll container, never a card: the press focused the container itself, which
 * draws nothing and has no neighbours, and the remote was stuck on home with nothing lit until
 * Back (device report, 2026-10-16). The lit "Home" entry in the menu went the same way.
 */
class HomeFocusReturn {
    private var last: FocusRequester? = null

    /** Where the remote goes when the control it left from is no longer drawn: home's first stop. */
    var fallback: FocusRequester? = null

    fun note(requester: FocusRequester) {
        last = requester
    }

    /** Drops both, with the screen they belong to. */
    fun forget() {
        last = null
        fallback = null
    }

    /**
     * Hands the remote back to home: to the control it left from, or to home's first stop when
     * that one has gone. False when neither can take it, so the press is left to the D-pad.
     */
    fun restore(): Boolean = listOfNotNull(last, fallback).any { requester ->
        // A requester whose control has left the composition — a card scrolled away, a rail
        // rebuilt without it — throws rather than failing quietly. That is the "gone" case, and
        // never worth a crash.
        runCatching { requester.requestFocus() }.isSuccess
    }
}

/**
 * Has this control note itself in [focusReturn] each time it takes the remote. Put it on the
 * control itself, ahead of its `clickable`.
 */
@Composable
internal fun Modifier.notesFocus(focusReturn: HomeFocusReturn): Modifier {
    val requester = remember { FocusRequester() }
    return focusRequester(requester).onFocusChanged { if (it.isFocused) focusReturn.note(requester) }
}

/** How often the rails are rebuilt so a now-caption cannot sit wrong for long. */
private const val REBUILD_INTERVAL_MS = 5L * 60 * 1000

/** How long a change waits, while home waits under the video, before the rails are rebuilt. */
private const val HIDDEN_REBUILD_DELAY_MS = 4_000L

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

/** The banner's progress bar: 520px at 1080p. */
private val BANNER_PROGRESS_WIDTH = 260.dp

/** The banner's time line is 26px at 1080p, against the body's 24. */
private const val BANNER_META_SCALE = 26f / 24f
