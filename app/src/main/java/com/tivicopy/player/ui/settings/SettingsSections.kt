package com.tivicopy.player.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tivicopy.player.BuildInfo
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.Outcome
import com.tivicopy.player.core.TextNormalizer
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.builtin.IsraelRadioStations
import com.tivicopy.player.data.prefs.AppLocale
import com.tivicopy.player.data.prefs.ParentalGate
import com.tivicopy.player.data.prefs.ParentalPin
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.UiMessage
import com.tivicopy.player.ui.common.PinEntry
import com.tivicopy.player.ui.common.StudioField
import com.tivicopy.player.ui.common.describe
import com.tivicopy.player.ui.common.keyProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TextSetting(
    title: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    StudioField(
        value = draft,
        onValueChange = { draft = it; onChange(it) },
        label = title,
        placeholder = placeholder,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Playlists: list, refresh, guide update and the per-source refresh interval. */
@Composable
fun SourcesSection(vm: AppViewModel, onAddSource: () -> Unit) {
    val state by vm.state.collectAsState()
    val settings = ServiceLocator.settings
    val s by settings.state.collectAsState()
    val context = LocalContext.current

    SettingsSectionTitle(stringResource(R.string.playlists_title), topDivider = false)
    state.playlists.forEach { playlist ->
        // The radio playlist has its own destination (the Radio tab) and can never become the
        // Channels tab's active playlist (AppViewModel.selectPlaylist), so tapping this row
        // refreshes it in place instead of silently doing nothing.
        val isRadio = playlist.url == IsraelRadioStations.M3U_URL
        SettingsRow(
            title = playlist.name,
            subtitle = if (playlist.lastRefreshUtc == 0L) {
                stringResource(R.string.playlist_never_refreshed)
            } else {
                stringResource(
                    R.string.playlist_last_refresh,
                    TimeModel.formatDayTime(playlist.lastRefreshUtc),
                ) + (playlist.lastRefreshMessage?.let { " · $it" } ?: "")
            },
            onClick = if (isRadio) {
                { vm.refreshPlaylist(playlist.id) }
            } else {
                { vm.selectPlaylist(playlist.id) }
            },
        )
    }
    if (state.playlists.isEmpty()) {
        Text(
            text = stringResource(R.string.setup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }

    // FR-SRC-01: more than one subscription. The add screen otherwise appears only on a first
    // run, when there is no playlist yet, and a second one could never be added.
    SettingsRow(
        title = stringResource(R.string.playlist_add),
        subtitle = stringResource(R.string.playlist_add_hint),
        onClick = onAddSource,
    )

    SettingsRow(
        title = stringResource(R.string.playlist_refresh_now),
        subtitle = state.lastRefresh?.let {
            stringResource(R.string.refresh_report, it.added, it.removed, it.renamed, it.total)
        },
        onClick = { vm.refreshPlaylist() },
    )
    SettingsRow(
        title = stringResource(R.string.guide_update_now),
        subtitle = state.guideProgress?.let {
            stringResource(R.string.guide_updating, it.channels, it.programmes)
        } ?: stringResource(
            R.string.guide_match_summary,
            state.guideStats.matched,
            state.guideStats.totalChannels,
            state.guideStats.matchedExact,
            state.guideStats.matchedName,
            state.guideStats.matchedFuzzy,
            state.guideStats.matchedManual,
        ),
        onClick = { vm.refreshGuide() },
    )
    OffsetRow(
        title = stringResource(R.string.settings_epg_offset),
        minutes = s.globalEpgOffsetMinutes,
    ) { settings.setGlobalEpgOffset(it); vm.refreshNowNext() }

    SettingsSwitch(
        title = stringResource(R.string.interval_daily),
        subtitle = stringResource(R.string.refresh_interval),
        checked = s.epgRefreshIntervalMinutes > 0,
    ) { settings.setEpgRefreshInterval(if (it) 12 * 60 else 0) }

    // Anything left unmatched is one tap away from the manual mapping flow (FR-EPG-09).
    if (state.guideStats.unmatched > 0) {
        SettingsRow(
            title = stringResource(R.string.guide_unmatched, state.guideStats.unmatched),
            subtitle = stringResource(R.string.action_map_guide),
        )
    }
    Text(
        text = TextNormalizer.redactUrl(state.activePlaylist?.epgUrl.orEmpty()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** Recording destination, padding and limits. */
@Composable
fun RecordingSection(vm: AppViewModel) {
    val settings = ServiceLocator.settings
    val s by settings.state.collectAsState()

    // The SAF tree picker is the only path that needs no broad storage permission.
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                ServiceLocator.appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            settings.setRecordingFolder(uri.toString())
        }
    }

    SettingsSectionTitle(stringResource(R.string.settings_recording), topDivider = false)
    SettingsRow(
        title = stringResource(R.string.rec_destination),
        subtitle = s.recordingFolderUri.ifBlank { stringResource(R.string.rec_no_destination) },
        onClick = { picker.launch(null) },
    )
    SettingsSlider(
        title = stringResource(R.string.rec_padding, s.recordingPreRollMinutes, s.recordingPostRollMinutes),
        valueLabel = "${s.recordingPreRollMinutes} min",
        value = s.recordingPreRollMinutes.toFloat(),
        range = 0f..30f,
    ) { settings.setRecordingPadding(it.toInt(), s.recordingPostRollMinutes) }
    SettingsSlider(
        title = stringResource(R.string.rec_padding, s.recordingPreRollMinutes, s.recordingPostRollMinutes),
        valueLabel = "${s.recordingPostRollMinutes} min",
        value = s.recordingPostRollMinutes.toFloat(),
        range = 0f..60f,
    ) { settings.setRecordingPadding(s.recordingPreRollMinutes, it.toInt()) }
    SettingsSlider(
        title = stringResource(R.string.rec_concurrency),
        valueLabel = s.maxConcurrentRecordings.toString(),
        value = s.maxConcurrentRecordings.toFloat(),
        range = 1f..8f,
        steps = 6,
    ) { settings.setMaxConcurrentRecordings(it.toInt()) }
    SettingsSlider(
        title = stringResource(R.string.rec_low_space),
        valueLabel = "${s.recordingFreeSpaceWarnGb} GB",
        value = s.recordingFreeSpaceWarnGb.toFloat(),
        range = 1f..100f,
    ) { settings.setRecordingFreeSpaceWarnGb(it.toInt()) }
    Text(
        text = stringResource(R.string.rec_uses_connection),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
    if (!ServiceLocator.recordings.canScheduleExact()) {
        Text(
            text = stringResource(R.string.rec_exact_alarms_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    Text(
        text = stringResource(R.string.rec_device_must_stay_on),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * PIN setup, lock scope and the documented recovery position (FR-PRF-04/08).
 *
 * Once a PIN exists, loosening anything here asks for it first: the field to change it and the
 * lock-scope switches stay out of reach until the PIN is entered. "Remove PIN" stays reachable
 * either way — it is the recovery for a forgotten PIN, and it takes every lock with it.
 */
@Composable
fun ParentalSection(vm: AppViewModel) {
    val settings = ServiceLocator.settings
    val s by settings.state.collectAsState()
    val unlocked by vm.parentalUnlocked.collectAsState()
    var pin by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    val invalidPinMessage = stringResource(R.string.pin_hint)
    val gated = ParentalGate.parentalChangeNeedsPin(s.parentalPinHash.isNotBlank(), unlocked)

    SettingsSectionTitle(stringResource(R.string.settings_parental), topDivider = false)
    if (gated) {
        PinEntry(vm = vm, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
    } else {
        ParentalPinSetup(
            pin = pin,
            onPin = { pin = it },
            feedback = feedback,
            hasPin = s.parentalPinHash.isNotBlank(),
            onSave = {
                if (ParentalPin.isValidPin(pin)) {
                    vm.setPin(pin)
                    pin = ""
                    feedback = null
                } else {
                    feedback = invalidPinMessage
                }
            },
        )
    }
    if (s.parentalPinHash.isNotBlank()) {
        val scope = rememberCoroutineScope()
        SettingsRow(title = stringResource(R.string.pin_remove)) {
            settings.clearParentalPin()
            // Clearing the PIN also clears every lock, so a forgotten PIN can never make
            // content permanently unreachable.
            scope.launch(Dispatchers.IO) {
                ServiceLocator.dao.allChannels().filter { it.locked }.forEach { channel ->
                    ServiceLocator.dao.setChannelFlags(channel.id, locked = false)
                }
            }
        }
    }
    if (!gated) {
        SettingsSwitch(
            title = stringResource(R.string.pin_lock_settings),
            checked = s.lockSettings,
            onChange = settings::setLockSettings,
        )
        SettingsSwitch(
            title = stringResource(R.string.pin_lock_channels),
            checked = s.lockChannels,
            onChange = settings::setLockChannels,
        )
        SettingsSwitch(
            title = stringResource(R.string.pin_hide_locked),
            checked = s.hideLockedChannels,
            onChange = settings::setHideLockedChannels,
        )
    }
    Text(
        text = stringResource(R.string.pin_recovery),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** The field and Save row that set or change the PIN. */
@Composable
private fun ParentalPinSetup(
    pin: String,
    onPin: (String) -> Unit,
    feedback: String?,
    hasPin: Boolean,
    onSave: () -> Unit,
) {
    StudioField(
        value = pin,
        onValueChange = { onPin(it.filter(Char::isDigit).take(6)) },
        label = if (!hasPin) stringResource(R.string.pin_set) else stringResource(R.string.pin_change),
        placeholder = stringResource(R.string.pin_hint),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
    SettingsRow(title = stringResource(R.string.action_save), subtitle = feedback, onClick = onSave)
}

/** Export and restore through the Storage Access Framework (FR-SET-08). */
@Composable
fun BackupSection(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeCredentials by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val exporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.let { out ->
                    ServiceLocator.backup.export(out, includeCredentials)
                }
            }
            feedback = when (result) {
                is Outcome.Ok -> context.getString(R.string.backup_exported, "${result.value} B")
                is Outcome.Err -> result.error.describe(context)
                null -> context.getString(R.string.err_storage)
            }
        }
    }

    val importer = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.let { input ->
                    ServiceLocator.backup.import(input)
                }
            }
            feedback = when (result) {
                is Outcome.Ok -> context.getString(
                    R.string.backup_imported,
                    result.value.playlists,
                    result.value.groups,
                    result.value.channels,
                )
                is Outcome.Err -> result.error.describe(context)
                null -> context.getString(R.string.err_storage)
            }
            vm.reload()
        }
    }

    SettingsSectionTitle(stringResource(R.string.settings_backup), topDivider = false)
    SettingsSwitch(
        title = stringResource(R.string.backup_include_credentials),
        subtitle = stringResource(R.string.backup_credentials_warning),
        checked = includeCredentials,
        onChange = { includeCredentials = it },
    )
    SettingsRow(title = stringResource(R.string.backup_export), subtitle = feedback) {
        exporter.launch("tivicopy-backup.json")
    }
    SettingsRow(title = stringResource(R.string.backup_import)) {
        importer.launch(arrayOf("application/json", "text/plain", "*/*"))
    }
}

/**
 * Diagnostics (FR-SET-13/14).
 *
 * The app version is deliberately the first line: it is what makes a bug report actionable,
 * and it is the fastest way to confirm which build is actually running on the device.
 */
@Composable
fun DiagnosticsSection(vm: AppViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val status by vm.playbackStatus.collectAsState()
    var guideRows by remember { mutableStateOf(0 to 0) }

    LaunchedEffect(Unit) {
        guideRows = withContext(Dispatchers.IO) {
            ServiceLocator.dao.programmeCount() to ServiceLocator.dao.epgChannelCount()
        }
    }

    SettingsSectionTitle(stringResource(R.string.diag_title), topDivider = false)
    SettingsRow(title = stringResource(R.string.diag_version), subtitle = BuildInfo.short)
    SettingsRow(
        title = stringResource(R.string.diag_stream),
        subtitle = status.currentUrlRedacted ?: "—",
    )
    SettingsRow(
        title = stringResource(R.string.diag_decoders),
        subtitle = "${status.videoDecoderMode} / ${status.audioDecoderMode}" +
            if (status.softwareFallbackApplied) " (software fallback)" else "",
    )
    SettingsRow(
        title = stringResource(
            R.string.diag_video,
            status.videoCodec ?: "—",
            status.width,
            status.height,
            status.frameRate,
        ),
        subtitle = stringResource(R.string.diag_audio, status.audioCodec ?: "—"),
    )
    SettingsRow(
        title = stringResource(R.string.diag_bitrate, status.bitrate / 1000),
        subtitle = stringResource(R.string.diag_dropped, status.droppedFrames) + " · " +
            stringResource(R.string.diag_buffer, status.bufferedMs),
    )
    SettingsRow(
        title = stringResource(R.string.diag_guide),
        subtitle = stringResource(R.string.diag_guide_rows, guideRows.first, guideRows.second),
    )
    SettingsRow(
        title = stringResource(R.string.guide_match_title),
        subtitle = stringResource(
            R.string.guide_match_summary,
            state.guideStats.matched,
            state.guideStats.totalChannels,
            state.guideStats.matchedExact,
            state.guideStats.matchedName,
            state.guideStats.matchedFuzzy,
            state.guideStats.matchedManual,
        ),
    )
    val report = buildDiagnosticReport(context, vm, guideRows)
    val copiedMessage = stringResource(R.string.diag_copied)
    SettingsRow(title = stringResource(R.string.diag_copy_report)) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("diagnostics", report))
        vm.post(UiMessage(text = copiedMessage))
    }
    Text(
        text = report,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(12.dp),
    )
    KeyCodeProbe()
}

/**
 * Builds the shareable report. Every URL goes through [TextNormalizer.redactUrl] first, so a
 * report can be pasted into a support thread without leaking a subscription (NFR-SEC-02).
 */
@Composable
private fun buildDiagnosticReport(
    context: Context,
    vm: AppViewModel,
    guideRows: Pair<Int, Int>,
): String {
    val state by vm.state.collectAsState()
    val status by vm.playbackStatus.collectAsState()
    return buildString {
        append(BuildInfo.full(context))
        // FR-UI-23: what the app is actually speaking, which is the one thing only the device
        // can answer when someone reports that the interface ignored their choice.
        appendLine(
            "Language: ${ServiceLocator.settings.state.value.language}" +
                " · platform ${AppLocale.platformLanguage(context)?.name ?: "—"}" +
                " · resolved ${AppLocale.resolved(context).toLanguageTag()}" +
                " · ${if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "RTL" else "LTR"}",
        )
        appendLine("Playlists: ${state.playlists.size}")
        state.playlists.forEach { p ->
            appendLine("  - ${p.name} [${p.type}] ${TextNormalizer.redactUrl(p.url)} ok=${p.lastRefreshOk}")
        }
        appendLine("Guide: ${guideRows.first} programmes / ${guideRows.second} guide channels")
        appendLine(
            "Match: ${state.guideStats.matched}/${state.guideStats.totalChannels} " +
                "(exact ${state.guideStats.matchedExact}, name ${state.guideStats.matchedName}, " +
                "fuzzy ${state.guideStats.matchedFuzzy}, manual ${state.guideStats.matchedManual})",
        )
        appendLine("Stream: ${status.currentUrlRedacted ?: "—"}")
        appendLine("Video: ${status.videoCodec} ${status.width}x${status.height} @${status.frameRate}")
        appendLine("Audio: ${status.audioCodec}")
        appendLine("Decoders: ${status.videoDecoderMode}/${status.audioDecoderMode} fallback=${status.softwareFallbackApplied}")
        appendLine("Dropped: ${status.droppedFrames}, buffer ${status.bufferedMs} ms, retries ${status.retriesUsed}")
        status.error?.let { appendLine("Last error: ${it.kind} ${it.detail ?: ""}") }
    }
}

/** FR-UI-12: shows the raw code a remote sends, so odd remotes can be bound. */
@Composable
fun KeyCodeProbe() {
    var last by remember { mutableStateOf<Pair<String, Int>?>(null) }
    SettingsSectionTitle(stringResource(R.string.diag_keys))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .keyProbe { name, code -> last = name to code },
    ) {
        Text(
            text = stringResource(R.string.diag_keys_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        last?.let { (name, code) ->
            Text(
                text = stringResource(R.string.diag_last_key, name, code),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
