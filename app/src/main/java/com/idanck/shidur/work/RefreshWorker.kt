package com.idanck.shidur.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.tv.HomeRow
import com.idanck.shidur.tv.WatchNext
import com.idanck.shidur.core.Outcome
import com.idanck.shidur.data.prefs.Settings
import com.idanck.shidur.record.SERIES_LOOKAHEAD_MILLIS
import java.util.concurrent.TimeUnit

/**
 * Background playlist and guide refresh (FR-SRC-10, FR-EPG-05).
 *
 * WorkManager is deliberately the wrong tool for a recording start — it offers no timing
 * guarantee — but it is the right one here: a guide that lands ten minutes late is harmless,
 * and WorkManager handles the doze, retry and network-constraint behaviour that a bare
 * scheduler would have to reinvent.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        val dao = ServiceLocator.dao
        val sources = ServiceLocator.sources
        val epg = ServiceLocator.epg
        val settings = ServiceLocator.settings.state.value

        val now = System.currentTimeMillis()
        var anyFailure = false

        // No guide at all yet: the first guide download failed (or never ran) while the channel
        // refresh beside it succeeded. The guide used to wait for the playlist's own clock, so a
        // failed first attempt — and WorkManager's retry of this very run, which found nothing
        // due — left the guide empty for a whole interval (a day, for the bundled channels).
        val guideMissing = settings.epgRefreshIntervalMinutes > 0 && dao.programmeCount() == 0

        dao.playlists().filter { it.enabled }.forEach { playlist ->
            val due = playlist.refreshIntervalMinutes > 0 &&
                now - playlist.lastRefreshUtc >= playlist.refreshIntervalMinutes * 60_000L
            val guideOnly = !due && guideMissing && !playlist.epgUrl.isNullOrBlank()
            if (!due && !guideOnly) return@forEach

            if (due) {
                when (sources.refresh(playlist.id)) {
                    is Outcome.Err -> anyFailure = true
                    is Outcome.Ok -> Unit
                }
            }

            if (settings.epgRefreshIntervalMinutes > 0) {
                // A playlist with no guide address (the bundled radio) has no guide to fail on;
                // counting its "no guide URL" as a failure made every run that refreshed it retry.
                val refreshed = dao.playlist(playlist.id)?.takeIf { !it.epgUrl.isNullOrBlank() }
                    ?: return@forEach
                if (epg.refreshGuide(refreshed) is Outcome.Err) anyFailure = true
            }
        }

        // FR-REC-04: a series recording covers episodes the guide had not published when the
        // user asked for it, so every refresh extends the schedule forward.
        val recordings = ServiceLocator.recordings
        recordings.activeSeries().forEach { (channelId, keys) ->
            val channel = dao.channel(channelId) ?: return@forEach
            val upcoming = epg.programmesFor(channel, now, now + SERIES_LOOKAHEAD_MILLIS)
            keys.forEach { key -> recordings.extendSeries(channel, upcoming, key) }
        }

        // Housekeeping that has to happen somewhere and must not depend on the UI being open.
        epg.pruneOldProgrammes()
        ServiceLocator.cache.enforce(settings.cacheBudgetMb)

        // FR-UPD-02: one small request, rate-limited to a day inside the manager, and never a
        // download — deciding to install is the user's. Its outcome does not affect this run:
        // an unreachable update host says nothing about the playlists that just refreshed.
        ServiceLocator.updates.check(force = false)

        // The home screen's featured rail (FR-UI-26), on the same terms: rate-limited inside the
        // repository, a no-op when no feed address is configured, and its outcome is nobody
        // else's business here — a promotional rail cannot fail a playlist refresh.
        ServiceLocator.featured.refresh(force = false)

        // The library's feeds (ADR-067), every six hours inside the repository. A podcast host
        // that is down is not a reason to retry the playlists.
        ServiceLocator.podcasts.refreshAll(force = false)

        // FR-TVI-02: the hourly sweep is what catches the cases a channel change cannot — a
        // channel a playlist refresh deleted, or one that has simply gone stale — so this one
        // is forced past the throttle that exists for zapping.
        WatchNext.sync(applicationContext, force = true)
        // FR-TVI-03: the same sweep is also what moves each card's "on now" line along with the
        // schedule, so it is forced too.
        HomeRow.sync(applicationContext, force = true)

        // A failed refresh is retried by WorkManager with its own backoff; the previous
        // snapshot is still intact either way (FR-SRC-12).
        return if (anyFailure) Result.retry() else Result.success()
    }
}

object RefreshScheduler {

    private const val WORK_NAME = "periodic-refresh"

    fun scheduleAll(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        // WorkManager stores the worker by class name, and KEEP keeps that stored name. When the
        // package moved (ADR-060) the bridge's stored work still named the old class, which no
        // longer exists: KEEP would have left a job that fails on every run, and the bridge would
        // never refresh in the background again. A class name that differs from the one last
        // scheduled replaces the work once; after that it is KEEP as before.
        val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val worker = RefreshWorker::class.java.name
        val renamed = prefs.getString(Settings.K_REFRESH_WORKER, null) != worker
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            if (renamed) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        if (renamed) prefs.edit().putString(Settings.K_REFRESH_WORKER, worker).apply()
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
