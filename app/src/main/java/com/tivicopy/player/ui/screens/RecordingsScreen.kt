package com.tivicopy.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.cache.CacheBudget
import com.tivicopy.player.data.model.Recording
import com.tivicopy.player.data.model.RecordingState
import com.tivicopy.player.record.RecordingStorage
import com.tivicopy.player.ui.common.EmptyState
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.SectionHeader
import com.tivicopy.player.ui.common.TwoLine
import com.tivicopy.player.ui.theme.StudioTheme
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

    val sectionStyle = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold)
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Studio's screen head (`canvas/Recordings.dc.html`): the title as a channel would set it,
        // and the count of each list beside it.
        item(key = "head") {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.rec_title),
                    style = StudioTheme.type.titleSmall,
                    color = StudioTheme.colors.ink,
                )
                Text(
                    text = stringResource(R.string.rec_summary, scheduled.size, finished.size),
                    style = StudioTheme.type.meta,
                    color = StudioTheme.colors.ink2,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        if (scheduled.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.rec_scheduled), style = sectionStyle, color = StudioTheme.colors.ink2, topDivider = false) }
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
            item { SectionHeader(stringResource(R.string.rec_completed), style = sectionStyle, color = StudioTheme.colors.ink2, topDivider = false) }
            items(finished, key = { it.id }) { recording ->
                RecordingRow(
                    recording = recording,
                    onPrimary = {
                        scope.launch(Dispatchers.IO) {
                            // Deletes the file itself, on a plain path or a SAF tree alike, so a
                            // removed recording does not keep occupying the user's storage.
                            RecordingStorage.delete(ServiceLocator.appContext, recording.filePath)
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
                secondary = "${recording.channelName} · " + TimeModel.ltr(
                    "${TimeModel.formatDay(recording.startUtc)} " +
                        "${TimeModel.formatClock(recording.startUtc)}–${TimeModel.formatClock(recording.endUtc)}",
                ),
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
        val studio = StudioTheme.colors
        StatusPill(
            text = stringResource(recording.state.label()),
            dot = when (recording.state) {
                RecordingState.COMPLETED -> studio.ok
                RecordingState.RECORDING -> studio.tally
                RecordingState.PARTIAL -> studio.cue
                RecordingState.FAILED -> studio.error
                else -> studio.ink3
            },
            textColour = if (recording.state == RecordingState.FAILED) studio.error else studio.ink,
        )
        TextButton(onClick = onPrimary) { Text(primaryLabel) }
    }
}

/**
 * Studio's status pill (`ListRow`'s `status` trail): a quiet `surface` plate with a coloured dot —
 * the tally for recording now, `ok` for done, the error colour for a failure.
 */
@Composable
private fun StatusPill(text: String, dot: Color, textColour: Color) {
    val studio = StudioTheme.colors
    val shape = MaterialTheme.shapes.extraSmall
    Row(
        modifier = Modifier
            .clip(shape)
            .background(studio.surface)
            .border(1.dp, studio.hairlineSoft, shape)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(dot))
        Text(
            text = text,
            style = StudioTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
            color = textColour,
        )
    }
}

/**
 * The one user-facing name per recording state.
 *
 * Package-visible rather than private because the guide grid badges the same states (FR-UI-10),
 * and two hand-written maps are exactly how the two surfaces would drift apart.
 */
@androidx.annotation.StringRes
internal fun RecordingState.label(): Int = when (this) {
    RecordingState.SCHEDULED -> R.string.rec_state_scheduled
    RecordingState.RECORDING -> R.string.rec_state_recording
    RecordingState.COMPLETED -> R.string.rec_state_completed
    RecordingState.PARTIAL -> R.string.rec_state_partial
    RecordingState.FAILED -> R.string.rec_state_failed
    RecordingState.CANCELLED -> R.string.rec_state_cancelled
}
