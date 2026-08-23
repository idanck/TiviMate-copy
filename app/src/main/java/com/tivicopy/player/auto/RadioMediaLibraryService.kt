package com.tivicopy.player.auto

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.net.RequestProfile
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

    private val nowPlayingListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            browseTree.setNowPlaying(mediaItem?.mediaId)
            session?.let { sessionCallback?.notifyBrowseRefresh(it) }
        }
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
