package com.tivicopy.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.VirtualGroup
import com.tivicopy.player.ui.common.Badge
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.EmptyState
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.ProgrammeProgress
import com.tivicopy.player.ui.common.TwoLine

/**
 * Groups on one side, channels in the middle, now/next on the other (docs/03 section 3.2).
 *
 * The same composable serves the TV overlay and the phone's Live tab; [compact] drops the
 * group column on narrow screens, where the group filter moves into a chip row instead.
 */
@Composable
fun ChannelListScreen(
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showNowNext: Boolean = true,
) {
    val state by vm.state.collectAsState()
    var previewChannel by remember { mutableStateOf<Channel?>(null) }
    var preview by remember { mutableStateOf<Pair<Programme?, Programme?>>(null to null) }

    LaunchedEffect(previewChannel?.id) {
        val channel = previewChannel
        preview = if (channel == null) null to null else {
            val list = vm.programmesFor(
                channel,
                System.currentTimeMillis() - 60 * 60_000L,
                System.currentTimeMillis() + 6 * 60 * 60_000L,
            )
            val now = list.firstOrNull { System.currentTimeMillis() in it.startUtc until it.endUtc }
            val next = list.firstOrNull { it.startUtc >= (now?.endUtc ?: System.currentTimeMillis()) }
            now to next
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        if (!compact) {
            GroupColumn(
                vm = vm,
                modifier = Modifier.width(220.dp).fillMaxHeight(),
            )
            VerticalDivider()
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (compact) GroupChips(vm)
            val channels = state.list.channels
            if (channels.isEmpty()) {
                EmptyState(
                    text = if (state.list.search.isBlank()) stringResource(R.string.empty_no_channels)
                    else stringResource(R.string.empty_no_results),
                )
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(channels, key = { it.id }) { channel ->
                        ChannelRow(
                            channel = channel,
                            selected = state.currentChannel?.id == channel.id,
                            onClick = { onPlay(channel) },
                            onFocused = { previewChannel = channel },
                            onToggleFavourite = { vm.toggleFavourite(channel) },
                        )
                    }
                }
            }
        }

        if (showNowNext && !compact) {
            VerticalDivider()
            NowNextPane(
                channel = previewChannel ?: state.currentChannel,
                now = preview.first,
                next = preview.second,
                modifier = Modifier.width(280.dp).fillMaxHeight().padding(12.dp),
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    FocusableRow(
        onClick = onClick,
        selected = selected,
        contentDescription = channel.displayName,
        onFocused = onFocused,
    ) {
        Text(
            text = channel.number.takeIf { it > 0 }?.toString().orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        ChannelLogo(channel = channel, sizeDp = 36)
        TwoLine(
            primary = channel.displayName,
            secondary = null,
            modifier = Modifier.weight(1f),
        )
        if (channel.hasCatchup) Badge(text = stringResource(R.string.catchup_available))
        if (channel.locked) Badge(text = "🔒")
        // FR-ORG-07. This was a `★` badge that only reported the state; marking a favourite meant
        // opening the guide, picking a programme and using its dialog. A control on the row is
        // one press on touch, and one step right on a D-pad.
        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (channel.favourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (channel.favourite) R.string.action_unfavourite else R.string.action_favourite,
                ),
                tint = if (channel.favourite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun GroupColumn(vm: AppViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            GroupRow(stringResource(R.string.all_channels), state.list.selectedGroupId == VirtualGroup.ALL) {
                vm.selectGroup(VirtualGroup.ALL)
            }
        }
        item {
            GroupRow(stringResource(R.string.favourites), state.list.selectedGroupId == VirtualGroup.FAVOURITES) {
                vm.selectGroup(VirtualGroup.FAVOURITES)
            }
        }
        item {
            GroupRow(stringResource(R.string.recently_watched), state.list.selectedGroupId == VirtualGroup.RECENT) {
                vm.selectGroup(VirtualGroup.RECENT)
            }
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
        items(state.list.groups, key = { it.id }) { group ->
            GroupRow(group.name, state.list.selectedGroupId == group.id) { vm.selectGroup(group.id) }
        }
    }
}

@Composable
private fun GroupRow(name: String, selected: Boolean, onClick: () -> Unit) {
    FocusableRow(onClick = onClick, selected = selected, contentDescription = name) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Narrow-screen replacement for the group column. */
@Composable
private fun GroupChips(vm: AppViewModel) {
    val state by vm.state.collectAsState()
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            androidx.compose.material3.FilterChip(
                selected = state.list.selectedGroupId == VirtualGroup.ALL,
                onClick = { vm.selectGroup(VirtualGroup.ALL) },
                label = { Text(stringResource(R.string.all_channels)) },
            )
        }
        item {
            androidx.compose.material3.FilterChip(
                selected = state.list.selectedGroupId == VirtualGroup.FAVOURITES,
                onClick = { vm.selectGroup(VirtualGroup.FAVOURITES) },
                label = { Text(stringResource(R.string.favourites)) },
            )
        }
        items(state.list.groups, key = { it.id }) { group ->
            androidx.compose.material3.FilterChip(
                selected = state.list.selectedGroupId == group.id,
                onClick = { vm.selectGroup(group.id) },
                label = { Text(group.name, maxLines = 1) },
            )
        }
    }
}

@Composable
fun NowNextPane(
    channel: Channel?,
    now: Programme?,
    next: Programme?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (channel == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChannelLogo(channel = channel, sizeDp = 48)
            Text(
                text = channel.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (now == null) {
            // FR-EPG-16: an explicit state, never a blank row.
            Text(
                text = stringResource(R.string.no_guide_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = TimeModel.formatClockRange(now.startUtc, now.endUtc),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = now.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ProgrammeProgress(now.startUtc, now.endUtc)
            now.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        next?.let {
            Box(modifier = Modifier.fillMaxWidth()) {
                TwoLine(
                    primary = it.title,
                    secondary = TimeModel.formatClock(it.startUtc),
                    primaryStyle = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
