package com.tivicopy.player.player

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
import com.tivicopy.player.App
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.prefs.AppLocale
import com.tivicopy.player.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps a radio station audible once [com.tivicopy.player.ui.MainActivity] is no longer visible.
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
        ServiceLocator.player.status
            .onEach { updateNotification(it.title, it.playing) }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_PLAY_PAUSE) {
            ServiceLocator.player.togglePlayPause()
        } else {
            val status = ServiceLocator.player.status.value
            promoteToForeground(status.title, status.playing)
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(title: String, playing: Boolean) {
        val notification = buildNotification(title, playing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private fun updateNotification(title: String, playing: Boolean) {
        try {
            NotificationManagerCompat.from(this).notify(FOREGROUND_ID, buildNotification(title, playing))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was refused; the stream keeps playing regardless.
        }
    }

    private fun buildNotification(title: String, playing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getService(
            this,
            0,
            Intent(this, RadioBackgroundService::class.java).setAction(ACTION_TOGGLE_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_RADIO)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
            .setContentText(getString(R.string.radio_notif_playing))
            .setContentIntent(openApp)
            // Dismissible once paused, exactly like the recorder's own notification when it is
            // not actively writing (RecordingService.buildNotification) — an ongoing notification
            // for a stream that is not making sound would be a lie the user cannot swipe away.
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (playing) R.string.action_pause else R.string.action_play),
                toggle,
            )
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val FOREGROUND_ID = 3001
        private const val ACTION_TOGGLE_PLAY_PAUSE = "com.tivicopy.player.player.RADIO_TOGGLE_PLAY_PAUSE"

        fun start(context: Context) {
            val intent = Intent(context, RadioBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RadioBackgroundService::class.java))
        }
    }
}
