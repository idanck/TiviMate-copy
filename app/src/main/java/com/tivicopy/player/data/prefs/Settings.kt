package com.tivicopy.player.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.DecoderMode
import com.tivicopy.player.data.net.UserAgents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Buffer sizes offered in Settings, in milliseconds.
 *
 * `DefaultLoadControl` refuses a `minMs` below either playback threshold and a `maxMs` below
 * `minMs`, and it throws instead of clamping, so `minMs` is never smaller than [rebufferMs]
 * here. [com.tivicopy.player.player.BufferDurations] re-checks the same invariants, because
 * the custom values come from preferences and cannot be constrained at this end.
 */
enum class BufferPreset(val minMs: Int, val maxMs: Int, val playbackMs: Int, val rebufferMs: Int) {
    MINIMAL(1_000, 3_000, 400, 1_000),
    SMALL(2_000, 8_000, 800, 2_000),
    NORMAL(3_000, 30_000, 1_500, 3_000),
    LARGE(10_000, 60_000, 3_000, 6_000),
    CUSTOM(3_000, 30_000, 1_500, 3_000),
}

enum class AspectMode { FIT, FILL, ZOOM, STRETCH, RATIO_16_9, RATIO_4_3, ORIGINAL }

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

enum class StartBehaviour { LAST_CHANNEL, SPECIFIC_CHANNEL, CHANNEL_LIST, GUIDE }

enum class ExternalPlayerScope { NEVER, VOD_ONLY, ALWAYS }

/**
 * All user settings, backed by SharedPreferences.
 *
 * Values are exposed as a [StateFlow] of a snapshot so Compose recomposes on change without
 * every screen registering its own listener. Credentials never live here — see [SecureStore].
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Snapshot> = _state

    data class Snapshot(
        val globalEpgOffsetMinutes: Int,
        val bufferPreset: BufferPreset,
        val customBufferMinMs: Int,
        val customBufferMaxMs: Int,
        val videoDecoder: DecoderMode,
        val audioDecoder: DecoderMode,
        val aspectMode: AspectMode,
        val preferredAudioLanguage: String,
        val preferredSubtitleLanguage: String,
        val subtitlesEnabled: Boolean,
        val subtitleTextScale: Float,
        val theme: ThemeMode,
        val overlayAutoHideSeconds: Int,
        val overlayOpacity: Float,
        val fontScale: Float,
        val userAgent: String,
        val connectTimeoutSeconds: Int,
        val readTimeoutSeconds: Int,
        val retries: Int,
        val allowInsecureTls: Boolean,
        val startBehaviour: StartBehaviour,
        val resumeLastChannel: Boolean,
        val lastChannelId: Long,
        val previousChannelId: Long,
        val startChannelId: Long,
        val confirmExit: Boolean,
        val autoStartOnBoot: Boolean,
        val numericZapTimeoutMs: Int,
        val pipEnabled: Boolean,
        val pipOnHome: Boolean,
        val externalPlayerScope: ExternalPlayerScope,
        val externalPlayerPackage: String,
        val cacheBudgetMb: Int,
        val epgRefreshIntervalMinutes: Int,
        val epgRetentionPastDays: Int,
        val showNewGroups: Boolean,
        val recordingPreRollMinutes: Int,
        val recordingPostRollMinutes: Int,
        val recordingFolderUri: String,
        val recordingFreeSpaceWarnGb: Int,
        val maxConcurrentRecordings: Int,
        val parentalPinHash: String,
        val parentalPinSalt: String,
        val lockSettings: Boolean,
        val lockChannels: Boolean,
        val hideLockedChannels: Boolean,
        val activePlaylistId: Long,
        val verboseLogging: Boolean,
    )

    private fun read() = Snapshot(
        globalEpgOffsetMinutes = prefs.getInt(K_EPG_OFFSET, 0),
        bufferPreset = prefs.enum(K_BUFFER_PRESET, BufferPreset.NORMAL),
        customBufferMinMs = prefs.getInt(K_BUFFER_MIN, BufferPreset.NORMAL.minMs),
        customBufferMaxMs = prefs.getInt(K_BUFFER_MAX, BufferPreset.NORMAL.maxMs),
        videoDecoder = prefs.enum(K_VIDEO_DECODER, DecoderMode.AUTO),
        audioDecoder = prefs.enum(K_AUDIO_DECODER, DecoderMode.AUTO),
        aspectMode = prefs.enum(K_ASPECT, AspectMode.FIT),
        preferredAudioLanguage = prefs.getString(K_AUDIO_LANG, "") ?: "",
        preferredSubtitleLanguage = prefs.getString(K_SUB_LANG, "") ?: "",
        subtitlesEnabled = prefs.getBoolean(K_SUBS_ON, false),
        subtitleTextScale = prefs.getFloat(K_SUB_SCALE, 1.0f),
        theme = prefs.enum(K_THEME, ThemeMode.DARK),
        overlayAutoHideSeconds = prefs.getInt(K_OVERLAY_HIDE, 5),
        overlayOpacity = prefs.getFloat(K_OVERLAY_OPACITY, 0.85f),
        fontScale = prefs.getFloat(K_FONT_SCALE, 1.0f),
        userAgent = prefs.getString(K_USER_AGENT, UserAgents.DEFAULT) ?: UserAgents.DEFAULT,
        connectTimeoutSeconds = prefs.getInt(K_CONNECT_TIMEOUT, 15),
        readTimeoutSeconds = prefs.getInt(K_READ_TIMEOUT, 30),
        retries = prefs.getInt(K_RETRIES, 2),
        allowInsecureTls = prefs.getBoolean(K_INSECURE_TLS, false),
        startBehaviour = prefs.enum(K_START_BEHAVIOUR, StartBehaviour.LAST_CHANNEL),
        resumeLastChannel = prefs.getBoolean(K_RESUME_LAST, true),
        lastChannelId = prefs.getLong(K_LAST_CHANNEL, 0),
        previousChannelId = prefs.getLong(K_PREV_CHANNEL, 0),
        startChannelId = prefs.getLong(K_START_CHANNEL, 0),
        confirmExit = prefs.getBoolean(K_CONFIRM_EXIT, true),
        autoStartOnBoot = prefs.getBoolean(K_AUTOSTART, false),
        numericZapTimeoutMs = prefs.getInt(K_NUMERIC_TIMEOUT, 2_000),
        pipEnabled = prefs.getBoolean(K_PIP, true),
        pipOnHome = prefs.getBoolean(K_PIP_HOME, false),
        externalPlayerScope = prefs.enum(K_EXT_SCOPE, ExternalPlayerScope.NEVER),
        externalPlayerPackage = prefs.getString(K_EXT_PACKAGE, "") ?: "",
        cacheBudgetMb = prefs.getInt(K_CACHE_BUDGET, 500),
        epgRefreshIntervalMinutes = prefs.getInt(K_EPG_REFRESH, 12 * 60),
        epgRetentionPastDays = prefs.getInt(K_EPG_RETENTION, 8),
        showNewGroups = prefs.getBoolean(K_SHOW_NEW_GROUPS, true),
        recordingPreRollMinutes = prefs.getInt(K_PRE_ROLL, 2),
        recordingPostRollMinutes = prefs.getInt(K_POST_ROLL, 5),
        recordingFolderUri = prefs.getString(K_REC_FOLDER, "") ?: "",
        recordingFreeSpaceWarnGb = prefs.getInt(K_REC_WARN_GB, 5),
        maxConcurrentRecordings = prefs.getInt(K_MAX_RECORDINGS, 2),
        parentalPinHash = prefs.getString(K_PIN_HASH, "") ?: "",
        parentalPinSalt = prefs.getString(K_PIN_SALT, "") ?: "",
        lockSettings = prefs.getBoolean(K_LOCK_SETTINGS, false),
        lockChannels = prefs.getBoolean(K_LOCK_CHANNELS, true),
        hideLockedChannels = prefs.getBoolean(K_HIDE_LOCKED, false),
        activePlaylistId = prefs.getLong(K_ACTIVE_PLAYLIST, 0),
        verboseLogging = prefs.getBoolean(K_VERBOSE, false),
    )

    private fun commit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    fun setGlobalEpgOffset(minutes: Int) =
        commit { putInt(K_EPG_OFFSET, TimeModel.clampOffset(minutes)) }

    fun setBufferPreset(p: BufferPreset) = commit { putString(K_BUFFER_PRESET, p.name) }
    fun setCustomBuffer(minMs: Int, maxMs: Int) = commit {
        putInt(K_BUFFER_MIN, minMs.coerceIn(200, 120_000))
        putInt(K_BUFFER_MAX, maxMs.coerceIn(500, 300_000))
    }

    fun setVideoDecoder(m: DecoderMode) = commit { putString(K_VIDEO_DECODER, m.name) }
    fun setAudioDecoder(m: DecoderMode) = commit { putString(K_AUDIO_DECODER, m.name) }
    fun setAspectMode(m: AspectMode) = commit { putString(K_ASPECT, m.name) }
    fun setPreferredAudioLanguage(v: String) = commit { putString(K_AUDIO_LANG, v.trim()) }
    fun setPreferredSubtitleLanguage(v: String) = commit { putString(K_SUB_LANG, v.trim()) }
    fun setSubtitlesEnabled(v: Boolean) = commit { putBoolean(K_SUBS_ON, v) }
    fun setSubtitleTextScale(v: Float) = commit { putFloat(K_SUB_SCALE, v.coerceIn(0.5f, 2.5f)) }
    fun setTheme(t: ThemeMode) = commit { putString(K_THEME, t.name) }
    fun setOverlayAutoHideSeconds(v: Int) = commit { putInt(K_OVERLAY_HIDE, v.coerceIn(2, 60)) }
    fun setOverlayOpacity(v: Float) = commit { putFloat(K_OVERLAY_OPACITY, v.coerceIn(0.3f, 1f)) }
    fun setFontScale(v: Float) = commit { putFloat(K_FONT_SCALE, v.coerceIn(0.8f, 1.6f)) }
    fun setUserAgent(v: String) = commit { putString(K_USER_AGENT, v.ifBlank { UserAgents.DEFAULT }) }
    fun setTimeouts(connect: Int, read: Int, retries: Int) = commit {
        putInt(K_CONNECT_TIMEOUT, connect.coerceIn(3, 120))
        putInt(K_READ_TIMEOUT, read.coerceIn(3, 300))
        putInt(K_RETRIES, retries.coerceIn(0, 10))
    }
    fun setAllowInsecureTls(v: Boolean) = commit { putBoolean(K_INSECURE_TLS, v) }
    fun setStartBehaviour(v: StartBehaviour) = commit { putString(K_START_BEHAVIOUR, v.name) }
    fun setResumeLastChannel(v: Boolean) = commit { putBoolean(K_RESUME_LAST, v) }
    fun setStartChannel(id: Long) = commit { putLong(K_START_CHANNEL, id) }
    fun setConfirmExit(v: Boolean) = commit { putBoolean(K_CONFIRM_EXIT, v) }
    fun setAutoStartOnBoot(v: Boolean) = commit { putBoolean(K_AUTOSTART, v) }
    fun setNumericZapTimeout(ms: Int) = commit { putInt(K_NUMERIC_TIMEOUT, ms.coerceIn(500, 6_000)) }
    fun setPipEnabled(v: Boolean) = commit { putBoolean(K_PIP, v) }
    fun setPipOnHome(v: Boolean) = commit { putBoolean(K_PIP_HOME, v) }
    fun setExternalPlayer(scope: ExternalPlayerScope, pkg: String) = commit {
        putString(K_EXT_SCOPE, scope.name); putString(K_EXT_PACKAGE, pkg.trim())
    }
    fun setCacheBudgetMb(v: Int) = commit { putInt(K_CACHE_BUDGET, v.coerceIn(50, 8_000)) }
    fun setEpgRefreshInterval(minutes: Int) = commit { putInt(K_EPG_REFRESH, minutes.coerceIn(0, 7 * 24 * 60)) }
    fun setEpgRetentionPastDays(days: Int) = commit { putInt(K_EPG_RETENTION, days.coerceIn(1, 60)) }
    fun setShowNewGroups(v: Boolean) = commit { putBoolean(K_SHOW_NEW_GROUPS, v) }
    fun setRecordingPadding(preMinutes: Int, postMinutes: Int) = commit {
        putInt(K_PRE_ROLL, preMinutes.coerceIn(0, 60)); putInt(K_POST_ROLL, postMinutes.coerceIn(0, 120))
    }
    fun setRecordingFolder(uri: String) = commit { putString(K_REC_FOLDER, uri) }
    fun setRecordingFreeSpaceWarnGb(v: Int) = commit { putInt(K_REC_WARN_GB, v.coerceIn(1, 200)) }
    fun setMaxConcurrentRecordings(v: Int) = commit { putInt(K_MAX_RECORDINGS, v.coerceIn(1, 8)) }
    fun setLockSettings(v: Boolean) = commit { putBoolean(K_LOCK_SETTINGS, v) }
    fun setLockChannels(v: Boolean) = commit { putBoolean(K_LOCK_CHANNELS, v) }
    fun setHideLockedChannels(v: Boolean) = commit { putBoolean(K_HIDE_LOCKED, v) }
    fun setActivePlaylist(id: Long) = commit { putLong(K_ACTIVE_PLAYLIST, id) }
    fun setVerboseLogging(v: Boolean) = commit { putBoolean(K_VERBOSE, v) }

    /** Remembers the current and previous channel so a single key can toggle (FR-PLB-20). */
    fun rememberChannel(id: Long) {
        val current = prefs.getLong(K_LAST_CHANNEL, 0)
        commit {
            if (current != id && current != 0L) putLong(K_PREV_CHANNEL, current)
            putLong(K_LAST_CHANNEL, id)
        }
    }

    fun setParentalPin(hash: String, salt: String) = commit {
        putString(K_PIN_HASH, hash); putString(K_PIN_SALT, salt)
    }

    fun clearParentalPin() = commit { remove(K_PIN_HASH); remove(K_PIN_SALT) }

    /** Snapshot of every stored key, for the backup file (FR-SET-08). */
    fun exportAll(): Map<String, Any?> = prefs.all.filterKeys { it != K_PIN_HASH && it != K_PIN_SALT }

    fun importAll(values: Map<String, Any?>) {
        prefs.edit().apply {
            values.forEach { (k, v) ->
                when (v) {
                    is Boolean -> putBoolean(k, v)
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is Float -> putFloat(k, v)
                    is String -> putString(k, v)
                    is Double -> putFloat(k, v.toFloat())
                    else -> Unit
                }
            }
        }.apply()
        _state.value = read()
    }

    private inline fun <reified E : Enum<E>> SharedPreferences.enum(key: String, fallback: E): E {
        val raw = getString(key, null) ?: return fallback
        return runCatching { enumValueOf<E>(raw) }.getOrDefault(fallback)
    }

    companion object {
        const val K_EPG_OFFSET = "epg_offset"
        const val K_BUFFER_PRESET = "buffer_preset"
        const val K_BUFFER_MIN = "buffer_min_ms"
        const val K_BUFFER_MAX = "buffer_max_ms"
        const val K_VIDEO_DECODER = "video_decoder"
        const val K_AUDIO_DECODER = "audio_decoder"
        const val K_ASPECT = "aspect_mode"
        const val K_AUDIO_LANG = "audio_language"
        const val K_SUB_LANG = "subtitle_language"
        const val K_SUBS_ON = "subtitles_enabled"
        const val K_SUB_SCALE = "subtitle_scale"
        const val K_THEME = "theme"
        const val K_OVERLAY_HIDE = "overlay_hide_seconds"
        const val K_OVERLAY_OPACITY = "overlay_opacity"
        const val K_FONT_SCALE = "font_scale"
        const val K_USER_AGENT = "user_agent"
        const val K_CONNECT_TIMEOUT = "connect_timeout"
        const val K_READ_TIMEOUT = "read_timeout"
        const val K_RETRIES = "retries"
        const val K_INSECURE_TLS = "insecure_tls"
        const val K_START_BEHAVIOUR = "start_behaviour"
        const val K_RESUME_LAST = "resume_last"
        const val K_LAST_CHANNEL = "last_channel"
        const val K_PREV_CHANNEL = "previous_channel"
        const val K_START_CHANNEL = "start_channel"
        const val K_CONFIRM_EXIT = "confirm_exit"
        const val K_AUTOSTART = "autostart_boot"
        const val K_NUMERIC_TIMEOUT = "numeric_timeout"
        const val K_PIP = "pip_enabled"
        const val K_PIP_HOME = "pip_on_home"
        const val K_EXT_SCOPE = "external_player_scope"
        const val K_EXT_PACKAGE = "external_player_package"
        const val K_CACHE_BUDGET = "cache_budget_mb"
        const val K_EPG_REFRESH = "epg_refresh_minutes"
        const val K_EPG_RETENTION = "epg_retention_days"
        const val K_SHOW_NEW_GROUPS = "show_new_groups"
        const val K_PRE_ROLL = "rec_pre_roll"
        const val K_POST_ROLL = "rec_post_roll"
        const val K_REC_FOLDER = "rec_folder"
        const val K_REC_WARN_GB = "rec_warn_gb"
        const val K_MAX_RECORDINGS = "rec_max_concurrent"
        const val K_PIN_HASH = "pin_hash"
        const val K_PIN_SALT = "pin_salt"
        const val K_LOCK_SETTINGS = "lock_settings"
        const val K_LOCK_CHANNELS = "lock_channels"
        const val K_HIDE_LOCKED = "hide_locked"
        const val K_ACTIVE_PLAYLIST = "active_playlist"
        const val K_VERBOSE = "verbose_logging"
    }
}
