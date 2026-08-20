package com.tivicopy.player.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.update.UpdateState
import com.tivicopy.player.ui.settings.messageRes
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Offers an update once one has been found, then reports on the one in flight (FR-UPD-03).
 *
 * Shown by both shells, and by neither of them while the app is in picture-in-picture: a dialog
 * over a floating window would be unreadable and uncloseable. "Later" is remembered for this
 * launch only, "skip" for good — the difference is what makes the prompt safe to show at all.
 *
 * The work itself lives on [ServiceLocator.appScope], so dismissing this dialog hides the
 * progress without abandoning the download.
 */
@Composable
fun UpdatePrompt() {
    val updates = ServiceLocator.updates
    val state by updates.state.collectAsState()
    val context = LocalContext.current

    var postponed by rememberSaveable { mutableStateOf(false) }
    // Only the person who pressed "update" here should be shown its progress; a download the
    // settings screen started has its own progress bar in place.
    var watching by rememberSaveable { mutableStateOf(false) }

    when (val current = state) {
        is UpdateState.Available -> {
            if (postponed || updates.isSkipped(current.manifest)) return
            val manifest = current.manifest
            val megabytes = manifest.sizeBytes / (1024.0 * 1024.0)
            val notes = manifest.notesFor(Locale.getDefault().toLanguageTag())

            AlertDialog(
                onDismissRequest = { postponed = true },
                title = { Text(stringResource(R.string.update_dialog_title)) },
                text = {
                    Column {
                        Text(
                            text = TimeModel.ltr(
                                stringResource(
                                    R.string.update_dialog_summary,
                                    manifest.versionName,
                                    String.format(Locale.ROOT, "%.1f", megabytes),
                                ),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!notes.isNullOrBlank()) {
                            Text(
                                text = notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { updates.skip(manifest) }) {
                            Text(stringResource(R.string.update_skip))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            watching = true
                            ServiceLocator.appScope.launch { updates.downloadAndInstall(manifest) }
                        },
                    ) {
                        Text(stringResource(R.string.update_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { postponed = true }) {
                        Text(stringResource(R.string.update_later))
                    }
                },
            )
        }

        is UpdateState.Downloading, is UpdateState.Verifying, is UpdateState.Installing -> {
            if (!watching) return
            val fraction = (current as? UpdateState.Downloading)?.fraction
            AlertDialog(
                // Not dismissible by a tap outside: the buttons say what the options are.
                onDismissRequest = {},
                title = { Text(stringResource(R.string.update_dialog_title)) },
                text = {
                    Column {
                        Text(
                            text = when (current) {
                                is UpdateState.Downloading ->
                                    stringResource(R.string.update_downloading, (current.fraction * 100).roundToInt())
                                is UpdateState.Verifying -> stringResource(R.string.update_verifying)
                                else -> stringResource(R.string.update_installing)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { watching = false }) {
                        Text(stringResource(R.string.update_hide))
                    }
                },
            )
        }

        is UpdateState.Failed, is UpdateState.Blocked -> {
            if (!watching) return
            val message = when (current) {
                is UpdateState.Blocked -> stringResource(current.blocker.messageRes())
                is UpdateState.Failed -> current.error.describe(context)
                else -> return
            }
            AlertDialog(
                onDismissRequest = { watching = false },
                title = { Text(stringResource(R.string.update_dialog_title)) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { watching = false; updates.dismiss() }) {
                        Text(stringResource(R.string.action_ok))
                    }
                },
            )
        }

        else -> Unit
    }
}
