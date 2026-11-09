package com.idanck.shidur.ui.screens

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
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.cache.CacheBudget
import com.idanck.shidur.data.model.Recording
import com.idanck.shidur.data.model.RecordingState
import com.idanck.shidur.record.RecordingStorage
import com.idanck.shidur.ui.common.EmptyState
import com.idanck.shidur.ui.common.FocusableRow
import com.idanck.shidur.ui.common.SectionHeader
import com.idanck.shidur.ui.common.TwoLine
import com.idanck.shidur.ui.theme.StudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import com.idanck.shidur.ui.common.StudioButton
import com.idanck.shidur.ui.common.StudioButtonSize
import com.idanck.shidur.ui.common.StudioButtonVariant
import com.idanck.shidur.ui.common.StudioIcons

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
    /**
     * Studio's ten-foot layout (`canvas/Recordings.dc.html`): the list at the start and the
     * recording the remote is on described at the end. Off on a phone.
     */
    twoPane: Boolean = false,
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
        // `Recordings` (empty), `TvRecordingsEmpty`: what the list is for, not only its name.
        EmptyState(
            text = stringResource(R.string.rec_empty_text),
            modifier = modifier,
            icon = StudioIcons.Record,
            title = stringResource(R.string.rec_empty_title),
        )
        return
    }

    val sectionStyle = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold)
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val cancel: (Recording) -> Unit = { recording ->
        scope.launch(Dispatchers.IO) {
            ServiceLocator.recordings.cancel(recording.id)
            withContext(Dispatchers.Main) { reloadToken++ }
        }
    }
    val delete: (Recording) -> Unit = { recording ->
        scope.launch(Dispatchers.IO) {
            // Deletes the file itself, on a plain path or a SAF tree alike, so a removed
            // recording does not keep occupying the user's storage.
            RecordingStorage.delete(ServiceLocator.appContext, recording.filePath)
            ServiceLocator.dao.deleteRecording(recording.id)
            withContext(Dispatchers.Main) { reloadToken++ }
        }
    }
    if (twoPane) {
        Row(modifier = modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RecordingsList(
                scheduled = scheduled,
                finished = finished,
                sectionStyle = sectionStyle,
                onCancel = cancel,
                onDelete = delete,
                onPlayRecording = onPlayRecording,
                onFocused = { selectedId = it.id },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            RecordingDetail(
                recording = recordings.firstOrNull { it.id == selectedId } ?: recordings.first(),
                onPlay = onPlayRecording,
                onCancel = cancel,
                onDelete = delete,
                modifier = Modifier.width(DETAIL_WIDTH).fillMaxHeight(),
            )
        }
        return
    }
    RecordingsList(
        scheduled = scheduled,
        finished = finished,
        sectionStyle = sectionStyle,
        onCancel = cancel,
        onDelete = delete,
        onPlayRecording = onPlayRecording,
        onFocused = {},
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun RecordingsList(
    scheduled: List<Recording>,
    finished: List<Recording>,
    sectionStyle: androidx.compose.ui.text.TextStyle,
    onCancel: (Recording) -> Unit,
    onDelete: (Recording) -> Unit,
    onPlayRecording: (Recording) -> Unit,
    onFocused: (Recording) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(8.dp),
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
                    onPrimary = { onCancel(recording) },
                    primaryLabel = stringResource(R.string.action_cancel),
                    onFocused = { onFocused(recording) },
                )
            }
        }
        if (finished.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.rec_completed), style = sectionStyle, color = StudioTheme.colors.ink2, topDivider = false) }
            items(finished, key = { it.id }) { recording ->
                RecordingRow(
                    recording = recording,
                    onPrimary = { onDelete(recording) },
                    primaryLabel = stringResource(R.string.action_delete),
                    onClick = { onPlayRecording(recording) },
                    onFocused = { onFocused(recording) },
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
    onFocused: () -> Unit = {},
) {
    FocusableRow(onClick = onClick, contentDescription = recording.title, onFocused = onFocused) {
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
        RecordingStatusPill(recording.state)
        StudioButton(
            label = primaryLabel,
            onClick = onPrimary,
            variant = StudioButtonVariant.GHOST,
            size = StudioButtonSize.MEDIUM,
        )
    }
}

@Composable
private fun RecordingStatusPill(state: RecordingState) {
    val studio = StudioTheme.colors
    StatusPill(
        text = stringResource(state.label()),
        dot = when (state) {
            RecordingState.COMPLETED -> studio.ok
            RecordingState.RECORDING -> studio.tally
            RecordingState.PARTIAL -> studio.cue
            RecordingState.FAILED -> studio.error
            else -> studio.ink3
        },
        textColour = if (state == RecordingState.FAILED) studio.error else studio.ink,
    )
}

/**
 * The recording the remote is on, described beside the list (`canvas/Recordings.dc.html`): a
 * plate with its channel where the canvas has a frame of it (the app keeps no thumbnails), its
 * state, the title at the display weight, channel, day, time and length, and what can be done with it.
 */
@Composable
private fun RecordingDetail(
    recording: Recording,
    onPlay: (Recording) -> Unit,
    onCancel: (Recording) -> Unit,
    onDelete: (Recording) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val metrics = com.idanck.shidur.ui.common.LocalTvMetrics.current
    Column(modifier = modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(metrics.controlCorner))
                .background(studio.card)
                .border(1.dp, studio.hairline, androidx.compose.foundation.shape.RoundedCornerShape(metrics.controlCorner)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = recording.channelName,
                style = type.titleSmall,
                color = studio.ink2,
                maxLines = 1,
            )
        }
        RecordingStatusPill(recording.state)
        Text(
            text = recording.title,
            style = type.titleSmall.copy(
                fontSize = type.titleSmall.fontSize * DETAIL_TITLE_SCALE,
                lineHeight = type.titleSmall.fontSize * DETAIL_TITLE_SCALE * 1.15f,
            ),
            color = studio.ink,
            maxLines = 2,
        )
        val minutes = ((recording.endUtc - recording.startUtc) / 60_000L).toInt()
        Text(
            text = recording.channelName + " · " + TimeModel.ltr(
                "${TimeModel.formatDay(recording.startUtc)} " +
                    "${TimeModel.formatClock(recording.startUtc)}–${TimeModel.formatClock(recording.endUtc)}",
            ) + " · " + stringResource(R.string.rec_minutes, minutes),
            style = type.meta,
            color = studio.ink2,
        )
        recording.message?.let { Text(text = it, style = type.caption, color = studio.ink2) }
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val scheduled = recording.state == RecordingState.SCHEDULED || recording.state == RecordingState.RECORDING
            if (!scheduled && !recording.filePath.isNullOrBlank()) {
                StudioButton(
                    label = stringResource(R.string.action_watch),
                    icon = StudioIcons.Play,
                    size = StudioButtonSize.MEDIUM,
                    onClick = { onPlay(recording) },
                )
            }
            StudioButton(
                label = stringResource(if (scheduled) R.string.action_cancel else R.string.action_delete),
                icon = if (scheduled) StudioIcons.Close else StudioIcons.Trash,
                size = StudioButtonSize.MEDIUM,
                onClick = { if (scheduled) onCancel(recording) else onDelete(recording) },
            )
        }
    }
}

/** `canvas/Recordings.dc.html`'s detail column: 640px at 1080p. */
private val DETAIL_WIDTH = 320.dp

/** The detail's title is 88px against the screen head's 64. */
private const val DETAIL_TITLE_SCALE = 88f / 64f

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
