package com.tivicopy.player.auto

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.net.RequestProfile
import com.tivicopy.player.player.StreamTitle
import kotlinx.coroutines.launch

/**
 * Exposes the bundled Israeli radio playlist to Android Auto (ADR-015), for sideload-only
 * testing via Android Auto's own "Unknown sources" developer setting — this app is not, and does
 * not need to be, reviewed by Google for Android Auto distribution.
 *
 * Manifest-declared entry point; the car host discovers and binds this service the same way any
 * `MediaBrowserService`/`MediaLibraryService` app is discovered, independent of whether
 * [com.tivicopy.player.ui.MainActivity] has ever been opened — so [onCreate] must not depend on
 * anything the launcher activity would otherwise have set up, and must not block the main thread
 * on database I/O the way [RadioBrowseTree]'s own contract forbids.
 *
 * The player here is entirely separate from [ServiceLocator.player] (see [RadioPlayerFactory]) —
 * radio in the car and playback in the phone/TV UI cannot interrupt each other.
 *
 * This service is also the only place allowed to ask the player what it is playing: [Player] may
 * only be touched on the main thread, while [RadioBrowseTree] answers browse requests on a
 * background one. So the listener installed in [onCreate] pushes each station change *into* the
 * browse tree, and asks the car to reload the folders it has open so the marker moves with it.
 *
 * The same listener carries the song a station announces in its stream ([StreamTitle]) onto the
 * car's Now Playing screen, under the station's name rather than instead of it: the queue item is
 * replaced in place with the song added to its metadata (see [withSong]).
 */
class RadioMediaLibraryService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var radioPlayer: RadioPlayer? = null
    private val browseTree = RadioBrowseTree()

    /**
     * Built in [onCreate], not here: its "Favourite" button resolves a string through
     * [ServiceLocator.locale], which is not usable until [ServiceLocator.init] has run.
     */
    private var sessionCallback: RadioSessionCallback? = null

    /** The station the browse tree was last told about, so a song change is not a station change. */
    private var nowPlayingId: String? = null

    private val nowPlayingListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Replacing the playing item to add its song can report a transition of its own; the
            // station has not changed, so neither has anything the browse tree says.
            if (mediaItem?.mediaId == nowPlayingId) return
            nowPlayingId = mediaItem?.mediaId
            browseTree.setNowPlaying(mediaItem?.mediaId)
            session?.let { sessionCallback?.notifyBrowseRefresh(it) }
            // A station skipped back to still carries the song it was playing when it was left.
            showSong(null)
        }

        override fun onMetadata(metadata: Metadata) {
            val raw = StreamTitle.icyTitle(metadata) ?: return
            val station = radioPlayer?.exoPlayer?.currentMediaItem?.mediaMetadata?.title?.toString()
            showSong(StreamTitle.clean(raw, stationName = station))
        }
    }

    /**
     * Puts [song] (or, for null, nothing) on the playing queue item. Same URI, so ExoPlayer updates
     * the item without re-preparing the stream — no gap in the audio — and the session pushes the
     * new metadata to the car.
     */
    private fun showSong(song: String?) {
        val player = radioPlayer?.exoPlayer ?: return
        val item = player.currentMediaItem ?: return
        val updated = withSong(item, song) ?: return
        player.replaceMediaItem(player.currentMediaItemIndex, updated)
    }

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)

        // No DB read here: everything this needs (global timeout/retry/TLS settings) is already
        // in memory. The radio playlist's own user-agent/headers, if it ever has any, are
        // resolved and applied below without blocking session creation on that lookup.
        val s = ServiceLocator.settings.state.value
        val initialProfile = RequestProfile(
            userAgent = s.userAgent,
            connectTimeoutSeconds = s.connectTimeoutSeconds,
            readTimeoutSeconds = s.readTimeoutSeconds,
            retries = s.retries,
            allowInsecureTls = s.allowInsecureTls,
        )
        val player = RadioPlayerFactory.build(this, initialProfile)
        radioPlayer = player

        val callback = RadioSessionCallback(browseTree)
        sessionCallback = callback
        session = MediaLibrarySession.Builder(this, player.exoPlayer, callback).build()
        player.exoPlayer.addListener(nowPlayingListener)

        // Off the main thread: bootstraps the playlist if this service is the first thing to run
        // in the process (see RadioBrowseTree.radioPlaylist), then applies its real profile.
        // OkHttpDataSource.Factory's setters are live, so this needs no player rebuild.
        ServiceLocator.appScope.launch {
            browseTree.radioPlaylist()?.let { playlist ->
                player.updateProfile(ServiceLocator.sources.profileFor(playlist))
            }
            // Renders every station logo into the square bitmap the car's Now Playing screen
            // wants, so tapping a station does not wait on a download to show it (RadioArtwork).
            RadioArtwork.prefetch(browseTree.logoUrls())
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    private companion object {
        /**
         * The subtitle the queue item had before any song was added to it, kept in the item's own
         * extras so each song replaces the last instead of piling up after it. Its presence, even
         * holding null, is what says "this item has been through [withSong]".
         */
        const val EXTRA_BASE_SUBTITLE = "com.tivicopy.player.auto.BASE_SUBTITLE"

        /**
         * [item] with [song] as its artist and in front of its own subtitle, or null when it
         * already says exactly that. The title stays the station's name.
         */
        fun withSong(item: MediaItem, song: String?): MediaItem? {
            val metadata = item.mediaMetadata
            val extras = metadata.extras
            if (song == null && extras?.containsKey(EXTRA_BASE_SUBTITLE) != true) return null
            if (song != null && metadata.artist?.toString() == song) return null
            val base = if (extras?.containsKey(EXTRA_BASE_SUBTITLE) == true) {
                extras.getString(EXTRA_BASE_SUBTITLE)
            } else {
                metadata.subtitle?.toString()
            }
            val newExtras = Bundle(extras ?: Bundle.EMPTY).apply {
                if (song == null) remove(EXTRA_BASE_SUBTITLE) else putString(EXTRA_BASE_SUBTITLE, base)
            }
            val newMetadata = metadata.buildUpon()
                .setArtist(song)
                .setSubtitle(listOfNotNull(song, base).joinToString(" · ").takeIf { it.isNotEmpty() })
                .setExtras(newExtras)
                .build()
            return item.buildUpon().setMediaMetadata(newMetadata).build()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        sessionCallback = null
        radioPlayer?.exoPlayer?.let { player ->
            player.removeListener(nowPlayingListener)
            player.release()
        }
        radioPlayer = null
        super.onDestroy()
    }
}
