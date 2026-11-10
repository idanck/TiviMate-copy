package com.idanck.shidur.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.update.UpdateBlocker
import com.idanck.shidur.data.update.UpdateManifest
import com.idanck.shidur.data.update.UpdateState
import com.idanck.shidur.ui.common.StudioButton
import com.idanck.shidur.ui.common.StudioButtonSize
import com.idanck.shidur.ui.common.StudioButtonVariant
import com.idanck.shidur.ui.common.StudioProgress
import com.idanck.shidur.ui.common.describe
import com.idanck.shidur.ui.common.openUnknownSources
import com.idanck.shidur.ui.common.rememberUpdateStarter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The update controls in Settings -> About (FR-UPD-06).
 *
 * Work runs on [ServiceLocator.appScope] rather than a composition scope: a 16 MB download
 * must not be cancelled because the user navigated away from this screen while it ran.
 */
@Composable
fun UpdateSettingsBlock() {
    val updates = ServiceLocator.updates
    val settings = ServiceLocator.settings
    val s by settings.state.collectAsState()
    val state by updates.state.collectAsState()
    val context = LocalContext.current
    val canInstall by updates.canInstall.collectAsState()
    val startUpdate = rememberUpdateStarter()

    SettingsSectionTitle(stringResource(R.string.update_title))

    val busy = state is UpdateState.Checking || state is UpdateState.Downloading ||
        state is UpdateState.Verifying || state is UpdateState.Installing

    SettingsRow(
        title = stringResource(R.string.update_check_now),
        subtitle = statusLine(state, s.updateLastCheckUtc),
        onClick = {
            if (!busy) ServiceLocator.appScope.launch { updates.check(force = true) }
        },
    )

    (state as? UpdateState.Downloading)?.let { downloading ->
        StudioProgress(
            fraction = downloading.fraction,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }

    // A refusal keeps the offer on screen: the user fixes what it named — joins Wi-Fi, ends a
    // recording, grants the permission — and presses the same button again.
    val offered = when (val current = state) {
        is UpdateState.Available -> current.manifest
        is UpdateState.Blocked -> current.manifest
        else -> null
    }
    if (offered != null) {
        UpdateNotes(offered)
        StudioButton(
            label = stringResource(R.string.update_install),
            onClick = { startUpdate(offered) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            variant = StudioButtonVariant.PRIMARY,
            size = StudioButtonSize.MEDIUM,
        )
        StudioButton(
            label = stringResource(R.string.update_skip),
            onClick = { updates.skip(offered) },
            modifier = Modifier.padding(horizontal = 12.dp),
            variant = StudioButtonVariant.GHOST,
            size = StudioButtonSize.MEDIUM,
        )
    }

    // Android will not let one app install another until the user says so once, per app. Watched
    // rather than read once: the grant happens on a screen of the platform's, and the row has to
    // go away when the user comes back with it.
    if (!canInstall) {
        val intent = updates.unknownSourcesIntent()
        SettingsRow(
            title = stringResource(R.string.update_unknown_sources),
            subtitle = stringResource(
                if (intent == null) R.string.update_unknown_sources_manual
                else R.string.update_unknown_sources_sub,
            ),
            onClick = { intent?.let { openUnknownSources(context, it) } },
        )
    }

    SettingsSwitch(
        title = stringResource(R.string.update_auto),
        subtitle = stringResource(R.string.update_auto_sub),
        checked = s.updateAutoCheck,
        onChange = settings::setUpdateAutoCheck,
    )
    SettingsSwitch(
        title = stringResource(R.string.update_wifi_only),
        subtitle = stringResource(R.string.update_wifi_only_sub),
        checked = s.updateWifiOnly,
        onChange = settings::setUpdateWifiOnly,
    )
}

/** Release notes in the interface language, when the build published any. */
@Composable
fun UpdateNotes(manifest: UpdateManifest) {
    val notes = manifest.notesFor(java.util.Locale.getDefault().toLanguageTag()) ?: return
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            text = notes,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The sentence for a refusal, chosen so each one names what to do about it. */
@StringRes
fun UpdateBlocker.messageRes(): Int = when (this) {
    UpdateBlocker.RECORDING_IN_PROGRESS -> R.string.update_blocked_recording
    UpdateBlocker.INSTALL_PERMISSION -> R.string.update_blocked_permission
    UpdateBlocker.METERED -> R.string.update_blocked_metered
    UpdateBlocker.NEEDS_NEWER_ANDROID -> R.string.update_blocked_android
    UpdateBlocker.NO_SPACE -> R.string.update_blocked_space
}

/** One line describing where the update cycle currently is. */
@Composable
fun statusLine(state: UpdateState, lastCheckUtc: Long): String {
    val context = LocalContext.current
    return when (state) {
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.Available -> stringResource(R.string.update_available, state.manifest.versionName)
        is UpdateState.Downloading ->
            stringResource(R.string.update_downloading, (state.fraction * 100).roundToInt())
        is UpdateState.Verifying -> stringResource(R.string.update_verifying)
        is UpdateState.Installing -> stringResource(R.string.update_installing)
        is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
        is UpdateState.Failed -> state.error.describe(context)
        is UpdateState.Blocked -> stringResource(state.blocker.messageRes())
        UpdateState.Idle ->
            if (lastCheckUtc > 0) {
                stringResource(R.string.update_last_check, TimeModel.ltr(TimeModel.formatDayTime(lastCheckUtc)))
            } else {
                stringResource(R.string.update_never_checked)
            }
    }
}
