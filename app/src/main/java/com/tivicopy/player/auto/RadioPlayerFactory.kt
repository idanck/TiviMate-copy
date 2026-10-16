package com.tivicopy.player.auto

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tivicopy.player.data.net.HttpFactory
import com.tivicopy.player.data.net.RequestProfile

/**
 * The ExoPlayer the Android Auto session plays through, plus the one thing about its request
 * profile that can legitimately change after construction: the bundled radio playlist's
 * user-agent/headers, resolved from the DB slightly after the player itself is built (see
 * [RadioMediaLibraryService]). [OkHttpDataSource.Factory]'s setters are live — every call to
 * [ExoPlayer]'s data source factory reads their current value at request time — so
 * [updateProfile] needs no player rebuild and no new [androidx.media3.session.MediaSession].
 */
class RadioPlayer(val exoPlayer: ExoPlayer, private val httpFactory: OkHttpDataSource.Factory) {
    fun updateProfile(profile: RequestProfile) {
        httpFactory.setUserAgent(profile.userAgent)
        httpFactory.setDefaultRequestProperties(profile.headers)
    }
}

/**
 * Builds the single [RadioPlayer] instance an Android Auto service session plays through for its
 * whole lifetime — deliberately separate from [com.tivicopy.player.ServiceLocator.player], the
 * one the phone/TV UI shares, so radio playback in the car and in-app playback never fight over
 * the same player (per the isolation decision in ADR-015).
 *
 * Unlike [com.tivicopy.player.player.PlayerEngine], which rebuilds its ExoPlayer on every channel
 * switch because different channels can carry different headers, this player is built **once**:
 * every station in the bundled radio playlist shares the same [RequestProfile] (headers/user-
 * agent are per-playlist, not per-channel — see `SourceRepository.profileFor`), so switching
 * station is an ordinary queue seek within one playlist of [androidx.media3.common.MediaItem]s,
 * never a rebuild.
 */
object RadioPlayerFactory {

    fun build(context: Context, initialProfile: RequestProfile): RadioPlayer {
        val httpFactory = OkHttpDataSource.Factory(HttpFactory.client(initialProfile))
            .setUserAgent(initialProfile.userAgent)
            .setDefaultRequestProperties(initialProfile.headers)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)),
            )
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            // As the app's own player does: losing the headphones or the car pauses the station
            // instead of moving it to the phone's speaker.
            .setHandleAudioBecomingNoisy(true)
            .build()

        return RadioPlayer(exoPlayer, httpFactory)
    }
}
