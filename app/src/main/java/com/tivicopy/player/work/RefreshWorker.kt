package com.tivicopy.player.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.Outcome
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

        dao.playlists().filter { it.enabled }.forEach { playlist ->
            val due = playlist.refreshIntervalMinutes > 0 &&
                now - playlist.lastRefreshUtc >= playlist.refreshIntervalMinutes * 60_000L
            if (!due) return@forEach

            when (sources.refresh(playlist.id)) {
                is Outcome.Err -> anyFailure = true
                is Outcome.Ok -> Unit
            }

            if (settings.epgRefreshIntervalMinutes > 0) {
                val refreshed = dao.playlist(playlist.id) ?: return@forEach
                if (epg.refreshGuide(refreshed) is Outcome.Err) anyFailure = true
            }
        }

        // Housekeeping that has to happen somewhere and must not depend on the UI being open.
        epg.pruneOldProgrammes()
        ServiceLocator.cache.enforce(settings.cacheBudgetMb)

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
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
