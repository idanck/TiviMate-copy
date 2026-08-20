package com.tivicopy.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.EmptyState
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.SectionHeader
import com.tivicopy.player.ui.common.TwoLine

/** Search across channels and guide entries (FR-UI-09). */
@Composable
fun SearchScreen(
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var programmes by remember { mutableStateOf<List<Programme>>(emptyList()) }

    LaunchedEffect(query) {
        vm.setSearch(query)
        programmes = if (query.length < 2) emptyList() else vm.searchProgrammes(query)
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.action_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        val channels = state.list.channels
        if (query.isNotBlank() && channels.isEmpty() && programmes.isEmpty()) {
            EmptyState(stringResource(R.string.empty_no_results))
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (channels.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.nav_live)) }
                items(channels, key = { "c${it.id}" }) { channel ->
                    FocusableRow(onClick = { onPlay(channel) }, contentDescription = channel.displayName) {
                        ChannelLogo(channel = channel, sizeDp = 32)
                        TwoLine(primary = channel.displayName, secondary = null, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (programmes.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.nav_guide)) }
                items(programmes, key = { "p${it.id}" }) { programme ->
                    FocusableRow(onClick = {}, contentDescription = programme.title) {
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
        }
    }
}
