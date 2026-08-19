package com.tivicopy.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.cache.CacheBudget
import com.tivicopy.player.data.model.Recording
import com.tivicopy.player.data.model.RecordingState
import com.tivicopy.player.ui.common.Badge
import com.tivicopy.player.ui.common.EmptyState
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.SectionHeader
import com.tivicopy.player.ui.common.TwoLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The recordings library and the scheduled list (FR-REC-19..21).
 *
 * Every recording shows its outcome, including the partial ones: a two-hour file with a gap is
 * reported as partial with the reason, never silently filed as if it were complete.
 */
@Composable
fun RecordingsScreen(
    onPlayRecording: (Recording) -> Unit,
    modifier: Modifier = Modifier,
) {
    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var reloadToken by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadToken) {
        recordings = withContext(Dispatchers.IO) { ServiceLocator.dao.recordings() }
    }

    val scheduled = recordings.filter { it.state == RecordingState.SCHEDULED || it.state == RecordingState.RECORDING }
    val finished = recordings.filter { it.state != RecordingState.SCHEDULED && it.state != RecordingState.RECORDING }

    if (recordings.isEmpty()) {
        EmptyState(stringResource(R.string.rec_title), modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (scheduled.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.rec_scheduled)) }
            items(scheduled, key = { it.id }) { recording ->
                RecordingRow(
                    recording = recording,
                    onPrimary = {
                        scope.launch(Dispatchers.IO) {
                            ServiceLocator.recordings.cancel(recording.id)
                            withContext(Dispatchers.Main) { reloadToken++ }
                        }
                    },
                    primaryLabel = stringResource(R.string.action_cancel),
                )
            }
        }
        if (finished.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.rec_completed)) }
            items(finished, key = { it.id }) { recording ->
                RecordingRow(
                    recording = recording,
                    onPrimary = {
                        scope.launch(Dispatchers.IO) {
                            recording.filePath?.let { path ->
                                if (path.startsWith("/")) java.io.File(path).delete()
                            }
                            ServiceLocator.dao.deleteRecording(recording.id)
                            withContext(Dispatchers.Main) { reloadToken++ }
                        }
                    },
                    primaryLabel = stringResource(R.string.action_delete),
                    onClick = { onPlayRecording(recording) },
                )
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    onPrimary: () -> Unit,
    primaryLabel: String,
    onClick: () -> Unit = {},
) {
    FocusableRow(onClick = onClick, contentDescription = recording.title) {
        Column(modifier = Modifier.weight(1f)) {
            TwoLine(
                primary = recording.title,
                secondary = "${recording.channelName} · ${TimeModel.formatDay(recording.startUtc)} " +
                    "${TimeModel.formatClock(recording.startUtc)}–${TimeModel.formatClock(recording.endUtc)}",
            )
            recording.message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (recording.fileSizeBytes > 0) {
            Text(
                text = CacheBudget.formatBytes(recording.fileSizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Badge(
            text = stringResource(recording.state.label()),
            colour = when (recording.state) {
                RecordingState.COMPLETED -> MaterialTheme.colorScheme.primary
                RecordingState.RECORDING -> MaterialTheme.colorScheme.error
                RecordingState.PARTIAL -> MaterialTheme.colorScheme.secondary
                RecordingState.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            },
        )
        TextButton(onClick = onPrimary) { Text(primaryLabel) }
    }
}

private fun RecordingState.label(): Int = when (this) {
    RecordingState.SCHEDULED -> R.string.rec_state_scheduled
    RecordingState.RECORDING -> R.string.rec_state_recording
    RecordingState.COMPLETED -> R.string.rec_state_completed
    RecordingState.PARTIAL -> R.string.rec_state_partial
    RecordingState.FAILED -> R.string.rec_state_failed
    RecordingState.CANCELLED -> R.string.rec_state_cancelled
}
