package com.tivicopy.player.record

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import com.tivicopy.player.R
import com.tivicopy.player.core.TextNormalizer
import com.tivicopy.player.data.db.Dao
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.model.Recording
import com.tivicopy.player.data.model.RecordingState
import com.tivicopy.player.data.prefs.AppLocale
import com.tivicopy.player.data.prefs.Settings
import java.io.File

/** How far ahead a series recording claims episodes from the guide. */
const val SERIES_LOOKAHEAD_MILLIS = 14L * 24 * 60 * 60 * 1000

/** Why a schedule request could not be honoured, so the UI can say something specific. */
sealed class ScheduleResult {
    data class Scheduled(val recording: Recording, val warnings: List<Warning>) : ScheduleResult()
    data class Rejected(val reason: Warning) : ScheduleResult()

    enum class Warning {
        /** The platform cannot wake this device; it must stay on (FR-REC-17). */
        DEVICE_MUST_STAY_ON,

        /** Exact alarms are not permitted; the start may drift (Android 12+). */
        EXACT_ALARMS_UNAVAILABLE,

        /** More concurrent recordings than the configured limit (FR-REC-08). */
        CONCURRENCY_LIMIT,

        /** Not enough free space for the estimated size (FR-REC-12). */
        INSUFFICIENT_SPACE,

        /** Free space is above the hard floor but below the warning threshold. */
        LOW_SPACE,

        /** No destination folder has been chosen yet. */
        NO_DESTINATION,

        /** The guide has no series metadata for this programme (FR-REC-05). */
        NO_SERIES_METADATA,
    }
}

/**
 * Schedules recordings with exact alarms and keeps them armed across reboots.
 *
 * The reliability requirements (FR-REC-14..17) are what this class exists for: a schedule is
 * an `AlarmManager` exact alarm that starts a foreground service, not a timer inside the UI
 * process, so a recording happens whether or not anything is on screen. Where the platform
 * genuinely cannot wake the device, that is reported at scheduling time rather than
 * discovered as a missing file the next morning.
 */
class RecordingScheduler(
    private val context: Context,
    private val dao: Dao,
    private val settings: Settings,
) {

    private val alarms: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** Bitrate assumed when estimating the size of a recording, in bits per second. */
    private val assumedBitrate = 6_000_000L

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarms?.canScheduleExactAlarms() == true else true

    /**
     * The platform gives no way to power a device on from a cold state. `setExactAndAllowWhileIdle`
     * wakes a *sleeping* device, which covers most TV boxes; a device that is switched off
     * cannot be reached at all, which is what the user is warned about.
     */
    private fun deviceMustStayOnWarning(): ScheduleResult.Warning? =
        if (canScheduleExact()) ScheduleResult.Warning.DEVICE_MUST_STAY_ON
        else ScheduleResult.Warning.EXACT_ALARMS_UNAVAILABLE

    fun scheduleProgramme(channel: Channel, programme: Programme): ScheduleResult = schedule(
        channel = channel,
        title = programme.title,
        startUtc = programme.startUtc - settings.state.value.recordingPreRollMinutes * 60_000L,
        endUtc = programme.endUtc + settings.state.value.recordingPostRollMinutes * 60_000L,
        seriesKey = null,
    )

    /**
     * FR-REC-04/05: records every future episode the guide currently lists, and stores the
     * series key so [extendSeries] can pick up episodes published later.
     *
     * When the guide carries no series metadata this is refused outright with
     * [ScheduleResult.Warning.NO_SERIES_METADATA]. That is the whole point: the documented
     * failure of the benchmark is accepting a series recording and then silently recording
     * nothing, so saying no here — before the user walks away expecting files — is the
     * feature.
     *
     * @param upcoming future programmes for this channel, already offset-corrected.
     */
    fun scheduleSeries(channel: Channel, programme: Programme, upcoming: List<Programme>): ScheduleResult {
        if (!programme.hasSeriesMetadata) {
            return ScheduleResult.Rejected(ScheduleResult.Warning.NO_SERIES_METADATA)
        }
        val key = seriesKeyFor(channel, programme)
        val first = schedule(
            channel = channel,
            title = programme.title,
            startUtc = programme.startUtc - settings.state.value.recordingPreRollMinutes * 60_000L,
            endUtc = programme.endUtc + settings.state.value.recordingPostRollMinutes * 60_000L,
            seriesKey = key,
        )
        if (first is ScheduleResult.Scheduled) {
            extendSeries(channel, upcoming.filter { it.startUtc > programme.startUtc }, key)
        }
        return first
    }

    /**
     * Schedules episodes of an already-requested series that the guide has published since.
     * Called after every guide refresh.
     *
     * @return how many new episodes were scheduled.
     */
    fun extendSeries(channel: Channel, upcoming: List<Programme>, seriesKey: String): Int {
        val canonicalTitle = seriesKey.substringAfter(':')
        val existing = dao.recordings().filter { it.channelId == channel.id }
        var added = 0
        upcoming.forEach { programme ->
            if (TextNormalizer.canonical(programme.title) != canonicalTitle) return@forEach
            // An episode already scheduled, recorded or explicitly cancelled is left alone.
            if (existing.any { it.startUtc <= programme.startUtc && it.endUtc >= programme.endUtc }) return@forEach
            val result = schedule(
                channel = channel,
                title = programme.title,
                startUtc = programme.startUtc - settings.state.value.recordingPreRollMinutes * 60_000L,
                endUtc = programme.endUtc + settings.state.value.recordingPostRollMinutes * 60_000L,
                seriesKey = seriesKey,
            )
            if (result is ScheduleResult.Scheduled) added++
        }
        return added
    }

    /** The key that ties every episode of one series on one channel together. */
    fun seriesKeyFor(channel: Channel, programme: Programme): String =
        "${channel.id}:${TextNormalizer.canonical(programme.title)}"

    /** Series the user has asked for, as channel id to key. */
    fun activeSeries(): Map<Long, Set<String>> =
        dao.recordings()
            .mapNotNull { r -> r.seriesKey?.let { r.channelId to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, keys) -> keys.toSet() }

    /** FR-REC-01: record the rest of what is on now, or a caller-chosen duration. */
    fun recordNow(channel: Channel, title: String, endUtc: Long): ScheduleResult {
        val now = System.currentTimeMillis()
        return schedule(channel, title, now, endUtc.coerceAtLeast(now + 60_000), null, emptyList())
    }

    fun schedule(
        channel: Channel,
        title: String,
        startUtc: Long,
        endUtc: Long,
        seriesKey: String?,
        extraWarnings: List<ScheduleResult.Warning> = emptyList(),
    ): ScheduleResult {
        val s = settings.state.value
        if (s.recordingFolderUri.isBlank()) {
            return ScheduleResult.Rejected(ScheduleResult.Warning.NO_DESTINATION)
        }

        val durationSeconds = ((endUtc - startUtc) / 1000).coerceAtLeast(1)
        val estimatedBytes = durationSeconds * assumedBitrate / 8
        val free = freeSpaceBytes(s.recordingFolderUri)
        if (free in 1 until estimatedBytes) {
            return ScheduleResult.Rejected(ScheduleResult.Warning.INSUFFICIENT_SPACE)
        }

        // FR-REC-08 counts connections, and a recording that has already started is holding one:
        // `pendingRecordings` is SCHEDULED only, so counting it alone let a second recording start
        // beside a running one however low the limit was set.
        val overlapping = (dao.pendingRecordings(startUtc) + dao.activeRecordings())
            .distinctBy { it.id }
            .count { it.startUtc < endUtc && it.endUtc > startUtc }
        if (overlapping >= s.maxConcurrentRecordings) {
            return ScheduleResult.Rejected(ScheduleResult.Warning.CONCURRENCY_LIMIT)
        }

        val recording = Recording(
            channelId = channel.id,
            channelName = channel.displayName,
            title = title,
            startUtc = startUtc,
            endUtc = endUtc,
            state = RecordingState.SCHEDULED,
            seriesKey = seriesKey,
        )
        val id = dao.upsertRecording(recording)
        val stored = recording.copy(id = id)
        arm(stored)

        val warnings = buildList {
            addAll(extraWarnings)
            deviceMustStayOnWarning()?.let { add(it) }
            if (free in 1 until s.recordingFreeSpaceWarnGb * 1024L * 1024 * 1024) {
                add(ScheduleResult.Warning.LOW_SPACE)
            }
        }
        return ScheduleResult.Scheduled(stored, warnings)
    }

    fun cancel(recordingId: Long) {
        alarms?.cancel(pendingIntent(recordingId))
        dao.recording(recordingId)?.let { r ->
            if (r.state == RecordingState.RECORDING) {
                context.startService(
                    Intent(context, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_STOP
                        putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
                    },
                )
            }
            dao.upsertRecording(r.copy(state = RecordingState.CANCELLED))
        }
    }

    fun cancelSeries(seriesKey: String) {
        dao.recordings(setOf(RecordingState.SCHEDULED))
            .filter { it.seriesKey == seriesKey }
            .forEach { cancel(it.id) }
    }

    /** FR-REC-15: called from the boot receiver and on every cold start. */
    fun rearmAll() {
        val now = System.currentTimeMillis()
        dao.pendingRecordings(now).forEach { recording ->
            if (recording.startUtc <= now && recording.endUtc > now) {
                // The device was off when this should have started; begin what is left of it.
                startNow(recording.id)
            } else {
                arm(recording)
            }
        }
        // Anything left marked RECORDING did not survive the process; mark it partial so the
        // library never shows a recording as running when nothing is writing to it.
        dao.activeRecordings().forEach { r ->
            dao.upsertRecording(
                r.copy(
                    state = if ((r.fileSizeBytes) > 0) RecordingState.PARTIAL else RecordingState.FAILED,
                    message = "interrupted",
                ),
            )
        }
    }

    private fun arm(recording: Recording) {
        val manager = alarms ?: return
        val intent = pendingIntent(recording.id)
        val triggerAt = recording.startUtc
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canScheduleExact()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, triggerAt, intent)
            }
        } catch (_: SecurityException) {
            // Exact-alarm permission was revoked between the check and the call.
            manager.set(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        }
    }

    /**
     * Starts the recorder for [recordingId] now: from its alarm, or for one the device was off
     * through (FR-REC-15).
     *
     * Android 12+ refuses a foreground service started from the background unless something
     * exempts it. An exact alarm firing does; an inexact one does not, and on Android 15 neither
     * does a boot broadcast for this service's type. The refusal is an exception, and uncaught —
     * in the alarm receiver, or in `Application.onCreate` through [rearmAll] — it took the whole
     * app down. The recording is marked failed with the reason instead, so the library says what
     * happened rather than the recording silently never existing.
     */
    fun startNow(recordingId: Long) {
        val recording = dao.recording(recordingId) ?: return
        // Cancelled, or already started by another path: a service started for it would owe the
        // platform a foreground notification it has nothing to show for.
        if (recording.state != RecordingState.SCHEDULED) return
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException.
            dao.upsertRecording(
                recording.copy(
                    state = RecordingState.FAILED,
                    message = AppLocale.wrap(context).getString(R.string.rec_error_not_started),
                ),
            )
        }
    }

    /** The request code is the recording id, which is what makes cancelling match one alarm. */
    private fun pendingIntent(recordingId: Long): PendingIntent {
        val intent = Intent(context, RecordingAlarmReceiver::class.java).apply {
            action = RecordingAlarmReceiver.ACTION_FIRE
            putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, recordingId.toInt(), intent, flags)
    }

    private fun freeSpaceBytes(folderUri: String): Long = try {
        val path = if (folderUri.startsWith("/")) folderUri else context.filesDir.absolutePath
        StatFs(File(path).absolutePath).availableBytes
    } catch (_: Exception) {
        -1L
    }
}
