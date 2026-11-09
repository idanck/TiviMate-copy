package com.idanck.shidur.ui.settings

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
import com.idanck.shidur.BuildInfo
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.Outcome
import com.idanck.shidur.core.TextNormalizer
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.builtin.IsraelRadioStations
import com.idanck.shidur.data.news.NewsDigests
import com.idanck.shidur.data.news.NewsSource
import com.idanck.shidur.data.news.SuggestedNewsSource
import com.idanck.shidur.data.news.SuggestedNewsSources
import com.idanck.shidur.data.prefs.AppLocale
import com.idanck.shidur.data.prefs.ParentalGate
import com.idanck.shidur.data.prefs.ParentalPin
import com.idanck.shidur.ui.AppViewModel
import com.idanck.shidur.ui.UiMessage
import com.idanck.shidur.ui.common.ListRow
import com.idanck.shidur.ui.common.PinEntry
import com.idanck.shidur.ui.common.RowTrail
import com.idanck.shidur.ui.common.StudioField
import com.idanck.shidur.ui.common.describe
import com.idanck.shidur.ui.common.keyProbe
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.theme.StudioTheme
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
        editOnPress = true,
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
            icon = if (isRadio) StudioIcons.Radio else StudioIcons.Tv,
            selected = playlist.id == state.activePlaylist?.id,
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
        icon = StudioIcons.Plus,
        chevron = true,
        onClick = onAddSource,
    )

    SettingsRow(
        title = stringResource(R.string.playlist_refresh_now),
        icon = StudioIcons.Restart,
        subtitle = state.lastRefresh?.let {
            stringResource(R.string.refresh_report, it.added, it.removed, it.renamed, it.total)
        },
        onClick = { vm.refreshPlaylist() },
    )
    SettingsRow(
        title = stringResource(R.string.guide_update_now),
        icon = StudioIcons.Update,
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
        style = StudioTheme.type.caption,
        color = StudioTheme.colors.ink3,
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
        keyStep = 1f,
    ) { settings.setRecordingPadding(it.toInt(), s.recordingPostRollMinutes) }
    SettingsSlider(
        title = stringResource(R.string.rec_padding, s.recordingPreRollMinutes, s.recordingPostRollMinutes),
        valueLabel = "${s.recordingPostRollMinutes} min",
        value = s.recordingPostRollMinutes.toFloat(),
        range = 0f..60f,
        keyStep = 1f,
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
        keyStep = 1f,
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
        SettingsRow(title = stringResource(R.string.pin_remove), icon = StudioIcons.Trash) {
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
        style = StudioTheme.type.caption,
        color = StudioTheme.colors.ink3,
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
        // A refused PIN is the field's own error state (`Field`), not a line under a button.
        helper = feedback,
        isError = feedback != null,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
        ),
        editOnPress = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
    SettingsRow(title = stringResource(R.string.action_save), icon = StudioIcons.Check, onClick = onSave)
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
                // The text colour: the key last pressed is a reading, not where the remote is.
                color = StudioTheme.colors.ink,
            )
        }
    }
}

/**
 * The screensaver's news sources (FR-UI-33, canvas `TvSettingsFlashes`): one row per source with
 * its own switch, a field and a row to add another, and a note on what a source is.
 *
 * Empty by default, as the task list's address is (ADR-024): this app is installed on other
 * people's televisions, and which newsroom scrolls past on one is its owner's to choose.
 */
@Composable
fun NewsSourcesSetting(vm: AppViewModel, sources: List<NewsSource>) {
    val settings = ServiceLocator.settings
    val context = LocalContext.current
    val digests by ServiceLocator.news.state.collectAsState()
    SettingsSectionTitle(stringResource(R.string.settings_news_sources))
    sources.forEach { source ->
        ListRow(
            label = NewsDigests.label(source, digests[source.url]),
            sub = source.url,
            trail = RowTrail.TOGGLE,
            toggled = source.enabled,
            onClick = {
                settings.setNewsSourceEnabled(source.url, !source.enabled)
                // A source switched back on is read now rather than at the screensaver's next
                // ten minutes; one switched off simply stops being shown.
                if (!source.enabled) ServiceLocator.appScope.launch { ServiceLocator.news.refresh(force = true) }
            },
            // A long press rather than a third control on every row: removing a source is rare,
            // and on a remote each extra control is another stop on the way down the list.
            onLongClick = {
                settings.removeNewsSource(source.url)
                ServiceLocator.appScope.launch { ServiceLocator.news.refresh(force = false) }
                vm.post(UiMessage(text = context.getString(R.string.settings_news_source_removed)))
            },
        )
        RowDivider()
    }

    var draft by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    StudioField(
        value = draft,
        onValueChange = { draft = it; refused = false },
        label = stringResource(R.string.settings_news_source_url),
        placeholder = "https://…/rss",
        isError = refused,
        helper = if (refused) {
            stringResource(R.string.settings_news_source_refused, NewsSource.MAX_SOURCES)
        } else {
            null
        },
        editOnPress = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
    ListRow(
        label = stringResource(R.string.settings_news_source_add),
        sub = stringResource(R.string.settings_news_source_add_sub),
        icon = StudioIcons.Plus,
        onClick = {
            val url = draft.trim()
            if (!settings.addNewsSource(url)) {
                refused = true
                return@ListRow
            }
            draft = ""
            ServiceLocator.appScope.launch {
                ServiceLocator.news.refresh(force = true)
                // Said now, while the person who typed the address is still looking: otherwise
                // a site's home page pasted in place of its feed fails silently, at night.
                if (ServiceLocator.news.state.value[url] == null) {
                    vm.post(UiMessage(text = context.getString(R.string.settings_news_source_unreadable)))
                }
            }
        },
    )
    RowDivider()

    // The suggestions (ADR-038): checked feeds, offered rather than switched on, so an owner who
    // wants headlines does not have to type a feed address with a remote. Hidden once the list
    // is full, where pressing one could only be refused.
    val suggestions = SuggestedNewsSources.available(sources)
    if (suggestions.isNotEmpty() && sources.size < NewsSource.MAX_SOURCES) {
        SettingsSectionTitle(stringResource(R.string.settings_news_suggested))
        suggestions.forEach { suggestion ->
            val name = stringResource(suggestedNameRes(suggestion))
            ListRow(
                label = name,
                sub = stringResource(
                    when {
                        !suggestion.timed -> R.string.settings_news_suggested_flashes_untimed
                        suggestion.flashes -> R.string.settings_news_suggested_flashes
                        else -> R.string.settings_news_suggested_articles
                    },
                ),
                icon = StudioIcons.Plus,
                onClick = {
                    if (!settings.addNewsSource(suggestion.url)) {
                        vm.post(
                            UiMessage(
                                text = context.getString(R.string.settings_news_source_refused, NewsSource.MAX_SOURCES),
                            ),
                        )
                        return@ListRow
                    }
                    ServiceLocator.appScope.launch { ServiceLocator.news.refresh(force = true) }
                    vm.post(UiMessage(text = context.getString(R.string.settings_news_suggested_added, name)))
                },
            )
            RowDivider()
        }
    }

    Text(
        text = stringResource(R.string.settings_news_sources_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** The name a suggested news source is shown under, in the screen's language. */
private fun suggestedNameRes(source: SuggestedNewsSource): Int = when (source.id) {
    "ynet" -> R.string.news_source_ynet
    "walla" -> R.string.news_source_walla
    "maariv" -> R.string.news_source_maariv
    "i24" -> R.string.news_source_i24
    "n12" -> R.string.news_source_n12
    "israel_hayom" -> R.string.news_source_israel_hayom
    "inn" -> R.string.news_source_inn
    "srugim" -> R.string.news_source_srugim
    else -> R.string.settings_news_sources
}
