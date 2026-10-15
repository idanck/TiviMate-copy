package com.tivicopy.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.model.VodItem
import com.tivicopy.player.data.xtream.isSeriesContainer
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.EmptyState
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.SectionHeader
import com.tivicopy.player.ui.common.StudioField
import com.tivicopy.player.ui.common.StudioProgress
import com.tivicopy.player.ui.common.TwoLine
import kotlinx.coroutines.launch
import com.tivicopy.player.ui.common.StudioIcons

/**
 * Search across channels, the guide and on-demand (FR-UI-09).
 *
 * This screen owns its query. It used to write into the shared channel-list filter, which meant
 * closing it left the Live list filtered with nothing on screen saying so, and scoped its own
 * results to whichever group was selected at the time.
 */
@Composable
fun SearchScreen(
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    onOpenProgramme: (Channel, Programme) -> Unit = { _, _ -> },
    onPlayVod: (VodItem) -> Unit = {},
    /**
     * A series result opens its episodes instead of playing, because a series is not a stream.
     * The shells route this to the on-demand screen, which owns the one episode list.
     */
    onOpenSeries: (VodItem) -> Unit = {},
) {
    val results by vm.searchState.collectAsState()
    var query by remember { mutableStateOf(results.query) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) { vm.search(query) }
    // Leaving the screen drops the results, so it does not reopen showing an old answer.
    DisposableEffect(Unit) { onDispose { vm.clearSearch() } }

    Column(modifier = modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioField(
            value = query,
            onValueChange = { query = it },
            label = stringResource(R.string.action_search),
            icon = StudioIcons.Search,
            modifier = Modifier.fillMaxWidth(),
        )
        if (results.running) StudioProgress(fraction = 0f, indeterminate = true, modifier = Modifier.fillMaxWidth())

        if (query.isNotBlank() && !results.running && results.isEmpty) {
            EmptyState(stringResource(R.string.empty_no_results), icon = StudioIcons.Search)
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (results.channels.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.nav_live)) }
                items(results.channels, key = { "c${it.id}" }) { channel ->
                    FocusableRow(onClick = { onPlay(channel) }, contentDescription = channel.displayName) {
                        ChannelLogo(channel = channel, sizeDp = 32)
                        TwoLine(primary = channel.displayName, secondary = null, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (results.programmes.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.nav_guide)) }
                items(results.programmes, key = { "p${it.id}" }) { programme ->
                    FocusableRow(
                        // A guide hit used to do nothing when pressed. Resolving the channel it
                        // belongs to turns it into watch, record and catch-up.
                        onClick = {
                            scope.launch {
                                vm.channelForProgramme(programme)?.let { onOpenProgramme(it, programme) }
                            }
                        },
                        contentDescription = programme.title,
                    ) {
                        TwoLine(
                            primary = programme.title,
                            secondary = TimeModel.formatDayTime(programme.startUtc),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = TimeModel.formatDuration(programme.durationMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (results.vod.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.nav_vod)) }
                items(results.vod, key = { "v${it.id}" }) { item ->
                    val open = { if (item.isSeriesContainer) onOpenSeries(item) else onPlayVod(item) }
                    FocusableRow(onClick = open, contentDescription = item.name) {
                        TwoLine(primary = item.name, secondary = item.year, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
