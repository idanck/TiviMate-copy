package com.tivicopy.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.tivicopy.player.data.model.VodItem
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.EmptyState
import com.tivicopy.player.ui.common.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** VOD and series browsing with artwork and a continue-watching row (FR-PLB-28/29). */
@Composable
fun VodScreen(
    vm: AppViewModel,
    onPlay: (VodItem) -> Unit,
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
                        onPlay = onPlay,
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
                        onPlay = onPlay,
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

@Composable
private fun VodTile(item: VodItem, onPlay: (VodItem) -> Unit, onToggleFavourite: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .focusable()
            .clickable { onPlay(item) },
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
