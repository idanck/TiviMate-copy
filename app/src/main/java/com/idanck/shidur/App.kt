package com.idanck.shidur

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.idanck.shidur.data.account.AccountRepository
import com.idanck.shidur.data.backup.BackupManager
import com.idanck.shidur.data.cache.CacheBudget
import com.idanck.shidur.data.db.Dao
import com.idanck.shidur.data.db.Database
import com.idanck.shidur.data.featured.FeaturedRepository
import com.idanck.shidur.data.handover.BridgeHandover
import com.idanck.shidur.data.handover.Handover
import com.idanck.shidur.data.handover.HandoverImporter
import com.idanck.shidur.data.news.NewsRepository
import com.idanck.shidur.data.prefs.AppLocale
import com.idanck.shidur.data.prefs.Settings
import com.idanck.shidur.data.repo.EpgRepository
import com.idanck.shidur.data.repo.SourceRepository
import com.idanck.shidur.data.tasks.TaskRepository
import com.idanck.shidur.data.update.UpdateManager
import com.idanck.shidur.data.youtube.MusicRepository
import com.idanck.shidur.data.youtube.YouTubeRepository
import com.idanck.shidur.data.podcast.PodcastDao
import com.idanck.shidur.data.podcast.PodcastDirectory
import com.idanck.shidur.data.podcast.PodcastRepository
import com.idanck.shidur.data.podcast.PodcastSync
import com.idanck.shidur.player.PlayerEngine
import com.idanck.shidur.record.RecordingScheduler
import com.idanck.shidur.tv.HomeRow
import com.idanck.shidur.tv.WatchNext
import com.idanck.shidur.work.RefreshScheduler
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
    lateinit var youtube: YouTubeRepository private set
    lateinit var music: MusicRepository private set
    lateinit var podcasts: PodcastRepository private set
    lateinit var podcastDirectory: PodcastDirectory private set
    lateinit var podcastSync: PodcastSync private set
    lateinit var handoverImporter: HandoverImporter private set

    /**
     * The bridge's installer for the new app (ADR-060): its own manifest, the new package only.
     * Null in the new app itself.
     */
    var handover: UpdateManager? = null
        private set

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
        backup = BackupManager(dao, settings, PodcastDao(database))
        recordings = RecordingScheduler(appContext, dao, settings)
        updates = UpdateManager(appContext, settings, dao)
        featured = FeaturedRepository(settings)
        tasks = TaskRepository(settings)
        news = NewsRepository(settings)
        account = AccountRepository(appContext, appScope)
        youtube = YouTubeRepository(appContext) { account.functions() }
        podcastDirectory = PodcastDirectory { account.functions() }
        // The history says where an episode was heard (ADR-067); the television is the one with
        // the leanback feature.
        podcasts = PodcastRepository(PodcastDao(database), device = {
            val ui = appContext.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            if (ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) "tv" else "phone"
        })
        // After `podcasts`: PodcastSync reads it here, and reading a lateinit before it is set
        // throws in Application.onCreate, so every launch crashed (0.32.5).
        // The library, marks and queue follow the account between devices (ADR-067).
        podcastSync = PodcastSync(podcasts, account.state, { account.firestore() }, appScope).also { it.start() }
        // YouTube Music (ADR-069): the likes, saved albums and history Shidur keeps follow the account.
        music = MusicRepository(appContext, { account.functions() }, { account.firestore() }, account.state, appScope, device = {
            val ui = appContext.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            if (ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) "tv" else "phone"
        }).also { it.start() }
        handoverImporter = HandoverImporter(appContext, database, settings)
        if (Handover.isBridge) {
            handover = UpdateManager(
                appContext,
                settings,
                dao,
                manifestUrl = BuildConfig.SHIDUR_MANIFEST_URL,
                targetPackage = Handover.shidurPackage(appContext.packageName),
            )
            BridgeHandover.load(settings)
        }
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
        // ADR-060: the first start of the new app, with the old package's data waiting. The rest
        // of the start-up writes into the very tables the import replaces (the bundled playlists,
        // a refresh, the recording schedule), so it waits; the import restarts the app when done,
        // and a failed one hands the start-up back ([startBackgroundWork]).
        val importer = ServiceLocator.handoverImporter
        if (importer.pending()) {
            ServiceLocator.appScope.launch { importer.run() }
            return
        }
        startBackgroundWork(this)
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_RADIO = "radio_playback"

        /** "Updated — open it" after an in-app update (FR-UPD-09); temporary, see ADR-062. */
        const val CHANNEL_UPDATES = "updates"

        /** Everything a cold start sets going once the database is this install's to write. */
        fun startBackgroundWork(context: Context) {
            val app = context.applicationContext
            RefreshScheduler.scheduleAll(app)
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
            ServiceLocator.appScope.launch { WatchNext.sync(app) }
            // The app's own home-screen row (FR-TVI-03), on the same terms.
            ServiceLocator.appScope.launch { HomeRow.sync(app) }
            // The screensaver's task list (FR-UI-32), on the same terms as the two above: rate
            // limited inside the repository, a no-op while no address is configured, and never a
            // reason to slow a cold start down or to fail one.
            ServiceLocator.appScope.launch { ServiceLocator.tasks.refresh(force = false) }
        }

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
            // HIGH: the whole point is to be seen right after the app closed for an update.
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_UPDATES,
                    strings.getString(R.string.channel_updates),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = strings.getString(R.string.channel_updates_desc) },
            )
        }
    }
}
