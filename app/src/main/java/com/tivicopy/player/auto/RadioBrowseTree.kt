package com.tivicopy.player.auto

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.builtin.IsraelRadioStations
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.ChannelSort
import com.tivicopy.player.data.model.Playlist
import kotlinx.coroutines.runBlocking

/**
 * The Android Auto browse tree for the built-in radio playlist (ADR-015): one flat level of
 * stations under a single root node. Built fresh from the DB on every call, not cached — a
 * playlist refresh or a hide/unhide is picked up the next time the car asks, with no invalidation
 * to get wrong. All methods here do DB I/O and must be called off the main thread.
 */
class RadioBrowseTree {

    val root: MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(IsraelRadioStations.NAME)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                .build(),
        )
        .build()

    /**
     * The bundled radio [Playlist] row.
     *
     * `App.onCreate` already seeds it, but this service can be the *first* thing to run in the
     * process — Android Auto binds it independently of `MainActivity` — so a car connecting in
     * the narrow window before that fire-and-forget bootstrap coroutine lands must not see an
     * empty station list. [com.tivicopy.player.data.repo.SourceRepository.ensureRadioPlaylist]
     * is idempotent and cheap once the row exists, so calling it again here — blocking only the
     * background thread this is invoked from, never the main thread — closes that race for free.
     */
    fun radioPlaylist(): Playlist? {
        runBlocking { ServiceLocator.sources.ensureRadioPlaylist() }
        return ServiceLocator.dao.playlists().firstOrNull { it.url == IsraelRadioStations.M3U_URL }
    }

    /** All stations, in provider order, as resolved playable [MediaItem]s (title, artwork, stream URI). */
    fun stations(): List<MediaItem> {
        val playlistId = radioPlaylist()?.id ?: return emptyList()
        return ServiceLocator.dao.channels(playlistId = playlistId, sort = ChannelSort.PROVIDER)
            .mapNotNull(::toMediaItem)
    }

    private fun toMediaItem(channel: Channel): MediaItem? {
        val url = channel.urls.firstOrNull() ?: return null
        val metadata = MediaMetadata.Builder()
            .setTitle(channel.displayName)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply { channel.displayLogo?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        return MediaItem.Builder()
            .setMediaId(channel.id.toString())
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        const val ROOT_ID = "radio_root"
    }
}
