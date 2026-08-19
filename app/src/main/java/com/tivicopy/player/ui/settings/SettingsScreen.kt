package com.tivicopy.player.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.tivicopy.player.data.model.DecoderMode
import com.tivicopy.player.data.net.UserAgents
import com.tivicopy.player.data.prefs.AspectMode
import com.tivicopy.player.data.prefs.BufferPreset
import com.tivicopy.player.data.prefs.ExternalPlayerScope
import com.tivicopy.player.data.prefs.StartBehaviour
import com.tivicopy.player.data.prefs.ThemeMode
import com.tivicopy.player.ui.AppViewModel

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
    modifier: Modifier = Modifier,
) {
    val settings = ServiceLocator.settings
    val s by settings.state.collectAsState()
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        when (section) {
            SettingsSection.ROOT -> {
                SettingsSectionTitle(stringResource(R.string.settings_title))
                SettingsRow(stringResource(R.string.settings_sources)) { onNavigate(SettingsSection.SOURCES) }
                SettingsRow(stringResource(R.string.settings_playback)) { onNavigate(SettingsSection.PLAYBACK) }
                SettingsRow(stringResource(R.string.settings_appearance)) { onNavigate(SettingsSection.APPEARANCE) }
                SettingsRow(stringResource(R.string.settings_network)) { onNavigate(SettingsSection.NETWORK) }
                SettingsRow(stringResource(R.string.settings_recording)) { onNavigate(SettingsSection.RECORDING) }
                SettingsRow(stringResource(R.string.settings_parental)) { onNavigate(SettingsSection.PARENTAL) }
                SettingsRow(stringResource(R.string.settings_backup)) { onNavigate(SettingsSection.BACKUP) }
                SettingsRow(stringResource(R.string.settings_diagnostics)) { onNavigate(SettingsSection.DIAGNOSTICS) }
                SettingsRow(
                    title = stringResource(R.string.settings_about),
                    subtitle = BuildInfo.short,
                ) { onNavigate(SettingsSection.ABOUT) }
            }

            SettingsSection.SOURCES -> SourcesSection(vm)

            SettingsSection.PLAYBACK -> {
                SettingsSectionTitle(stringResource(R.string.settings_playback))
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
                    options = listOf(
                        AspectMode.FIT to stringResource(R.string.aspect_fit),
                        AspectMode.FILL to stringResource(R.string.aspect_fill),
                        AspectMode.ZOOM to stringResource(R.string.aspect_zoom),
                        AspectMode.STRETCH to stringResource(R.string.aspect_stretch),
                        AspectMode.RATIO_16_9 to stringResource(R.string.aspect_16_9),
                        AspectMode.RATIO_4_3 to stringResource(R.string.aspect_4_3),
                        AspectMode.ORIGINAL to stringResource(R.string.aspect_original),
                    ),
                    selected = s.aspectMode,
                    onSelect = settings::setAspectMode,
                )
                TextSetting(
                    title = stringResource(R.string.audio_track),
                    value = s.preferredAudioLanguage,
                    placeholder = "en, he, ru…",
                    onChange = settings::setPreferredAudioLanguage,
                )
                SettingsSwitch(
                    title = stringResource(R.string.subtitle_track),
                    checked = s.subtitlesEnabled,
                    onChange = settings::setSubtitlesEnabled,
                )
                TextSetting(
                    title = stringResource(R.string.subtitle_track),
                    value = s.preferredSubtitleLanguage,
                    placeholder = "en, he…",
                    onChange = settings::setPreferredSubtitleLanguage,
                )
                SettingsSlider(
                    title = stringResource(R.string.settings_font_scale),
                    valueLabel = "%.1f×".format(s.subtitleTextScale),
                    value = s.subtitleTextScale,
                    range = 0.5f..2.5f,
                    onChange = settings::setSubtitleTextScale,
                )
                SettingsChoice(
                    title = stringResource(R.string.settings_start_behaviour),
                    options = listOf(
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
                SettingsSectionTitle(stringResource(R.string.settings_appearance))
                SettingsChoice(
                    title = stringResource(R.string.settings_theme),
                    options = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                        ThemeMode.LIGHT to stringResource(R.string.theme_light),
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
                SettingsSlider(
                    title = stringResource(R.string.settings_overlay_hide),
                    valueLabel = stringResource(R.string.settings_seconds, s.overlayAutoHideSeconds),
                    value = s.overlayAutoHideSeconds.toFloat(),
                    range = 2f..60f,
                ) { settings.setOverlayAutoHideSeconds(it.toInt()) }
                SettingsSlider(
                    title = stringResource(R.string.settings_overlay_hide),
                    valueLabel = "%.0f%%".format(s.overlayOpacity * 100),
                    value = s.overlayOpacity,
                    range = 0.3f..1f,
                    onChange = settings::setOverlayOpacity,
                )
            }

            SettingsSection.NETWORK -> {
                SettingsSectionTitle(stringResource(R.string.settings_network))
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

            SettingsSection.PARENTAL -> ParentalSection()

            SettingsSection.BACKUP -> BackupSection(vm)

            SettingsSection.DIAGNOSTICS -> DiagnosticsSection(vm)

            SettingsSection.ABOUT -> {
                SettingsSectionTitle(stringResource(R.string.settings_about))
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
