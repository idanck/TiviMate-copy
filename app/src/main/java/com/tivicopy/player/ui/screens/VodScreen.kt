package com.tivicopy.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) {
    var openSeries by remember { mutableStateOf<VodItem?>(null) }

    LaunchedEffect(openSeriesRequest) { openSeriesRequest?.let { openSeries = it } }

    // Registered from composition, which is what makes it outrank the handlers both shells set up
    // before it — the activity's for the TV overlay, `MobileRoot`'s for the phone — for exactly as
    // long as the episode list is open. Back returns to the grid rather than leaving on demand.
    BackHandler(enabled = openSeries != null) { openSeries = null }

    val series = openSeries
    if (series != null) {
        SeriesEpisodes(
            vm = vm,
            series = series,
            onPlay = onPlay,
            onBack = { openSeries = null },
            modifier = modifier,
        )
        return
    }

    VodGrid(vm = vm, onPlay = onPlay, onOpenSeries = { openSeries = it }, modifier = modifier)
}

@Composable
private fun VodGrid(
    vm: AppViewModel,
    onPlay: (VodItem) -> Unit,
    onOpenSeries: (VodItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var favouritesOnly by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var continueWatching by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    // Bumped after a favourite is toggled, so the grid re-reads without a full screen reload.
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.activePlaylist?.id, query, favouritesOnly, revision) {
        val playlistId = state.activePlaylist?.id
        val loaded = withContext(Dispatchers.IO) {
            val list = if (playlistId == null) emptyList()
            else ServiceLocator.dao.vod(
                playlistId,
                favouritesOnly = favouritesOnly,
                search = query.takeIf { it.length >= 2 },
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
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.action_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FilterChip(
            selected = favouritesOnly,
            onClick = { favouritesOnly = !favouritesOnly },
            label = { Text(stringResource(R.string.favourites)) },
        )

        // Continue-watching is not filtered by the database query, so it is dropped by hand while
        // the favourites filter is on — otherwise the grid answers a question nobody asked.
        val resumeRow = if (favouritesOnly) emptyList() else continueWatching
        if (items.isEmpty() && resumeRow.isEmpty()) {
            EmptyState(stringResource(R.string.empty_no_results))
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 130.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (resumeRow.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(stringResource(R.string.recently_watched))
                }
                items(resumeRow, key = { "cw${it.id}" }) { item ->
                    VodTile(
                        item = item,
                        onOpen = open,
                        onToggleFavourite = {
                            scope.launch {
                                // The write is off the main thread; `docs/11` finding 12 is the
                                // standing rule that no composable touches the database directly.
                                withContext(Dispatchers.IO) {
                                    ServiceLocator.dao.setVodFavourite(item.id, !item.favourite)
                                }
                                revision++
                            }
                        },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(stringResource(R.string.nav_vod))
                }
            }
            items(items, key = { it.id }) { item ->
                    VodTile(
                        item = item,
                        onOpen = open,
                        onToggleFavourite = {
                            scope.launch {
                                // The write is off the main thread; `docs/11` finding 12 is the
                                // standing rule that no composable touches the database directly.
                                withContext(Dispatchers.IO) {
                                    ServiceLocator.dao.setVodFavourite(item.id, !item.favourite)
                                }
                                revision++
                            }
                        },
                    )
                }
        }
    }
}

/**
 * One series, expanded into its seasons.
 *
 * The list is fetched when the series opens rather than at refresh time, because `get_series_info`
 * is one request per series; see `AppViewModel.episodesFor`.
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
        loading = true
        episodes = vm.episodesFor(series)
        loading = false
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            if (!series.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = series.posterUrl,
                    contentDescription = null,
                    modifier = Modifier.size(width = 40.dp, height = 60.dp).clip(RoundedCornerShape(4.dp)),
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

        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        if (!loading && episodes.isEmpty()) {
            EmptyState(stringResource(R.string.vod_no_episodes))
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
                                    // Patched in place rather than re-read: the round trip that
                                    // filled this list begins with a network request, and a star
                                    // does not justify making it again.
                                    episodes = episodes.map {
                                        if (it.id == episode.id) it.copy(favourite = !it.favourite) else it
                                    }
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
                imageVector = if (episode.favourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (episode.favourite) R.string.action_unfavourite else R.string.action_favourite,
                ),
                tint = if (episode.favourite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun VodTile(item: VodItem, onOpen: (VodItem) -> Unit, onToggleFavourite: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .focusable()
            .clickable { onOpen(item) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (item.posterUrl.isNullOrBlank()) {
                Text(
                    text = item.name.take(2).uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    imageVector = if (item.favourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = stringResource(
                        if (item.favourite) R.string.action_unfavourite else R.string.action_favourite,
                    ),
                    tint = if (item.favourite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
