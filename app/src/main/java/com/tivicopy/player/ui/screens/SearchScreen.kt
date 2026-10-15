package com.tivicopy.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontWeight
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
import com.tivicopy.player.ui.common.ListRow
import com.tivicopy.player.ui.common.MENU_MIN_OPACITY
import com.tivicopy.player.ui.common.panelSurface
import com.tivicopy.player.ui.common.RowTone
import com.tivicopy.player.ui.common.RowTrail
import com.tivicopy.player.ui.common.StudioField
import com.tivicopy.player.ui.common.StudioProgress
import com.tivicopy.player.ui.theme.StudioTheme
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

    // `TvSearch`: the field with the search glyph and no label, then the results under headings
    // in the secondary ink, each a list row with its glyph — and on a television, the panel
    // beside them saying where the search looks (ADR-034).
    val tv = StudioTheme.television
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = if (tv) 24.dp else 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StudioField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.action_search),
                icon = StudioIcons.Search,
                modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
            )
            if (results.running) StudioProgress(fraction = 0f, indeterminate = true, modifier = Modifier.fillMaxWidth())

            if (query.isNotBlank() && !results.running && results.isEmpty) {
                EmptyState(stringResource(R.string.empty_no_results), icon = StudioIcons.Search)
                return@Column
            }

            val now = System.currentTimeMillis()
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (results.channels.isNotEmpty()) {
                    item { ResultHeading(stringResource(R.string.nav_live)) }
                    items(results.channels, key = { "c${it.id}" }) { channel ->
                        FocusableRow(onClick = { onPlay(channel) }, contentDescription = channel.displayName) {
                            ChannelLogo(channel = channel, sizeDp = 32)
                            Text(
                                text = channel.displayName,
                                style = StudioTheme.type.rowLabel,
                                color = StudioTheme.colors.ink,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (results.programmes.isNotEmpty()) {
                    item { ResultHeading(stringResource(R.string.nav_guide)) }
                    items(results.programmes, key = { "p${it.id}" }) { programme ->
                        val airing = programme.startUtc <= now && now < programme.endUtc
                        ListRow(
                            label = programme.title,
                            sub = TimeModel.ltr(TimeModel.formatDayTime(programme.startUtc)),
                            icon = StudioIcons.Guide,
                            // On air is the tally, as everywhere: red is what is on now.
                            trail = if (airing) RowTrail.STATUS else RowTrail.VALUE,
                            tone = if (airing) RowTone.TALLY else RowTone.MUTED,
                            value = if (airing) {
                                stringResource(R.string.playback_live_badge)
                            } else {
                                TimeModel.formatDuration(programme.durationMillis)
                            },
                            // A guide hit used to do nothing when pressed. Resolving the channel it
                            // belongs to turns it into watch, record and catch-up.
                            onClick = {
                                scope.launch {
                                    vm.channelForProgramme(programme)?.let { onOpenProgramme(it, programme) }
                                }
                            },
                        )
                    }
                }
                if (results.vod.isNotEmpty()) {
                    item { ResultHeading(stringResource(R.string.nav_vod)) }
                    items(results.vod, key = { "v${it.id}" }) { item ->
                        ListRow(
                            label = item.name,
                            sub = item.year,
                            icon = StudioIcons.Movie,
                            onClick = { if (item.isSeriesContainer) onOpenSeries(item) else onPlayVod(item) },
                        )
                    }
                }
            }
        }
        if (tv) SearchScopePanel(modifier = Modifier.padding(top = 12.dp, end = 24.dp).width(200.dp))
    }
}

/**
 * `TvSearch`'s "where we search": what the one field looks through, and where the keyboard will
 * be while it is typed into. It explains; nothing in it is a filter (ADR-034).
 */
@Composable
private fun SearchScopePanel(modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Column(
        modifier = modifier
            .panelSurface(opacity = MENU_MIN_OPACITY, shape = MaterialTheme.shapes.large)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.search_scope_title),
            style = type.rowLabel.copy(fontWeight = FontWeight.SemiBold),
            color = studio.ink,
        )
        Text(text = stringResource(R.string.search_scope_body), style = type.meta, color = studio.ink2)
        Text(
            text = stringResource(R.string.search_scope_keyboard),
            style = type.caption.copy(fontWeight = FontWeight.Normal),
            color = studio.ink2,
        )
    }
}

/** A group of results' heading (`TvSearch`'s "guide"): the meta step, semibold, secondary ink. */
@Composable
private fun ResultHeading(text: String) {
    Text(
        text = text,
        style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
        color = StudioTheme.colors.ink2,
        modifier = Modifier.padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 2.dp),
    )
}
