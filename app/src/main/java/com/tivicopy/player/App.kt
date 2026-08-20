package com.tivicopy.player

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.tivicopy.player.data.backup.BackupManager
import com.tivicopy.player.data.cache.CacheBudget
import com.tivicopy.player.data.db.Dao
import com.tivicopy.player.data.db.Database
import com.tivicopy.player.data.prefs.AppLocale
import com.tivicopy.player.data.prefs.Settings
import com.tivicopy.player.data.prefs.localised
import com.tivicopy.player.data.repo.EpgRepository
import com.tivicopy.player.data.repo.SourceRepository
import com.tivicopy.player.player.PlayerEngine
import com.tivicopy.player.record.RecordingScheduler
import com.tivicopy.player.work.RefreshScheduler

/**
 * Manual dependency graph.
 *
 * A hand-written locator is used rather than an annotation-processed injector: the graph is
 * small and entirely singleton-scoped, and keeping annotation processing out of the build is
 * what lets this project compile from a clean checkout with nothing but the Gradle wrapper.
 */
object ServiceLocator {

    @Volatile private var initialised = false

    lateinit var appContext: Context private set
    lateinit var database: Database private set
    lateinit var dao: Dao private set
    lateinit var settings: Settings private set
    lateinit var sources: SourceRepository private set
    lateinit var epg: EpgRepository private set
    lateinit var cache: CacheBudget private set
    lateinit var backup: BackupManager private set
    lateinit var recordings: RecordingScheduler private set

    /** The player is created lazily so a headless recording run never builds one. */
    val player: PlayerEngine by lazy { PlayerEngine(appContext, settings) }

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        appContext = context.applicationContext
        database = Database(appContext)
        dao = Dao(database)
        settings = Settings(appContext)
        sources = SourceRepository(appContext, dao, settings)
        epg = EpgRepository(dao, settings, sources)
        cache = CacheBudget(appContext)
        backup = BackupManager(dao, settings)
        recordings = RecordingScheduler(appContext, dao, settings)
        initialised = true
    }
}

/**
 * [coil.ImageLoaderFactory] is implemented so the image cache has a real, budget-derived size
 * limit. Without it Coil sizes its own cache from free disk space and the app would be reduced
 * to deleting files behind Coil's journal, which is exactly the unbounded-cache failure mode
 * FR-SET-15 exists to prevent.
 */
class App : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        // Coil may build the loader before onCreate finishes on some launch paths; init is
        // idempotent, so asking for it here costs nothing and removes the ordering question.
        ServiceLocator.init(this)
        val budgetBytes = ServiceLocator.settings.state.value.cacheBudgetMb * 1024L * 1024L
        return ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(CacheBudget.IMAGE_DIR))
                    // Images get half the budget; the guide database and its working files
                    // account for the rest.
                    .maxSizeBytes((budgetBytes / 2).coerceAtLeast(32L * 1024 * 1024))
                    .build()
            }
            .build()
    }

    /**
     * FR-UI-23: the chosen language has to be in place before anything reads a string, and
     * `Application` resolves resources through this base context. Without it the notification
     * channels below, and everything else resolved off the application context, would speak the
     * system language while the UI spoke the user's.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        createNotificationChannels(this)
        RefreshScheduler.scheduleAll(this)
        // Re-arm anything that survived a process death but not a reboot.
        ServiceLocator.recordings.rearmAll()
        // Keep the on-disk cache inside its budget on every cold start (FR-SET-15).
        ServiceLocator.cache.enforce(ServiceLocator.settings.state.value.cacheBudgetMb)
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_ALERTS = "alerts"

        /**
         * The platform stores a channel's name at creation and never re-reads it, so changing the
         * interface language has to re-declare them. Creating a channel that already exists
         * updates its name and description, which is exactly what is wanted here.
         */
        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val strings = context.localised
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RECORDING,
                    strings.getString(R.string.channel_recording),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = strings.getString(R.string.channel_recording_desc) },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    strings.getString(R.string.channel_alerts),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = strings.getString(R.string.channel_alerts_desc) },
            )
        }
    }
}
