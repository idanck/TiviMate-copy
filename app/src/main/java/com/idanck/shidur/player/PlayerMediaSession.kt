package com.idanck.shidur.player

import android.content.Context
import androidx.annotation.MainThread
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A media session over the app's own player, so the play/pause button on a pair of headphones —
 * wired or Bluetooth — reaches what this app is playing.
 *
 * There was none: only Android Auto's separate radio player had a session. The platform sends a
 * headset's buttons to a media session, and with none here it sent them to whichever other app had
 * one — the device report (2026-10-16) had Spotify in the status bar while the radio played — so
 * the buttons paused and resumed nothing of this app's.
 *
 * Held, not permanent. Someone has to be listening for the session to be right: the activity while
 * it is on screen or in its floating window, and [RadioBackgroundService] while it carries a
 * station in the background. With neither, the app has deliberately paused (`MainActivity.
 * pauseInBackground`), and a session left behind would let a headset start a TV channel's sound
 * from a backgrounded app with nothing on screen to stop it. So the session lives exactly as long
 * as someone holds it.
 *
 * The player itself is rebuilt by [PlayerEngine] whenever a decoder or a buffer setting changes,
 * so the session follows [PlayerEngine.currentPlayer] and is handed each new instance in turn.
 * Play/pause, and preparing again a stream that failed, are Media3's own handling of the button
 * on that player — the same steps [PlayerEngine.togglePlayPause] takes.
 *
 * Main thread only, as the player is.
 */
object PlayerMediaSession {

    /**
     * Distinct from Android Auto's session ([com.idanck.shidur.auto.RadioMediaLibraryService]),
     * which keeps the default: two sessions with one ID in a process is a crash.
     */
    private const val SESSION_ID = "shidur.player"

    const val HOLDER_ACTIVITY = "activity"
    const val HOLDER_RADIO_SERVICE = "radio-service"

    private val holders = mutableSetOf<String>()
    private var session: MediaSession? = null
    private var following: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @MainThread
    fun hold(holder: String, context: Context, engine: PlayerEngine) {
        holders += holder
        if (following != null) return
        val appContext = context.applicationContext
        following = scope.launch {
            engine.currentPlayer.collect { player -> attach(appContext, player) }
        }
    }

    @MainThread
    fun release(holder: String) {
        holders -= holder
        if (holders.isNotEmpty()) return
        following?.cancel()
        following = null
        session?.release()
        session = null
    }

    private fun attach(context: Context, player: ExoPlayer?) {
        val current = session
        when {
            player == null -> {
                current?.release()
                session = null
            }
            current == null -> session = MediaSession.Builder(context, player).setId(SESSION_ID).build()
            current.player !== player -> current.player = player
        }
    }
}
