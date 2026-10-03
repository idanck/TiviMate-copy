package com.tivicopy.player.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
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
    /**
     * The user's intent, as distinct from [playing]: a rebuffering stream is not playing but
     * has not been paused. Play/pause buttons must render and act on this — an icon derived
     * from [playing] shows Play during a stall, and "Play" that flips the flag then pauses.
     */
    val playWhenReady: Boolean = false,
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
    /**
     * The song the stream says is playing, already cleaned ([StreamTitle]); null when the stream
     * announces nothing, or nothing worth showing. Only Shoutcast/Icecast radio streams send one,
     * and only some of those — see ADR-026.
     */
    val streamTitle: String? = null,
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
    private var builtBuffer: BufferDurations? = null
    private var profile = RequestProfile()

    private companion object {
        /** Smallest custom minimum buffer offered, matching the slider in Settings. */
        const val MIN_CUSTOM_BUFFER_MS = 200

        /** How far `maxBufferMs` is kept above `minBufferMs` so loading has a band to work in. */
        const val MIN_BUFFER_BAND_MS = 500
    }

    /** Bumped whenever the source changes, so a retry armed for the previous one is dropped. */
    private var playbackGeneration = 0

    /** Set by the host so a failover can be scheduled off the player's callback thread. */
    var onScheduleRetry: ((delayMillis: Long, action: () -> Unit) -> Unit)? = null

    /** Non-fatal notices worth a toast: which fallback was taken and why. */
    var onNotice: ((AppError, String) -> Unit)? = null

    fun exoPlayer(): ExoPlayer = player ?: build().also { player = it }

    /**
     * The one play/pause toggle every surface shares — the transport button, the mini bar, the
     * remote's key and the floating window's action. On the existing instance only: building a
     * player just to pause it would leak a codec (see [positionAndDuration]'s warning), and the
     * floating window's action can land after [release] on the way out of the app.
     */
    fun togglePlayPause() {
        val exo = player ?: return
        val resuming = !exo.playWhenReady
        // Resuming from a terminal failure: retries exhausted leave the player sitting in
        // IDLE, where the flag alone starts nothing — every play button would flip its icon
        // and do nothing. Preparing again retries the current source.
        if (resuming && exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.playWhenReady = resuming
    }

    /**
     * Background pause/resume, on the existing instance only — the host pauses when it stops
     * without a picture-in-picture window, because sound from a fully backgrounded app with no
     * notification and no way to stop it is a defect, not a feature.
     */
    fun setPlayWhenReady(wanted: Boolean) {
        player?.playWhenReady = wanted
    }

    /**
     * Where playback has reached, and how long the item is, or null when there is no player.
     *
     * [exoPlayer] builds one on demand, which is right for a caller about to play something and
     * wrong for one that only wants to note a position: that caller would construct a player,
     * read zero out of it, and leave it behind. Duration is [C.TIME_UNSET] until the source has
     * been read, so the caller must treat a non-positive value as unknown.
     */
    fun positionAndDuration(): Pair<Long, Long>? = player?.let { it.currentPosition to it.duration }

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
                // A choice made in the playback menu outranks the stored preference, and outlives
                // this instance: a failover or a decoder change rebuilds the player from scratch,
                // and without carrying the choice here the audio silently reverted to the
                // provider's default mid-programme.
                .setPreferredAudioLanguage(
                    chosenAudioLanguage ?: s.preferredAudioLanguage.takeIf { it.isNotBlank() },
                )
                .setPreferredTextLanguage(
                    chosenTextLanguage ?: s.preferredSubtitleLanguage.takeIf { it.isNotBlank() },
                )
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, textTracksDisabled)
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
        builtBuffer = buffer

        exo.addListener(listener)
        exo.addAnalyticsListener(analytics)
        exo.playWhenReady = true
        // A rebuild while the phone's player screen is collapsed must come up video-less too,
        // or the saving below silently ends at the first decoder or buffer change.
        applyVideoOutputWanted(exo)
        _currentPlayer.value = exo
        return exo
    }

    // ---------------------------------------------------------- video output

    private var videoOutputWanted = true

    /**
     * Whether anything on screen is actually showing the video track.
     *
     * The phone's browse screens keep the stream running behind a mini player with no surface
     * anywhere, so decoding video there is battery and bandwidth spent on frames nothing
     * displays. Disabling the track — rather than pausing — keeps the audio alive, and
     * re-enabling it picks the video back up at the live edge. [build] re-applies the flag so
     * an instance rebuild while collapsed stays video-less.
     */
    fun setVideoOutputWanted(wanted: Boolean) {
        if (videoOutputWanted == wanted) return
        videoOutputWanted = wanted
        player?.let(::applyVideoOutputWanted)
    }

    private fun applyVideoOutputWanted(exo: ExoPlayer) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoOutputWanted)
            .build()
    }

    private fun bufferValues(s: Settings.Snapshot): BufferDurations {
        val preset = s.bufferPreset
        if (preset != BufferPreset.CUSTOM) {
            return BufferDurations.reconcile(
                preset.minMs,
                preset.maxMs,
                preset.playbackMs,
                preset.rebufferMs,
            )
        }
        // FR-PLB-16 offers the millisecond values so a low minimum can actually lower latency.
        // Reconciling alone would raise the minimum to meet CUSTOM's fixed thresholds, which
        // silently discards everything the slider offers below 3 s, so the thresholds are
        // brought down to the chosen minimum instead of the minimum being pushed up to them.
        // The floor matches the slider's own minimum; a zero-length buffer is not a latency
        // setting, it is a stall. The band above it keeps `maxBufferMs` clear of `minBufferMs`,
        // because the two sliders move independently and an inverted pair would otherwise
        // collapse into a window with no hysteresis at all.
        val min = s.customBufferMinMs.coerceAtLeast(MIN_CUSTOM_BUFFER_MS)
        return BufferDurations.reconcile(
            minMs = min,
            maxMs = s.customBufferMaxMs.coerceAtLeast(min + MIN_BUFFER_BAND_MS),
            playbackMs = preset.playbackMs.coerceAtMost(min),
            rebufferMs = preset.rebufferMs.coerceAtMost(min),
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
            // The four durations are everything the load control is built from, so comparing
            // the preset as well only forces a visible stream restart when two presets happen
            // to reconcile to the same numbers.
            buffer != builtBuffer

        if (stale) {
            releasePlayer()
            player = build()
        }
        return stale
    }

    // ------------------------------------------------------------------- play

    fun play(channel: Channel, profileForChannel: RequestProfile, urlOverride: String? = null) {
        playbackGeneration++
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
        playbackGeneration++
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
                // Otherwise the card keeps the previous channel's failover counters and
                // claims this one is on "2/3" of sources it does not have.
                sourceIndex = 0,
                sourceCount = currentUrls.size,
                retriesUsed = 0,
                streamTitle = null,
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
            // The previous source's song, or the previous station's, is not this one's.
            streamTitle = null,
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
        // Anything already queued belongs to a player that is about to stop existing.
        playbackGeneration++
        releasePlayer()
        trackSelector = null
        // The source is forgotten along with the player. The engine outlives the activity on
        // the ServiceLocator, and `applySettingsChange` treats a non-empty URL list as "had a
        // source": left populated, the first settings change of the *next* session silently
        // resurrected the previous session's stream — on the phone as audio with no UI
        // attached to it, because nothing was marked as playing.
        currentChannel = null
        currentUrls = emptyList()
        currentSourceIndex = 0
        // `releasePlayer` detaches the listener before releasing — nothing will ever correct
        // the last state it saw. Left alone, the next launch draws a spinner or an error
        // banner for a player that no longer exists.
        _status.value = PlaybackStatus()
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

    /**
     * What the user picked in the playback menu, remembered as a *language* rather than an index.
     *
     * The override itself cannot survive a rebuild: group indices belong to the stream the current
     * instance parsed, and a failover to another URL — or a decoder change — reparses it. The
     * language does survive, and is what the viewer actually chose; re-applying it as the
     * preferred language in [build] is what keeps a Hebrew audio track selected across a failover
     * instead of silently reverting to the provider's default mid-programme.
     *
     * Null means "no choice made", so the stored preference in Settings still applies.
     */
    private var chosenAudioLanguage: String? = null
    private var chosenTextLanguage: String? = null
    private var textTracksDisabled = false

    fun selectTrack(option: TrackOption) {
        val exo = player ?: return
        val group = exo.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        when (group.type) {
            C.TRACK_TYPE_AUDIO -> chosenAudioLanguage = option.language
            C.TRACK_TYPE_TEXT -> {
                chosenTextLanguage = option.language
                textTracksDisabled = false
            }
        }
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .setTrackTypeDisabled(group.type, false)
            .build()
    }

    /**
     * Turns subtitles off for this stream.
     *
     * A named wrapper so the UI does not have to reach for a media3 track-type constant to say
     * something as ordinary as "off"; the picker in the playback menu is the only caller, and the
     * alternative was importing `C` into a Compose file.
     */
    fun disableTextTracks() {
        // Remembered for the same reason a chosen language is: subtitles turned off before a
        // failover should stay off after it.
        textTracksDisabled = true
        chosenTextLanguage = null
        disableTrackType(C.TRACK_TYPE_TEXT)
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

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _status.value = _status.value.copy(playWhenReady = playWhenReady)
        }

        override fun onTracksChanged(tracks: Tracks) = refreshDiagnostics()

        override fun onMetadata(metadata: Metadata) {
            // A batch without an ICY entry says nothing about the song, so the last one stands.
            val raw = StreamTitle.icyTitle(metadata) ?: return
            _status.value = _status.value.copy(
                streamTitle = StreamTitle.clean(raw, stationName = currentChannel?.displayName),
            )
        }

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
            _status.value = _status.value.copy(
                error = mapped,
                errorDetail = error.errorCodeName,
                playing = false,
                streamTitle = null,
            )
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
            // Nothing is playing any more, so no song is either — the notification would
            // otherwise go on naming one over a dead stream.
            streamTitle = null,
        )
    }

    /**
     * Runs [action] after [delayMillis], unless whatever it was scheduled for has been replaced.
     *
     * The host posts these to a `Handler` and nothing removes the callback, so a retry armed for
     * one channel used to fire against whichever channel was playing when it landed — up to
     * fifteen seconds later, re-preparing a stream that was working and reporting the wrong
     * title. The generation is bumped by every new source, so a stale action becomes a no-op.
     */
    private fun schedule(delayMillis: Long, action: () -> Unit) {
        val generation = playbackGeneration
        val guarded = { if (generation == playbackGeneration) action() }
        val scheduler = onScheduleRetry
        if (scheduler != null) scheduler(delayMillis, guarded) else guarded()
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
