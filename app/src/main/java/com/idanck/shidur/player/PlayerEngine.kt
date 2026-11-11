package com.idanck.shidur.player

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.idanck.shidur.core.AppError
import com.idanck.shidur.core.TextNormalizer
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.DecoderMode
import com.idanck.shidur.data.net.HttpFactory
import com.idanck.shidur.data.net.RequestProfile
import com.idanck.shidur.data.prefs.BufferPreset
import com.idanck.shidur.data.prefs.Settings
import com.idanck.shidur.player.timeshift.TimeshiftRoutingDataSourceFactory
import com.idanck.shidur.player.timeshift.TimeshiftSession
import com.idanck.shidur.player.timeshift.TimeshiftUnavailableException
import java.io.File
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
    /**
     * A split-screen panel's live stream was ended by the other side, cleanly, as if the channel
     * had finished, and trying again did not help: what an IPTV server does to a connection beyond
     * the account's limit when a newer one opens (ADR-041). Only a [PlayerEngine.Role.PANEL] says
     * so; the full-screen player does not look for it.
     */
    val closed: Boolean = false,
    /**
     * The live channel plays from the timeshift buffer (ADR-065): it can be paused, rewound and
     * fast-forwarded, and [PlayerEngine.liveWindow] says where in the buffer it is.
     */
    val timeshift: Boolean = false,
) {
    /** No retry is coming: the stream failed for good, or its server closed it ([closed]). */
    val failed: Boolean get() = closed || errorDetail != null
}

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

/**
 * Where buffered live playback stands (ADR-065): how much programme the buffer holds, where in it
 * the picture is, and so how far behind live.
 */
data class LiveWindow(val durationMs: Long, val positionMs: Long) {
    /** Behind the live edge by this much; under [LIVE_SLACK_MS] counts as live. */
    val behindMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)

    val atLive: Boolean get() = behindMs <= LIVE_SLACK_MS

    companion object {
        /** The player's own distance from the edge, and a segment's worth more. */
        const val LIVE_SLACK_MS = 12_000L
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
 * One ExoPlayer instance and everything the UI needs from it.
 *
 * The app's full-screen player is one of these ([Role.MAIN], on the `ServiceLocator`), and so is
 * each panel of the split screen, Sport and News mode ([Role.PANEL], ADR-054): every panel is a
 * player of its own, with the same retries, failover, decoder choice, buffer preset and track
 * selection as the full-screen one. The media session, the floating window, the launcher's
 * "continue watching" row and resume marks hang off the `ServiceLocator`'s instance, not off this
 * class, so a panel has none of them.
 *
 * What a panel does differently, because several play at once:
 *
 * - its sound plays at volume zero unless it is the [setAudible] one, and only that one asks the
 *   system for audio focus — several players asking would each take it from the one before and
 *   pause it;
 * - encoded audio (AC-3, E-AC-3) is decoded rather than passed through to an HDMI receiver, which
 *   ignores the volume and takes one passed-through stream at a time;
 * - its buffer has a ceiling in bytes, so several of them fit on a television box;
 * - a live stream that ends was closed by its server: the next address is tried, then the same one
 *   once more, and then the panel says so ([PlaybackStatus.closed]);
 * - the host's pause for every panel ([setPaused]) holds across retries.
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
    private val role: Role = Role.MAIN,
) {

    /** The full-screen player, or one panel of the split screen (ADR-054). */
    enum class Role { MAIN, PANEL }

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
    /** Rounds of [scheduleReconnect] since the channel last played; reset by READY and by a new source. */
    private var reconnectAttempts = 0
    private var softwareFallbackApplied = false
    private var builtVideoMode = DecoderMode.AUTO
    private var builtAudioMode = DecoderMode.AUTO
    private var builtBuffer: BufferDurations? = null
    private var profile = RequestProfile()

    /** What the player's own sources read the provider with; a timeshift session opens it too. */
    private var upstreamFactory: DataSource.Factory? = null

    /** Whether the channel now playing asked for the timeshift buffer ([play]). */
    private var timeshiftWanted = false

    /** The buffer the current source plays from, if it does (ADR-065). */
    private var timeshift: TimeshiftSession? = null

    /** Addresses of this channel that turned out not to be buffered (not MPEG-TS, and so on). */
    private val directOnly = HashSet<String>()

    /** Set while the buffer was let go of for the background; resuming starts live again. */
    private var timeshiftSuspended = false

    private companion object {
        /** Smallest custom minimum buffer offered, matching the slider in Settings. */
        const val MIN_CUSTOM_BUFFER_MS = 200

        /** How far `maxBufferMs` is kept above `minBufferMs` so loading has a band to work in. */
        const val MIN_BUFFER_BAND_MS = 500

        const val TAG = "PlayerEngine"

        /**
         * A panel's ceiling on buffered bytes. Without one the load control sizes itself for a
         * single player, well over a hundred megabytes of video; four of those on a television
         * box with two gigabytes is how an app gets killed (ADR-041).
         */
        const val PANEL_TARGET_BUFFER_BYTES = 24 * 1024 * 1024

        /** How long after a panel's stream is closed the one automatic try waits. */
        const val END_RETRY_MS = 2_000L

        /** How long a panel's stream must have played for a later close to earn another try. */
        const val STEADY_MS = 60_000L

        /** The wait before a panel tries a live channel's next address after its stream ended. */
        const val NEXT_SOURCE_MS = 500L
    }

    /** Whether this player is heard. Always, for [Role.MAIN]; see [setAudible]. */
    private var audible = role == Role.MAIN

    /** Set by [setPaused]: every source started from now on starts paused. */
    private var heldPaused = false

    /** Whether the one automatic try after a panel's stream was closed has been spent. */
    private var endRetried = false

    /**
     * When the stream from the current address first became ready, on the monotonic clock; zero
     * until it has. A rebuffer does not move it: what it measures is how long the stream has been
     * up, not how long since the last stall.
     */
    private var readyAt = 0L

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
        // The buffer was let go of for the background (see [setPlayWhenReady]): live again.
        if (resuming && timeshiftSuspended) {
            timeshiftSuspended = false
            startCurrentSource(_status.value.title)
            return
        }
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
        val exo = player ?: return
        // The buffer goes with the screen: an app in the background must not go on taking the
        // provider's one connection and the disk (ADR-065). Back in front, the channel starts
        // again, live, as it did before the buffer existed.
        if (!wanted && timeshift != null) {
            stopTimeshift()
            timeshiftSuspended = true
            exo.stop()
            exo.playWhenReady = false
            return
        }
        if (wanted && timeshiftSuspended) {
            timeshiftSuspended = false
            startCurrentSource(_status.value.title)
            return
        }
        // Back from the background onto a stream that failed meanwhile: the flag alone starts
        // nothing in IDLE (see [togglePlayPause]), so the viewer came back to a dead channel.
        if (wanted && exo.playbackState == Player.STATE_IDLE && exo.mediaItemCount > 0) exo.prepare()
        exo.playWhenReady = wanted
    }

    /**
     * Set while the player stands paused because another app took the sound for good, and only
     * then — any other change to play/pause, by the user or by the host, clears it. That is what
     * tells "another app took over" apart from "the user paused", which a resume must never undo.
     */
    private var pausedForAudioFocus = false

    /**
     * Picks playback back up after another app took the sound, and does nothing otherwise. For
     * the host to call when this app is back in front of the viewer; see
     * `MainActivity.resumeIfFocusTookIt` for when that is.
     */
    fun resumeAfterAudioFocusLoss() {
        val exo = player ?: return
        if (!pausedForAudioFocus) return
        pausedForAudioFocus = false
        exo.playWhenReady = true
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

    // ------------------------------------------------------------ panels

    /**
     * Whether this player is the one heard: volume, and the system's audio focus, follow it. For a
     * [Role.PANEL]; the split screen hears one panel at a time and moves the sound at once, which
     * muting allows and turning the audio track off would not — re-enabling a track re-buffers.
     * Applied to the instance now and to any built later.
     */
    fun setAudible(value: Boolean) {
        if (audible == value) return
        audible = value
        player?.let(::applyAudible)
    }

    /**
     * The split screen's pause for every panel at once. Unlike [togglePlayPause] it holds: a retry
     * or a failover while paused starts the next source paused too.
     */
    fun setPaused(value: Boolean) {
        heldPaused = value
        setPlayWhenReady(!value)
    }

    private fun applyAudible(exo: ExoPlayer) {
        exo.volume = if (audible) 1f else 0f
        exo.setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ audible)
    }

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
            .apply { if (role == Role.PANEL) setTargetBufferBytes(PANEL_TARGET_BUFFER_BYTES) }
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
        upstreamFactory = httpFactory

        val exo = ExoPlayer.Builder(context)
            .setRenderersFactory(
                SelectiveRenderersFactory(
                    context,
                    effectiveVideoMode,
                    audioMode,
                    encodedAudioPassthrough = role == Role.MAIN,
                ),
            )
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                // The timeshift playlist has a scheme of its own; everything else, its segment
                // files included, goes where it always went.
                DefaultMediaSourceFactory(
                    TimeshiftRoutingDataSourceFactory(DefaultDataSource.Factory(context, httpFactory)),
                ),
            )
            // Without these the player never asked the system for audio focus, so it never
            // heard that another app had started making sound either: a channel kept playing
            // over the other app's audio, in the floating window or from the radio's
            // background service, until something else stopped it. With focus handled, a
            // notification or the voice assistant briefly lowers or pauses it and it comes back
            // on its own; another app starting its own playback pauses it for good. A panel asks
            // only while it is the one heard ([setAudible]).
            .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ audible)
            // Headphones pulled out, or a Bluetooth pair disconnecting, pauses rather than
            // carrying on out of the speaker (owner's call, 2026-10-16). Media3 listens for the
            // system's "audio becoming noisy" and pauses; play resumes it as any pause.
            .setHandleAudioBecomingNoisy(true)
            .build()

        builtVideoMode = effectiveVideoMode
        builtAudioMode = audioMode
        builtBuffer = buffer

        exo.addListener(listener)
        exo.addAnalyticsListener(analytics)
        exo.volume = if (audible) 1f else 0f
        exo.playWhenReady = !heldPaused
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

    /**
     * @param timeshift play from the timeshift buffer where the stream allows it (ADR-065): the
     *   host says so for a television channel when the setting is on, never for radio
     */
    fun play(
        channel: Channel,
        profileForChannel: RequestProfile,
        urlOverride: String? = null,
        timeshift: Boolean = false,
    ) {
        playbackGeneration++
        currentChannel = channel
        timeshiftWanted = timeshift && role == Role.MAIN && channel.licenseKey.isNullOrBlank()
        directOnly.clear()
        timeshiftSuspended = false
        profile = profileForChannel
        currentUrls = urlOverride?.let(::listOf) ?: channel.urls
        currentSourceIndex = 0
        retriesUsed = 0
        reconnectAttempts = 0
        softwareFallbackApplied = false
        endRetried = false
        readyAt = 0L
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
        timeshiftWanted = false
        timeshiftSuspended = false
        profile = profileForItem
        currentUrls = listOf(url)
        currentSourceIndex = 0
        retriesUsed = 0
        reconnectAttempts = 0
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
        stopTimeshift()
        val buffered = if (timeshiftWanted && url !in directOnly) startTimeshift(url) else null
        val item = if (buffered != null) bufferedItem(buffered) else MediaItem.Builder()
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
        exo.playWhenReady = !heldPaused

        _status.value = _status.value.copy(
            title = title,
            error = null,
            errorDetail = null,
            closed = false,
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
            timeshift = buffered != null,
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
        stopTimeshift()
        player?.stop()
    }

    /**
     * Silences playback at once, for a host on its way out of the app. [release] would do it as
     * well, but it holds the calling thread until the decoders are torn down — and the host calls
     * this from `onPause`, which the next screen waits on before it can appear. Queued retries
     * are dropped, so nothing starts the stream again before the [release] that follows.
     */
    fun silence() {
        playbackGeneration++
        stopTimeshift()
        player?.stop()
    }

    fun release() {
        // Anything already queued belongs to a player that is about to stop existing.
        playbackGeneration++
        stopTimeshift()
        timeshiftSuspended = false
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
        // Belongs to the instance going away; the next one starts out playing anyway.
        pausedForAudioFocus = false
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
            // A live channel has no end, so a panel's stream that ends was ended by its server.
            if (state == Player.STATE_ENDED && role == Role.PANEL && currentChannel != null) {
                onPanelStreamEnded()
                return
            }
            if (state == Player.STATE_READY && readyAt == 0L) readyAt = SystemClock.elapsedRealtime()
            _status.value = _status.value.copy(
                buffering = state == Player.STATE_BUFFERING,
                playing = state == Player.STATE_READY && player?.playWhenReady == true,
            )
            if (state == Player.STATE_READY) {
                retriesUsed = 0
                reconnectAttempts = 0
                refreshDiagnostics()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _status.value = _status.value.copy(playing = isPlaying)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            pausedForAudioFocus =
                !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
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

        if (timeshift != null) {
            // Not a failure of the stream: this one cannot be buffered, so it plays directly, at
            // once and without spending a retry (ADR-065).
            if (error.hasCause<TimeshiftUnavailableException>()) {
                currentUrls.getOrNull(currentSourceIndex)?.let(directOnly::add)
                Log.w(TAG, "$title: no timeshift buffer (${error.cause?.message}); playing directly")
                schedule(0) { startCurrentSource(title) }
                return
            }
            // Paused past the oldest of the buffer: playback carries on from what is left, the
            // oldest it holds, rather than calling the channel broken.
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player?.let {
                    it.seekTo(0)
                    it.prepare()
                }
                return
            }
        }

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

        // A live channel that failed for want of a connection is not gone: the Wi-Fi dropped,
        // the router restarted, the provider blinked. The retries above last about three
        // seconds, and until now that was it — the channel sat dead with the network long back
        // (an emulator QA run: no request for two minutes after a 20-second outage) until the
        // viewer zapped away and back. A 404 or a DRM refusal will not fix itself; these may.
        if (ReconnectPolicy.applies(mapped.kind, live = currentChannel != null)) scheduleReconnect(title)
    }

    /**
     * A panel's live stream ended (ADR-041, first device report): to the player that is a stream
     * reaching its end, which is not an error, so before this the panel stood on a frozen frame
     * saying nothing. An IPTV server ends the older streams of an account past its connection
     * limit this way, and a server restarting can too.
     *
     * The channel's next address is tried, if it has one, and then the same one once more. A stream
     * that closes again is left closed, saying so, and the viewer tries again by hand: past the
     * account's limit every reconnect closes another panel's stream, and retrying for ever would
     * have the panels take the one connection from each other in turn. A stream that had played
     * steadily for a while earns its one retry back.
     */
    private fun onPanelStreamEnded() {
        val title = _status.value.title
        val steady = readyAt > 0 && SystemClock.elapsedRealtime() - readyAt > STEADY_MS
        readyAt = 0L
        if (steady) endRetried = false
        if (currentSourceIndex + 1 < currentUrls.size) {
            Log.w(TAG, "$title: the stream ended; trying the next address")
            currentSourceIndex++
            retriesUsed = 0
            _status.value = _status.value.copy(buffering = true, playing = false)
            schedule(NEXT_SOURCE_MS) { startCurrentSource(title) }
            return
        }
        // The same address once more, not every address over again: on an account at its limit
        // each address opened is one more panel's stream closed.
        if (!endRetried) {
            Log.w(TAG, "$title: the stream ended; trying once more")
            endRetried = true
            _status.value = _status.value.copy(buffering = true, playing = false)
            schedule(END_RETRY_MS) { startCurrentSource(title) }
            return
        }
        Log.w(TAG, "$title: the stream ended again; its server closed it")
        // Nothing scheduled may start it again, and it holds no decoder and no buffer while it
        // waits for the viewer: detached now, released just after, because this is reached from
        // inside the player's own callback.
        playbackGeneration++
        val doomed = player
        player = null
        _currentPlayer.value = null
        if (doomed != null) {
            doomed.removeListener(listener)
            doomed.removeAnalyticsListener(analytics)
            val post = onScheduleRetry
            if (post != null) post(0L) { doomed.release() } else doomed.release()
        }
        _status.value = _status.value.copy(
            buffering = false,
            playing = false,
            closed = true,
            streamTitle = null,
        )
    }

    /**
     * Another full round over the channel's sources, after [ReconnectPolicy.delayMs], for as long
     * as the viewer stays on the channel — a zap bumps the generation and drops it.
     */
    private fun scheduleReconnect(title: String) {
        reconnectAttempts++
        schedule(ReconnectPolicy.delayMs(reconnectAttempts)) {
            // Paused, by the viewer or by the app leaving the screen: nothing reconnects behind
            // their back. [setPlayWhenReady] prepares the failed source when playback resumes.
            if (player?.playWhenReady != true) return@schedule
            currentSourceIndex = 0
            retriesUsed = 0
            startCurrentSource(title)
        }
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

    private fun mapError(error: PlaybackException): AppError = playbackErrorOf(error)

    // -------------------------------------------------------------- timeshift

    /**
     * Where buffered playback stands, or null when the channel is not playing from the buffer or
     * the buffer has nothing yet. For the UI to read while it is showing (it polls).
     */
    fun liveWindow(): LiveWindow? {
        val exo = player ?: return null
        if (timeshift == null) return null
        val timeline = exo.currentTimeline
        if (timeline.isEmpty) return null
        val window = timeline.getWindow(exo.currentMediaItemIndex, Timeline.Window())
        val duration = window.durationMs
        if (duration == C.TIME_UNSET || duration <= 0) return null
        return LiveWindow(durationMs = duration, positionMs = exo.currentPosition.coerceIn(0, duration))
    }

    /** Moves through the buffer by [deltaMs], back when negative; no further than live. */
    fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val window = liveWindow() ?: return
        val target = (window.positionMs + deltaMs).coerceIn(0, window.durationMs)
        if (target >= window.durationMs - LiveWindow.LIVE_SLACK_MS / 2) {
            exo.seekToDefaultPosition()
        } else {
            exo.seekTo(target)
        }
        if (!exo.playWhenReady) exo.playWhenReady = true
    }

    /** Back to the live edge, playing. */
    fun jumpToLive() {
        val exo = player ?: return
        exo.seekToDefaultPosition()
        exo.playWhenReady = true
    }

    private fun bufferedItem(session: TimeshiftSession): MediaItem = MediaItem.Builder()
        .setUri(session.playlistUri)
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        // Close to the edge, and at the stream's own speed: the player must not creep towards
        // live after a pause by playing faster, which a viewer behind live hears as a squeak.
        .setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(TIMESHIFT_TARGET_OFFSET_MS)
                .setMinPlaybackSpeed(1f)
                .setMaxPlaybackSpeed(1f)
                .build(),
        )
        .build()

    /**
     * A buffer for [url], or null when there is no room for one: what the free space allows,
     * keeping [TIMESHIFT_RESERVE_BYTES] for everything else, up to the minutes asked for.
     */
    private fun startTimeshift(url: String): TimeshiftSession? {
        val factory = upstreamFactory ?: return null
        val root = File(context.noBackupFilesDir, TIMESHIFT_DIR)
        val s = settings.state.value
        val free = runCatching { root.mkdirs(); StatFs(root.path).availableBytes }.getOrDefault(0L)
        val room = free - TIMESHIFT_RESERVE_BYTES
        if (room < TIMESHIFT_MIN_BYTES) {
            Log.w(TAG, "no room for a timeshift buffer (${free / 1_048_576} MB free)")
            return null
        }
        // A directory of its own per session, named by the clock so one from a process before
        // this one (a crash, a kill) is never taken for it, and swept away below.
        val dir = File(root, "live-${System.currentTimeMillis()}")
        clearTimeshiftLeftovers(root, keep = dir.name)
        val session = TimeshiftSession(
            dir = dir,
            uri = android.net.Uri.parse(url),
            upstreamFactory = factory,
            maxDurationUs = s.timeshiftMinutes * 60_000_000L,
            maxBytes = room,
        )
        session.start()
        timeshift = session
        return session
    }

    private fun stopTimeshift() {
        timeshift?.stop()
        timeshift = null
    }

    /** Files a session that never stopped cleanly left behind (a crash, a kill), off this thread. */
    private fun clearTimeshiftLeftovers(root: File, keep: String) {
        if (leftoversCleared) return
        leftoversCleared = true
        val stale = root.listFiles()?.filter { it.name != keep }.orEmpty()
        if (stale.isEmpty()) return
        Thread({ stale.forEach { it.deleteRecursively() } }, "timeshift-clean").start()
    }

    private var leftoversCleared = false
}

/** Whether [T] is anywhere in this failure's chain of causes. */
private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var c: Throwable? = this
    while (c != null) {
        if (c is T) return true
        c = c.cause
    }
    return false
}

private const val TIMESHIFT_DIR = "timeshift"
private const val TIMESHIFT_TARGET_OFFSET_MS = 6_000L
/** Left free for the rest of the app and the system, whatever the buffer would like. */
private const val TIMESHIFT_RESERVE_BYTES = 500L * 1024 * 1024
/** Below this there is no point: about two minutes of a television channel. */
private const val TIMESHIFT_MIN_BYTES = 64L * 1024 * 1024

/** How every [PlayerEngine] plays its sound: as a film's, for the media volume. */
private val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

/**
 * What a player failure means to the viewer, as an [AppError] the UI can explain — the DRM case
 * included, which must never be a silent black screen (FR-TVI-06).
 */
internal fun playbackErrorOf(error: PlaybackException): AppError = when (error.errorCode) {
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

/**
 * When a live channel that ran out of retries and sources is tried again ([PlayerEngine]).
 *
 * Only for a failure that can pass on its own — no connection, a timeout, a 5xx (which
 * [AppError.fromHttp] files under [AppError.Kind.Network]) — and only for a live channel: an
 * on-demand title would restart from its beginning on every round.
 */
internal object ReconnectPolicy {
    /** Waits grow by this much per attempt: 5 s, 10 s, then [MAX_MS] for as long as it takes. */
    const val STEP_MS = 5_000L
    const val MAX_MS = 15_000L

    fun applies(kind: AppError.Kind, live: Boolean): Boolean =
        live && (kind == AppError.Kind.Network || kind == AppError.Kind.Timeout)

    fun delayMs(attempt: Int): Long = (STEP_MS * attempt.coerceAtLeast(1)).coerceAtMost(MAX_MS)
}
