package com.tivicopy.player.auto

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
 */
class RadioMediaLibraryService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var radioPlayer: RadioPlayer? = null
    private val browseTree = RadioBrowseTree()

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

        session = MediaLibrarySession.Builder(this, player.exoPlayer, RadioSessionCallback(browseTree))
            .build()

        // Off the main thread: bootstraps the playlist if this service is the first thing to run
        // in the process (see RadioBrowseTree.radioPlaylist), then applies its real profile.
        // OkHttpDataSource.Factory's setters are live, so this needs no player rebuild.
        ServiceLocator.appScope.launch {
            browseTree.radioPlaylist()?.let { playlist ->
                player.updateProfile(ServiceLocator.sources.profileFor(playlist))
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        radioPlayer?.exoPlayer?.release()
        radioPlayer = null
        super.onDestroy()
    }
}
