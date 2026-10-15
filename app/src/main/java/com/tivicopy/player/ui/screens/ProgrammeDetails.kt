package com.tivicopy.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.catchup.CatchupUrlBuilder
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.record.ScheduleResult
import com.tivicopy.player.ui.common.Badge
import com.tivicopy.player.ui.common.DialogButton
import com.tivicopy.player.ui.common.StudioButtonVariant
import com.tivicopy.player.ui.common.StudioDialog

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
    /** Receives the state to write, not a toggle — see [com.tivicopy.player.ui.AppViewModel.setFavourite]. */
    onFavourite: (Boolean) -> Unit,
    onMessage: (String) -> Unit,
    /** Later programmes on this channel, so a series recording can claim them immediately. */
    upcoming: List<Programme> = emptyList(),
) {
    val now = System.currentTimeMillis()
    val inPast = programme.endUtc < now
    // The dialog is handed a snapshot the shell captured when it opened, and nothing re-reads it
    // while it is up, so the toggle tracks its own state to keep the label truthful.
    var favourite by remember(channel.id) { mutableStateOf(channel.favourite) }
    val archiveAvailable = channel.hasCatchup && CatchupUrlBuilder.isWithinWindow(channel, programme.startUtc, now)

    StudioDialog(
        onDismissRequest = onDismiss,
        title = programme.title,
        buttons = {
            // The one action this sheet is for comes first and takes focus: catching up on what
            // has aired, or watching what has not. The rest are ghosts (`Dialog`).
            if (archiveAvailable) {
                DialogButton(
                    label = stringResource(R.string.catchup_available),
                    onClick = { onCatchup(); onDismiss() },
                    variant = StudioButtonVariant.PRIMARY,
                    initialFocus = true,
                )
            }
            if (!inPast) {
                DialogButton(
                    label = stringResource(R.string.action_watch),
                    onClick = { onWatch(); onDismiss() },
                    variant = StudioButtonVariant.PRIMARY,
                    initialFocus = true,
                )
            }
            DialogButton(
                label = stringResource(R.string.action_record),
                onClick = {
                    onMessage(scheduleRecording(channel, programme))
                    onDismiss()
                },
            )
            DialogButton(
                label = stringResource(R.string.rec_record_series),
                onClick = {
                    onMessage(scheduleSeries(channel, programme, upcoming))
                    onDismiss()
                },
            )
            // The label has to say what the press will do; a fixed "Favourite" left the user
            // unable to tell whether they had just added or removed one.
            DialogButton(
                label = stringResource(if (favourite) R.string.action_unfavourite else R.string.action_favourite),
                onClick = { favourite = !favourite; onFavourite(favourite) },
            )
            DialogButton(label = stringResource(R.string.action_close), onClick = onDismiss)
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                // The channel name may be Hebrew and the rest is numeric, so the numeric part
                // is isolated as one run: otherwise the day, the times and the duration merge
                // into a single left-to-right run and land in the wrong order (NFR-UX-08).
                text = "${channel.displayName} · " + TimeModel.ltr(
                    "${TimeModel.formatDay(programme.startUtc)} " +
                        "${TimeModel.formatClock(programme.startUtc)}–${TimeModel.formatClock(programme.endUtc)} " +
                        "(${TimeModel.formatDuration(programme.durationMillis)})",
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            programme.subtitle?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            // A grey chip, not the cue: a category is never where the remote is (MIGRATION.md §3).
            programme.category?.let { Badge(it) }
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
    
    }
}

/**
 * Schedules and returns the sentence to show the user, warnings included. Also the transport
 * bar's "Record" (ADR-034), so a recording started there says exactly what one started here does.
 */
internal fun scheduleRecording(channel: Channel, programme: Programme): String =
    describe(ServiceLocator.recordings.scheduleProgramme(channel, programme), programme.title)

/** FR-REC-04/05: a series the guide cannot describe is refused, not silently accepted. */
private fun scheduleSeries(channel: Channel, programme: Programme, upcoming: List<Programme>): String =
    describe(ServiceLocator.recordings.scheduleSeries(channel, programme, upcoming), programme.title)

private fun describe(result: ScheduleResult, title: String): String {
    // The application context keeps whatever language it was attached with (FR-UI-23).
    val context = ServiceLocator.locale
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
