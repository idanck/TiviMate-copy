package com.tivicopy.player.player

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tivicopy.player.core.AppError
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.DecoderMode
import com.tivicopy.player.data.net.HttpFactory
import com.tivicopy.player.data.net.RequestProfile
import com.tivicopy.player.data.prefs.Settings
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What one Sport-mode panel reports: for its own loader and message, and for the screensaver. */
data class PanelStatus(
    val buffering: Boolean = false,
    val playing: Boolean = false,
    val error: AppError? = null,
    /** No retry is coming: the panel says what went wrong instead of spinning. */
    val failed: Boolean = false,
    /**
     * Stopped for want of a hardware video decoder while other panels held them: the device's limit
     * on pictures decoded at once, not anything wrong with the channel. The session starts it again
     * when a decoder comes free, and when the remote turns to it.
     */
    val starved: Boolean = false,
)

/**
 * One of Sport mode's four players (ADR-041).
 *
 * Deliberately not a [PlayerEngine]. The engine is the app's one full-screen player, and the media
 * session, the floating window, the launcher's "continue watching" row, resume marks and the radio
 * all hang off it; a panel needs none of that. What it keeps is what a live channel needs to stay
 * on screen: the channel's request profile, its decoder choice, retry with backoff, failover down
 * its URLs, and the reconnect rounds the engine gives a live channel that lost the network.
 *
 * Every panel plays its sound all the time, at volume zero unless it is the [setAudible] one.
 * Muting rather than turning the audio track off is what lets the sound follow the remote at once:
 * re-enabling a track makes the player re-buffer, and a second of silence on every press is not
 * "the sound follows the focus". Only the audible panel asks the system for audio focus — four
 * players asking would each take it from the one before and pause it.
 *
 * A panel in the background is offered hardware video decoders only ([setSoftwareAllowed]). A
 * television box holds a few at most, and a panel that found them taken used to fall through to
 * a software decoder, which cannot keep up with a live HD picture on such a box: every panel but
 * one sat on a frozen frame and said nothing (the first device report, 2026-10-17). Offered
 * hardware only, it fails at once instead, and says the device cannot play another channel at the
 * same time. The panel in focus keeps the software fallback, so the picture being watched and
 * heard always gets a decoder.
 *
 * All of it runs on the main thread, which is where the players are built and where their
 * listeners are called, except the decoder filter, which the playback thread calls.
 */
class PanelPlayer(
    private val context: Context,
    private val settings: Settings,
    private val main: Handler,
    /** Called whenever [status] or the decoder this panel holds changes. */
    private val onChanged: () -> Unit,
    /** Called when the panel gives up on its channel, so freeing whatever decoder it had. */
    private val onGaveUp: () -> Unit,
) {

    private val _player = MutableStateFlow<ExoPlayer?>(null)

    /** The live instance, for the panel's view to attach to; null while nothing is playing. */
    val player: StateFlow<ExoPlayer?> = _player

    private val _status = MutableStateFlow(PanelStatus())
    val status: StateFlow<PanelStatus> = _status

    private var channel: Channel? = null
    private var profile = RequestProfile()
    private var urls: List<String> = emptyList()
    private var sourceIndex = 0
    private var retries = 0
    private var reconnects = 0
    private var audible = false
    private var paused = false

    /** Bumped by every new source and by [release], so a retry armed for the last one is dropped. */
    private var generation = 0

    /** Whether this panel has a video decoder running, hardware or software. */
    var holdsVideoDecoder = false
        private set

    /** Whether that decoder runs on the CPU. */
    private var onSoftwareDecoder = false

    /**
     * Whether that software decoder was chosen over a hardware one that was on offer, rather than
     * being the only kind the device has for the stream.
     */
    private var softwareOverHardware = false

    /** Whether this panel may fall back to a software video decoder: the panel in focus may. */
    @Volatile
    private var softwareAllowed = true

    /** Whether the channel or the settings asked for software video decoding on purpose. */
    @Volatile
    private var softwareChosen = false

    /**
     * The software video decoders offered so far, by name, and whether each was ever offered beside
     * a hardware one: filled on the playback thread as decoders are offered, read on this one as
     * one comes up. As many entries as the device has decoders.
     */
    private val softwareDecoders = ConcurrentHashMap<String, Boolean>()

    /** Whether the last decoder list was cut down to hardware for this panel; see [handleError]. */
    @Volatile
    private var hardwareOnly = false

    private var droppedFrames = 0
    private var droppedLoggedAt = 0L

    /** Whether the stream is ready to play: buffered, whether or not it is paused. */
    val isReady: Boolean
        get() = _player.value?.playbackState == Player.STATE_READY

    fun play(channel: Channel, profile: RequestProfile) {
        generation++
        this.channel = channel
        this.profile = profile
        urls = channel.urls
        sourceIndex = 0
        retries = 0
        reconnects = 0
        droppedFrames = 0
        // The data source captures the profile, and the renderers the channel's decoder choice,
        // so a new channel gets a new instance rather than a new media item.
        releasePlayer()
        start()
    }

    /** Stops and lets go of the decoders and the connection. [play] starts again from nothing. */
    fun release() {
        generation++
        releasePlayer()
        channel = null
        urls = emptyList()
        setStatus(PanelStatus())
    }

    /**
     * Quiet at once, for an activity on its way out: [release] waits for the decoders to be torn
     * down, and the screen the viewer is going to waits for the activity. Pending retries are
     * dropped, so nothing starts the stream again before the release that follows.
     */
    fun silence() {
        generation++
        _player.value?.stop()
    }

    /** Drawn as connecting while the session holds this panel back for the one in focus. */
    fun markPending() {
        if (_player.value == null) setStatus(PanelStatus(buffering = true))
    }

    /** Whether this panel is the one heard. Applied to the instance now and to any built later. */
    fun setAudible(value: Boolean) {
        if (audible == value) return
        audible = value
        _player.value?.let(::applyAudible)
    }

    /**
     * Whether this panel may fall back to a software video decoder — the panel in focus may, one in
     * the background may not. Read as each decoder is chosen. A panel sent to the background while
     * it is on a software decoder starts its channel again on hardware, or stops for want of it:
     * left as it is, it would stand frozen behind the panel the viewer moved to.
     */
    fun setSoftwareAllowed(value: Boolean) {
        if (softwareAllowed == value) return
        softwareAllowed = value
        val current = channel
        if (!value && softwareOverHardware && !softwareChosen && current != null) play(current, profile)
    }

    fun setPaused(value: Boolean) {
        paused = value
        _player.value?.playWhenReady = !value
    }

    private fun start() {
        val url = urls.getOrNull(sourceIndex)
        if (url == null) {
            setStatus(PanelStatus(error = AppError(AppError.Kind.NotFound, "no url"), failed = true))
            return
        }
        val exo = _player.value ?: build().also { _player.value = it }
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = !paused
        setStatus(PanelStatus(buffering = true))
    }

    private fun build(): ExoPlayer {
        val s = settings.state.value
        val current = channel
        val video = current?.videoDecoder?.takeIf { it != DecoderMode.AUTO } ?: s.videoDecoder
        val audio = current?.audioDecoder?.takeIf { it != DecoderMode.AUTO } ?: s.audioDecoder
        softwareChosen = video == DecoderMode.SOFTWARE
        val http = OkHttpDataSource.Factory(HttpFactory.client(profile))
            .setUserAgent(profile.userAgent)
            .setDefaultRequestProperties(profile.headers)
        val exo = ExoPlayer.Builder(context)
            .setRenderersFactory(
                SelectiveRenderersFactory(
                    context,
                    video,
                    audio,
                    encodedAudioPassthrough = false,
                    videoDecoderFilter = ::videoDecoders,
                ),
            )
            // A few seconds of a live stream, and a ceiling on the bytes. The defaults are sized for
            // one player and allow well over a hundred megabytes of video each; four of those on a
            // television box with two gigabytes is how an app gets killed.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
                    .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                    .build(),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
        exo.addListener(listener)
        exo.addAnalyticsListener(analytics)
        applyAudible(exo)
        return exo
    }

    /**
     * The video decoders this panel may use, on the playback thread: every one while software is
     * allowed or asked for, otherwise the hardware ones — or all of them, when the device has no
     * hardware decoder for the stream at all and software is the only way to show it. "Hardware"
     * is [MediaCodecInfo.hardwareAccelerated], the flag the decoder choice in Settings goes by too.
     */
    private fun videoDecoders(offered: List<MediaCodecInfo>): List<MediaCodecInfo> {
        val hardware = offered.filter { it.hardwareAccelerated }
        offered.filterNot { it.hardwareAccelerated }.forEach {
            softwareDecoders.merge(it.name, hardware.isNotEmpty(), Boolean::or)
        }
        if (softwareAllowed || softwareChosen || hardware.isEmpty() || hardware.size == offered.size) {
            hardwareOnly = false
            return offered
        }
        hardwareOnly = true
        return hardware
    }

    private fun applyAudible(exo: ExoPlayer) {
        exo.volume = if (audible) 1f else 0f
        exo.setAudioAttributes(ATTRIBUTES, /* handleAudioFocus = */ audible)
    }

    private fun releasePlayer() {
        clearDecoderState()
        _player.value?.let {
            it.removeListener(listener)
            it.removeAnalyticsListener(analytics)
            it.release()
        }
        _player.value = null
    }

    private fun clearDecoderState() {
        holdsVideoDecoder = false
        onSoftwareDecoder = false
        softwareOverHardware = false
    }

    private fun setStatus(value: PanelStatus) {
        _status.value = value
        onChanged()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                retries = 0
                reconnects = 0
            }
            val exo = _player.value
            val ready = state == Player.STATE_READY
            setStatus(
                _status.value.copy(
                    buffering = state == Player.STATE_BUFFERING,
                    playing = exo?.isPlaying == true,
                    // A stream that came back is no longer the one that failed.
                    error = if (ready) null else _status.value.error,
                    failed = if (ready) false else _status.value.failed,
                    starved = if (ready) false else _status.value.starved,
                ),
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            setStatus(_status.value.copy(playing = isPlaying))
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    /**
     * Which video decoder this panel got, for the session and for the log: the decoder each panel
     * got is what settles how many pictures a given device decodes at once
     * (`adb logcat -s SportPanel`).
     */
    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            val besideHardware = softwareDecoders[decoderName]
            holdsVideoDecoder = true
            onSoftwareDecoder = besideHardware != null
            softwareOverHardware = besideHardware == true
            Log.i(TAG, "${channel?.displayName}: video decoder $decoderName, software=$onSoftwareDecoder")
            onChanged()
        }

        override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
            clearDecoderState()
            onChanged()
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            // A running total, written now and then: a panel that cannot keep up reports drops
            // every second or two, and four of those would bury the decoder lines above.
            this@PanelPlayer.droppedFrames += droppedFrames
            val now = SystemClock.elapsedRealtime()
            if (now - droppedLoggedAt < DROPPED_LOG_INTERVAL_MS) return
            droppedLoggedAt = now
            Log.i(TAG, "${channel?.displayName}: ${this@PanelPlayer.droppedFrames} frames dropped so far")
        }
    }

    private fun handleError(error: PlaybackException) {
        val mapped = playbackErrorOf(error)
        // The code alone: retries and reconnect rounds repeat, and the trace is kept for [settle].
        Log.w(TAG, "${channel?.displayName}: ${error.errorCodeName}")

        // A panel offered hardware decoders only whose decoder will not start has met the device's
        // limit on pictures at once — judged by the list it was actually offered, not by whether it
        // may have software now. If the remote has come to it meanwhile, it starts again with the
        // software fallback the panel in focus is owed. A format the device cannot decode, a
        // decoder failing on its own and DRM it cannot satisfy will not fix themselves, and say so.
        if (mapped.kind == AppError.Kind.DecoderFailed || mapped.kind == AppError.Kind.DrmUnsupported) {
            val starved = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && hardwareOnly
            val current = channel
            if (starved && softwareAllowed && current != null) {
                // Not from inside the failing player's own callback, as the full-screen player
                // does its software retry.
                schedule(0) { play(current, profile) }
                return
            }
            settle(mapped, starved = starved)
            return
        }

        if (retries < MAX_RETRIES) {
            retries++
            setStatus(_status.value.copy(error = mapped, buffering = true, playing = false))
            schedule(RETRY_STEP_MS * retries) { start() }
            return
        }

        if (sourceIndex + 1 < urls.size) {
            sourceIndex++
            retries = 0
            schedule(NEXT_SOURCE_MS) { start() }
            return
        }

        settle(mapped)
        // The same rounds the full-screen player gives a live channel that lost the network.
        if (ReconnectPolicy.applies(mapped.kind, live = true)) {
            reconnects++
            schedule(ReconnectPolicy.delayMs(reconnects)) {
                sourceIndex = 0
                retries = 0
                start()
            }
        }
    }

    /**
     * Gives up on the channel, saying why. A starved panel also lets go of its player — the buffer,
     * the connection and the playback thread — so that it holds nothing another panel could use,
     * and so that the session can start it again like a panel that was never started. It is
     * detached at once and released just after, because this is reached from inside the player's
     * own callbacks.
     */
    private fun settle(error: AppError, starved: Boolean = false) {
        val first = reconnects == 0
        // With its trace once per channel, not on every reconnect round after it.
        if (first) Log.w(TAG, "${channel?.displayName}: gave up on ${error.kind}", error.cause)
        if (starved) {
            generation++
            clearDecoderState()
            val doomed = _player.value
            doomed?.removeListener(listener)
            doomed?.removeAnalyticsListener(analytics)
            _player.value = null
            if (doomed != null) main.post { doomed.release() }
        }
        setStatus(PanelStatus(error = error, failed = true, starved = starved))
        // Whatever decoder it had is free for a panel that went without one. Once per channel: the
        // reconnect rounds that follow free nothing new.
        if (first && !starved) onGaveUp()
    }

    private fun schedule(delayMillis: Long, action: () -> Unit) {
        val armed = generation
        main.postDelayed({ if (armed == generation) action() }, delayMillis)
    }

    private companion object {
        const val TAG = "SportPanel"

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        const val MIN_BUFFER_MS = 4_000
        const val MAX_BUFFER_MS = 15_000
        const val PLAYBACK_BUFFER_MS = 1_500
        const val REBUFFER_MS = 3_000
        const val TARGET_BUFFER_BYTES = 24 * 1024 * 1024

        const val MAX_RETRIES = 2
        const val RETRY_STEP_MS = 1_000L
        const val NEXT_SOURCE_MS = 500L

        const val DROPPED_LOG_INTERVAL_MS = 10_000L
    }
}
