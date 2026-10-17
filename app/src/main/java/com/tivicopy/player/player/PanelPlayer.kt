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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tivicopy.player.core.AppError
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.DecoderMode
import com.tivicopy.player.data.net.HttpFactory
import com.tivicopy.player.data.net.RequestProfile
import com.tivicopy.player.data.prefs.Settings
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
     * The provider ended the stream, cleanly, as if the channel had finished, and trying again did
     * not help: what an IPTV server does to a connection beyond the account's limit when a newer
     * one opens (ADR-041).
     */
    val closed: Boolean = false,
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
 * All of it runs on the main thread, which is where the players are built and where their
 * listeners are called.
 */
class PanelPlayer(
    private val context: Context,
    private val settings: Settings,
    private val main: Handler,
    /** Called whenever [status] changes, so the session can say whether anything is playing. */
    private val onChanged: () -> Unit,
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

    /** Whether the one automatic try after the provider closed the stream has been spent. */
    private var endRetried = false

    /**
     * When the stream from the current address first became ready, on the monotonic clock; zero
     * until it has. A rebuffer does not move it: what it measures is how long the stream has been
     * up, not how long since the last stall.
     */
    private var readyAt = 0L

    fun play(channel: Channel, profile: RequestProfile) {
        generation++
        this.channel = channel
        this.profile = profile
        urls = channel.urls
        sourceIndex = 0
        retries = 0
        reconnects = 0
        endRetried = false
        readyAt = 0L
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

    /** Whether this panel is the one heard. Applied to the instance now and to any built later. */
    fun setAudible(value: Boolean) {
        if (audible == value) return
        audible = value
        _player.value?.let(::applyAudible)
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
        val http = OkHttpDataSource.Factory(HttpFactory.client(profile))
            .setUserAgent(profile.userAgent)
            .setDefaultRequestProperties(profile.headers)
        val exo = ExoPlayer.Builder(context)
            .setRenderersFactory(
                SelectiveRenderersFactory(context, video, audio, encodedAudioPassthrough = false),
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
        applyAudible(exo)
        return exo
    }

    private fun applyAudible(exo: ExoPlayer) {
        exo.volume = if (audible) 1f else 0f
        exo.setAudioAttributes(ATTRIBUTES, /* handleAudioFocus = */ audible)
    }

    private fun releasePlayer() {
        _player.value?.let {
            it.removeListener(listener)
            it.release()
        }
        _player.value = null
    }

    private fun setStatus(value: PanelStatus) {
        _status.value = value
        onChanged()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                onEnded()
                return
            }
            if (state == Player.STATE_READY) {
                retries = 0
                reconnects = 0
                if (readyAt == 0L) readyAt = SystemClock.elapsedRealtime()
            }
            val exo = _player.value
            setStatus(
                _status.value.copy(
                    buffering = state == Player.STATE_BUFFERING,
                    playing = exo?.isPlaying == true,
                    // A stream that came back is no longer the one that failed.
                    error = if (state == Player.STATE_READY) null else _status.value.error,
                    failed = if (state == Player.STATE_READY) false else _status.value.failed,
                ),
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            setStatus(_status.value.copy(playing = isPlaying))
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    /**
     * A live channel has no end, so a stream that ends was ended by the other side.
     *
     * The first device report (2026-10-17): three of four panels stood on a frozen frame, saying
     * nothing, while one played on — and not necessarily the one in focus. The account allowed one
     * connection at a time, and the provider closed each older stream a few seconds after a newer
     * one opened; to the player that is a stream reaching its end, which is not an error, so there
     * was no message and no retry.
     *
     * Now the channel's next address is tried, if it has one, and then the same one once more: a
     * clean close can also be a server restarting. A stream that closes again soon after is left
     * closed, saying so, and OK tries again by hand. Past the account's limit every reconnect
     * closes another panel's stream, and retrying for ever would have the screens take the one
     * connection from each other in turn for as long as Sport mode stayed open. A stream that had
     * been playing steadily for a while earns its one retry back.
     */
    private fun onEnded() {
        val name = channel?.displayName
        // The retry comes back only to a stream that had been up steadily; one that never got
        // going, or was closed again within the minute, does not get it back.
        val steady = readyAt > 0 && SystemClock.elapsedRealtime() - readyAt > STEADY_MS
        readyAt = 0L
        if (steady) endRetried = false
        // Connecting again meanwhile, not standing on a frozen frame saying nothing.
        if (sourceIndex + 1 < urls.size) {
            Log.w(TAG, "$name: the stream ended; trying the next address")
            sourceIndex++
            retries = 0
            setStatus(PanelStatus(buffering = true))
            schedule(NEXT_SOURCE_MS) { start() }
            return
        }
        // The same address once more, not every address over again: on an account at its limit
        // each address opened is one more panel's stream closed.
        if (!endRetried) {
            Log.w(TAG, "$name: the stream ended; trying once more")
            endRetried = true
            setStatus(PanelStatus(buffering = true))
            schedule(END_RETRY_MS) { start() }
            return
        }
        Log.w(TAG, "$name: the stream ended again; the provider closed it")
        // Nothing scheduled may start it again, and it holds nothing while it waits for OK: the
        // decoder and the buffer go. Detached now, released just after, because this is reached
        // from inside the player's own callback.
        generation++
        val doomed = _player.value
        doomed?.removeListener(listener)
        _player.value = null
        if (doomed != null) main.post { doomed.release() }
        setStatus(PanelStatus(failed = true, closed = true))
    }

    private fun handleError(error: PlaybackException) {
        val mapped = playbackErrorOf(error)
        Log.w(TAG, "${channel?.displayName}: ${error.errorCodeName}")

        // A decoder that will not start is, here, almost always the device's limit on how many
        // pictures it can decode at once; trying again changes nothing until a panel is freed.
        // DRM the device cannot satisfy will not fix itself either.
        if (mapped.kind == AppError.Kind.DecoderFailed || mapped.kind == AppError.Kind.DrmUnsupported) {
            settle(mapped)
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
            readyAt = 0L
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
                readyAt = 0L
                start()
            }
        }
    }

    private fun settle(error: AppError) {
        setStatus(PanelStatus(error = error, failed = true))
    }

    private fun schedule(delayMillis: Long, action: () -> Unit) {
        val armed = generation
        main.postDelayed({ if (armed == generation) action() }, delayMillis)
    }

    private companion object {
        const val TAG = "SportPanel"

        /** How long after the provider closes a stream the one automatic try waits. */
        const val END_RETRY_MS = 2_000L

        /** How long a stream must have played for a later close to earn another automatic try. */
        const val STEADY_MS = 60_000L

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
    }
}
