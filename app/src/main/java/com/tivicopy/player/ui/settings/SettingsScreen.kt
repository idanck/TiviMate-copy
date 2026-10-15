package com.tivicopy.player.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tivicopy.player.BuildInfo
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.cache.CacheBudget
import com.tivicopy.player.data.model.ChannelSort
import com.tivicopy.player.data.model.DecoderMode
import com.tivicopy.player.data.net.UserAgents
import com.tivicopy.player.data.prefs.AppLanguage
import com.tivicopy.player.data.prefs.AppLocale
import com.tivicopy.player.data.prefs.AspectMode
import com.tivicopy.player.ui.common.labelRes
import com.tivicopy.player.data.prefs.BufferPreset
import com.tivicopy.player.data.prefs.ParentalGate
import com.tivicopy.player.data.prefs.ExternalPlayerScope
import com.tivicopy.player.data.prefs.StartBehaviour
import com.tivicopy.player.data.prefs.ThemeMode
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.PinEntry
import com.tivicopy.player.ui.common.findActivity
import com.tivicopy.player.tv.WatchNext
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.theme.StudioTheme
import com.tivicopy.player.ui.common.StudioIcons

enum class SettingsSection { ROOT, SOURCES, PLAYBACK, APPEARANCE, NETWORK, RECORDING, PARENTAL, BACKUP, DIAGNOSTICS, ABOUT }

/**
 * Settings, organised so every screen is reachable with a D-pad alone (FR-UI-01).
 *
 * Nothing here is gated behind a payment: this build is for personal use, so the entitlement
 * split described in `docs/06` is deliberately absent and every feature is simply available
 * (see `docs/adr/ADR-007`).
 */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    section: SettingsSection,
    onNavigate: (SettingsSection) -> Unit,
    onAddSource: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Studio's ten-foot layout (`canvas/Settings.dc.html`): the sections in a panel at the start,
     * always on screen, and the open section beside them. Off on a phone, which has room for one.
     */
    twoPane: Boolean = false,
) {
    val settings = ServiceLocator.settings
    val s by settings.state.collectAsState()
    val context = LocalContext.current

    // FR-PRF-04: "Require the PIN to open Settings" — until 2026-10-13 a switch nothing read.
    val unlocked by vm.parentalUnlocked.collectAsState()
    if (ParentalGate.settingsNeedPin(s.parentalPinHash.isNotBlank(), s.lockSettings, unlocked)) {
        PinEntry(vm = vm, modifier = modifier.fillMaxWidth().padding(24.dp))
        return
    }

    if (twoPane) {
        Row(modifier = modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(
                modifier = Modifier
                    .width(SECTIONS_PANEL_WIDTH)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(LocalTvMetrics.current.panelCorner))
                    .background(StudioTheme.colors.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = StudioTheme.type.titleSmall,
                    color = StudioTheme.colors.ink,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
                )
                SettingsSectionRows(selected = section, onNavigate = onNavigate)
            }
            if (section == SettingsSection.ROOT) {
                // Nothing open yet: the panel is the whole of what there is to choose from.
                Text(
                    text = stringResource(R.string.settings_pick_section),
                    style = StudioTheme.type.meta,
                    color = StudioTheme.colors.ink2,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                SettingsScreen(
                    vm = vm,
                    section = section,
                    onNavigate = onNavigate,
                    onAddSource = onAddSource,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        when (section) {
            SettingsSection.ROOT -> {
                SettingsSectionTitle(stringResource(R.string.settings_title), topDivider = false)
                SettingsSectionRows(selected = null, onNavigate = onNavigate)
            }

            SettingsSection.SOURCES -> SourcesSection(vm, onAddSource)

            SettingsSection.PLAYBACK -> {
                SettingsSectionTitle(stringResource(R.string.settings_playback), topDivider = false)
                SettingsChoice(
                    title = stringResource(R.string.decoder_video),
                    options = decoderOptions(),
                    selected = s.videoDecoder,
                ) { settings.setVideoDecoder(it); vm.onSettingsChanged() }
                SettingsChoice(
                    title = stringResource(R.string.decoder_audio),
                    options = decoderOptions(),
                    selected = s.audioDecoder,
                ) { settings.setAudioDecoder(it); vm.onSettingsChanged() }
                SettingsChoice(
                    title = stringResource(R.string.buffer_size),
                    options = listOf(
                        BufferPreset.MINIMAL to stringResource(R.string.buffer_minimal),
                        BufferPreset.SMALL to stringResource(R.string.buffer_small),
                        BufferPreset.NORMAL to stringResource(R.string.buffer_normal),
                        BufferPreset.LARGE to stringResource(R.string.buffer_large),
                        BufferPreset.CUSTOM to stringResource(R.string.buffer_custom),
                    ),
                    selected = s.bufferPreset,
                ) { settings.setBufferPreset(it); vm.onSettingsChanged() }
                if (s.bufferPreset == BufferPreset.CUSTOM) {
                    // FR-PLB-16 asks for the millisecond values, not only the presets.
                    SettingsSlider(
                        title = stringResource(R.string.buffer_custom_values, s.customBufferMinMs, s.customBufferMaxMs),
                        valueLabel = "${s.customBufferMinMs} ms",
                        value = s.customBufferMinMs.toFloat(),
                        range = 200f..30_000f,
                    ) { settings.setCustomBuffer(it.toInt(), s.customBufferMaxMs); vm.onSettingsChanged() }
                    SettingsSlider(
                        title = stringResource(R.string.buffer_size),
                        valueLabel = "${s.customBufferMaxMs} ms",
                        value = s.customBufferMaxMs.toFloat(),
                        range = 1_000f..120_000f,
                    ) { settings.setCustomBuffer(s.customBufferMinMs, it.toInt()); vm.onSettingsChanged() }
                }
                SettingsChoice(
                    title = stringResource(R.string.aspect_ratio),
                    options = AspectMode.entries.map { it to stringResource(it.labelRes()) },
                    selected = s.aspectMode,
                    onSelect = settings::setAspectMode,
                )
                TextSetting(
                    title = stringResource(R.string.settings_audio_language),
                    value = s.preferredAudioLanguage,
                    placeholder = "en, he, ru…",
                    onChange = settings::setPreferredAudioLanguage,
                )
                SettingsSwitch(
                    title = stringResource(R.string.settings_subtitles_enabled),
                    checked = s.subtitlesEnabled,
                    onChange = settings::setSubtitlesEnabled,
                )
                TextSetting(
                    title = stringResource(R.string.settings_subtitle_language),
                    value = s.preferredSubtitleLanguage,
                    placeholder = "en, he…",
                    onChange = settings::setPreferredSubtitleLanguage,
                )
                SettingsSlider(
                    title = stringResource(R.string.settings_subtitle_scale),
                    valueLabel = "%.1f×".format(s.subtitleTextScale),
                    value = s.subtitleTextScale,
                    range = 0.5f..2.5f,
                    onChange = settings::setSubtitleTextScale,
                )
                SettingsChoice(
                    title = stringResource(R.string.settings_start_behaviour),
                    options = listOf(
                        StartBehaviour.HOME to stringResource(R.string.start_home),
                        StartBehaviour.LAST_CHANNEL to stringResource(R.string.start_last_channel),
                        StartBehaviour.SPECIFIC_CHANNEL to stringResource(R.string.start_specific_channel),
                        StartBehaviour.CHANNEL_LIST to stringResource(R.string.start_channel_list),
                        StartBehaviour.GUIDE to stringResource(R.string.start_guide),
                    ),
                    selected = s.startBehaviour,
                    onSelect = settings::setStartBehaviour,
                )
                SettingsSwitch(
                    title = stringResource(R.string.settings_pip),
                    checked = s.pipEnabled,
                    onChange = settings::setPipEnabled,
                )
                // FR-TVI-02. On by default — that is the feature — but it publishes viewing
                // history to a surface outside the app, so turning it off has to actually take
                // the rows away rather than just stop adding to them.
                SettingsSwitch(
                    title = stringResource(R.string.settings_watch_next),
                    subtitle = stringResource(R.string.settings_watch_next_sub),
                    checked = s.watchNextEnabled,
                    onChange = { enabled ->
                        settings.setWatchNextEnabled(enabled)
                        ServiceLocator.appScope.launch {
                            if (enabled) {
                                WatchNext.sync(context, force = true)
                            } else {
                                WatchNext.clear(context)
                            }
                        }
                    },
                )
                // FR-UI-26. The home screen's Featured row reads whatever this address serves;
                // empty — the default — means there is no row at all.
                TextSetting(
                    title = stringResource(R.string.settings_featured_url),
                    value = s.featuredFeedUrl,
                    placeholder = "https://…/featured.json",
                    onChange = settings::setFeaturedFeedUrl,
                )
                SettingsRow(
                    title = stringResource(R.string.settings_featured_refresh),
                    subtitle = stringResource(R.string.settings_featured_url_sub),
                    onClick = {
                        ServiceLocator.appScope.launch {
                            ServiceLocator.featured.refresh(force = true)
                        }
                    },
                )
                SettingsSwitch(
                    title = stringResource(R.string.settings_pip_home),
                    checked = s.pipOnHome,
                    onChange = settings::setPipOnHome,
                )
                SettingsSwitch(
                    title = stringResource(R.string.settings_autostart),
                    checked = s.autoStartOnBoot,
                    onChange = settings::setAutoStartOnBoot,
                )
                SettingsSwitch(
                    title = stringResource(R.string.settings_confirm_exit),
                    checked = s.confirmExit,
                    onChange = settings::setConfirmExit,
                )
                SettingsChoice(
                    title = stringResource(R.string.settings_external_player),
                    options = listOf(
                        ExternalPlayerScope.NEVER to stringResource(R.string.ext_never),
                        ExternalPlayerScope.VOD_ONLY to stringResource(R.string.ext_vod_only),
                        ExternalPlayerScope.ALWAYS to stringResource(R.string.ext_always),
                    ),
                    selected = s.externalPlayerScope,
                ) { settings.setExternalPlayer(it, s.externalPlayerPackage) }
            }

            SettingsSection.APPEARANCE -> {
                SettingsSectionTitle(stringResource(R.string.settings_appearance), topDivider = false)
                // FR-UI-23. Unlike every other setting here, this one cannot be applied to the
                // running composition: resources are resolved from the activity's base context,
                // which is only attached once. Recreating the activity re-runs
                // `attachBaseContext`, which is where the choice is read.
                SettingsChoice(
                    title = stringResource(R.string.settings_language),
                    options = listOf(
                        AppLanguage.SYSTEM to stringResource(R.string.language_system),
                        AppLanguage.ENGLISH to stringResource(R.string.language_english),
                        AppLanguage.HEBREW to stringResource(R.string.language_hebrew),
                    ),
                    selected = s.language,
                ) { chosen ->
                    if (chosen != s.language) {
                        settings.setLanguage(chosen)
                        // The cached application-language context is now stale.
                        ServiceLocator.refreshLocale()
                        // Android 13 and later apply the choice themselves and recreate the
                        // activity with it; below that it is applied from `attachBaseContext`,
                        // which only runs again on a recreate. Either way the notification
                        // channels are re-declared in `MainActivity.onCreate`, because the
                        // platform stores their names when they are created.
                        if (!AppLocale.applyToPlatform(context, chosen)) {
                            context.findActivity()?.recreate()
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.settings_language_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                SettingsChoice(
                    title = stringResource(R.string.settings_theme),
                    options = listOf(
                        ThemeMode.DARK to stringResource(R.string.theme_dark),
                        ThemeMode.AMOLED to stringResource(R.string.theme_amoled),
                    ),
                    selected = s.theme,
                    onSelect = settings::setTheme,
                )
                SettingsSlider(
                    title = stringResource(R.string.settings_font_scale),
                    valueLabel = "%.1f×".format(s.fontScale),
                    value = s.fontScale,
                    range = 0.8f..1.6f,
                    onChange = settings::setFontScale,
                )
                // FR-ORG-06. Manual moves live in `sort_index`, which only the provider order
                // reads, so switching away from it parks your own ordering rather than losing it.
                SettingsChoice(
                    title = stringResource(R.string.settings_channel_sort),
                    options = listOf(
                        ChannelSort.PROVIDER to stringResource(R.string.sort_provider),
                        ChannelSort.NAME_ASC to stringResource(R.string.sort_name),
                        ChannelSort.MOST_WATCHED to stringResource(R.string.sort_most_watched),
                        ChannelSort.RECENTLY_WATCHED to stringResource(R.string.sort_recent),
                    ),
                    selected = s.channelSort,
                ) { settings.setChannelSort(it); vm.reload() }
                Text(
                    text = stringResource(R.string.sort_manual_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                SettingsSwitch(
                    title = stringResource(R.string.settings_show_new_groups),
                    checked = s.showNewGroups,
                    onChange = settings::setShowNewGroups,
                )
                SettingsSlider(
                    title = stringResource(R.string.settings_overlay_hide),
                    valueLabel = stringResource(R.string.settings_seconds, s.overlayAutoHideSeconds),
                    value = s.overlayAutoHideSeconds.toFloat(),
                    range = 2f..60f,
                ) { settings.setOverlayAutoHideSeconds(it.toInt()) }
                SettingsSlider(
                    title = stringResource(R.string.settings_overlay_opacity),
                    valueLabel = "%.0f%%".format(s.overlayOpacity * 100),
                    value = s.overlayOpacity,
                    range = 0.3f..1f,
                    onChange = settings::setOverlayOpacity,
                )
                // FR-UI-31 / ADR-024. On by default: it is a burn-in guard, and a guard nobody
                // switched on protects nobody. It never touches moving video.
                SettingsSwitch(
                    title = stringResource(R.string.settings_screensaver),
                    subtitle = stringResource(R.string.settings_screensaver_sub),
                    checked = s.screensaverEnabled,
                    onChange = settings::setScreensaverEnabled,
                )
                SettingsSlider(
                    title = stringResource(R.string.settings_screensaver_idle),
                    valueLabel = stringResource(R.string.settings_minutes, s.screensaverIdleMinutes),
                    value = s.screensaverIdleMinutes.toFloat(),
                    range = 1f..60f,
                ) { settings.setScreensaverIdleMinutes(it.toInt()) }
                // FR-UI-32. Off by default, and pointless until the address below is set: this
                // app is installed on other people's televisions, and a to-do list is the last
                // thing that should appear on one uninvited.
                SettingsSwitch(
                    title = stringResource(R.string.settings_screensaver_tasks),
                    subtitle = stringResource(R.string.settings_screensaver_tasks_sub),
                    checked = s.screensaverTasks,
                    onChange = { enabled ->
                        settings.setScreensaverTasks(enabled)
                        if (enabled) {
                            ServiceLocator.appScope.launch {
                                ServiceLocator.tasks.refresh(force = true)
                            }
                        }
                    },
                )
                TextSetting(
                    title = stringResource(R.string.settings_task_url),
                    value = s.taskFeedUrl,
                    placeholder = "https://…/tasks.md",
                    onChange = settings::setTaskFeedUrl,
                )
                SettingsRow(
                    title = stringResource(R.string.settings_task_refresh),
                    subtitle = stringResource(R.string.settings_task_url_sub),
                    onClick = {
                        ServiceLocator.appScope.launch {
                            ServiceLocator.tasks.refresh(force = true)
                        }
                    },
                )
            }

            SettingsSection.NETWORK -> {
                SettingsSectionTitle(stringResource(R.string.settings_network), topDivider = false)
                Text(
                    text = stringResource(R.string.settings_user_agent_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                TextSetting(
                    title = stringResource(R.string.settings_user_agent),
                    value = s.userAgent,
                    placeholder = UserAgents.DEFAULT,
                    onChange = settings::setUserAgent,
                )
                UserAgents.PRESETS.forEach { (label, value) ->
                    SettingsRow(title = label, subtitle = value, onClick = { settings.setUserAgent(value) })
                }
                SettingsSlider(
                    title = stringResource(R.string.settings_timeouts),
                    valueLabel = "${s.connectTimeoutSeconds}s / ${s.readTimeoutSeconds}s / ${s.retries}",
                    value = s.connectTimeoutSeconds.toFloat(),
                    range = 3f..120f,
                ) { settings.setTimeouts(it.toInt(), s.readTimeoutSeconds, s.retries) }
                SettingsSlider(
                    title = stringResource(R.string.action_retry),
                    valueLabel = s.retries.toString(),
                    value = s.retries.toFloat(),
                    range = 0f..10f,
                    steps = 9,
                ) { settings.setTimeouts(s.connectTimeoutSeconds, s.readTimeoutSeconds, it.toInt()) }
                SettingsSwitch(
                    title = stringResource(R.string.settings_insecure_tls),
                    subtitle = stringResource(R.string.settings_insecure_tls_warning),
                    checked = s.allowInsecureTls,
                ) { settings.setAllowInsecureTls(it); vm.onSettingsChanged() }
            }

            SettingsSection.RECORDING -> RecordingSection(vm)

            SettingsSection.PARENTAL -> ParentalSection(vm)

            SettingsSection.BACKUP -> BackupSection(vm)

            SettingsSection.DIAGNOSTICS -> DiagnosticsSection(vm)

            SettingsSection.ABOUT -> {
                SettingsSectionTitle(stringResource(R.string.settings_about), topDivider = false)
                Text(
                    text = stringResource(R.string.about_blurb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    text = BuildInfo.full(context),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    text = stringResource(
                        R.string.about_secure_store,
                        if (com.tivicopy.player.data.prefs.SecureStore.isHardwareBacked) {
                            stringResource(R.string.about_secure_yes)
                        } else {
                            stringResource(R.string.about_secure_no)
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                // The bundled typefaces are under the SIL OFL (ADR-027), which asks for the
                // copyright notice to travel with the font. The licences themselves ship beside
                // them in `assets/licenses/`; this is the credit a reader can see.
                Text(
                    text = stringResource(R.string.about_typeface),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                UpdateSettingsBlock()
                CacheRows()
            }
        }
    }
}

@Composable
private fun decoderOptions(): List<Pair<DecoderMode, String>> = listOf(
    DecoderMode.AUTO to stringResource(R.string.decoder_auto),
    DecoderMode.HARDWARE to stringResource(R.string.decoder_hardware),
    DecoderMode.SOFTWARE to stringResource(R.string.decoder_software),
)

/** Cache usage and the three clear actions (FR-SET-15). */
@Composable
fun CacheRows() {
    val settings = ServiceLocator.settings
    val s by settings.state.collectAsState()
    var usage by remember { mutableStateOf(ServiceLocator.cache.usage()) }

    SettingsSectionTitle(stringResource(R.string.settings_cache))
    SettingsRow(
        title = stringResource(R.string.settings_cache),
        subtitle = stringResource(
            R.string.settings_cache_usage,
            CacheBudget.formatBytes(usage.totalBytes),
            CacheBudget.formatBytes(s.cacheBudgetMb * 1024L * 1024),
        ),
    )
    SettingsSlider(
        title = stringResource(R.string.settings_cache),
        valueLabel = "${s.cacheBudgetMb} MB",
        value = s.cacheBudgetMb.toFloat(),
        range = 50f..4_000f,
    ) { settings.setCacheBudgetMb(it.toInt()) }
    SettingsRow(stringResource(R.string.settings_cache_clear_images)) {
        ServiceLocator.cache.clearImages(); usage = ServiceLocator.cache.usage()
    }
    SettingsRow(stringResource(R.string.settings_cache_clear_guide)) {
        ServiceLocator.cache.clearGuideCache(); usage = ServiceLocator.cache.usage()
    }
    SettingsRow(stringResource(R.string.settings_cache_clear_all)) {
        ServiceLocator.cache.clearAll(); usage = ServiceLocator.cache.usage()
    }
}

/** Guide offset controls used by both the global setting and the per-channel override. */
@Composable
fun OffsetRow(title: String, minutes: Int, onChange: (Int) -> Unit) {
    SettingsSlider(
        title = title,
        valueLabel = stringResource(R.string.settings_offset_minutes, minutes),
        value = minutes.toFloat(),
        range = -720f..720f,
        steps = (1440 / TimeModel.OFFSET_STEP_MINUTES) - 1,
    ) { onChange(TimeModel.clampOffset(it.toInt())) }
}

/**
 * The section rows, each with its glyph (`canvas/Settings.dc.html`, `PSettings.dc.html`): the root
 * list on a phone and the panel beside the open section on a television, where [selected] marks
 * the one that is open.
 */
@Composable
private fun SettingsSectionRows(selected: SettingsSection?, onNavigate: (SettingsSection) -> Unit) {
    val entries = listOf(
        Triple(SettingsSection.SOURCES, R.string.settings_sources, StudioIcons.Storage),
        Triple(SettingsSection.PLAYBACK, R.string.settings_playback, StudioIcons.Play),
        Triple(SettingsSection.APPEARANCE, R.string.settings_appearance, StudioIcons.Appearance),
        Triple(SettingsSection.NETWORK, R.string.settings_network, StudioIcons.Network),
        Triple(SettingsSection.RECORDING, R.string.settings_recording, StudioIcons.Record),
        Triple(SettingsSection.PARENTAL, R.string.settings_parental, StudioIcons.Lock),
        Triple(SettingsSection.BACKUP, R.string.settings_backup, StudioIcons.Backup),
        Triple(SettingsSection.DIAGNOSTICS, R.string.settings_diagnostics, StudioIcons.Pulse),
        Triple(SettingsSection.ABOUT, R.string.settings_about, StudioIcons.Info),
    )
    entries.forEach { (target, label, icon) ->
        SettingsRow(
            title = stringResource(label),
            subtitle = if (target == SettingsSection.ABOUT) BuildInfo.short else null,
            icon = icon,
            selected = target == selected,
        ) { onNavigate(target) }
    }
}

/** `canvas/Settings.dc.html`'s section panel: 560px at 1080p. */
private val SECTIONS_PANEL_WIDTH = 280.dp
