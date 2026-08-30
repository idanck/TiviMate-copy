package com.tivicopy.player.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.tivicopy.player.data.update.UpdateBlocker
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

    // Coming back from the permission screen with the grant in hand. The user pressed "update
    // now", was told to allow installs first, and did — having to find and press "update now" a
    // second time is a step this can take for them (issue #29).
    //
    // Keyed on the refusal itself, which is a data class: a second, different blocker (metered,
    // no space) reports itself in the dialog instead of restarting this, and the same refusal
    // never restarts it twice. [UpdateManager.downloadAndInstall] re-checks every blocker
    // anyway, so nothing here can start a download the settings say no to.
    val canInstall by updates.canInstall.collectAsState()
    val blockedOnPermission = (state as? UpdateState.Blocked)
        ?.takeIf { it.blocker == UpdateBlocker.INSTALL_PERMISSION }
    LaunchedEffect(blockedOnPermission, canInstall) {
        val manifest = blockedOnPermission?.manifest ?: return@LaunchedEffect
        if (!canInstall) return@LaunchedEffect
        ServiceLocator.appScope.launch { updates.downloadAndInstall(manifest) }
    }

    when (val current = state) {
        is UpdateState.Available -> {
            if (postponed || updates.isSkipped(current.manifest)) return
            val manifest = current.manifest
            val megabytes = manifest.sizeBytes / (1024.0 * 1024.0)
            val notes = manifest.notesFor(Locale.getDefault().toLanguageTag())

            AppDialog(
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
            AppDialog(
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

        is UpdateState.Failed -> {
            if (!watching) return
            AppDialog(
                onDismissRequest = { watching = false },
                title = { Text(stringResource(R.string.update_dialog_title)) },
                text = { Text(current.error.describe(context)) },
                confirmButton = {
                    // Nothing here is retryable from this dialog, so the terminal state goes
                    // with it: the settings row returns to reporting when it last looked.
                    TextButton(onClick = { watching = false; updates.dismiss() }) {
                        Text(stringResource(R.string.action_ok))
                    }
                },
            )
        }

        /*
         * A refusal, and — for the one refusal the app can do something about — the way out of it.
         *
         * "Allow installs from this app first" used to be a dead end: its only button closed the
         * dialog and called `dismiss()`, which also took the offer off the settings screen, so the
         * user had to press "check for updates" again to get back to the same message. Every other
         * blocker names a condition only the user can change (end the recording, join Wi-Fi, free
         * space) and keeps the plain acknowledgement; this one can hand them the screen that
         * grants it, and the offer stays alive either way.
         */
        is UpdateState.Blocked -> {
            if (!watching) return
            val permission = current.blocker == UpdateBlocker.INSTALL_PERMISSION
            // Null on the TV builds that ship no such activity, and below API 26 where the
            // switch is global rather than per app: there the instructions are all we have.
            val intent = if (permission) updates.unknownSourcesIntent() else null
            AppDialog(
                onDismissRequest = { watching = false },
                title = { Text(stringResource(R.string.update_dialog_title)) },
                text = {
                    Column {
                        Text(
                            text = stringResource(current.blocker.messageRes()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (permission) {
                            Text(
                                text = stringResource(
                                    if (intent == null) R.string.update_unknown_sources_manual
                                    else R.string.update_unknown_sources_sub,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    if (intent == null) {
                        // The offer is deliberately left standing: the user fixes what the
                        // message named and presses "update" again, from here or from settings.
                        TextButton(onClick = { watching = false }) {
                            Text(stringResource(R.string.action_ok))
                        }
                    } else {
                        TextButton(onClick = { openUnknownSources(context, intent) }) {
                            Text(stringResource(R.string.update_unknown_sources))
                        }
                    }
                },
                dismissButton = {
                    // Only alongside a confirm button that goes somewhere: the plain
                    // acknowledgement shown instead already closes the dialog by itself.
                    if (intent != null) {
                        TextButton(onClick = { watching = false }) {
                            Text(stringResource(R.string.update_later))
                        }
                    }
                },
            )
        }

        else -> Unit
    }
}

/**
 * Opens the platform's "install unknown apps" screen for this app.
 *
 * Shared by the prompt and the settings row so both survive the same surprise: some TV builds
 * advertise the activity through `resolveActivity` and then refuse to start it. The caller has
 * already shown the manual route beside the button, which is what a refusal falls back to.
 */
internal fun openUnknownSources(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing to report: the instructions are on screen and the offer is still live.
    }
}
