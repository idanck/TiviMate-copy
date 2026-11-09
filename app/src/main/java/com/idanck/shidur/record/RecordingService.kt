package com.idanck.shidur.record

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.idanck.shidur.App
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.model.Recording
import com.idanck.shidur.data.model.RecordingState
import com.idanck.shidur.data.net.HttpFactory
import com.idanck.shidur.data.prefs.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes live streams to disk, independently of any UI (FR-REC-14).
 *
 * The recorder opens its **own** HTTP connection rather than tapping the playback pipeline
 * (ADR-003). That costs one provider connection per recording, which is stated in the UI, and
 * buys two things worth more: a recording is unaffected by what the user is watching, and the
 * bytes are written exactly as the provider sent them, so the file is a plain MPEG-TS any
 * player can open (FR-REC-10).
 *
 * A dropped stream reconnects and keeps appending to the same file, counting the gap
 * (FR-REC-18), because a two-hour recording with a ten-second hole is worth far more than no
 * recording at all.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Set by [onTimeout], so a recording it stopped says why instead of "Stopped". */
    @Volatile
    private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * FR-UI-23. A service is handed a fresh context by the framework rather than the one
     * `Application` was attached with, so without this every string below — the ongoing recording
     * notification included — would come back in the system language while the UI spoke the
     * user's chosen one.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recordingId = intent?.getLongExtra(EXTRA_RECORDING_ID, 0L) ?: 0L
        when (intent?.action) {
            ACTION_START -> start(recordingId)
            ACTION_STOP -> stopOne(recordingId)
            else -> if (jobs.isEmpty()) stopSelf()
        }
        return START_STICKY
    }

    private fun start(recordingId: Long) {
        if (recordingId == 0L || jobs.containsKey(recordingId)) return
        val dao = ServiceLocator.dao
        val recording = dao.recording(recordingId) ?: run {
            if (jobs.isEmpty()) stopSelf()
            return
        }
        // Started with startForegroundService, so the platform is owed a startForeground call
        // before anything else — even for a recording cancelled since its alarm fired. Returning
        // without one was a crash ("did not then call Service.startForeground").
        val promoted = promoteToForeground(recording)
        if (!promoted || recording.state == RecordingState.CANCELLED) {
            if (!promoted && recording.state == RecordingState.SCHEDULED) {
                finish(recording, RecordingState.FAILED, getString(R.string.rec_error_not_started), 0, 0)
            }
            if (jobs.isEmpty()) {
                if (promoted) stopForegroundCompat()
                stopSelf()
            }
            return
        }
        acquireLocks()

        val job = scope.launch { runRecording(recording) }
        jobs[recordingId] = job
        job.invokeOnCompletion {
            jobs.remove(recordingId)
            if (jobs.isEmpty()) {
                releaseLocks()
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun stopOne(recordingId: Long) {
        jobs[recordingId]?.cancel()
    }

    private suspend fun runRecording(scheduled: Recording) {
        // Mutable, because the destination is only known once storage has been opened and every
        // exit path below builds its final update from this snapshot.
        var recording = scheduled
        val dao = ServiceLocator.dao
        val settings = ServiceLocator.settings.state.value
        val channel = dao.channel(recording.channelId)
        if (channel == null) {
            finish(recording, RecordingState.FAILED, getString(R.string.rec_error_channel_missing), 0, 0)
            return
        }
        val url = channel.urls.firstOrNull()
        if (url.isNullOrBlank()) {
            finish(recording, RecordingState.FAILED, getString(R.string.rec_error_no_url), 0, 0)
            return
        }

        val playlist = dao.playlist(channel.playlistId)
        val profile = playlist?.let { ServiceLocator.sources.profileFor(it) }
            ?: com.idanck.shidur.data.net.RequestProfile(userAgent = settings.userAgent)

        val target = RecordingStorage.create(this, settings.recordingFolderUri, recording)
        if (target == null) {
            finish(recording, RecordingState.FAILED, getString(R.string.rec_error_destination), 0, 0)
            return
        }

        // Carried on the local snapshot, not only written to the row: every `finish` below builds
        // its update from `recording`, so a locator that lived only in the database was written
        // straight back to NULL when the recording ended — leaving a file on disk that the
        // library could neither play nor delete.
        recording = recording.copy(state = RecordingState.RECORDING, filePath = target.locator)
        dao.upsertRecording(recording)
        notify(recording, ongoing = true)

        var written = 0L
        var gaps = 0
        var attempt = 0

        try {
            openOutput(target.uri)?.use { output ->
                while (currentCoroutineContext().isActive && System.currentTimeMillis() < recording.endUtc) {
                    val remaining = recording.endUtc - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val copied = runCatching { copyStream(url, profile, output, recording.endUtc) }
                        .getOrElse { -1L }
                    if (copied > 0) written += copied
                    if (System.currentTimeMillis() >= recording.endUtc) break
                    // The stream ended or failed before the scheduled end: reconnect.
                    gaps++
                    attempt++
                    val backoff = (1_000L shl attempt.coerceAtMost(4)).coerceAtMost(15_000L)
                    kotlinx.coroutines.delay(backoff)
                    if (attempt > MAX_RECONNECTS) break
                }
                output.flush()
            } ?: run {
                finish(recording, RecordingState.FAILED, getString(R.string.rec_error_destination), 0, 0)
                return
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            val reason = if (timedOut) R.string.rec_error_time_limit else R.string.rec_stopped_by_user
            finish(recording, RecordingState.PARTIAL, getString(reason), written, gaps)
            return
        } catch (t: Throwable) {
            finish(recording, RecordingState.PARTIAL, t.message ?: "io error", written, gaps)
            return
        }

        val state = when {
            written == 0L -> RecordingState.FAILED
            gaps > 0 || System.currentTimeMillis() < recording.endUtc - 30_000 -> RecordingState.PARTIAL
            else -> RecordingState.COMPLETED
        }
        finish(recording, state, if (gaps > 0) getString(R.string.rec_gaps, gaps) else null, written, gaps)
    }

    /** Copies until the stream ends, the deadline passes, or the job is cancelled. */
    private suspend fun copyStream(
        url: String,
        profile: com.idanck.shidur.data.net.RequestProfile,
        output: OutputStream,
        deadlineUtc: Long,
    ): Long {
        val client = HttpFactory.client(profile)
        val response = client.newCall(HttpFactory.request(url, profile)).execute()
        response.use { r ->
            if (!r.isSuccessful) return -1L
            val body = r.body ?: return -1L
            val input = body.byteStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            var lastFlush = System.currentTimeMillis()
            while (currentCoroutineContext().isActive) {
                if (System.currentTimeMillis() >= deadlineUtc) break
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
                val now = System.currentTimeMillis()
                if (now - lastFlush > FLUSH_INTERVAL_MS) {
                    output.flush()
                    lastFlush = now
                }
            }
            return total
        }
    }

    private fun openOutput(uri: Uri): OutputStream? = try {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            contentResolver.openOutputStream(uri, "w")
        } else {
            java.io.FileOutputStream(uri.path!!)
        }?.buffered(BUFFER_BYTES)
    } catch (_: Exception) {
        null
    }

    private fun finish(recording: Recording, state: RecordingState, message: String?, bytes: Long, gaps: Int) {
        val updated = recording.copy(
            state = state,
            message = message,
            fileSizeBytes = bytes,
            gapCount = gaps,
        )
        ServiceLocator.dao.upsertRecording(updated)
        notify(updated, ongoing = false)
    }

    // ------------------------------------------------------------ foreground

    /**
     * Answers whether the platform let the service into the foreground. Android 15 gives a
     * dataSync service six hours in every 24, and past that refuses with
     * ForegroundServiceStartNotAllowedException — an IllegalStateException — rather than
     * letting the recording run.
     */
    private fun promoteToForeground(recording: Recording): Boolean {
        val notification = buildNotification(recording, ongoing = true)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    /**
     * Android 15: the dataSync allowance has run out. A service still running a few seconds after
     * this is crashed, so every recording stops now and keeps what it has written, ending as
     * partial with the reason.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        jobs.values.forEach { it.cancel() }
        releaseLocks()
        stopForegroundCompat()
        stopSelf()
    }

    private fun buildNotification(recording: Recording, ongoing: Boolean): Notification {
        val text = when (recording.state) {
            RecordingState.RECORDING, RecordingState.SCHEDULED ->
                getString(R.string.rec_notif_recording, recording.channelName)
            RecordingState.COMPLETED -> getString(R.string.rec_notif_done)
            RecordingState.PARTIAL -> getString(R.string.rec_notif_partial, recording.message ?: "")
            else -> getString(R.string.rec_notif_failed, recording.message ?: "")
        }
        val stopIntent = PendingIntent.getService(
            this,
            recording.id.toInt(),
            Intent(this, RecordingService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_RECORDING_ID, recording.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_RECORDING)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(recording.title)
            .setContentText(text)
            .setSubText(TimeModel.formatClock(recording.startUtc) + " – " + TimeModel.formatClock(recording.endUtc))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply {
                if (ongoing) addAction(0, getString(R.string.action_stop), stopIntent)
            }
            .build()
    }

    /** FR-SET-12: every state change produces a visible notification. */
    private fun notify(recording: Recording, ongoing: Boolean) {
        val manager = NotificationManagerCompat.from(this)
        val id = (NOTIFICATION_BASE + recording.id).toInt()
        try {
            manager.notify(id, buildNotification(recording, ongoing))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was refused; the recording itself is unaffected.
        }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)?.apply {
                setReferenceCounted(false)
                acquire(MAX_LOCK_MILLIS)
            }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.idanck.shidur.record.START"
        const val ACTION_STOP = "com.idanck.shidur.record.STOP"
        const val EXTRA_RECORDING_ID = "recording_id"

        private const val FOREGROUND_ID = 1001
        private const val NOTIFICATION_BASE = 2000L
        private const val BUFFER_BYTES = 64 * 1024
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val MAX_RECONNECTS = 20
        private const val MAX_LOCK_MILLIS = 6L * 60 * 60 * 1000
        private const val WAKE_TAG = "tivicopy:recording"
        private const val WIFI_TAG = "tivicopy:recording"
    }
}

/** Resolves the destination for a recording, on a SAF tree or a plain path. */
object RecordingStorage {

    /**
     * [locator] is what gets stored on the recording: an absolute path for a plain file, or the
     * document URI for a SAF tree. Both forms are enough to play the file back *and* to delete
     * it later — storing only a display name would leave SAF recordings undeletable.
     */
    data class Target(val uri: Uri, val locator: String)

    /**
     * FR-REC-10 / 7.3: files are written through the Storage Access Framework into a tree the
     * user granted, so no broad storage permission is needed on Android 10+.
     */
    fun create(context: Context, folder: String, recording: Recording): Target? {
        if (folder.isBlank()) return null
        val name = fileName(recording)
        return if (folder.startsWith("content://")) {
            createDocument(context, Uri.parse(folder), name)
        } else {
            val dir = java.io.File(folder)
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = java.io.File(dir, name)
            runCatching { file.createNewFile() }.getOrNull() ?: return null
            Target(Uri.fromFile(file), file.absolutePath)
        }
    }

    private fun createDocument(context: Context, treeUri: Uri, name: String): Target? = try {
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val created = DocumentsContract.createDocument(context.contentResolver, parent, MIME_TS, name)
        created?.let { Target(it, it.toString()) }
    } catch (_: Exception) {
        null
    }

    /** FR-REC-11 default template, sanitised for filesystems that reject `/` and `:`. */
    fun fileName(recording: Recording): String {
        val safeChannel = sanitize(recording.channelName)
        val safeTitle = sanitize(recording.title)
        val stamp = TimeModel.formatForFilename(recording.startUtc)
        return "$safeChannel - $safeTitle - $stamp.ts"
    }

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().take(80).ifBlank { "untitled" }

    /** Deletes a finished recording, whichever storage it landed on. */
    fun delete(context: Context, locator: String?): Boolean {
        if (locator.isNullOrBlank()) return false
        return runCatching {
            if (locator.startsWith("content://")) {
                DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(locator))
            } else {
                java.io.File(locator).delete()
            }
        }.getOrDefault(false)
    }

    private const val MIME_TS = "video/mp2t"
}
