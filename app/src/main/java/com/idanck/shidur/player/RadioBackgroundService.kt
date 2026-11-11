package com.idanck.shidur.player

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.idanck.shidur.App
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.data.prefs.AppLocale
import com.idanck.shidur.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Keeps a radio station (or a podcast episode) audible once [com.idanck.shidur.ui.MainActivity]
 * is no longer visible, under the system's own media card (ADR-068).
 *
 * A locked or turned-off screen stops the activity exactly the way leaving the app does, and
 * `MainActivity.onStop` responds by pausing playback — right, for a live TV channel that has a
 * picture nothing is showing any more, wrong for a radio station, which has no picture to lose.
 * `onStop` starts this service instead of pausing when what's playing is radio (see
 * `AppViewModel.isRadioPlaying`).
 *
 * This does nothing to the stream itself: it plays through the very same [ServiceLocator.player]
 * the UI was already using, at whatever position it had already reached. It is a Media3
 * [MediaSessionService]: its session, over a [BackgroundSessionPlayer], is what the notification
 * shade and the lock screen draw as a media card — the station's logo and name, the song it
 * announces, play/pause and previous/next station — and what a headset, a watch or the car's
 * Bluetooth controls reach. Media3 builds and updates that notification and holds the service in
 * the foreground while something plays; [onStartCommand] only makes sure the promotion the
 * platform is owed comes first.
 */
@OptIn(UnstableApi::class)
class RadioBackgroundService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var session: MediaSession? = null
    private var sessionPlayer: BackgroundSessionPlayer? = null

    /** What [BackgroundSessionPlayer.show] was last told, for a player swapped in after it. */
    private var display: BackgroundSessionPlayer.Display? = null

    /** FR-UI-23: a service is handed a fresh context, not the one `Application` speaks. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(App.CHANNEL_RADIO)
                .setChannelName(R.string.channel_radio)
                .setNotificationId(FOREGROUND_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_shidur) },
        )
        // Dispatched, not immediate: changing station releases the engine's player and builds the
        // next one in a single call, and only once it returns does the new one have its stream.
        // Swapped in before that, the session would see an empty player and take the card down,
        // only to put it back a moment later.
        ServiceLocator.player.currentPlayer
            .onEach { player -> player?.let(::attach) }
            .launchIn(scope)
        combine(
            BackgroundPlayback.card,
            ServiceLocator.player.status.map { it.title to it.streamTitle }.distinctUntilChanged(),
        ) { card, (title, song) -> displayOf(card, title, song) }
            .distinctUntilChanged()
            .onEach { next ->
                display = next
                sessionPlayer?.show(next)
            }
            .launchIn(scope)
    }

    /**
     * The card for [card] as the view model describes it, with the song the stream announces. The
     * engine's own title stands in while the view model has not published one — the service can
     * outlive it, and a card that says nothing is worse than one that says the stream's name.
     */
    private fun displayOf(card: BackgroundPlayback.Card?, title: String, song: String?) =
        BackgroundSessionPlayer.Display(
            title = card?.title ?: title.ifBlank { getString(R.string.app_name) },
            line = song ?: card?.subtitle ?: getString(R.string.radio_notif_playing),
            artworkUrl = card?.artworkUrl,
            stations = card?.stations == true,
        )

    /** Puts the engine's current [player] under the session, building the session the first time. */
    private fun attach(player: ExoPlayer) {
        if (sessionPlayer?.wrappedPlayer === player) return
        val wrapped = BackgroundSessionPlayer(
            player,
            onSkip = { delta -> BackgroundPlayback.skip(delta) },
            onStop = ::stopFromCard,
        )
        display?.let(wrapped::show)
        sessionPlayer = wrapped
        val current = session
        if (current == null) {
            val created = MediaSession.Builder(this, wrapped)
                .setId(SESSION_ID)
                .setSessionActivity(openAppIntent())
                .build()
            session = created
            // Added at once rather than when a controller first asks for it: no controller
            // binds to this service — the activity starts it — and Media3 only draws the card
            // for a session it has been given.
            addSession(created)
        } else {
            current.player = wrapped
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The card's buttons and a headset's keys arrive as media-button intents; Media3 hands
        // them to the session.
        super.onStartCommand(intent, flags, startId)
        // Promoted first, whatever the action asks for. A service started with
        // `startForegroundService` owes the platform a `startForeground` within a few seconds or
        // the process is killed, and Media3 only promotes once its own controller has connected
        // to the session — after this returns. Under the id Media3 posts its card with, so its
        // first card simply replaces this one.
        if (!isPlaybackOngoing()) promoteToForeground()
        when (intent?.action) {
            // The app is in front again ([stop]): the service goes, the station plays on in the
            // activity. By id, so a start that arrived after this release keeps the service.
            ACTION_RELEASE -> stopSelf(startId)
        }
        // Not Media3's START_STICKY: recreated by the system after the process died, this would
        // be a card over a player nothing has started.
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val notification = placeholderNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    /** What stands for Media3's card in the moment before it draws its own. */
    private fun placeholderNotification(): Notification {
        val status = ServiceLocator.player.status.value
        val shown = display ?: displayOf(BackgroundPlayback.card.value, status.title, status.streamTitle)
        return NotificationCompat.Builder(this, App.CHANNEL_RADIO)
            .setSmallIcon(R.drawable.ic_notification_shidur)
            .setContentTitle(shown.title)
            .setContentText(shown.line)
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply { session?.let { setStyle(MediaStyleNotificationHelper.MediaStyle(it)) } }
            .build()
    }

    /**
     * The paused card swiped away, or a stop key: "done with this", not "hide it". The stream is
     * paused rather than stopped, so the app opened later finds it where it was; the service goes,
     * rather than staying started with nothing left on screen to stop it.
     */
    private fun stopFromCard() {
        ServiceLocator.player.setPlayWhenReady(false)
        stopSelf()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_APP,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        started = false
        scope.cancel()
        session?.let { current ->
            removeSession(current)
            // The session only: the player is the engine's, and the app plays on with it.
            current.release()
        }
        session = null
        sessionPlayer = null
        // A paused card is left detached from the service; with the service gone it would be a
        // card for a session that no longer exists.
        NotificationManagerCompat.from(this).cancel(FOREGROUND_ID)
        super.onDestroy()
    }

    companion object {
        private const val FOREGROUND_ID = 3001
        private const val REQUEST_OPEN_APP = 0
        private const val ACTION_RELEASE = "com.idanck.shidur.player.RADIO_RELEASE"

        /**
         * Distinct from the activity's ([PlayerMediaSession]) and Android Auto's
         * ([com.idanck.shidur.auto.RadioMediaLibraryService]): two sessions with one ID in a
         * process is a crash, and coming back to the app holds the activity's before this one goes.
         */
        private const val SESSION_ID = "shidur.background"

        /** Whether [start] was asked since the service last went, so [stop] has something to release. */
        @Volatile
        private var started = false

        fun start(context: Context) {
            val intent = Intent(context, RadioBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            // After the call: a start the platform refused (it throws) left nothing to release.
            started = true
        }

        /**
         * Not `stopService`: a service started with `startForegroundService` and stopped before its
         * `onStartCommand` has promoted it kills the app (ForegroundServiceDidNotStartInTime). Going
         * to the background and straight back — the activity's onStop starting this, its onStart
         * stopping it — did exactly that (QA-18, run 42973570517). The release goes through
         * [onStartCommand] instead, which promotes before anything else, and only when [start] was
         * asked, so a stop with nothing running starts nothing.
         */
        fun stop(context: Context) {
            if (!started) return
            started = false
            val release = Intent(context, RadioBackgroundService::class.java).setAction(ACTION_RELEASE)
            // From the background a plain start can be refused; the service has then at least had
            // the time its own start took, and stopping it is the old behaviour.
            runCatching { context.startService(release) }
                .onFailure { context.stopService(Intent(context, RadioBackgroundService::class.java)) }
        }
    }
}
