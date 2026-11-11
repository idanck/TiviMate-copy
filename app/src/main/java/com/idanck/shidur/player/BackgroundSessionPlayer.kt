package com.idanck.shidur.player

import android.net.Uri
import androidx.annotation.MainThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi

/**
 * The player the background card's session sees (ADR-068): [PlayerEngine]'s own ExoPlayer, with
 * what the card should say and what its buttons should do laid over it.
 *
 * - **What is playing.** The engine's media items carry no metadata: the UI draws its own. Left
 *   alone, the card would show nothing, or a station's raw ICY title ("Artist - Song") as if it
 *   were the station. [getMediaMetadata] answers instead with the station's name, the song it
 *   announces and its logo, from [show].
 * - **Previous/next.** The engine plays one stream at a time, so the player has no "next". On a
 *   station the four skip commands are offered and handed to [onSkip], which moves along the
 *   station list (a new stream, and a new ExoPlayer under a new one of these).
 * - **Stop.** Swiping the paused card away, or a headset's stop key, asks the player to stop. The
 *   engine's player is not this session's to stop — the app would come back to a dead stream — so
 *   [onStop] is asked instead, and the service pauses and goes.
 *
 * The session reads metadata from its listener callbacks, not only from [getMediaMetadata], so
 * every listener is wrapped ([MetadataListener]) to replace the ExoPlayer's own metadata in
 * [Player.Listener.onMediaMetadataChanged], and [show] calls them itself when the card changes.
 *
 * Main thread only, as the player is.
 */
@UnstableApi
class BackgroundSessionPlayer(
    player: Player,
    private val onSkip: (Int) -> Unit,
    private val onStop: () -> Unit,
) : ForwardingPlayer(player) {

    /** What the card shows. */
    data class Display(
        val title: String,
        val line: String?,
        val artworkUrl: String?,
        val stations: Boolean,
    )

    private var display = Display(title = "", line = null, artworkUrl = null, stations = false)

    private val listeners = LinkedHashMap<Player.Listener, MetadataListener>()

    @MainThread
    fun show(next: Display) {
        if (next == display) return
        val commandsChanged = next.stations != display.stations
        display = next
        val metadata = mediaMetadata
        val commands = availableCommands
        val events = Player.Events(
            FlagSet.Builder()
                .add(Player.EVENT_MEDIA_METADATA_CHANGED)
                .apply { if (commandsChanged) add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED) }
                .build(),
        )
        // A copy: a listener may remove itself from inside its callback.
        for (listener in listeners.keys.toList()) {
            listener.onMediaMetadataChanged(metadata)
            if (commandsChanged) listener.onAvailableCommandsChanged(commands)
            listener.onEvents(this, events)
        }
    }

    override fun addListener(listener: Player.Listener) {
        if (listener in listeners) return
        val wrapped = MetadataListener(listener)
        listeners[listener] = wrapped
        super.addListener(wrapped)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapped = listeners.remove(listener) ?: return
        super.removeListener(wrapped)
    }

    override fun getMediaMetadata(): MediaMetadata = MediaMetadata.Builder()
        .setTitle(display.title)
        .setDisplayTitle(display.title)
        .setArtist(display.line)
        .setStation(display.title.takeIf { display.stations })
        .setArtworkUri(display.artworkUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
        .build()

    override fun getCurrentMediaItem(): MediaItem? =
        super.getCurrentMediaItem()?.buildUpon()?.setMediaMetadata(mediaMetadata)?.build()

    override fun getAvailableCommands(): Player.Commands {
        val own = super.getAvailableCommands()
        if (!display.stations) return own
        return own.buildUpon().addAll(*SKIP_COMMANDS).build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        if (display.stations && command in SKIP_COMMANDS) true else super.isCommandAvailable(command)

    override fun hasNextMediaItem(): Boolean = display.stations || super.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean = display.stations || super.hasPreviousMediaItem()

    override fun seekToNext() = if (display.stations) onSkip(1) else super.seekToNext()

    override fun seekToNextMediaItem() = if (display.stations) onSkip(1) else super.seekToNextMediaItem()

    override fun seekToPrevious() = if (display.stations) onSkip(-1) else super.seekToPrevious()

    override fun seekToPreviousMediaItem() =
        if (display.stations) onSkip(-1) else super.seekToPreviousMediaItem()

    override fun stop() = onStop()

    /**
     * Passes everything through to [inner] except the metadata, which is always the card's.
     * Equal to another wrapper of the same listener, as [ForwardingPlayer] compares the listeners
     * it is given when one is removed.
     *
     * Every callback is written out: [Player.Listener]'s are Java default methods, which Kotlin's
     * `by` delegation does not forward — a delegated wrapper passed on nothing but its own
     * override. `BackgroundSessionPlayerTest` checks that none is missed.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    internal inner class MetadataListener(val inner: Player.Listener) : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
            inner.onMediaMetadataChanged(this@BackgroundSessionPlayer.mediaMetadata)

        override fun onEvents(player: Player, events: Player.Events) = inner.onEvents(player, events)
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = inner.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
            inner.onMediaItemTransition(mediaItem, reason)
        override fun onTracksChanged(tracks: Tracks) = inner.onTracksChanged(tracks)
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) =
            inner.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = inner.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = inner.onLoadingChanged(isLoading)
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
            inner.onAvailableCommandsChanged(availableCommands)
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
            inner.onTrackSelectionParametersChanged(parameters)
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
            inner.onPlayerStateChanged(playWhenReady, playbackState)
        override fun onPlaybackStateChanged(playbackState: Int) = inner.onPlaybackStateChanged(playbackState)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
            inner.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
            inner.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = inner.onIsPlayingChanged(isPlaying)
        override fun onRepeatModeChanged(repeatMode: Int) = inner.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
            inner.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPlayerError(error: PlaybackException) = inner.onPlayerError(error)
        override fun onPlayerErrorChanged(error: PlaybackException?) = inner.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(reason: Int) = inner.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = inner.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
            inner.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) =
            inner.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
            inner.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
            inner.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = inner.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) =
            inner.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = inner.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
            inner.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = inner.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = inner.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: VideoSize) = inner.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = inner.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = inner.onRenderedFirstFrame()
        override fun onCues(cues: List<Cue>) = inner.onCues(cues)
        override fun onCues(cueGroup: CueGroup) = inner.onCues(cueGroup)
        override fun onMetadata(metadata: Metadata) = inner.onMetadata(metadata)

        override fun equals(other: Any?): Boolean = other is MetadataListener && other.inner == inner

        override fun hashCode(): Int = inner.hashCode()
    }

    private companion object {
        val SKIP_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}
