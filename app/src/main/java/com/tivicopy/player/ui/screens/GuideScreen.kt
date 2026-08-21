package com.tivicopy.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.EmptyState
import com.tivicopy.player.ui.common.LocalTvMetrics

// The three dimensions this grid is laid out from now come from `LocalTvMetrics`, which the
// television shell fills in from the real viewport (see `ui/common/TvMetrics.kt`). The touch shell
// leaves the default in place, so the phone's grid is unchanged.

/**
 * The grid guide: channels down, time across (FR-UI-05).
 *
 * Two things keep this responsive on a mid-range TV box with a 500-channel, 7-day guide
 * (FR-UI-07, NFR-PERF-03):
 *
 * - the channel axis is a `LazyColumn`, so only visible rows are composed at all;
 * - programmes are fetched for the **visible time window only**, in one batched query per
 *   distinct EPG offset, and refetched when the window moves — the guide is never held in
 *   memory in full.
 *
 * Scrolling backwards is the same code path as forwards (FR-CU-02): the window simply moves,
 * and past programmes carry an archive badge where the channel offers one.
 */
@Composable
fun GuideScreen(
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    onOpenProgramme: (Channel, Programme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val channels = state.list.channels

    // Window origin, snapped to the half hour so the time axis lines up with the grid.
    var windowStart by remember {
        mutableStateOf(snapToHalfHour(System.currentTimeMillis() - 30 * 60_000L))
    }
    val windowEnd by remember { derivedStateOf { windowStart + WINDOW_HOURS * 60 * 60_000L } }

    val listState = rememberLazyListState()
    val timeScroll = rememberScrollState()
    var programmes by remember { mutableStateOf<Map<Long, List<Programme>>>(emptyMap()) }

    // The visible slice of the channel axis drives the query, so a 500-channel guide costs the
    // same as a 20-channel one.
    //
    // How many rows are visible is asked of the layout rather than assumed. It used to be a
    // constant twelve, which was a guess at a phone's row count and wrong on a television in both
    // directions: too many rows on a small overlay meant querying guide data nothing showed, and
    // too few on a large panel meant every row past the eighteenth rendered "no guide data" while
    // the data sat in the database. That was a correctness bug, not a tuning one.
    val visibleChannels by remember(channels) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val firstVisible = visible.firstOrNull()?.index ?: listState.firstVisibleItemIndex
            val lastVisible = visible.lastOrNull()?.index ?: (firstVisible + FALLBACK_ROWS)
            val first = (firstVisible - PREFETCH_ROWS).coerceAtLeast(0)
            val last = (lastVisible + 1 + PREFETCH_ROWS).coerceAtMost(channels.size)
            if (first >= last) emptyList() else channels.subList(first, last).toList()
        }
    }

    LaunchedEffect(visibleChannels, windowStart) {
        // Replaced rather than accumulated: the map is bounded by what is on screen, so a
        // long scroll through a 500-channel guide cannot grow the heap without limit.
        programmes = if (visibleChannels.isEmpty()) emptyMap()
        else vm.guideWindow(visibleChannels, windowStart, windowEnd)
    }

    Column(modifier = modifier.fillMaxSize()) {
        GuideToolbar(
            windowStart = windowStart,
            onJumpNow = { windowStart = snapToHalfHour(System.currentTimeMillis() - 30 * 60_000L) },
            onShift = { hours -> windowStart += hours * 60 * 60_000L },
        )
        HorizontalDivider()

        if (channels.isEmpty()) {
            EmptyState(stringResource(R.string.empty_no_channels))
            return@Column
        }

        TimeAxis(windowStart = windowStart, scrollState = timeScroll)
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(channels, key = { it.id }) { channel ->
                GuideRow(
                    channel = channel,
                    programmes = programmes[channel.id].orEmpty(),
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    scrollState = timeScroll,
                    onChannelClick = { onPlay(channel) },
                    onProgrammeClick = { onOpenProgramme(channel, it) },
                )
            }
        }
    }
}

@Composable
private fun GuideToolbar(
    windowStart: Long,
    onJumpNow: () -> Unit,
    onShift: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = TimeModel.formatDay(windowStart),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(modifier = Modifier.weight(1f))
        TextButton(onClick = { onShift(-24) }) { Text(stringResource(R.string.guide_shift_back_day)) }
        TextButton(onClick = { onShift(-2) }) { Text(stringResource(R.string.guide_shift_back_hours)) }
        Button(onClick = onJumpNow) { Text(stringResource(R.string.guide_jump_now)) }
        TextButton(onClick = { onShift(2) }) { Text(stringResource(R.string.guide_shift_forward_hours)) }
        TextButton(onClick = { onShift(24) }) { Text(stringResource(R.string.guide_shift_forward_day)) }
    }
}

/**
 * The hour ruler. It shares [scrollState] with every row, so horizontal scrolling stays in
 * lock-step without a custom layout or a nested scroll contract.
 */
@Composable
private fun TimeAxis(windowStart: Long, scrollState: androidx.compose.foundation.ScrollState) {
    val metrics = LocalTvMetrics.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(metrics.guideChannelColumnWidth))
        Row(modifier = Modifier.horizontalScroll(scrollState)) {
            repeat(WINDOW_HOURS) { hour ->
                val at = windowStart + hour * 60 * 60_000L
                Box(
                    modifier = Modifier
                        .width(metrics.guideHourWidth)
                        .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Text(
                        text = TimeModel.formatClock(at),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    programmes: List<Programme>,
    windowStart: Long,
    windowEnd: Long,
    scrollState: androidx.compose.foundation.ScrollState,
    onChannelClick: () -> Unit,
    onProgrammeClick: (Programme) -> Unit,
) {
    val metrics = LocalTvMetrics.current
    Row(modifier = Modifier.fillMaxWidth().height(metrics.guideRowHeight)) {
        Row(
            modifier = Modifier
                .width(metrics.guideChannelColumnWidth)
                // No `focusable()` beside `clickable()`: clickable is focusable in its own right,
                // so the pair produced two focus targets and cost a remote an invisible extra
                // press to cross each row.
                .clickable(onClick = onChannelClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChannelLogo(channel = channel, sizeDp = metrics.rowLogoSize)
            Text(
                text = channel.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Box(modifier = Modifier.horizontalScroll(scrollState)) {
            if (programmes.isEmpty()) {
                // FR-EPG-16: say so, rather than leaving an unexplained empty lane.
                Box(
                    modifier = Modifier
                        .width(metrics.guideHourWidth * WINDOW_HOURS)
                        .height(metrics.guideRowHeight)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(R.string.no_guide_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            } else {
                Row {
                    var cursor = windowStart
                    programmes.filter { it.endUtc > windowStart && it.startUtc < windowEnd }
                        .forEach { programme ->
                            val start = programme.startUtc.coerceAtLeast(windowStart)
                            if (start > cursor) {
                                TimeGap(widthMillis = start - cursor)
                            }
                            val end = programme.endUtc.coerceAtMost(windowEnd)
                            ProgrammeCell(
                                programme = programme,
                                widthMillis = (end - start).coerceAtLeast(60_000L),
                                archived = channel.hasCatchup && programme.endUtc < System.currentTimeMillis(),
                                onClick = { onProgrammeClick(programme) },
                            )
                            cursor = end
                        }
                    if (cursor < windowEnd) TimeGap(widthMillis = windowEnd - cursor)
                }
            }
        }
    }
}

/** Blank space for a stretch of time the guide has no entry for. */
@Composable
private fun TimeGap(widthMillis: Long) {
    val metrics = LocalTvMetrics.current
    Box(
        modifier = Modifier
            .width(millisToDp(widthMillis, metrics.guideHourWidth))
            .height(metrics.guideRowHeight),
    )
}

@Composable
private fun ProgrammeCell(
    programme: Programme,
    widthMillis: Long,
    archived: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val live = now in programme.startUtc until programme.endUtc
    val background = when {
        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
        live -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val metrics = LocalTvMetrics.current
    Column(
        modifier = Modifier
            .width(millisToDp(widthMillis, metrics.guideHourWidth))
            .height(metrics.guideRowHeight)
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(
                2.dp,
                if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            // As in the channel cell above: `clickable` carries the focus target, and pairing it
            // with `focusable()` made every cell cost two D-pad presses to pass.
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = programme.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = TimeModel.formatClock(programme.startUtc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // FR-UI-10: archive availability is badged in the grid itself.
            if (archived) {
                Text(
                    text = stringResource(R.string.catchup_available),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun millisToDp(millis: Long, hourWidth: Dp) = hourWidth * (millis.toFloat() / 3_600_000f)

private fun snapToHalfHour(utcMillis: Long): Long = (utcMillis / 1_800_000L) * 1_800_000L

/** Hours rendered at once; anything wider is reached by scrolling the window, not the layout. */
private const val WINDOW_HOURS = 6

/**
 * How many rows to assume before the list has measured itself.
 *
 * Only ever used for the first composition, when `visibleItemsInfo` is still empty; one frame
 * later the real count replaces it.
 */
private const val FALLBACK_ROWS = 8

/** Prefetch margin either side of what is on screen. */
private const val PREFETCH_ROWS = 6
