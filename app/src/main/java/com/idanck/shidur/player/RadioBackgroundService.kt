package com.idanck.shidur.player

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.idanck.shidur.App
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.data.prefs.AppLocale
import com.idanck.shidur.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps a radio station audible once [com.idanck.shidur.ui.MainActivity] is no longer visible.
 *
 * A locked or turned-off screen stops the activity exactly the way leaving the app does, and
 * `MainActivity.onStop` responds by pausing playback — right, for a live TV channel that has a
 * picture nothing is showing any more, wrong for a radio station, which has no picture to lose.
 * `onStop` starts this service instead of pausing when what's playing is radio (see
 * `AppViewModel.isRadioPlaying`).
 *
 * This does nothing to the stream itself: it plays through the very same [ServiceLocator.player]
 * instance the UI was already using, at whatever position it had already reached. Its only job is
 * to hold a foreground-service priority (so the process backing that player survives a locked
 * screen) and to show an ongoing notification with a way to pause and to get back into the app —
 * a backgrounded stream with no indication it is still running and no way to stop it would be a
 * defect, not a feature, exactly as the pause it replaces was written to avoid for video.
 */
class RadioBackgroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    /** FR-UI-23: a service is handed a fresh context, not the one `Application` speaks. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // The headset's buttons follow the station into the background (PlayerMediaSession).
        PlayerMediaSession.hold(PlayerMediaSession.HOLDER_RADIO_SERVICE, this, ServiceLocator.player)
        ServiceLocator.player.status
            // [PlaybackStatus.playWhenReady], not `playing`: a station that is rebuffering has
            // not been paused, and a button drawn from `playing` would offer Play mid-stall and
            // then pause the stream when it was pressed — the same distinction the picture-in-
            // picture controls in MainActivity are built on.
            .onEach { updateNotification(it.title, it.streamTitle, it.playWhenReady) }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promoted first, whatever the action asks for. A service started with
        // `startForegroundService` owes the platform a `startForeground` within a few seconds or
        // the process is killed — and the notification's own buttons arrive back through here, so
        // an action delivered to a service the system had since recreated would otherwise leave
        // it running with that debt unpaid.
        val status = ServiceLocator.player.status.value
        promoteToForeground(status.title, status.streamTitle, status.playWhenReady)
        when (intent?.action) {
            ACTION_TOGGLE_PLAY_PAUSE -> ServiceLocator.player.togglePlayPause()
            // Swiping a paused notification away means "done with this", not "hide it". Without
            // somewhere for that swipe to go, the service stayed in the foreground holding the
            // process up with nothing left on screen to stop it.
            ACTION_STOP -> {
                ServiceLocator.player.setPlayWhenReady(false)
                stopSelf()
            }
            // The app is in front again ([stop]): the service goes, the station plays on in the
            // activity. By id, so a start that arrived after this release keeps the service.
            ACTION_RELEASE -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(title: String, song: String?, wantsPlay: Boolean) {
        val notification = buildNotification(title, song, wantsPlay)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private fun updateNotification(title: String, song: String?, wantsPlay: Boolean) {
        try {
            NotificationManagerCompat.from(this).notify(FOREGROUND_ID, buildNotification(title, song, wantsPlay))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was refused; the stream keeps playing regardless.
        }
    }

    /** @param song what the station says is on ([PlaybackStatus.streamTitle]), when it says. */
    private fun buildNotification(title: String, song: String?, wantsPlay: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_RADIO)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
            .setContentText(song ?: getString(R.string.radio_notif_playing))
            .setContentIntent(openApp)
            // Dismissible once paused, exactly like the recorder's own notification when it is
            // not actively writing (RecordingService.buildNotification) — an ongoing notification
            // for a stream that is not making sound would be a lie the user cannot swipe away.
            .setOngoing(wantsPlay)
            .setOnlyAlertOnce(true)
            // ...and that swipe now ends the service rather than only hiding it.
            .setDeleteIntent(serviceIntent(REQUEST_STOP, ACTION_STOP))
            .addAction(
                if (wantsPlay) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (wantsPlay) R.string.action_pause else R.string.action_play),
                serviceIntent(REQUEST_TOGGLE, ACTION_TOGGLE_PLAY_PAUSE),
            )
            .build()
    }

    private fun serviceIntent(requestCode: Int, action: String): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, RadioBackgroundService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        started = false
        scope.cancel()
        PlayerMediaSession.release(PlayerMediaSession.HOLDER_RADIO_SERVICE)
        super.onDestroy()
    }

    companion object {
        private const val FOREGROUND_ID = 3001
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_TOGGLE = 1
        private const val REQUEST_STOP = 2
        private const val ACTION_TOGGLE_PLAY_PAUSE = "com.idanck.shidur.player.RADIO_TOGGLE_PLAY_PAUSE"
        private const val ACTION_STOP = "com.idanck.shidur.player.RADIO_STOP"
        private const val ACTION_RELEASE = "com.idanck.shidur.player.RADIO_RELEASE"

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
