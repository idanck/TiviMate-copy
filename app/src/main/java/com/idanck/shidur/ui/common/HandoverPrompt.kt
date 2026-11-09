package com.idanck.shidur.ui.common

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.idanck.shidur.App
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.AppError
import com.idanck.shidur.data.handover.BridgeHandover
import com.idanck.shidur.data.handover.Handover
import com.idanck.shidur.data.handover.HandoverImporter
import com.idanck.shidur.data.handover.HandoverState
import com.idanck.shidur.data.update.UpdateBlocker
import com.idanck.shidur.data.update.UpdateManager
import com.idanck.shidur.data.update.UpdateState
import com.idanck.shidur.ui.settings.messageRes
import com.idanck.shidur.ui.theme.StudioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The move to the new package (ADR-060), on whichever side of it this build is: the bridge offers
 * the new app and points at it once it holds the data; the new app reports its import and offers
 * to remove the old package. Shown where [UpdatePrompt] is, by both shells.
 */
@Composable
fun HandoverPrompt() {
    if (Handover.isBridge) BridgePrompt() else ImportPrompt()
}

// ---- The bridge ------------------------------------------------------------------------------

@Composable
private fun BridgePrompt() {
    val installer = ServiceLocator.handover ?: return
    val context = LocalContext.current
    val handedOver by BridgeHandover.handedOver.collectAsState()
    val state by installer.state.collectAsState()
    var postponed by rememberSaveable { mutableStateOf(false) }
    // Pressed "move now": from here on the dialog follows the install, whatever the state says.
    var moving by rememberSaveable { mutableStateOf(false) }

    // The platform installs the new app on a screen of its own and says nothing to this one when
    // the user comes back, so the dialog looks for itself while it is up.
    var installed by remember { mutableStateOf(BridgeHandover.isShidurInstalled(context)) }
    if (postponed) return
    LaunchedEffect(Unit) {
        while (true) {
            installed = BridgeHandover.isShidurInstalled(context)
            delay(2_000)
        }
    }

    if (handedOver) {
        StudioDialog(
            onDismissRequest = { postponed = true },
            title = stringResource(R.string.handover_bridge_done_title),
            icon = StudioIcons.Check,
            display = true,
            buttons = {
                if (installed) {
                    DialogButton(
                        label = stringResource(R.string.handover_bridge_open),
                        onClick = { BridgeHandover.openShidur(context) },
                        variant = StudioButtonVariant.PRIMARY,
                        initialFocus = true,
                    )
                }
                DialogButton(
                    label = stringResource(R.string.handover_bridge_remove),
                    onClick = { BridgeHandover.uninstallSelf(context) },
                    variant = StudioButtonVariant.SECONDARY,
                    initialFocus = !installed,
                )
            },
        ) {
            Text(stringResource(R.string.handover_bridge_done_body))
        }
        return
    }

    if (installed) {
        // Installed and not yet opened: the new app takes the data in on its first start.
        StudioDialog(
            onDismissRequest = { postponed = true },
            title = stringResource(R.string.handover_bridge_title),
            icon = StudioIcons.Update,
            display = true,
            buttons = {
                DialogButton(
                    label = stringResource(R.string.handover_bridge_open),
                    onClick = { BridgeHandover.openShidur(context) },
                    variant = StudioButtonVariant.PRIMARY,
                    initialFocus = true,
                )
                DialogButton(label = stringResource(R.string.update_later), onClick = { postponed = true })
            },
        ) {
            Text(stringResource(R.string.handover_bridge_installed_body))
        }
        return
    }

    val start: () -> Unit = {
        moving = true
        ServiceLocator.appScope.launch { startMove(installer) }
    }

    when (val current = state) {
        is UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Verifying, is UpdateState.Installing ->
            if (moving) MoveProgress(current) else MoveOffer(start, onLater = { postponed = true })

        is UpdateState.Failed -> if (moving) {
            MoveProblem(
                message = current.error.describe(context),
                onRetry = start,
                onLater = { moving = false; installer.dismiss(); postponed = true },
            )
        } else {
            MoveOffer(start, onLater = { postponed = true })
        }

        is UpdateState.Blocked -> if (moving) {
            MoveBlocked(installer, current.blocker, onRetry = start, onLater = { moving = false; postponed = true })
        } else {
            MoveOffer(start, onLater = { postponed = true })
        }

        is UpdateState.UpToDate -> if (moving) {
            // The manifest answered and named a version no newer than one already installed —
            // which, with nothing installed, means it named nothing usable.
            MoveProblem(
                message = stringResource(R.string.handover_bridge_not_found),
                onRetry = start,
                onLater = { moving = false; installer.dismiss(); postponed = true },
            )
        } else {
            MoveOffer(start, onLater = { postponed = true })
        }

        else -> MoveOffer(start, onLater = { postponed = true })
    }
}

/** Reads the new app's manifest, then downloads, checks and installs what it names. */
private suspend fun startMove(installer: UpdateManager) {
    val manifest = when (val found = installer.check(force = true)) {
        is com.idanck.shidur.core.Outcome.Ok -> found.value ?: return
        is com.idanck.shidur.core.Outcome.Err -> return
    }
    installer.downloadAndInstall(manifest)
}

@Composable
private fun MoveOffer(onMove: () -> Unit, onLater: () -> Unit) {
    StudioDialog(
        onDismissRequest = onLater,
        title = stringResource(R.string.handover_bridge_title),
        icon = StudioIcons.Update,
        display = true,
        buttons = {
            DialogButton(
                label = stringResource(R.string.handover_bridge_go),
                onClick = onMove,
                variant = StudioButtonVariant.PRIMARY,
                icon = StudioIcons.Update,
                initialFocus = true,
            )
            DialogButton(label = stringResource(R.string.update_later), onClick = onLater)
        },
    ) {
        Text(stringResource(R.string.handover_bridge_body))
    }
}

@Composable
private fun MoveProgress(state: UpdateState) {
    val fraction = (state as? UpdateState.Downloading)?.fraction
    StudioDialog(
        onDismissRequest = {},
        dismissOnClickOutside = false,
        title = stringResource(R.string.handover_bridge_title),
        icon = StudioIcons.Update,
        display = true,
        buttons = {},
    ) {
        Text(
            text = when (state) {
                is UpdateState.Checking -> stringResource(R.string.handover_bridge_checking)
                is UpdateState.Downloading -> stringResource(R.string.update_downloading, (state.fraction * 100).roundToInt())
                is UpdateState.Verifying -> stringResource(R.string.update_verifying)
                else -> stringResource(R.string.update_installing)
            },
        )
        StudioProgress(
            fraction = fraction ?: 0f,
            indeterminate = fraction == null,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun MoveProblem(message: String, onRetry: () -> Unit, onLater: () -> Unit) {
    StudioDialog(
        onDismissRequest = onLater,
        title = stringResource(R.string.handover_bridge_title),
        icon = StudioIcons.Alert,
        danger = true,
        buttons = {
            DialogButton(
                label = stringResource(R.string.action_retry),
                onClick = onRetry,
                variant = StudioButtonVariant.PRIMARY,
                initialFocus = true,
            )
            DialogButton(label = stringResource(R.string.update_later), onClick = onLater)
        },
    ) {
        Text(message)
    }
}

@Composable
private fun MoveBlocked(installer: UpdateManager, blocker: UpdateBlocker, onRetry: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    val permission = blocker == UpdateBlocker.INSTALL_PERMISSION
    val intent = if (permission) installer.unknownSourcesIntent() else null
    // Back from the permission screen with the grant: carry on without a second press. The grant
    // is this app's, and the activity re-reads it on resume through the app's own update manager.
    val canInstall by ServiceLocator.updates.canInstall.collectAsState()
    LaunchedEffect(permission, canInstall) {
        if (permission && canInstall) onRetry()
    }
    StudioDialog(
        onDismissRequest = onLater,
        title = stringResource(R.string.handover_bridge_title),
        icon = if (permission) StudioIcons.Lock else StudioIcons.Info,
        buttons = {
            if (intent != null) {
                DialogButton(
                    label = stringResource(R.string.update_unknown_sources),
                    onClick = { openUnknownSources(context, intent) },
                    variant = StudioButtonVariant.PRIMARY,
                    initialFocus = true,
                )
            } else {
                DialogButton(
                    label = stringResource(R.string.action_retry),
                    onClick = onRetry,
                    variant = StudioButtonVariant.PRIMARY,
                    initialFocus = true,
                )
            }
            DialogButton(label = stringResource(R.string.update_later), onClick = onLater)
        },
    ) {
        Text(stringResource(blocker.messageRes()))
        if (permission) {
            Text(
                text = stringResource(
                    if (intent == null) R.string.update_unknown_sources_manual else R.string.update_unknown_sources_sub,
                ),
                style = StudioTheme.type.caption,
                color = StudioTheme.colors.ink3,
            )
        }
    }
}

// ---- The new app -----------------------------------------------------------------------------

@Composable
private fun ImportPrompt() {
    val importer = ServiceLocator.handoverImporter
    val context = LocalContext.current
    val state by importer.state.collectAsState()

    when (val current = state) {
        is HandoverState.Importing -> StudioDialog(
            onDismissRequest = {},
            dismissOnClickOutside = false,
            title = stringResource(R.string.handover_import_title),
            icon = StudioIcons.Update,
            display = true,
            buttons = {},
        ) {
            Text(stringResource(R.string.handover_importing))
            StudioProgress(fraction = 0f, indeterminate = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }

        is HandoverState.Imported -> {
            val restart = { HandoverImporter.restart(context.applicationContext) }
            StudioDialog(
                onDismissRequest = restart,
                dismissOnClickOutside = false,
                title = stringResource(R.string.handover_imported_title),
                icon = StudioIcons.Check,
                display = true,
                buttons = {
                    DialogButton(
                        label = stringResource(R.string.handover_continue),
                        onClick = restart,
                        variant = StudioButtonVariant.PRIMARY,
                        initialFocus = true,
                    )
                },
            ) {
                val counts = current.result.counts
                Text(
                    stringResource(
                        R.string.handover_imported_summary,
                        counts.playlists,
                        counts.channels,
                        counts.favourites,
                        counts.history,
                        counts.recordings,
                    ),
                )
                if (current.folderToChoose) {
                    Text(
                        stringResource(R.string.handover_imported_folder),
                        style = StudioTheme.type.caption,
                        color = StudioTheme.colors.ink3,
                    )
                }
                if (current.result.unreadableCredentials > 0) {
                    Text(
                        stringResource(R.string.handover_imported_credentials, current.result.unreadableCredentials),
                        style = StudioTheme.type.caption,
                        color = StudioTheme.colors.ink3,
                    )
                }
            }
        }

        is HandoverState.Failed -> ImportFailed(
            error = current.error,
            onRetry = { ServiceLocator.appScope.launch { importer.run() } },
            onNotNow = {
                importer.dismissFailure()
                // This start goes on without the data; the next one tries again.
                App.startBackgroundWork(context)
            },
        )

        HandoverState.Idle -> RemoveLegacyPrompt(importer, context)
    }
}

@Composable
private fun ImportFailed(error: AppError, onRetry: () -> Unit, onNotNow: () -> Unit) {
    val context = LocalContext.current
    StudioDialog(
        onDismissRequest = onNotNow,
        dismissOnClickOutside = false,
        title = stringResource(R.string.handover_failed_title),
        icon = StudioIcons.Alert,
        danger = true,
        buttons = {
            DialogButton(
                label = stringResource(R.string.action_retry),
                onClick = onRetry,
                variant = StudioButtonVariant.PRIMARY,
                initialFocus = true,
            )
            DialogButton(label = stringResource(R.string.handover_not_now), onClick = onNotNow)
        },
    ) {
        Text(stringResource(R.string.handover_failed_body))
        Text(
            text = error.detail ?: error.describe(context),
            style = StudioTheme.type.caption,
            color = StudioTheme.colors.ink3,
        )
    }
}

/** After the import: remove the old package. Offered once, and on one later start if refused. */
@Composable
private fun RemoveLegacyPrompt(importer: HandoverImporter, context: Context) {
    // Decided once per composition of the shell, so answering does not make it flash back.
    var due by rememberSaveable { mutableStateOf(importer.removalPromptDue()) }
    val reminder = remember { importer.removalPromptIsReminder() }
    if (!due) return
    val close = {
        importer.noteRemovalPrompted()
        due = false
    }
    StudioDialog(
        onDismissRequest = close,
        title = stringResource(if (reminder) R.string.handover_remind_title else R.string.handover_remove_title),
        icon = StudioIcons.Info,
        buttons = {
            DialogButton(
                label = stringResource(R.string.handover_remove),
                onClick = {
                    close()
                    runCatching { context.startActivity(importer.removeLegacyIntent()) }
                },
                variant = StudioButtonVariant.PRIMARY,
                initialFocus = true,
            )
            DialogButton(label = stringResource(R.string.handover_not_now), onClick = close)
        },
    ) {
        Text(stringResource(if (reminder) R.string.handover_remind_body else R.string.handover_remove_body))
    }
}
