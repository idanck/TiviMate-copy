package com.tivicopy.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.catchup.CatchupUrlBuilder
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.prefs.localised
import com.tivicopy.player.record.ScheduleResult

/**
 * Programme details with the actions FR-UI-08 lists.
 *
 * The recording actions are where the honesty requirements land: a schedule that the platform
 * cannot guarantee, or a series recording the guide cannot support, says so here, before the
 * user walks away expecting a file (FR-REC-05, FR-REC-17).
 */
@Composable
fun ProgrammeDetailsDialog(
    channel: Channel,
    programme: Programme,
    onDismiss: () -> Unit,
    onWatch: () -> Unit,
    onCatchup: () -> Unit,
    onFavourite: () -> Unit,
    onMessage: (String) -> Unit,
    /** Later programmes on this channel, so a series recording can claim them immediately. */
    upcoming: List<Programme> = emptyList(),
) {
    val now = System.currentTimeMillis()
    val inPast = programme.endUtc < now
    val archiveAvailable = channel.hasCatchup && CatchupUrlBuilder.isWithinWindow(channel, programme.startUtc, now)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(programme.title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${channel.displayName} · ${TimeModel.formatDay(programme.startUtc)} " +
                        "${TimeModel.formatClock(programme.startUtc)}–${TimeModel.formatClock(programme.endUtc)} " +
                        "(${TimeModel.formatDuration(programme.durationMillis)})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                programme.subtitle?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                programme.category?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (programme.seasonNumber != null || programme.episodeNumber != null) {
                    Text(
                        text = "S${programme.seasonNumber ?: 0}E${programme.episodeNumber ?: 0}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                programme.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                if (inPast && channel.hasCatchup && !archiveAvailable) {
                    Text(
                        text = stringResource(R.string.catchup_outside_window),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (inPast && !channel.hasCatchup) {
                    Text(
                        text = stringResource(R.string.catchup_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.rec_uses_connection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (archiveAvailable) {
                    Button(onClick = { onCatchup(); onDismiss() }) {
                        Text(stringResource(R.string.catchup_available))
                    }
                }
                if (!inPast) {
                    Button(onClick = { onWatch(); onDismiss() }) {
                        Text(stringResource(R.string.action_watch))
                    }
                }
                TextButton(onClick = {
                    onMessage(scheduleRecording(channel, programme))
                    onDismiss()
                }) {
                    Text(stringResource(R.string.action_record))
                }
                TextButton(onClick = {
                    onMessage(scheduleSeries(channel, programme, upcoming))
                    onDismiss()
                }) {
                    Text(stringResource(R.string.rec_record_series))
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onFavourite() }) { Text(stringResource(R.string.action_favourite)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

/** Schedules and returns the sentence to show the user, warnings included. */
private fun scheduleRecording(channel: Channel, programme: Programme): String =
    describe(ServiceLocator.recordings.scheduleProgramme(channel, programme), programme.title)

/** FR-REC-04/05: a series the guide cannot describe is refused, not silently accepted. */
private fun scheduleSeries(channel: Channel, programme: Programme, upcoming: List<Programme>): String =
    describe(ServiceLocator.recordings.scheduleSeries(channel, programme, upcoming), programme.title)

private fun describe(result: ScheduleResult, title: String): String {
    // The application context keeps whatever language it was attached with (FR-UI-23).
    val context = ServiceLocator.appContext.localised
    return when (result) {
        is ScheduleResult.Rejected -> context.getString(warningString(result.reason))
        is ScheduleResult.Scheduled -> buildString {
            append(context.getString(R.string.rec_state_scheduled))
            append(": ")
            append(title)
            result.warnings.forEach { warning ->
                append("\n")
                append(context.getString(warningString(warning)))
            }
        }
    }
}

private fun warningString(warning: ScheduleResult.Warning): Int = when (warning) {
    ScheduleResult.Warning.DEVICE_MUST_STAY_ON -> R.string.rec_device_must_stay_on
    ScheduleResult.Warning.EXACT_ALARMS_UNAVAILABLE -> R.string.rec_exact_alarms_unavailable
    ScheduleResult.Warning.CONCURRENCY_LIMIT -> R.string.rec_concurrency
    ScheduleResult.Warning.INSUFFICIENT_SPACE -> R.string.rec_insufficient_space
    ScheduleResult.Warning.LOW_SPACE -> R.string.rec_low_space
    ScheduleResult.Warning.NO_DESTINATION -> R.string.rec_no_destination
    ScheduleResult.Warning.NO_SERIES_METADATA -> R.string.rec_no_series_metadata
}
