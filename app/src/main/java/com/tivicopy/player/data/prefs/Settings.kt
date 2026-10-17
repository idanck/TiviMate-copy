package com.tivicopy.player.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.ChannelSort
import com.tivicopy.player.data.model.DecoderMode
import com.tivicopy.player.data.net.UserAgents
import com.tivicopy.player.data.news.NewsSource
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

/**
 * Studio is drawn dark only (ADR-028). A stored `SYSTEM` or `LIGHT` from an earlier version no longer
 * parses and falls back to [DARK], which is where both of them now land anyway.
 */
enum class ThemeMode { DARK, AMOLED }

/**
 * The interface language (FR-UI-23).
 *
 * [SYSTEM] means "do not intervene", which is what makes an Android 13+ per-app language, or a
 * plain device-language change, keep working. [tag] is a BCP-47 tag, and the set is bounded by
 * `resourceConfigurations` in the build file — there is no point offering a language whose strings
 * are stripped from the APK.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    HEBREW("he");

    /** Null for [SYSTEM], which means "do not intervene". */
    fun locale(): java.util.Locale? = tag?.let(java.util.Locale::forLanguageTag)

    companion object {
        /** Kept free of [android.content.Context] so the resolution itself is unit-testable. */
        fun fromStored(raw: String?): AppLanguage =
            raw?.let { stored -> entries.firstOrNull { it.name == stored } } ?: SYSTEM

        /**
         * What a list of BCP-47 tags resolves to, for reading back the language Android 13's
         * per-app setting is applying (FR-UI-23).
         *
         * Only the first tag counts — it is the one the platform applies — and only its language
         * subtag, so `he-IL` is Hebrew. Hebrew is also accepted under `iw`: Android's
         * `java.util.Locale` still reports the legacy code, so a tag can come back either way.
         * An empty list means the user has not chosen one, and so does a language this build
         * does not ship.
         */
        fun fromLocaleTags(tags: String?): AppLanguage {
            val first = tags?.split(',')?.firstOrNull { it.isNotBlank() }?.trim() ?: return SYSTEM
            val language = first.substringBefore('-').substringBefore('_').lowercase()
            val current = if (language == "iw") "he" else language
            return entries.firstOrNull { it.tag == current } ?: SYSTEM
        }
    }
}

/**
 * What the app puts on screen when it opens (FR-SET-04).
 *
 * [HOME] is appended rather than inserted, and the enum is stored by name, so the position here
 * is cosmetic — but appending is the habit that keeps it that way.
 */
enum class StartBehaviour { LAST_CHANNEL, SPECIFIC_CHANNEL, CHANNEL_LIST, GUIDE, HOME }

enum class ExternalPlayerScope { NEVER, VOD_ONLY, ALWAYS }

/**
 * How fast the screensaver's headlines scroll (FR-UI-33), in dp a second. [SLOW] is the canvas's
 * own pace: eight headlines in eighty seconds on a 1080p panel (`TvScreensaverCombo`).
 */
enum class FlashSpeed(val dpPerSecond: Float) { SLOW(10f), NORMAL(16f), FAST(24f) }

/**
 * All user settings, backed by SharedPreferences.
 *
 * Values are exposed as a [StateFlow] of a snapshot so Compose recomposes on change without
 * every screen registering its own listener. Credentials never live here — see [SecureStore].
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        val language: AppLanguage,
        val channelSort: ChannelSort,
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
        val updateAutoCheck: Boolean,
        val updateWifiOnly: Boolean,
        val updateSkippedVersionCode: Int,
        val updateLastCheckUtc: Long,
        /** The manifest the last successful check read, verbatim; null when it found none. */
        val updateLastManifest: String?,
        /**
         * Where the home screen's featured rail reads from (FR-UI-26). Empty by default, and
         * empty means there is no rail at all — the app ships the mechanism, not a feed.
         */
        val featuredFeedUrl: String,
        val featuredLastCheckUtc: Long,
        /** The feed the last successful fetch read, verbatim; null when there is none. */
        val featuredLastFeed: String?,
        /**
         * Whether recently watched channels are published to the system launcher's
         * "Continue watching" row (FR-TVI-02). On by default, because that is the feature —
         * but it publishes viewing history outside the app, so it has a way off.
         */
        val watchNextEnabled: Boolean,
        /**
         * Whether the home screen carries its row of installed apps (FR-UI-28). Off by default:
         * the home screen is for channels, and a viewer who also wants it to launch other apps
         * switches the row on.
         */
        val showHomeApps: Boolean,
        /**
         * Whether a still screen turns into a screensaver after [screensaverIdleMinutes]
         * (FR-UI-31). On by default: this is a burn-in guard, and a guard nobody switched on
         * protects nobody.
         */
        val screensaverEnabled: Boolean,
        /** How long a still screen with nobody pressing anything is left alone. */
        val screensaverIdleMinutes: Int,
        /** Whether the radio screensaver also prints the open tasks (FR-UI-32). */
        val screensaverTasks: Boolean,
        /**
         * Where those tasks are read from. Empty by default, and empty means there are none —
         * the app ships the connection, not a list. See [com.tivicopy.player.data.tasks.TaskFeed].
         */
        val taskFeedUrl: String,
        val taskLastCheckUtc: Long,
        /** The document the last successful fetch read, verbatim; null when there is none. */
        val taskLastFeed: String?,
        /**
         * Whether the screensaver scrolls headlines beside its clock (FR-UI-33). On by default,
         * because it shows nothing at all until [newsSources] has an address in it.
         */
        val screensaverFlashes: Boolean,
        /**
         * Whether the screensaver's clock and headlines are drawn in their three soft colours
         * (ADR-037), rather than in the text colours alone.
         */
        val screensaverColours: Boolean,
        val flashSpeed: FlashSpeed,
        /**
         * Where the headlines come from. Empty by default, and empty means there are none — the
         * app ships the reader, not a newsroom. See [com.tivicopy.player.data.news.NewsFeed].
         */
        val newsSources: List<NewsSource>,
        val newsLastCheckUtc: Long,
        /** Every source's last headlines, already parsed; null when there are none. */
        val newsLastDigests: String?,
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
        language = prefs.enum(K_LANGUAGE, AppLanguage.SYSTEM),
        channelSort = prefs.enum(K_CHANNEL_SORT, ChannelSort.PROVIDER),
        overlayAutoHideSeconds = prefs.getInt(K_OVERLAY_HIDE, 5),
        overlayOpacity = prefs.getFloat(K_OVERLAY_OPACITY, 0.85f),
        fontScale = prefs.getFloat(K_FONT_SCALE, 1.0f),
        userAgent = prefs.getString(K_USER_AGENT, UserAgents.DEFAULT) ?: UserAgents.DEFAULT,
        connectTimeoutSeconds = prefs.getInt(K_CONNECT_TIMEOUT, 15),
        readTimeoutSeconds = prefs.getInt(K_READ_TIMEOUT, 30),
        retries = prefs.getInt(K_RETRIES, 2),
        allowInsecureTls = prefs.getBoolean(K_INSECURE_TLS, false),
        // Defaults to the channel list rather than resuming playback, so opening the app never
        // starts a stream on its own — the user lands on the channel picker and chooses one.
        startBehaviour = prefs.enum(K_START_BEHAVIOUR, StartBehaviour.HOME),
        resumeLastChannel = prefs.getBoolean(K_RESUME_LAST, true),
        lastChannelId = prefs.getLong(K_LAST_CHANNEL, 0),
        previousChannelId = prefs.getLong(K_PREV_CHANNEL, 0),
        startChannelId = prefs.getLong(K_START_CHANNEL, 0),
        confirmExit = prefs.getBoolean(K_CONFIRM_EXIT, true),
        autoStartOnBoot = prefs.getBoolean(K_AUTOSTART, false),
        numericZapTimeoutMs = prefs.getInt(K_NUMERIC_TIMEOUT, 2_000),
        pipEnabled = prefs.getBoolean(K_PIP, true),
        // On by default (device report): leaving the app mid-programme should keep the picture
        // in a floating window unless the user turns that off in Settings.
        pipOnHome = prefs.getBoolean(K_PIP_HOME, true),
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
        updateAutoCheck = prefs.getBoolean(K_UPDATE_AUTO, true),
        updateWifiOnly = prefs.getBoolean(K_UPDATE_WIFI_ONLY, true),
        updateSkippedVersionCode = prefs.getInt(K_UPDATE_SKIPPED, 0),
        updateLastCheckUtc = prefs.getLong(K_UPDATE_LAST_CHECK, 0),
        updateLastManifest = prefs.getString(K_UPDATE_MANIFEST, null),
        featuredFeedUrl = prefs.getString(K_FEATURED_URL, "") ?: "",
        featuredLastCheckUtc = prefs.getLong(K_FEATURED_LAST_CHECK, 0),
        featuredLastFeed = prefs.getString(K_FEATURED_FEED, null),
        watchNextEnabled = prefs.getBoolean(K_WATCH_NEXT, true),
        showHomeApps = prefs.getBoolean(K_SHOW_HOME_APPS, false),
        screensaverEnabled = prefs.getBoolean(K_SCREENSAVER, true),
        screensaverIdleMinutes = prefs.getInt(K_SCREENSAVER_IDLE, 10),
        screensaverTasks = prefs.getBoolean(K_SCREENSAVER_TASKS, false),
        taskFeedUrl = prefs.getString(K_TASK_URL, "") ?: "",
        taskLastCheckUtc = prefs.getLong(K_TASK_LAST_CHECK, 0),
        taskLastFeed = prefs.getString(K_TASK_FEED, null),
        screensaverFlashes = prefs.getBoolean(K_SAVER_FLASHES, true),
        screensaverColours = prefs.getBoolean(K_SAVER_COLOURS, true),
        flashSpeed = prefs.enum(K_FLASH_SPEED, FlashSpeed.SLOW),
        newsSources = NewsSource.decode(prefs.getString(K_NEWS_SOURCES, null)),
        newsLastCheckUtc = prefs.getLong(K_NEWS_LAST_CHECK, 0),
        newsLastDigests = prefs.getString(K_NEWS_DIGESTS, null),
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

    /**
     * FR-UI-23. Records the choice; applying it is [AppLocale]'s job, which either hands it to
     * the platform (Android 13+) or reads this key straight from preferences during
     * `attachBaseContext` on the recreate the caller then asks for.
     */
    fun setLanguage(v: AppLanguage) = commit { putString(K_LANGUAGE, v.name) }

    /** FR-ORG-06. Manual reordering only means anything under [ChannelSort.PROVIDER]. */
    fun setChannelSort(v: ChannelSort) = commit { putString(K_CHANNEL_SORT, v.name) }
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

    /** FR-UPD-06: the background check and the metered-network guard are both opt-out. */
    fun setUpdateAutoCheck(v: Boolean) = commit { putBoolean(K_UPDATE_AUTO, v) }
    fun setUpdateWifiOnly(v: Boolean) = commit { putBoolean(K_UPDATE_WIFI_ONLY, v) }

    /**
     * Silences one version. Stored as a floor rather than a set: skipping 12 also means
     * nothing older than 12 is worth asking about, and a later build clears it by being
     * greater than the floor.
     */
    fun setUpdateSkippedVersion(versionCode: Int) = commit { putInt(K_UPDATE_SKIPPED, versionCode) }

    /**
     * Records that a check happened, and drops a stale skip once that version is installed.
     *
     * [manifestJson] is kept verbatim because the check interval outlives the process: an
     * update the background sweep found overnight has to survive until someone opens the app,
     * and re-asking the host on every start is exactly what the interval exists to avoid.
     */
    fun rememberUpdateCheck(atUtc: Long, seenVersionCode: Int, manifestJson: String? = null) = commit {
        putLong(K_UPDATE_LAST_CHECK, atUtc)
        if (manifestJson == null) remove(K_UPDATE_MANIFEST) else putString(K_UPDATE_MANIFEST, manifestJson)
        if (seenVersionCode <= com.tivicopy.player.BuildInfo.versionCode) remove(K_UPDATE_SKIPPED)
    }

    /**
     * Points the featured rail somewhere, or nowhere.
     *
     * Any *change* of address drops the cached document and the check timestamp with it, not
     * only clearing it. The cache is keyed by nothing but this preference, so leaving it behind
     * would have a newly pointed rail keep promoting the previous feed's items until the six-hour
     * TTL expired — across cold starts, since the document is kept in preferences.
     */
    fun setFeaturedFeedUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed == prefs.getString(K_FEATURED_URL, "").orEmpty()) return
        commit {
            putString(K_FEATURED_URL, trimmed)
            remove(K_FEATURED_FEED)
            remove(K_FEATURED_LAST_CHECK)
        }
    }

    fun setWatchNextEnabled(v: Boolean) = commit { putBoolean(K_WATCH_NEXT, v) }

    fun setShowHomeApps(v: Boolean) = commit { putBoolean(K_SHOW_HOME_APPS, v) }

    /** FR-UI-31. */
    fun setScreensaverEnabled(v: Boolean) = commit { putBoolean(K_SCREENSAVER, v) }

    /**
     * The floor is one minute rather than zero: a screensaver that arms instantly would take the
     * screen away while somebody is reading the guide, and "off" is the switch above, not a
     * timeout of nothing.
     */
    fun setScreensaverIdleMinutes(v: Int) = commit { putInt(K_SCREENSAVER_IDLE, v.coerceIn(1, 120)) }

    fun setScreensaverTasks(v: Boolean) = commit { putBoolean(K_SCREENSAVER_TASKS, v) }

    /**
     * Points the task list somewhere, or nowhere. Drops the cached document on any *change* of
     * address, for the reason spelled out in [setFeaturedFeedUrl].
     */
    fun setTaskFeedUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed == prefs.getString(K_TASK_URL, "").orEmpty()) return
        commit {
            putString(K_TASK_URL, trimmed)
            remove(K_TASK_FEED)
            remove(K_TASK_LAST_CHECK)
        }
    }

    /** FR-UI-33. */
    fun setScreensaverFlashes(v: Boolean) = commit { putBoolean(K_SAVER_FLASHES, v) }

    fun setScreensaverColours(v: Boolean) = commit { putBoolean(K_SAVER_COLOURS, v) }

    fun setFlashSpeed(v: FlashSpeed) = commit { putString(K_FLASH_SPEED, v.name) }

    /**
     * Adds a news source, switched on, at the end of the list. False — and nothing stored — for
     * an address that is not HTTPS, one already in the list, or a list that is full.
     */
    fun addNewsSource(url: String): Boolean {
        val trimmed = url.trim()
        val current = state.value.newsSources
        if (!NewsSource.acceptable(trimmed)) return false
        if (current.any { it.url.equals(trimmed, ignoreCase = true) }) return false
        if (current.size >= NewsSource.MAX_SOURCES) return false
        commit { putString(K_NEWS_SOURCES, NewsSource.encode(current + NewsSource(trimmed))) }
        return true
    }

    fun setNewsSourceEnabled(url: String, enabled: Boolean) = commit {
        val next = state.value.newsSources.map { if (it.url == url) it.copy(enabled = enabled) else it }
        putString(K_NEWS_SOURCES, NewsSource.encode(next))
    }

    /** Its cached headlines go on the next refresh, which drops every address no longer listed. */
    fun removeNewsSource(url: String) = commit {
        putString(K_NEWS_SOURCES, NewsSource.encode(state.value.newsSources.filterNot { it.url == url }))
    }

    /** The headlines' twin of [rememberTasks]: parsed rather than verbatim (see `NewsRepository`). */
    fun rememberNews(atUtc: Long, digests: String?) = commit {
        putLong(K_NEWS_LAST_CHECK, atUtc)
        if (digests == null) remove(K_NEWS_DIGESTS) else putString(K_NEWS_DIGESTS, digests)
    }

    /** The task list's twin of [rememberFeatured], and cached for the same reason. */
    fun rememberTasks(atUtc: Long, document: String?) = commit {
        putLong(K_TASK_LAST_CHECK, atUtc)
        if (document == null) remove(K_TASK_FEED) else putString(K_TASK_FEED, document)
    }

    /** The featured-rail twin of [rememberUpdateCheck], and cached for the same reason. */
    fun rememberFeatured(atUtc: Long, feedJson: String?) = commit {
        putLong(K_FEATURED_LAST_CHECK, atUtc)
        if (feedJson == null) remove(K_FEATURED_FEED) else putString(K_FEATURED_FEED, feedJson)
    }

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

    /**
     * The channels Sport mode last had on screen, panel by panel, null where a panel was empty
     * (ADR-041). Read straight from preferences rather than carried on [Snapshot]: nothing on
     * screen observes it, and every write would otherwise recompose every screen that reads the
     * settings.
     */
    fun sportSlots(): List<Long?> = slotIds(K_SPORT_SLOTS)

    fun setSportSlots(ids: List<Long?>) = setSlotIds(K_SPORT_SLOTS, ids)

    /** News mode's grid, as [sportSlots] is Sport mode's; -1 marks the panel of headlines (ADR-042). */
    fun newsSlots(): List<Long?> = slotIds(K_NEWS_SLOTS)

    fun setNewsSlots(ids: List<Long?>) = setSlotIds(K_NEWS_SLOTS, ids)

    private fun slotIds(key: String): List<Long?> =
        prefs.getString(key, null)
            ?.split(',')
            ?.map { it.trim().toLongOrNull() }
            .orEmpty()

    private fun setSlotIds(key: String, ids: List<Long?>) {
        prefs.edit().putString(key, ids.joinToString(",") { it?.toString().orEmpty() }).apply()
    }

    /**
     * How many panels Sport mode shows, two or four (ADR-041). The phone offers both; the
     * television keeps four. Read straight from preferences, as [sportSlots] is.
     */
    fun sportPanels(): Int {
        // A backup restored through JSON can bring a number back as another type.
        val stored = runCatching { prefs.getInt(K_SPORT_PANELS, 4) }.getOrDefault(4)
        return if (stored <= 2) 2 else 4
    }

    fun setSportPanels(count: Int) {
        prefs.edit().putInt(K_SPORT_PANELS, if (count <= 2) 2 else 4).apply()
    }

    private inline fun <reified E : Enum<E>> SharedPreferences.enum(key: String, fallback: E): E {
        val raw = getString(key, null) ?: return fallback
        return runCatching { enumValueOf<E>(raw) }.getOrDefault(fallback)
    }

    companion object {
        /** Also opened directly by [AppLocale], which runs before this class is constructed. */
        const val PREFS_NAME = "settings"

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
        const val K_LANGUAGE = "app_language"
        const val K_CHANNEL_SORT = "channel_sort"
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
        const val K_UPDATE_AUTO = "update_auto_check"
        const val K_UPDATE_WIFI_ONLY = "update_wifi_only"
        const val K_UPDATE_SKIPPED = "update_skipped_code"
        const val K_UPDATE_LAST_CHECK = "update_last_check"
        const val K_UPDATE_MANIFEST = "update_last_manifest"
        const val K_FEATURED_URL = "featured_feed_url"
        const val K_FEATURED_LAST_CHECK = "featured_last_check"
        const val K_FEATURED_FEED = "featured_last_feed"
        const val K_WATCH_NEXT = "watch_next_enabled"
        const val K_SHOW_HOME_APPS = "show_home_apps"
        const val K_SCREENSAVER = "screensaver_enabled"
        const val K_SCREENSAVER_IDLE = "screensaver_idle_minutes"
        const val K_SCREENSAVER_TASKS = "screensaver_tasks"
        const val K_TASK_URL = "task_feed_url"
        const val K_TASK_LAST_CHECK = "task_last_check"
        const val K_TASK_FEED = "task_last_feed"
        const val K_SAVER_FLASHES = "screensaver_flashes"
        const val K_SAVER_COLOURS = "screensaver_colours"
        const val K_FLASH_SPEED = "screensaver_flash_speed"
        const val K_NEWS_SOURCES = "news_sources"
        const val K_NEWS_LAST_CHECK = "news_last_check"
        const val K_NEWS_DIGESTS = "news_last_digests"
        const val K_SPORT_SLOTS = "sport_slots"
        const val K_SPORT_PANELS = "sport_panels"
        const val K_NEWS_SLOTS = "news_slots"
    }
}
