package com.tivicopy.player.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.tivicopy.player.core.AppError
import com.tivicopy.player.core.TextNormalizer
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.DecoderMode
import com.tivicopy.player.data.net.HttpFactory
import com.tivicopy.player.data.net.RequestProfile
import com.tivicopy.player.data.prefs.BufferPreset
import com.tivicopy.player.data.prefs.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What is on screen right now, for the info card and the diagnostics view. */
data class PlaybackStatus(
    val title: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val error: AppError? = null,
    val errorDetail: String? = null,
    val sourceIndex: Int = 0,
    val sourceCount: Int = 0,
    val retriesUsed: Int = 0,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val bitrate: Int = 0,
    val droppedFrames: Int = 0,
    val bufferedMs: Long = 0,
    val videoDecoderMode: DecoderMode = DecoderMode.AUTO,
    val audioDecoderMode: DecoderMode = DecoderMode.AUTO,
    val softwareFallbackApplied: Boolean = false,
    val currentUrlRedacted: String? = null,
    val isLive: Boolean = false,
)

/**
 * Buffer durations, reconciled to what `DefaultLoadControl` will accept.
 *
 * The load control validates its four durations against each other and *throws* when they do
 * not line up, rather than clamping — so an inconsistent set does not degrade playback, it
 * takes the process down the moment the player is built. Since the custom values come from
 * preferences and can outlive any change to the presets, the invariants are enforced here on
 * every build rather than trusted at the call site.
 */
data class BufferDurations(
    val minMs: Int,
    val maxMs: Int,
    val playbackMs: Int,
    val rebufferMs: Int,
) {
    companion object {
        fun reconcile(minMs: Int, maxMs: Int, playbackMs: Int, rebufferMs: Int): BufferDurations {
            val playback = playbackMs.coerceAtLeast(0)
            val rebuffer = rebufferMs.coerceAtLeast(0)
            // Neither threshold may exceed the minimum buffer, and the maximum may not fall
            // below it.
            val min = minMs.coerceAtLeast(maxOf(playback, rebuffer))
            return BufferDurations(min, maxMs.coerceAtLeast(min), playback, rebuffer)
        }
    }
}

/** One selectable audio or subtitle track. */
data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val selected: Boolean,
)

/**
 * The single ExoPlayer instance and everything the UI needs from it.
 *
 * Responsibilities that live here rather than in the UI:
 *
 * - building the player with the right decoder selectors and buffer values, and *rebuilding*
 *   it when those change, because neither can be swapped on a live instance;
 * - retry with backoff and failover down the channel's declared URL list (FR-PLB-17);
 * - a one-shot software retry when a hardware decoder refuses to initialise (FR-PLB-05);
 * - turning a [PlaybackException] into an [AppError] the UI can explain, including the DRM
 *   case that must never be a silent black screen (FR-TVI-06).
 */
class PlayerEngine(
    private val context: Context,
    private val settings: Settings,
) {

    private val _status = MutableStateFlow(PlaybackStatus())
    val status: StateFlow<PlaybackStatus> = _status

    /**
     * The live instance, published so the view can re-attach.
     *
     * Neither the decoder selectors nor the load control can be swapped on a running
     * ExoPlayer, so a settings change replaces the whole instance. Anything holding the old
     * one — the `PlayerView` in particular — would otherwise keep a released player and show
     * a black screen.
     */
    private val _currentPlayer = MutableStateFlow<ExoPlayer?>(null)
    val currentPlayer: StateFlow<ExoPlayer?> = _currentPlayer

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    private var currentChannel: Channel? = null
    private var currentUrls: List<String> = emptyList()
    private var currentSourceIndex = 0
    private var retriesUsed = 0
    private var softwareFallbackApplied = false
    private var builtVideoMode = DecoderMode.AUTO
    private var builtAudioMode = DecoderMode.AUTO
    private var builtPreset: BufferPreset? = null
    private var builtBuffer: BufferDurations? = null
    private var profile = RequestProfile()

    /** Set by the host so a failover can be scheduled off the player's callback thread. */
    var onScheduleRetry: ((delayMillis: Long, action: () -> Unit) -> Unit)? = null

    /** Non-fatal notices worth a toast: which fallback was taken and why. */
    var onNotice: ((AppError, String) -> Unit)? = null

    fun exoPlayer(): ExoPlayer = player ?: build().also { player = it }

    // ------------------------------------------------------------------- build

    private fun build(): ExoPlayer {
        val s = settings.state.value
        val videoMode = currentChannel?.videoDecoder?.takeIf { it != DecoderMode.AUTO } ?: s.videoDecoder
        val audioMode = currentChannel?.audioDecoder?.takeIf { it != DecoderMode.AUTO } ?: s.audioDecoder
        val effectiveVideoMode = if (softwareFallbackApplied) DecoderMode.SOFTWARE else videoMode

        val buffer = bufferValues(s)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                buffer.minMs,
                buffer.maxMs,
                buffer.playbackMs,
                buffer.rebufferMs,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val selector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguage(s.preferredAudioLanguage.takeIf { it.isNotBlank() })
                .setPreferredTextLanguage(s.preferredSubtitleLanguage.takeIf { it.isNotBlank() })
                .setSelectUndeterminedTextLanguage(false)
                .setTunnelingEnabled(false)
                .build()
        }
        trackSelector = selector

        val httpFactory = OkHttpDataSource.Factory(HttpFactory.client(profile))
            .setUserAgent(profile.userAgent)
            .setDefaultRequestProperties(profile.headers)

        val exo = ExoPlayer.Builder(context)
            .setRenderersFactory(SelectiveRenderersFactory(context, effectiveVideoMode, audioMode))
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)),
            )
            .build()

        builtVideoMode = effectiveVideoMode
        builtAudioMode = audioMode
        builtPreset = s.bufferPreset
        builtBuffer = buffer

        exo.addListener(listener)
        exo.addAnalyticsListener(analytics)
        exo.playWhenReady = true
        _currentPlayer.value = exo
        return exo
    }

    private fun bufferValues(s: Settings.Snapshot): BufferDurations {
        val preset = s.bufferPreset
        val custom = preset == BufferPreset.CUSTOM
        return BufferDurations.reconcile(
            minMs = if (custom) s.customBufferMinMs else preset.minMs,
            maxMs = if (custom) s.customBufferMaxMs else preset.maxMs,
            playbackMs = preset.playbackMs,
            rebufferMs = preset.rebufferMs,
        )
    }

    /** @return true when the instance was actually replaced. */
    private fun rebuildIfNeeded(): Boolean {
        val s = settings.state.value
        val videoMode = if (softwareFallbackApplied) DecoderMode.SOFTWARE
        else currentChannel?.videoDecoder?.takeIf { it != DecoderMode.AUTO } ?: s.videoDecoder
        val audioMode = currentChannel?.audioDecoder?.takeIf { it != DecoderMode.AUTO } ?: s.audioDecoder
        val buffer = bufferValues(s)

        val stale = player == null ||
            videoMode != builtVideoMode ||
            audioMode != builtAudioMode ||
            s.bufferPreset != builtPreset ||
            buffer != builtBuffer

        if (stale) {
            releasePlayer()
            player = build()
        }
        return stale
    }

    // ------------------------------------------------------------------- play

    fun play(channel: Channel, profileForChannel: RequestProfile, urlOverride: String? = null) {
        currentChannel = channel
        profile = profileForChannel
        currentUrls = urlOverride?.let(::listOf) ?: channel.urls
        currentSourceIndex = 0
        retriesUsed = 0
        softwareFallbackApplied = false
        // The data source factory captures the profile, so a channel with its own headers or
        // user-agent needs the player rebuilt rather than just a new MediaItem.
        releasePlayer()
        player = build()
        startCurrentSource(channel.displayName)
    }

    /** Plays an arbitrary URL (catch-up, VOD, a recording) with the channel's request profile. */
    fun playUrl(title: String, url: String, profileForItem: RequestProfile, startPositionMs: Long = 0) {
        currentChannel = null
        profile = profileForItem
        currentUrls = listOf(url)
        currentSourceIndex = 0
        retriesUsed = 0
        softwareFallbackApplied = false
        releasePlayer()
        player = build()
        startCurrentSource(title, startPositionMs)
    }

    private fun startCurrentSource(title: String, startPositionMs: Long = 0) {
        val url = currentUrls.getOrNull(currentSourceIndex) ?: run {
            _status.value = _status.value.copy(
                title = title,
                error = AppError(AppError.Kind.NotFound, "channel has no playable URL"),
                // Terminal: `errorDetail` is what tells the UI to stop showing a spinner.
                errorDetail = "no url",
                buffering = false,
                playing = false,
            )
            return
        }
        val exo = exoPlayer()
        val item = MediaItem.Builder()
            .setUri(url)
            .apply {
                currentChannel?.licenseKey?.takeIf { it.isNotBlank() }?.let { key ->
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(drmSchemeUuid(currentChannel?.licenseType))
                            .setLicenseUri(key)
                            .build(),
                    )
                }
            }
            .build()

        exo.setMediaItem(item, startPositionMs)
        exo.prepare()
        exo.playWhenReady = true

        _status.value = _status.value.copy(
            title = title,
            error = null,
            errorDetail = null,
            // Claimed here rather than waiting for the first STATE_BUFFERING callback, so the
            // UI can say "connecting" from the moment the channel is chosen instead of showing
            // a black frame with nothing on it.
            buffering = true,
            playing = false,
            sourceIndex = currentSourceIndex,
            sourceCount = currentUrls.size,
            retriesUsed = retriesUsed,
            videoDecoderMode = builtVideoMode,
            audioDecoderMode = builtAudioMode,
            softwareFallbackApplied = softwareFallbackApplied,
            currentUrlRedacted = TextNormalizer.redactUrl(url),
            droppedFrames = 0,
        )
    }

    private fun drmSchemeUuid(licenseType: String?): java.util.UUID = when {
        licenseType?.contains("clearkey", true) == true -> C.CLEARKEY_UUID
        licenseType?.contains("playready", true) == true -> C.PLAYREADY_UUID
        else -> C.WIDEVINE_UUID
    }

    fun stop() {
        player?.stop()
    }

    fun release() {
        releasePlayer()
        trackSelector = null
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.removeAnalyticsListener(analytics)
            it.release()
        }
        player = null
        _currentPlayer.value = null
    }

    /**
     * Re-applies settings that can only take effect on a fresh instance, and resumes whatever
     * was playing. A live stream restarts at the edge; on-demand keeps its position.
     */
    fun applySettingsChange() {
        val title = _status.value.title
        val wasLive = _status.value.isLive
        val position = player?.currentPosition ?: 0
        val hadSource = currentUrls.isNotEmpty()
        if (rebuildIfNeeded() && hadSource) {
            startCurrentSource(title, if (wasLive) 0 else position)
        }
    }

    /** Re-applies a per-channel decoder change without losing the current position. */
    fun reloadWithDecoders(channel: Channel, profileForChannel: RequestProfile) {
        val position = player?.currentPosition ?: 0
        currentChannel = channel
        profile = profileForChannel
        softwareFallbackApplied = false
        releasePlayer()
        player = build()
        startCurrentSource(channel.displayName, if (_status.value.isLive) 0 else position)
    }

    // ---------------------------------------------------------------- tracks

    fun audioTracks(): List<TrackOption> = tracksOfType(C.TRACK_TYPE_AUDIO)

    fun textTracks(): List<TrackOption> = tracksOfType(C.TRACK_TYPE_TEXT)

    fun videoTracks(): List<TrackOption> = tracksOfType(C.TRACK_TYPE_VIDEO)

    private fun tracksOfType(type: Int): List<TrackOption> {
        val tracks: Tracks = player?.currentTracks ?: return emptyList()
        val out = ArrayList<TrackOption>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = buildString {
                    format.label?.let { append(it).append(' ') }
                    format.language?.let { append('[').append(it).append("] ") }
                    when (type) {
                        C.TRACK_TYPE_VIDEO -> if (format.height > 0) append("${format.width}x${format.height} ")
                        C.TRACK_TYPE_AUDIO -> if (format.channelCount > 0) append("${format.channelCount}ch ")
                    }
                    format.sampleMimeType?.substringAfter('/')?.let { append(it.uppercase()) }
                }.trim().ifBlank { "Track ${i + 1}" }
                out += TrackOption(
                    groupIndex = groupIndex,
                    trackIndex = i,
                    label = label,
                    language = format.language,
                    selected = group.isTrackSelected(i),
                )
            }
        }
        return out
    }

    fun selectTrack(option: TrackOption) {
        val exo = player ?: return
        val group = exo.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .setTrackTypeDisabled(group.type, false)
            .build()
    }

    fun disableTrackType(type: Int) {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, true)
            .build()
    }

    // ------------------------------------------------------------ diagnostics

    fun refreshDiagnostics() {
        val exo = player ?: return
        val format = exo.videoFormat
        val audio = exo.audioFormat
        _status.value = _status.value.copy(
            playing = exo.isPlaying,
            buffering = exo.playbackState == Player.STATE_BUFFERING,
            videoCodec = format?.sampleMimeType,
            audioCodec = audio?.sampleMimeType,
            width = format?.width ?: 0,
            height = format?.height ?: 0,
            frameRate = format?.frameRate ?: 0f,
            bitrate = format?.bitrate ?: 0,
            bufferedMs = (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0),
            isLive = exo.isCurrentMediaItemLive,
        )
    }

    // --------------------------------------------------------------- failover

    /** Dropped frames are only reported through the analytics surface, not Player.Listener. */
    private val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onDroppedVideoFrames(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            _status.value = _status.value.copy(droppedFrames = _status.value.droppedFrames + droppedFrames)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            _status.value = _status.value.copy(
                buffering = state == Player.STATE_BUFFERING,
                playing = state == Player.STATE_READY && player?.playWhenReady == true,
            )
            if (state == Player.STATE_READY) {
                retriesUsed = 0
                refreshDiagnostics()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _status.value = _status.value.copy(playing = isPlaying)
        }

        override fun onTracksChanged(tracks: Tracks) = refreshDiagnostics()

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    private fun handleError(error: PlaybackException) {
        val mapped = mapError(error)
        val title = _status.value.title

        // A hardware decoder that will not initialise gets exactly one software retry, and the
        // user is told what changed rather than seeing the picture silently differ (FR-PLB-05).
        val decoderInit = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
        if (decoderInit && !softwareFallbackApplied) {
            softwareFallbackApplied = true
            onNotice?.invoke(
                AppError(AppError.Kind.DecoderFailed, error.errorCodeName),
                "software",
            )
            schedule(0) {
                releasePlayer()
                player = build()
                startCurrentSource(title)
            }
            return
        }

        // DRM the device cannot satisfy is terminal; say so instead of retrying forever.
        if (mapped.kind == AppError.Kind.DrmUnsupported) {
            _status.value = _status.value.copy(error = mapped, errorDetail = error.errorCodeName, playing = false)
            return
        }

        val maxRetries = settings.state.value.retries
        if (retriesUsed < maxRetries) {
            retriesUsed++
            val backoff = (1_000L shl (retriesUsed - 1)).coerceAtMost(15_000L)
            _status.value = _status.value.copy(error = mapped, retriesUsed = retriesUsed)
            schedule(backoff) { startCurrentSource(title) }
            return
        }

        // Retries exhausted on this URL: move to the next declared source for the channel.
        if (currentSourceIndex + 1 < currentUrls.size) {
            currentSourceIndex++
            retriesUsed = 0
            onNotice?.invoke(mapped, "source ${currentSourceIndex + 1}/${currentUrls.size}")
            schedule(500) { startCurrentSource(title) }
            return
        }

        _status.value = _status.value.copy(
            error = mapped,
            errorDetail = error.errorCodeName,
            playing = false,
            retriesUsed = retriesUsed,
        )
    }

    private fun schedule(delayMillis: Long, action: () -> Unit) {
        val scheduler = onScheduleRetry
        if (scheduler != null) scheduler(delayMillis, action) else action()
    }

    private fun mapError(error: PlaybackException): AppError = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        -> AppError(AppError.Kind.DrmUnsupported, error.errorCodeName, error)

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> AppError(AppError.Kind.DecoderFailed, error.errorCodeName, error)

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val cause = error.cause
            val code = (cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (code != null) AppError.fromHttp(code) else AppError(AppError.Kind.Network, error.errorCodeName, error)
        }

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            AppError(AppError.Kind.Timeout, error.errorCodeName, error)

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        -> AppError(AppError.Kind.Network, error.errorCodeName, error)

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            AppError(AppError.Kind.NotFound, error.errorCodeName, error)

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> AppError(AppError.Kind.UnsupportedFormat, error.errorCodeName, error)

        else -> AppError(AppError.Kind.Unknown, error.errorCodeName, error)
    }
}
