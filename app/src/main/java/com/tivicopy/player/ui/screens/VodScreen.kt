package com.tivicopy.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.VodItem
import com.tivicopy.player.data.xtream.isSeriesContainer
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.EmptyState
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.SectionHeader
import com.tivicopy.player.ui.common.StudioChip
import com.tivicopy.player.ui.common.StudioField
import com.tivicopy.player.ui.common.StudioProgress
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tivicopy.player.ui.common.StudioIcons

/**
 * VOD and series browsing with artwork and a continue-watching row (FR-PLB-28/29).
 *
 * A series tile opens its episodes rather than playing: a series is a container with no stream of
 * its own, and handing its id to the player is the defect this screen was built to close.
 *
 * [openSeriesRequest] lets a search result open the same list the grid opens, so there is one
 * episode list in the app rather than one per route into it.
 */
@Composable
fun VodScreen(
    vm: AppViewModel,
    onPlay: (VodItem) -> Unit,
    modifier: Modifier = Modifier,
    openSeriesRequest: VodItem? = null,
    onSeriesRequestConsumed: () -> Unit = {},
    state: VodScreenState = rememberVodScreenState(),
) {
    // Taken once, and the shell told so it can drop it. The shell cannot see when the list is
    // dismissed, so a request left standing would reopen the series every time this screen came
    // back into composition — on returning from search, or on rotating.
    LaunchedEffect(openSeriesRequest) {
        val requested = openSeriesRequest ?: return@LaunchedEffect
        state.openSeries = requested
        onSeriesRequestConsumed()
    }

    // Registered from composition, which is what makes it outrank the handlers both shells set up
    // before it — the activity's for the TV overlay, `MobileRoot`'s for the phone — for exactly as
    // long as the episode list is open. Back returns to the grid rather than leaving on demand.
    BackHandler(enabled = state.openSeries != null) { state.openSeries = null }

    val series = state.openSeries
    if (series != null) {
        SeriesEpisodes(
            vm = vm,
            series = series,
            onPlay = onPlay,
            onBack = { state.openSeries = null },
            modifier = modifier,
        )
        return
    }

    VodGrid(
        vm = vm,
        onPlay = onPlay,
        onOpenSeries = { state.openSeries = it },
        state = state,
        modifier = modifier,
    )
}

/**
 * What the on-demand screen remembers about how the user left it: the search box, the
 * favourites filter, the open series and the scroll position.
 *
 * It exists to be hoisted. Held inside this screen it survives only what happens *within* the
 * screen (a series opening over the grid); the shells unmount the screen wholesale — a tab
 * switch, the search overlay — and every route back then landed on an empty box, a reset
 * filter and the top of the catalogue. The shell holds one of these and everything survives.
 */
class VodScreenState {
    var query by mutableStateOf("")
    var favouritesOnly by mutableStateOf(false)
    var openSeries by mutableStateOf<VodItem?>(null)
    val gridScroll = LazyGridState()

    /** Bumped after a favourite is toggled, so the grid re-reads without a full screen reload. */
    var revision by mutableIntStateOf(0)
}

/** A [VodScreenState] scoped to the calling composition, for callers with nowhere higher. */
@Composable
fun rememberVodScreenState(): VodScreenState = remember { VodScreenState() }

@Composable
private fun VodGrid(
    vm: AppViewModel,
    onPlay: (VodItem) -> Unit,
    onOpenSeries: (VodItem) -> Unit,
    state: VodScreenState,
    modifier: Modifier = Modifier,
) {
    val app by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var continueWatching by remember { mutableStateOf<List<VodItem>>(emptyList()) }

    // One toggle for every tile on this screen. The write is off the main thread — `docs/11`
    // finding 12 is the standing rule that no composable touches the database directly.
    val toggleFavourite: (VodItem) -> Unit = { item ->
        scope.launch {
            withContext(Dispatchers.IO) {
                ServiceLocator.dao.setVodFavourite(item.id, !item.favourite)
            }
            state.revision++
        }
    }

    LaunchedEffect(app.activePlaylist?.id, state.query, state.favouritesOnly, state.revision) {
        val playlistId = app.activePlaylist?.id
        val loaded = withContext(Dispatchers.IO) {
            val list = if (playlistId == null) emptyList()
            else ServiceLocator.dao.vod(
                playlistId,
                favouritesOnly = state.favouritesOnly,
                // Episodes are hidden from the catalogue because a list of them is noise — but
                // "my favourites" is the one list where a starred episode is the whole point, and
                // without this the star on an episode row wrote a flag nothing could ever show.
                includeEpisodes = state.favouritesOnly,
                search = state.query.takeIf { it.length >= 2 },
            )
            list to ServiceLocator.dao.continueWatching()
        }
        items = loaded.first
        continueWatching = loaded.second
    }

    // One decision, taken once: a container is opened and a stream is played. Every tile on this
    // screen goes through it, including the continue-watching row.
    val open: (VodItem) -> Unit = { if (it.isSeriesContainer) onOpenSeries(it) else onPlay(it) }

    Column(modifier = modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // `TvVod`: a Karantina title with the filters as chips beside it, then the posters.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A phone's title is its top bar's (`PVod`).
            if (StudioTheme.television) {
                Text(
                    text = stringResource(R.string.nav_vod),
                    style = StudioTheme.type.titleSmall,
                    color = StudioTheme.colors.ink,
                )
            }
            StudioChip(
                label = stringResource(R.string.favourites),
                selected = state.favouritesOnly,
                onClick = { state.favouritesOnly = !state.favouritesOnly },
            )
        }
        StudioField(
            value = state.query,
            onValueChange = { state.query = it },
            placeholder = stringResource(R.string.action_search),
            icon = StudioIcons.Search,
            modifier = Modifier.fillMaxWidth(),
        )

        // Continue-watching is not filtered by the database query, so it is dropped by hand while
        // the favourites filter is on — otherwise the grid answers a question nobody asked.
        val resumeRow = if (state.favouritesOnly) emptyList() else continueWatching
        if (items.isEmpty() && resumeRow.isEmpty()) {
            EmptyState(stringResource(R.string.empty_no_results), icon = StudioIcons.Search)
            return@Column
        }

        // Focus is claimed by the grid, not left to Compose's own search — which picks the first
        // focusable in the tree, and that is the search box above. On a remote that meant opening
        // the catalogue popped the on-screen keyboard over it, every time, when what the viewer
        // asked for was to look at the posters. The box is still one Up press away.
        val firstTile = remember { FocusRequester() }
        var claimedFocus by remember { mutableStateOf(false) }
        LaunchedEffect(items.isNotEmpty(), resumeRow.isNotEmpty()) {
            if (claimedFocus || (items.isEmpty() && resumeRow.isEmpty())) return@LaunchedEffect
            claimedFocus = true
            withFrameNanos { }
            runCatching { firstTile.requestFocus() }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 130.dp),
            state = state.gridScroll,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (resumeRow.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(stringResource(R.string.recently_watched))
                }
                itemsIndexed(resumeRow, key = { _, it -> "cw${it.id}" }) { index, item ->
                    VodTile(
                        item = item,
                        onOpen = open,
                        onToggleFavourite = { toggleFavourite(item) },
                        focusRequester = firstTile.takeIf { index == 0 },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(stringResource(R.string.nav_vod))
                }
            }
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                VodTile(
                    item = item,
                    onOpen = open,
                    onToggleFavourite = { toggleFavourite(item) },
                    // Only when there is no continue-watching row above to take it instead.
                    focusRequester = firstTile.takeIf { index == 0 && resumeRow.isEmpty() },
                )
            }
        }
    }
}

/**
 * One series, expanded into its seasons.
 *
 * The list is fetched when the series opens rather than at refresh time, because `get_series_info`
 * is one request per series; see `AppViewModel.refreshEpisodes`.
 */
@Composable
private fun SeriesEpisodes(
    vm: AppViewModel,
    series: VodItem,
    onPlay: (VodItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var episodes by remember(series.id) { mutableStateOf<List<VodItem>>(emptyList()) }
    var loading by remember(series.id) { mutableStateOf(true) }

    LaunchedEffect(series.id) {
        // Whatever a previous visit stored, on screen at once. Without this the screen is a bare
        // progress bar for as long as the panel takes to answer — including when every row is
        // already in the database and the request will only confirm them.
        episodes = vm.storedEpisodes(series)
        loading = true
        episodes = vm.refreshEpisodes(series)
        loading = false
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = StudioIcons.Back,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            if (!series.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = series.posterUrl,
                    contentDescription = null,
                    modifier = Modifier.size(width = 40.dp, height = 60.dp).clip(MaterialTheme.shapes.extraSmall),
                )
            }
            Text(
                text = series.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (!series.plot.isNullOrBlank()) {
            Text(
                text = series.plot,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (loading) StudioProgress(fraction = 0f, indeterminate = true, modifier = Modifier.fillMaxWidth())

        if (!loading && episodes.isEmpty()) {
            EmptyState(stringResource(R.string.vod_no_episodes), icon = StudioIcons.Movie)
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            episodes.groupBy { it.seasonNumber ?: 1 }.forEach { (season, inSeason) ->
                item(key = "season-$season") {
                    SectionHeader(stringResource(R.string.vod_season, season))
                }
                inSeason.forEach { episode ->
                    item(key = episode.id) {
                        EpisodeRow(
                            episode = episode,
                            onPlay = { onPlay(episode) },
                            onToggleFavourite = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        ServiceLocator.dao.setVodFavourite(episode.id, !episode.favourite)
                                    }
                                    // Re-read rather than patched from the row this composition
                                    // captured: two quick taps both compute their new value from
                                    // the same stale flag, and the second patch would leave the
                                    // star disagreeing with the database until the series was
                                    // reopened. The database has no network in front of it.
                                    episodes = vm.storedEpisodes(series)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: VodItem, onPlay: () -> Unit, onToggleFavourite: () -> Unit) {
    FocusableRow(onClick = onPlay, contentDescription = episode.name) {
        val number = episode.episodeNumber
        if (number != null) {
            Text(
                // Isolated: "S01E04" is a strong left-to-right run, and without the isolate it
                // migrates to the wrong side of the Hebrew title beside it (FR-UI-21).
                text = TimeModel.ltr(
                    stringResource(R.string.vod_episode_label, episode.seasonNumber ?: 1, number),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = episode.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (episode.favourite) StudioIcons.StarOn else StudioIcons.Star,
                contentDescription = stringResource(
                    if (episode.favourite) R.string.action_unfavourite else R.string.action_favourite,
                ),
                // A favourite is a full star in white (`star-on`), never the cue: amber marks focus.
                tint = if (episode.favourite) StudioTheme.colors.ink else StudioTheme.colors.ink2,
            )
        }
    }
}

@Composable
private fun VodTile(
    item: VodItem,
    onOpen: (VodItem) -> Unit,
    onToggleFavourite: () -> Unit,
    /** Set on the first tile so the grid can claim focus before the search box does. */
    focusRequester: FocusRequester? = null,
) {
    // This tile was focusable with nothing to show for it — no `onFocusChanged`, no ring, no
    // scale. On a remote the catalogue was a grid of posters with an invisible cursor somewhere
    // in it, which is NFR-UX-02 ("focus is always visible on TV") failing outright.
    var focused by remember { mutableStateOf(false) }
    // The corner every focusable thing in the surrounding shell is cut to — 8dp on a phone, the
    // card's 14dp on a television — rather than a literal that made this the one tile in the
    // catalogue that did not match the rows beside it.
    val shape = RoundedCornerShape(LocalTvMetrics.current.rowCorner)
    Column(
        modifier = Modifier
            // The app's one focus treatment, with no fill: the poster *is* the content, so
            // painting behind it does nothing and painting over it hides what is being chosen.
            // This tile used to ring itself from 0dp to 3dp — moving its own poster inside the
            // clip at the moment it was pressed — and to rest on `Color.Transparent`, which is
            // transparent *black*, so the ring darkened on its way out.
            .focusSurface(focused = focused, shape = shape, fill = FocusFill.NONE)
            .onFocusChanged { focused = it.hasFocus }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            // `clickable` is focusable in its own right; the `focusable()` that used to sit
            // beside it made every tile cost the remote two presses to pass.
            .clickable { onOpen(item) }
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                // `TvVod`'s poster: the card colour inside a faint hairline until the art loads.
                .background(StudioTheme.colors.card)
                .border(1.dp, StudioTheme.colors.hairlineSoft, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (item.posterUrl.isNullOrBlank()) {
                Text(
                    text = item.name.take(2).uppercase(),
                    style = StudioTheme.type.h1,
                    color = StudioTheme.colors.ink3,
                    textAlign = TextAlign.Center,
                )
            } else {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // FR-ORG-07 names VOD alongside channels. The column and the backup file have carried
            // this flag since the first release; nothing could ever set it.
            IconButton(
                onClick = onToggleFavourite,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = if (item.favourite) StudioIcons.StarOn else StudioIcons.Star,
                    contentDescription = stringResource(
                        if (item.favourite) R.string.action_unfavourite else R.string.action_favourite,
                    ),
                    // A favourite is a full star in white (`star-on`), never the cue: amber marks focus.
                    tint = if (item.favourite) StudioTheme.colors.ink else StudioTheme.colors.ink2,
                )
            }
        }
        Text(
            text = item.name,
            style = StudioTheme.type.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = StudioTheme.colors.ink,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}
