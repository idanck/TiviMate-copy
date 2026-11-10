package com.idanck.shidur.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.update.UpdateBlocker
import com.idanck.shidur.data.update.UpdateState
import com.idanck.shidur.ui.settings.messageRes
import com.idanck.shidur.ui.theme.StudioTheme
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
    val startUpdate = rememberUpdateStarter()

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

            // `TvUpdatePrompt`: an announcement, so a display title, and three equal choices —
            // all secondary, with focus on "update now".
            StudioDialog(
                onDismissRequest = { postponed = true },
                title = stringResource(R.string.update_dialog_title),
                icon = StudioIcons.Update,
                display = true,
                buttons = {
                    DialogButton(
                        label = stringResource(R.string.update_now),
                        onClick = {
                            watching = true
                            startUpdate(manifest)
                        },
                        variant = StudioButtonVariant.SECONDARY,
                        icon = StudioIcons.Update,
                        initialFocus = true,
                    )
                    DialogButton(
                        label = stringResource(R.string.update_later),
                        onClick = { postponed = true },
                        variant = StudioButtonVariant.SECONDARY,
                    )
                    DialogButton(
                        label = stringResource(R.string.update_skip),
                        onClick = { updates.skip(manifest) },
                        variant = StudioButtonVariant.SECONDARY,
                    )
                },
            ) {
                Text(
                    text = TimeModel.ltr(
                        stringResource(
                            R.string.update_dialog_summary,
                            manifest.versionName,
                            String.format(Locale.ROOT, "%.1f", megabytes),
                        ),
                    ),
                )
                if (!notes.isNullOrBlank()) {
                    // The canvas's note line: the release notes, in the third ink.
                    Text(text = notes, style = StudioTheme.type.caption, color = StudioTheme.colors.ink3)
                }
            }
        }

        is UpdateState.Downloading, is UpdateState.Verifying, is UpdateState.Installing -> {
            if (!watching) return
            val fraction = (current as? UpdateState.Downloading)?.fraction
            StudioDialog(
                // Not dismissible by a tap outside: the buttons say what the options are.
                onDismissRequest = {},
                dismissOnClickOutside = false,
                title = stringResource(R.string.update_dialog_title),
                icon = StudioIcons.Update,
                display = true,
                buttons = {
                    DialogButton(
                        label = stringResource(R.string.update_hide),
                        onClick = { watching = false },
                        initialFocus = true,
                    )
                },
            ) {
                Text(
                    text = when (current) {
                        is UpdateState.Downloading ->
                            stringResource(R.string.update_downloading, (current.fraction * 100).roundToInt())
                        is UpdateState.Verifying -> stringResource(R.string.update_verifying)
                        else -> stringResource(R.string.update_installing)
                    },
                )
                // Checking the signature and handing over to the installer have no fraction to
                // show, and still have to look like something is happening.
                StudioProgress(
                    fraction = fraction ?: 0f,
                    indeterminate = fraction == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }

        is UpdateState.Failed -> {
            if (!watching) return
            StudioDialog(
                onDismissRequest = { watching = false },
                title = stringResource(R.string.update_dialog_title),
                icon = StudioIcons.Alert,
                danger = true,
                buttons = {
                    // Nothing here is retryable from this dialog, so the terminal state goes
                    // with it: the settings row returns to reporting when it last looked.
                    DialogButton(
                        label = stringResource(R.string.action_ok),
                        onClick = { watching = false; updates.dismiss() },
                        variant = StudioButtonVariant.PRIMARY,
                        initialFocus = true,
                    )
                },
            ) {
                Text(current.error.describe(context))
            }
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
            StudioDialog(
                onDismissRequest = { watching = false },
                title = stringResource(R.string.update_dialog_title),
                // The permission prompt (`TvUpdatePermission`) wears the lock; any other refusal
                // is information.
                icon = if (permission) StudioIcons.Lock else StudioIcons.Info,
                buttons = {
                    if (intent == null) {
                        // The offer is deliberately left standing: the user fixes what the
                        // message named and presses "update" again, from here or from settings.
                        DialogButton(
                            label = stringResource(R.string.action_ok),
                            onClick = { watching = false },
                            variant = StudioButtonVariant.PRIMARY,
                            initialFocus = true,
                        )
                    } else {
                        DialogButton(
                            label = stringResource(R.string.update_unknown_sources),
                            onClick = { openUnknownSources(context, intent) },
                            variant = StudioButtonVariant.PRIMARY,
                            initialFocus = true,
                        )
                        // Only alongside a button that goes somewhere: the plain acknowledgement
                        // shown instead already closes the dialog by itself.
                        DialogButton(label = stringResource(R.string.update_later), onClick = { watching = false })
                    }
                },
            ) {
                Text(text = stringResource(current.blocker.messageRes()))
                if (permission) {
                    Text(
                        text = stringResource(
                            if (intent == null) R.string.update_unknown_sources_manual
                            else R.string.update_unknown_sources_sub,
                        ),
                        style = StudioTheme.type.caption,
                        color = StudioTheme.colors.ink3,
                    )
                }
            }
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
