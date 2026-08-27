package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.focus.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
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
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.ProgrammeProgress
import com.tivicopy.player.ui.common.SectionHeader
import com.tivicopy.player.ui.common.monogram
import com.tivicopy.player.ui.common.monogramColour
import com.tivicopy.player.ui.home.HomeCard
import com.tivicopy.player.ui.home.HomeRail
import com.tivicopy.player.ui.home.HomeRailId
import com.tivicopy.player.ui.home.HomeRails
import com.tivicopy.player.ui.screens.NowNextPane
import kotlinx.coroutines.delay

/**
 * The ten-foot home screen (FR-UI-24): a navigation rail, the live picture as a hero, and rails
 * of cards below.
 *
 * This is a change of mind about what the television shell's root is — `docs/03` §3.2 used to
 * say the app should boot into video rather than a dashboard, and ADR-016 records why that no
 * longer holds. The video does not go away: it plays in the hero throughout, docked by the same
 * mechanism the guide uses, so nothing here ever stops a stream.
 *
 * Two rules from the rest of the shell are load-bearing here and are easy to undo by accident:
 *
 * - **No `focusable()` beside `clickable()`.** `clickable` is a focus target on its own, and
 *   pairing them costs the remote an extra press to leave every tile.
 * - **No text field.** On a D-pad surface a text field takes the first focus and pops the
 *   on-screen keyboard over the content. Search is a rail destination, not a box.
 */
@Composable
fun TvHomeScreen(
    vm: AppViewModel,
    onDockBounds: (Rect) -> Unit,
    onOpen: (TvOverlay) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
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
    // here, because the hero is what the docked picture is for. One rule, and the hint line under
    // the hero says it out loud.
    val open: (HomeCard) -> Unit = { card ->
        val channelId = card.channelId
        when {
            channelId == null -> Unit
            channelId == state.currentChannel?.id -> onFullscreen()
            else -> vm.playChannelId(channelId)
        }
    }

    val firstCard = remember { FocusRequester() }
    var claimedFocus by remember { mutableStateOf(false) }
    LaunchedEffect(rails.isNotEmpty()) {
        if (claimedFocus || rails.isEmpty()) return@LaunchedEffect
        claimedFocus = true
        // The target has to be composed before it can be focused; this frame is load-bearing.
        withFrameNanos { }
        runCatching { firstCard.requestFocus() }
    }

    Row(modifier = modifier.fillMaxSize()) {
        HomeNavRail(
            destinations = destinations,
            onOpen = onOpen,
            modifier = Modifier
                .width(metrics.homeNavRailWidth)
                .fillMaxHeight(),
        )
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            HomeHero(
                channel = state.currentChannel,
                now = state.now,
                next = state.next,
                onDockBounds = onDockBounds,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusGroup(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rails, key = { it.id.name }) { rail ->
                    HomeCardRail(
                        title = titles[rail.id].orEmpty(),
                        cards = rail.cards,
                        playingChannelId = state.currentChannel?.id,
                        cardWidth = metrics.homeCardWidth,
                        firstCardFocus = firstCard.takeIf { rail.id == rails.firstOrNull()?.id },
                        onOpen = open,
                    )
                }
            }
        }
    }
}

/** One row of the navigation rail. A null [overlay] is the row for the screen you are on. */
private data class HomeDestination(
    val label: String,
    val icon: ImageVector,
    val overlay: TvOverlay?,
)

/**
 * The vertical navigation rail.
 *
 * Built from [com.tivicopy.player.ui.common.FocusableRow] rather than hand-rolled, so it behaves
 * exactly like the channel list's group column the viewer already knows — same focus ring, same
 * Menu-key handling. Labels are always visible rather than expanding on focus: a rail that
 * changes width reflows every other rail on the screen each time focus crosses into it.
 *
 * On-demand is deliberately absent. This build carries the two bundled Israeli playlists and
 * nothing else (ADR-013, ADR-015), and a destination that opens an empty catalogue is worse
 * than no destination.
 */
@Composable
private fun HomeNavRail(
    destinations: List<HomeDestination>,
    onOpen: (TvOverlay) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .focusGroup()
            .padding(vertical = 16.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        )
        destinations.forEach { destination ->
            FocusableRow(
                onClick = { destination.overlay?.let(onOpen) },
                selected = destination.overlay == null,
                contentDescription = destination.label,
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
}

/**
 * The hero: the live picture, plus what is on it.
 *
 * The picture itself is not drawn here. The slot below is an empty, unpainted box whose position
 * is reported to `TvRoot`, which reshapes the one `PlayerSurface` it owns into it — the same
 * mechanism `PictureInGuide` uses, and the reason nothing re-tunes when the home screen opens.
 * The caption reads the playing channel rather than the focused card, so the picture and the
 * words are never about two different channels.
 */
@Composable
private fun HomeHero(
    channel: Channel?,
    now: Programme?,
    next: Programme?,
    onDockBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalTvMetrics.current
    Row(
        modifier = modifier.padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier = Modifier
                .width(metrics.homeHeroWidth)
                // 16:9, never scaled apart from the width: the picture letterboxes against its
                // own frame the moment the slot stops matching the video's shape.
                .height(metrics.homeHeroWidth * 9 / 16)
                .onGloballyPositioned { coordinates ->
                    val origin: Offset = coordinates.positionInRoot()
                    onDockBounds(
                        Rect(
                            origin.x,
                            origin.y,
                            origin.x + coordinates.size.width,
                            origin.y + coordinates.size.height,
                        ),
                    )
                },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NowNextPane(channel = channel, now = now, next = next, modifier = Modifier.fillMaxWidth())
            // Only once there is something up there. The hint names "the channel playing above",
            // and printing that under an empty slot — which is what a start behaviour that tunes
            // nothing leaves — describes a box that is not there.
            if (channel != null) {
                Text(
                    text = stringResource(R.string.home_fullscreen_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomeCardRail(
    title: String,
    cards: List<HomeCard>,
    playingChannelId: Long?,
    cardWidth: Dp,
    firstCardFocus: FocusRequester?,
    onOpen: (HomeCard) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            text = title,
            topDivider = false,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            // Not TV_CONTENT_INSET: `OverlayScaffold` has already applied it around this whole
            // screen, and applying it again here would double the margin to 48dp. This is only
            // room for a focused card's ring at the start of the row.
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
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
