package com.tivicopy.player

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.tivicopy.player.data.account.AccountRepository
import com.tivicopy.player.data.backup.BackupManager
import com.tivicopy.player.data.cache.CacheBudget
import com.tivicopy.player.data.db.Dao
import com.tivicopy.player.data.db.Database
import com.tivicopy.player.data.featured.FeaturedRepository
import com.tivicopy.player.data.news.NewsRepository
import com.tivicopy.player.data.prefs.AppLocale
import com.tivicopy.player.data.prefs.Settings
import com.tivicopy.player.data.repo.EpgRepository
import com.tivicopy.player.data.repo.SourceRepository
import com.tivicopy.player.data.tasks.TaskRepository
import com.tivicopy.player.data.update.UpdateManager
import com.tivicopy.player.player.PlayerEngine
import com.tivicopy.player.record.RecordingScheduler
import com.tivicopy.player.tv.HomeRow
import com.tivicopy.player.tv.WatchNext
import com.tivicopy.player.work.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    lateinit var updates: UpdateManager private set
    lateinit var featured: FeaturedRepository private set
    lateinit var tasks: TaskRepository private set
    lateinit var news: NewsRepository private set
    lateinit var account: AccountRepository private set

    /**
     * A scope for writes that must outlive the screen that ordered them.
     *
     * The last thing an on-demand session does is record where it got to, and that happens in
     * `Activity.onStop` — which, on the way out of the app, is followed immediately by
     * `onCleared` cancelling `viewModelScope`. A write dispatched onto that scope can be
     * cancelled before it reaches the database, so the one write that matters most would be the
     * one most likely to be dropped. `SupervisorJob` so one failed write cannot take the rest
     * of the scope down with it.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var localeContext: Context? = null

    /**
     * The application context, resolving strings in the chosen interface language (FR-UI-23).
     *
     * `Application` is attached once and is not rebuilt when the language changes — only the
     * activity is recreated — so anything reading a string outside a composition needs this
     * rather than [appContext]. Cached, because building it allocates a `Configuration` and a
     * context and it is read once per user-facing message.
     */
    val locale: Context
        get() = localeContext ?: AppLocale.wrap(appContext).also { localeContext = it }

    /** Called when the language setting changes, so the next read rebuilds. */
    fun refreshLocale() {
        localeContext = null
    }

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
        updates = UpdateManager(appContext, settings, dao)
        featured = FeaturedRepository(settings)
        tasks = TaskRepository(settings)
        news = NewsRepository(settings)
        account = AccountRepository(appContext, appScope)
        initialised = true
        // After `initialised`: a Firebase start that throws must not leave the rest unbuilt, and
        // AccountRepository.start catches everything it can throw anyway (ADR-059).
        account.start()
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
        // FR-UI-23. On Android 13 and later the platform is the one applying the language, so
        // this is where a choice made in an older build — or restored from a backup — is handed
        // over to it, and where a change made from the device's own per-app language screen is
        // taken back. Before the channels below, which are declared in whatever it settles on.
        AppLocale.reconcile(this, ServiceLocator.settings)
        createNotificationChannels(this)
        RefreshScheduler.scheduleAll(this)
        // The bundled playlists this app ships with (docs/00-scope-and-platforms.md §0.1);
        // idempotent, so these are no-ops after the first cold start.
        ServiceLocator.appScope.launch { ServiceLocator.sources.ensureBuiltInPlaylist() }
        ServiceLocator.appScope.launch { ServiceLocator.sources.ensureRadioPlaylist() }
        ServiceLocator.appScope.launch { ServiceLocator.sources.sealStoredCredentials() }
        // Re-arm anything that survived a process death but not a reboot. Kept on this thread:
        // it also marks recordings a dead process left running as interrupted, and run alongside
        // an alarm that has just fired, that sweep could catch the recording it started.
        ServiceLocator.recordings.rearmAll()
        // Keep the on-disk cache inside its budget on every cold start (FR-SET-15). That is a
        // walk over every file in the cache, thousands of logos, so it runs off the main thread,
        // where it held up every launch (docs/qa, finding 7).
        ServiceLocator.appScope.launch {
            ServiceLocator.cache.enforce(ServiceLocator.settings.state.value.cacheBudgetMb)
        }
        // The home screen's featured rail (FR-UI-26) and the launcher's continue-watching row
        // (FR-TVI-02). Both are rate-limited internally, both are no-ops when unconfigured or
        // off a television, and neither is allowed to matter: a promotional rail and a shelf
        // outside the app are not reasons to slow down a cold start or to fail one.
        ServiceLocator.appScope.launch { ServiceLocator.featured.refresh(force = false) }
        ServiceLocator.appScope.launch { WatchNext.sync(this@App) }
        // The app's own home-screen row (FR-TVI-03), on the same terms.
        ServiceLocator.appScope.launch { HomeRow.sync(this@App) }
        // The screensaver's task list (FR-UI-32), on the same terms as the two above: rate
        // limited inside the repository, a no-op while no address is configured, and never a
        // reason to slow a cold start down or to fail one.
        ServiceLocator.appScope.launch { ServiceLocator.tasks.refresh(force = false) }
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_RADIO = "radio_playback"

        /**
         * The platform stores a channel's name at creation and never re-reads it, so changing the
         * interface language has to re-declare them. Creating a channel that already exists
         * updates its name and description, which is exactly what is wanted here.
         */
        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val strings = ServiceLocator.locale
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
            // LOW, not DEFAULT: this is an ongoing "still playing" indicator, not something
            // worth a sound or a heads-up every time a station changes.
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RADIO,
                    strings.getString(R.string.channel_radio),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = strings.getString(R.string.channel_radio_desc) },
            )
        }
    }
}
