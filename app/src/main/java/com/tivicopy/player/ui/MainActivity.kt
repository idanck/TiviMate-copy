package com.tivicopy.player.ui

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.tivicopy.player.App
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.news.NewsDigests
import com.tivicopy.player.data.news.NewsRepository
import com.tivicopy.player.data.prefs.AppLocale
import com.tivicopy.player.data.prefs.StartBehaviour
import com.tivicopy.player.tv.WatchNextPlan
import com.tivicopy.player.player.PlayerMediaSession
import com.tivicopy.player.player.RadioBackgroundService
import com.tivicopy.player.ui.account.SignInScreen
import com.tivicopy.player.ui.common.saverPlaying
import com.tivicopy.player.ui.common.ChannelScreensaver
import com.tivicopy.player.ui.common.NoticeTone
import com.tivicopy.player.ui.common.NoticeVisuals
import com.tivicopy.player.ui.common.PinRequestDialog
import com.tivicopy.player.ui.common.SaverKind
import com.tivicopy.player.ui.common.SaverPlaying
import com.tivicopy.player.ui.common.SceneClock
import com.tivicopy.player.ui.common.Screensaver
import com.tivicopy.player.ui.common.StudioNoticeHost
import com.tivicopy.player.ui.common.describe
import com.tivicopy.player.ui.common.IdleScene
import com.tivicopy.player.ui.common.idleScene
import com.tivicopy.player.ui.common.saverDueAt
import com.tivicopy.player.ui.common.screensaverFor
import com.tivicopy.player.ui.mobile.MobileRoot
import com.tivicopy.player.ui.mobile.PlayerScreen
import com.tivicopy.player.ui.settings.SettingsSection
import com.tivicopy.player.ui.theme.TiviCopyTheme
import com.tivicopy.player.ui.tv.TvOverlay
import com.tivicopy.player.ui.tv.TvRoot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The single activity for both form factors.
 *
 * ADR-002 chose one adaptive binary over separate TV and mobile modules, so the split happens
 * here: [isTelevision] picks a shell, and everything below it is shared. That is what keeps a
 * channel hidden on the phone hidden on the TV, with no synchronisation code at all.
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private var shownOverlay by mutableStateOf(TvOverlay.NONE)

    /**
     * The TV shell's overlay. Every write is a move made from wherever the viewer was, so it also
     * forgets [openedFromVideo]; [openFromVideo] is the one route that sets it again.
     */
    private var overlay: TvOverlay
        get() = shownOverlay
        set(value) {
            shownOverlay = value
            openedFromVideo = null
        }

    /**
     * The browse surface on screen, if it was opened from the fullscreen video: the channel list
     * on the arrow that points at it, or the guide on GUIDE, yellow or the transport bar's button.
     * Back from it returns to the video instead of rising to home (ADR-036). Any other move —
     * a side-menu choice, OK on a channel, another surface — clears it, through [overlay]'s setter.
     */
    private var openedFromVideo by mutableStateOf<TvOverlay?>(null)

    /**
     * The channel/radio list Back returns to from bare fullscreen video (FR-SET-06). Fullscreen
     * playback is no longer the app's root screen — this is — so Back goes up to it first, and
     * only a further Back from here reaches the exit confirmation. Kept as whichever of
     * [TvOverlay.CHANNELS]/[TvOverlay.RADIO] was opened last, updated alongside `overlay` below.
     */
    private var lastBrowseOverlay by mutableStateOf(TvOverlay.HOME)
    private var settingsSection by mutableStateOf(SettingsSection.ROOT)
    private var numericEntry by mutableStateOf("")
    private var lastBackPress = 0L
    private var infoCardHideAt = 0L

    // Phone-shell navigation, hoisted like [overlay] because the activity itself needs it:
    // [onUserLeaveHint] gates PiP on the player screen being the thing on screen.
    private var playerOpen by mutableStateOf(false)
    private var inPip by mutableStateOf(false)

    /**
     * Which of the phone's modes of several panels is the screen, Sport (ADR-041) or News
     * (ADR-042); null for neither. The television's are [overlay].
     */
    private var panelsOpen by mutableStateOf<SportSession.Mode?>(null)

    /** Set when [onStop] paused a stream nothing was showing, so [onStart] can resume it. */
    private var pausedInBackground = false

    /**
     * Set on entering the floating window, cleared by [onResume]. A window that leaves and is
     * followed by a stop rather than a resume was closed, not expanded; see [onStop].
     */
    private var leftForPip = false

    /**
     * Read per press rather than cached: the layout direction follows the interface language, and
     * a change to that recreates the activity anyway, but the system locale can also change under
     * a running process.
     */
    private val isRtl: Boolean
        get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /**
     * Which shell this device gets (ADR-002).
     *
     * Read per call rather than cached: `uiMode` is in the manifest's `configChanges`, so a device
     * that switches into or out of television mode is *not* recreated, and a `by lazy` answer
     * taken at the first composition would outlive the truth.
     *
     * [android.app.UiModeManager] is asked first because it is the canonical question. The feature
     * flags alone missed television boxes and sticks that report a touchscreen they do not have —
     * those devices were handed the phone shell, which draws a top app bar, a bottom navigation
     * bar and inset padding, and that is one of the three reasons the interface was reported as
     * not filling the screen.
     */
    private val isTelevision: Boolean
        get() {
            val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager)
                ?.currentModeType
            if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
            val pm = packageManager
            return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                pm.hasSystemFeature("android.software.leanback_only") ||
                !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        }

    /**
     * Whether the sign-in screen stands in front of the app (ADR-059): the owner's remote
     * `requireSignIn` switch is on and nobody is signed in. Read straight from the repository so
     * the key handlers see the same answer the composition does.
     */
    private val signInFirst: Boolean
        get() = ServiceLocator.account.gated.value

    /**
     * Lay the window out edge to edge, and on a television hide the system bars outright.
     *
     * Nothing in this app ever asked for this before, so `decorFitsSystemWindows` stayed at its
     * default and the whole Compose tree was inset by whatever bars the device reported — which
     * `themes.xml` then painted black. That is the letterbox the user reported. `targetSdk` is 35,
     * where Android 15 forces edge to edge anyway, so without this the same build behaved
     * differently either side of that version: content under the bars on 15, a shrunken window
     * below it. One call settles both.
     *
     * The bars are hidden only on the television. The phone needs them, and [MobileRoot]'s own
     * `Scaffold` keeps its default insets so its chrome stays clear of them.
     */
    private fun goEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (!isTelevision) return
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * FR-UI-23: the interface language is applied here, before any resource is resolved.
     * [android.app.Activity.recreate] runs this again, which is how a change in Settings takes
     * effect without restarting the process.
     *
     * Both halves are needed below Android 13. The wrap puts the locale on the context this
     * activity resolves resources from; registering it as the activity's override configuration
     * is what keeps it there. `ResourcesManager` rebases every `Resources` held under an
     * activity token onto that override whenever the configuration changes, and a locale that
     * was only ever on the base context is not in it — so it is dropped, and the interface
     * quietly returns to the system language. On Android 13 and later both are no-ops: the
     * platform has the choice and has already applied it to the whole process.
     */
    override fun attachBaseContext(newBase: Context) {
        val platformApplied = AppLocale.platformApplied(newBase)
        super.attachBaseContext(AppLocale.wrap(newBase, platformApplied))
        AppLocale.activityOverride(newBase, platformApplied)
            ?.let { runCatching { applyOverrideConfiguration(it) } }
    }

    /**
     * A launcher card followed while the app is already running (FR-TVI-04).
     *
     * `setIntent` is load-bearing: this activity is `singleTask`, so without it `getIntent()`
     * keeps answering with the intent that first started the process, and a second card would
     * replay the first one's channel.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Plays whatever channel a `shidur://channel/<id>` link names.
     *
     * Anything else — a launch from the icon, a share, a malformed link — leaves this a no-op,
     * so the start behaviour below decides what is on screen as usual.
     */
    private fun handleDeepLink(intent: Intent?) {
        val channelId = WatchNextPlan.channelIdFromDeepLink(intent?.dataString) ?: return
        vm.playChannelId(channelId)
        // Arriving from the launcher means asking for the channel, not for a menu over it.
        deepLinkConsumed = true
        overlay = TvOverlay.NONE
        lastBrowseOverlay = TvOverlay.HOME
        if (!isTelevision) {
            panelsOpen = null
            playerOpen = true
        }
    }

    /**
     * Whether a launcher card decided what is on screen.
     *
     * `AppViewModel.playChannelId` already stops [AppViewModel.resumeStartChannel] tuning a
     * different channel over the top, but the start behaviour also opens a *surface* — and a
     * home screen drawn over the channel the viewer just picked from the launcher is the same
     * bug wearing a different hat.
     */
    private var deepLinkConsumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goEdgeToEdge()
        ServiceLocator.init(this)
        // FR-UI-23. The picker has to show what the app is actually speaking, and on Android 13
        // and later the language can also be changed from the device's own per-app screen.
        AppLocale.reconcile(this, ServiceLocator.settings)
        // Channel names are stored by the platform when the channel is created, so they only
        // follow a language change if they are re-declared. Doing it here covers both routes to
        // one: the recreate below Android 13, and the platform's own above it.
        App.createNotificationChannels(this)
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        registerPipActionReceiver()

        // A recreation must come back where it was. Locale is not in the manifest's
        // `configChanges`, so a device-language change recreates the activity — and doing
        // that inside a PiP window without these left the browse shell squeezed into it,
        // with the video track disabled underneath.
        playerOpen = savedInstanceState?.getBoolean(KEY_PLAYER_OPEN) ?: false
        panelsOpen = savedInstanceState?.getString(KEY_PANELS_OPEN)
            ?.let { name -> SportSession.Mode.entries.firstOrNull { it.name == name } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) inPip = isInPictureInPictureMode
        if (inPip) {
            playerOpen = true
            leftForPip = true
        }

        // A launcher card can be what started the process, so the launch intent gets the same
        // handling a warm one gets in [onNewIntent] — after the state restored above, which it
        // is entitled to override, and only on a real launch. A recreation (a language change)
        // runs onCreate again holding the same intent, and replaying it there would re-tune the
        // channel out from under whatever the viewer had moved on to.
        if (savedInstanceState == null) handleDeepLink(intent)

        setContent {
            val prefs by vm.settingsState.collectAsState()
            val state by vm.state.collectAsState()
            val playback by vm.playbackStatus.collectAsState()
            val snackbar = remember { SnackbarHostState() }

            // The floating window's own play/pause has to track reality: the action's icon is
            // baked into the params, so a state change while in the window re-submits them.
            // Width and height are keys too — a failover or track switch reshapes the window.
            LaunchedEffect(inPip, playback.playWhenReady, playback.width, playback.height) {
                if (inPip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { setPictureInPictureParams(pipParams(playback.playWhenReady)) }
                }
            }

            // The screen stays awake for watching, not for reading: browsing is full screen
            // now with no video on it, and pinning the display through a long look at the
            // guide is battery spent on nothing.
            LaunchedEffect(playerOpen, inPip, panelsOpen) {
                if (isTelevision || playerOpen || inPip || panelsOpen != null) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            val context = LocalContext.current

            // FR-SET-12 / NFR-REL-06: every message reaches the user in plain language.
            LaunchedEffect(state.message?.id) {
                val message = state.message ?: return@LaunchedEffect
                val text = message.text ?: message.error?.describe(context)
                if (!text.isNullOrBlank()) {
                    snackbar.showSnackbar(
                        NoticeVisuals(text, if (message.error != null) NoticeTone.ERROR else NoticeTone.DEFAULT),
                    )
                }
                vm.consumeMessage()
            }

            // ADR-059: nothing plays behind the sign-in screen, and the start behaviour waits for
            // it — a channel resumed under it would be heard and not seen. Applied once a launch,
            // when the screen first lets the viewer in.
            val gated by ServiceLocator.account.gated.collectAsState()
            val showSignIn = gated
            var startApplied by remember { mutableStateOf(false) }
            LaunchedEffect(showSignIn) {
                if (showSignIn) {
                    standDownForSignIn()
                    return@LaunchedEffect
                }
                if (startApplied) return@LaunchedEffect
                startApplied = true
                // Overlays belong to the TV shell; the phone has its own navigation. Setting
                // one here anyway left an invisible overlay open on the phone, which then ate
                // the first Back press without changing anything on screen.
                if (isTelevision && !deepLinkConsumed) {
                    when (prefs.startBehaviour) {
                        StartBehaviour.HOME -> overlay = TvOverlay.HOME
                        StartBehaviour.CHANNEL_LIST -> overlay = TvOverlay.CHANNELS
                        StartBehaviour.GUIDE -> overlay = TvOverlay.GUIDE
                        else -> Unit
                    }
                }
                // Not into the phone's Sport mode, restored after the process was let go: the
                // channel would play on the full-screen player over the panels, on a connection
                // of its own, with the player screen open, unseen, underneath them (ADR-041).
                val resumed = if (!isTelevision && panelsOpen != null) false else vm.resumeStartChannel()
                // The phone browses full screen, so a resumed channel opens the player screen —
                // otherwise it would be audible behind the channel list with no video anywhere.
                if (!isTelevision && resumed) playerOpen = true
            }

            LaunchedEffect(Unit) {
                // FR-UPD-02: a look on launch, rate-limited to once a day inside the manager.
                // Without it a device that is opened and closed daily would rely entirely on a
                // background worker that doze can defer for hours. Also behind the sign-in screen
                // (ADR-059): an update is how a device stuck there gets a fix.
                ServiceLocator.appScope.launch { ServiceLocator.updates.check(force = false) }
            }

            // FR-UI-31, ADR-024: the burn-in guard. Everything but a picture being watched needs
            // protecting (ADR-040), and a live channel left on full screen on the television has a
            // screensaver of its own after a longer wait (ADR-049); the countdown below decides when.
            val sportPlaying by vm.sport.anyPlaying.collectAsState()
            val inSport = if (isTelevision) overlay.isPanels else panelsOpen != null
            val scene = if (inSport) {
                // Sport mode's panels are pictures being watched, though the full-screen player
                // stands silent underneath them (ADR-041).
                if (sportPlaying) IdleScene.MOVING else IdleScene.STILL
            } else idleScene(
                playing = playback.playing,
                hasPicture = playback.width > 0 || playback.height > 0,
                failed = playback.error != null,
                // The one question the two shells answer differently: on the television a guide
                // or a menu may be covering the picture or docking it into a corner, and on the
                // phone there is no picture on screen at all unless the player screen is open.
                videoFillsPanel = if (isTelevision) overlay.videoFillsPanel else playerOpen,
                // ADR-049: a live channel, on the television. A film, a catch-up programme or a
                // recording runs to an end somebody is waiting for, and the phone is held.
                liveChannel = isTelevision && state.nowPlaying is NowPlayingInfo.Live,
            )
            val saver = screensaverFor(prefs.screensaverEnabled, prefs.channelSaverEnabled, scene, inPip)
            // When the screen became the kind of screen it is — for the clock, the moment it
            // turned still (ADR-040, decision 3). Stamped inside the effect below rather than
            // keyed on: a change of kind restarts the countdown through `saver`, and the stamp
            // itself never restarts anything.
            val sceneClock = remember { SceneClock(SystemClock.elapsedRealtime()) }
            // One timer, armed and slept through, rather than a clock ticking every second: a
            // key press moves `lastInteractionAt`, which restarts this effect, so an idle
            // television costs one suspended coroutine and nothing else. Each screensaver has its
            // own wait and counts it from its own moment (`saverDueAt`): the clock from the later
            // of the last key press and the screen turning still, the channel's from the last key
            // press alone, so a stream that rebuffers does not start the hour again.
            LaunchedEffect(saver, lastInteractionAt, prefs.screensaverIdleMinutes, prefs.channelSaverMinutes) {
                val now = SystemClock.elapsedRealtime()
                val since = sceneClock.stamp(saver, now)
                if (saver == null) {
                    screensaverOn = false
                    return@LaunchedEffect
                }
                // A picture back under the clock's screensaver — a stream that recovered on its
                // own, with nobody there to press anything — goes to the channel's at once: black
                // over a channel that plays again is the worse of the two.
                if (screensaverOn && saver == SaverKind.CHANNEL) {
                    saverShown = SaverKind.CHANNEL
                    return@LaunchedEffect
                }
                val due = saverDueAt(
                    kind = saver,
                    lastInteractionAt = lastInteractionAt,
                    sceneSince = since,
                    idleMinutes = prefs.screensaverIdleMinutes,
                    channelIdleMinutes = prefs.channelSaverMinutes,
                )
                val wait = due - now
                if (wait > 0) delay(wait)
                // A screensaver already up stays up through a change of kind until the new one's
                // own wait is over: a channel that stalls for a second under its screensaver is
                // not blacked out for it, and one that fails for good is, after the clock's wait.
                saverShown = saver
                screensaverOn = true
            }
            // FR-UI-33: the headlines are fetched as the screensaver arms, and again on the
            // repository's own ten-minute limit for as long as it stays up — which, on a
            // television left on a menu overnight, is all night.
            LaunchedEffect(screensaverOn, prefs.screensaverFlashes) {
                if (!screensaverOn || !prefs.screensaverFlashes) return@LaunchedEffect
                while (true) {
                    ServiceLocator.news.refresh(force = false)
                    delay(NewsRepository.CHECK_INTERVAL_MS)
                }
            }
            // FR-UI-32: the task list rides in the screensaver while a station is behind it
            // (ADR-040). Fetched as it is about to show rather than on a schedule of its own; the
            // repository rate-limits it to one request every half hour.
            val behindSaver = saverPlaying(state.nowPlaying, state.now?.title, playback)
            val saverTasks = screensaverOn && prefs.screensaverTasks && behindSaver is SaverPlaying.Radio
            LaunchedEffect(saverTasks) {
                if (saverTasks) ServiceLocator.tasks.refresh(force = false)
            }
            val taskFeed by ServiceLocator.tasks.state.collectAsState()

            TiviCopyTheme(
                mode = prefs.theme,
                fontScale = prefs.fontScale,
                television = isTelevision,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // The screensaver is a sibling of the shell rather than something inside
                    // it: it covers both shells, the snackbar and every overlay, and neither
                    // shell should have to know it exists (FR-UI-31).
                    Box(modifier = Modifier.fillMaxSize()) {
                        Scaffold(
                            snackbarHost = { StudioNoticeHost(snackbar) },
                            // This Scaffold exists only to host the snackbar; its content padding was
                            // already discarded below. Saying so explicitly keeps it from insetting
                            // anything now that the window is edge to edge — each shell handles its
                            // own insets, and MobileRoot's Scaffold is the one that must keep them.
                            contentWindowInsets = WindowInsets(0),
                        ) { _ ->
                            if (isTelevision) {
                                // Android TV boxes do PiP too, and the window must be bare video:
                                // an open overlay squeezed into it is unreadable chrome.
                                if (inPip) {
                                    PlayerScreen(
                                        vm = vm,
                                        onClose = {},
                                        onOpenGuide = {},
                                        onBrightness = {},
                                        onVolume = {},
                                        twoPane = false,
                                        inPip = true,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else if (showSignIn) {
                                    SignInScreen(modifier = Modifier.fillMaxSize())
                                } else {
                                    LaunchedEffect(overlay) {
                                        if (overlay == TvOverlay.HOME ||
                                            overlay == TvOverlay.CHANNELS ||
                                            overlay == TvOverlay.RADIO
                                        ) {
                                            lastBrowseOverlay = overlay
                                        }
                                    }
                                    TvRoot(
                                        vm = vm,
                                        overlay = if (state.playlists.isEmpty() && !state.loading) TvOverlay.ADD_SOURCE else overlay,
                                        settingsSection = settingsSection,
                                        onOverlayChange = { overlay = it },
                                        onOpenFromVideo = ::openFromVideo,
                                        openedFromVideo = openedFromVideo,
                                        onSettingsSection = { settingsSection = it },
                                        numericEntry = numericEntry,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            } else if (showSignIn) {
                                SignInScreen(modifier = Modifier.fillMaxSize())
                            } else {
                                MobileRoot(
                                    vm = vm,
                                    settingsSection = settingsSection,
                                    onSettingsSection = { settingsSection = it },
                                    playerOpen = playerOpen,
                                    onPlayerOpenChange = { playerOpen = it },
                                    panelsOpen = panelsOpen,
                                    onPanelsOpenChange = { panelsOpen = it },
                                    inPip = inPip,
                                    numericEntry = numericEntry,
                                    onBrightness = ::adjustBrightness,
                                    onVolume = ::adjustVolume,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        // FR-PRF-04: the PIN prompt for a locked channel, above whichever shell is
                        // up — every route onto a channel ends in the one gate in `vm.play`.
                        if (!inPip) PinRequestDialog(vm)

                        if (screensaverOn) {
                            val newsDigests by ServiceLocator.news.state.collectAsState()
                            val flashes = if (prefs.screensaverFlashes) {
                                remember(prefs.newsSources, newsDigests) {
                                    NewsDigests.merge(prefs.newsSources, newsDigests, System.currentTimeMillis())
                                }
                            } else {
                                emptyList()
                            }
                            if (saverShown == SaverKind.CHANNEL) {
                                // ADR-049: the channel keeps playing under it, dimmed, so it is
                                // drawn over the shell like the clock's, and is as transparent to
                                // the picture as the shell's own bars.
                                ChannelScreensaver(
                                    channel = (state.nowPlaying as? NowPlayingInfo.Live)?.channel,
                                    programme = state.now,
                                    headlines = flashes.isNotEmpty(),
                                    opacity = prefs.overlayOpacity,
                                    onProgrammeEnded = { vm.refreshNowNext() },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Screensaver(
                                    playing = behindSaver,
                                    flashes = flashes,
                                    flashesUpdatedUtc = newsDigests.values.maxOfOrNull { it.fetchedUtc } ?: 0L,
                                    colours = prefs.screensaverColours,
                                    speed = prefs.flashSpeed,
                                    modifier = Modifier.fillMaxSize(),
                                    // Only the radio gets the tasks (FR-UI-32). A paused film is a
                                    // screen somebody is coming back to in a minute, and not the place
                                    // for somebody's to-do list.
                                    tasks = if (saverTasks) taskFeed.items else emptyList(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The system puts its bars back whenever the window loses focus — a dialog, a launcher
     * overlay, the way out of picture-in-picture. Hiding them once in [onCreate] would therefore
     * hold only until the first such interruption, after which the television would quietly go
     * back to being letterboxed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isTelevision) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // --------------------------------------------------------- the burn-in guard

    /**
     * When the remote was last touched, on the monotonic clock (FR-UI-31, ADR-024).
     *
     * `elapsedRealtime` rather than wall time: the countdown must not be cancelled — or fired
     * early — by a clock correction, which on a television box that has just reached the network
     * is a routine event a minute or two after boot.
     *
     * A Compose state because the countdown is armed from the composition, which is where the
     * player status and the settings that decide it already are.
     */
    private var lastInteractionAt by mutableStateOf(SystemClock.elapsedRealtime())

    /** Whether the screensaver is on screen. Owned here because key dispatch has to know. */
    private var screensaverOn by mutableStateOf(false)

    /**
     * Which screensaver [screensaverOn] puts up (ADR-049). Set as it comes up rather than read
     * from the scene each frame, so the one on screen is kept through a change of scene until the
     * other's own wait is over.
     */
    private var saverShown by mutableStateOf(SaverKind.CLOCK)

    /**
     * Keys whose remaining events belong to the press that dismissed the screensaver.
     *
     * Swallowing the down half alone is not enough: OK is resolved on key-*up* ([onKeyUp]), so
     * pressing OK to wake the screen would also have opened the transport bar behind it, and a
     * press of Back would have walked up an overlay the viewer never saw. The whole gesture —
     * repeats and the eventual up — belongs to the dismissal.
     */
    private val wakingKeys = mutableSetOf<Int>()

    /**
     * Restarts the idle countdown, and takes the screensaver off if it was on.
     *
     * The stamp is coalesced to [INTERACTION_RESOLUTION_MS], and that is not a detail: a held
     * D-pad key repeats about thirty times a second, [lastInteractionAt] is read from the
     * composition, and every write would therefore re-run the root composable and cancel and
     * relaunch the countdown coroutine. A second of slack on a ten-minute timer costs nothing;
     * thirty of those a second, while a viewer scrolls the guide, costs frames.
     *
     * Waking is never coalesced away, because by then the countdown has been asleep for minutes
     * and the delta is far past the threshold — and `screensaverOn` is cleared regardless.
     */
    private fun noteInteraction() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInteractionAt >= INTERACTION_RESOLUTION_MS) lastInteractionAt = now
        if (screensaverOn) screensaverOn = false
    }

    /**
     * Every key the app sees, before anything else sees it.
     *
     * Volume and mute are deliberately *not* interaction. Turning a radio station down from the
     * sofa is not looking at the screen, and lighting a station backdrop back up for another ten
     * minutes is the opposite of what the person who pressed it asked for. They pass straight
     * through, waking nothing: the screensaver is not a modal, and it must never eat the volume.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in VOLUME_KEYS) return super.dispatchKeyEvent(event)

        if (event.keyCode in wakingKeys) {
            if (event.action == KeyEvent.ACTION_UP) wakingKeys.remove(event.keyCode)
            return true
        }
        if (screensaverOn) {
            noteInteraction()
            if (event.action == KeyEvent.ACTION_DOWN) wakingKeys.add(event.keyCode)
            return true
        }
        noteInteraction()
        return super.dispatchKeyEvent(event)
    }

    /**
     * The phone's half of the same clock.
     *
     * Only the press is counted, not the drag: a scroll is one interaction, not forty. The tap
     * that dismisses the screensaver is swallowed by the screensaver itself, which is a full
     * screen surface with its own tap detector — there is nothing to consume here.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) noteInteraction()
        return super.dispatchTouchEvent(event)
    }

    // ------------------------------------------------------------- remote keys

    /**
     * Default D-pad bindings from `docs/03` section 3.4: Up/Down zap, Left opens the channel
     * list, Right opens the filmstrip, OK opens the transport bar, a long OK opens the playback
     * menu, Back goes up one level and a second Back exits.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // The sign-in screen's keys are its own buttons', in Compose; nothing zaps under it.
        if (signInFirst) return super.onKeyDown(keyCode, event)
        if (!isTelevision) return onPhoneKeyDown(keyCode) || super.onKeyDown(keyCode, event)

        // Sport mode has its own bindings (ADR-041); none of the full-screen player's below apply.
        if (overlay.isPanels) return onSportKeyDown(keyCode) || super.onKeyDown(keyCode, event)

        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            numericEntry = (numericEntry + (keyCode - KeyEvent.KEYCODE_0)).take(4)
            scheduleNumericCommit()
            return true
        }

        // OK is tracked wherever a long press means something, which is now everywhere the video
        // is still the screen — not only over the bare screen. Registering it inside the branch
        // below would have meant that once the transport bar was up, holding OK stopped opening
        // the menu: `onKeyLongPress` only ever fires for a key the activity asked to track.
        val isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        if (isOk && overlay.isPassthrough) {
            event.startTracking()
            return true
        }

        // The dedicated channel keys and the transport keys act on the stream, not on whatever is
        // drawn over it, so they stay live for as long as the video is still the screen. Only the
        // *arrows* are ambiguous — Up over the filmstrip means "move focus", not "next channel" —
        // and those are handled below, after the overlay has had its say.
        if (overlay.isPassthrough) {
            when (keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_TV -> {
                    vm.toggleLastChannel()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    ServiceLocator.player.togglePlayPause()
                    return true
                }
            }
        }

        // While an overlay owns the D-pad, the arrows belong to it. The filmstrip, the transport
        // bar and the playback menu draw focusable controls over playing video, so they take the
        // arrows — while the channel and transport keys above keep working underneath, as does
        // numeric channel entry.
        if (!overlay.leavesDpadToShell) {
            return super.onKeyDown(keyCode, event)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); true }
            // FR-UI-21: in a right-to-left layout the two horizontal bindings swap, so the key
            // pointing at the channel column still opens the channel column. Without this a
            // Hebrew interface has a mirrored screen and an unmirrored remote.
            // The channel list opened here, and the guide below, are opened *from* the video, so
            // Back from either returns to it rather than rising to home (ADR-036).
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isRtl) overlay = TvOverlay.MINI_GUIDE else openFromVideo(TvOverlay.CHANNELS)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isRtl) openFromVideo(TvOverlay.CHANNELS) else overlay = TvOverlay.MINI_GUIDE
                true
            }
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_YELLOW -> { openFromVideo(TvOverlay.GUIDE); true }
            KeyEvent.KEYCODE_INFO -> { showInfoCard(); true }
            KeyEvent.KEYCODE_SEARCH -> { overlay = TvOverlay.SEARCH; true }
            KeyEvent.KEYCODE_MENU -> { overlay = TvOverlay.SETTINGS; true }
            KeyEvent.KEYCODE_PROG_RED -> { overlay = TvOverlay.RECORDINGS; true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                ServiceLocator.player.togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_TV -> { vm.toggleLastChannel(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Sport mode's remote (ADR-041). The arrows, OK and Menu are the panels' own, in Compose; what
     * reaches here is what acts on the panels as a set or on the one with the remote.
     *
     * Play/pause is taken even though Sport mode has no transport bar: left unclaimed, the system
     * hands it to the media session, which would start the full-screen player again underneath.
     * The digits are swallowed for the same reason — they tune the full-screen player.
     */
    private fun onSportKeyDown(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> vm.sport.step(1)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> vm.sport.step(-1)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            -> vm.sport.togglePause()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> Unit
            else -> return false
        }
        return true
    }

    /**
     * A phone with a Bluetooth remote or a keyboard: the channel keys, the digits and Last act on
     * the full-screen player the way they do on the television (QA-09). Only there — over the
     * lists they would change channel behind the user's back. A focused text field has already
     * taken its own keys before the activity is asked.
     */
    private fun onPhoneKeyDown(keyCode: Int): Boolean {
        // Sport mode's keys are the television's (ADR-041), and they act on its panels.
        if (panelsOpen != null && !inPip) return onSportKeyDown(keyCode)
        if (!playerOpen || inPip) return false
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                numericEntry = (numericEntry + (keyCode - KeyEvent.KEYCODE_0)).take(4)
                scheduleNumericCommit()
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> vm.zap(1)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> vm.zap(-1)
            KeyEvent.KEYCODE_LAST_CHANNEL -> vm.toggleLastChannel()
            else -> return false
        }
        return true
    }

    /**
     * The short OK press is resolved on key-up, because [onKeyLongPress] only fires for a key
     * the activity asked to track and `isCanceled` is how the platform reports that the long
     * press already consumed it.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        if (isTelevision && isOk && overlay.leavesDpadToShell && !signInFirst) {
            // A short OK opens the transport bar. It used to open the info card, which said what
            // was playing and offered nothing to do about it: the television had no play, no
            // pause, no aspect control and no way to pick an audio track, while the phone had all
            // four. The bar carries the same identity the card did, plus the controls.
            if (!event.isCanceled) overlay = TvOverlay.TRANSPORT
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isTelevision && !signInFirst &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            // Was Settings ▸ Playback, which is a preferences screen rather than a playback menu:
            // it left the programme to change a default. This is the menu docs/03 section 3.6
            // describes, acting on the stream in front of you.
            overlay = TvOverlay.PLAYBACK_MENU
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    /**
     * Silences everything the sign-in screen stands in front of (ADR-059): the player, Sport or
     * News mode's panels, the phone's player screen, and the television's overlays, so the viewer
     * comes in at home.
     */
    private fun standDownForSignIn() {
        if (panelsOpen != null || overlay.isPanels) vm.exitSport(restartPaused = false)
        panelsOpen = null
        playerOpen = false
        if (isTelevision) overlay = TvOverlay.HOME
        ServiceLocator.player.stop()
    }

    /** Back goes up one level; a second press within a short window exits (FR-SET-06). */
    private fun handleBack() {
        // Every rung of the overlay ladder below reads [overlay], which only the TV shell ever
        // sets. The phone navigates with MobileRoot's own BackHandler and reaches this callback
        // only once it has nowhere left to go, so climbing the ladder there moved an invisible
        // overlay and ate the press without changing anything on screen — the same defect the
        // start-up effect above already had to be corrected for.
        // Behind the sign-in screen there is no ladder to climb (ADR-059): Back leaves.
        if (isTelevision && !signInFirst && stepUpOneOverlay()) return
        confirmOrExit()
    }

    /**
     * One level up the TV shell's overlay ladder. Returns false once it is already standing on
     * the top rung, which is what makes Back from there mean "leave the app".
     */
    private fun stepUpOneOverlay(): Boolean {
        when {
            // Sport mode (ADR-041): the picker closes, a panel filling the screen goes back into
            // the grid, and the grid leaves for the full-screen player on the channel with the
            // remote.
            overlay.isPanels -> stepUpInSport()

            overlay == TvOverlay.SETTINGS && settingsSection != SettingsSection.ROOT ->
                settingsSection = SettingsSection.ROOT

            // "Up one level" now has a level to go up to. The playback menu is opened *from* the
            // transport bar as often as from a long press, and collapsing both to bare video in a
            // single press — which is what a flat `overlay = NONE` did — threw away the bar the
            // user was working in and made Back feel like a cancel.
            overlay == TvOverlay.PLAYBACK_MENU -> overlay = TvOverlay.TRANSPORT

            // Bare fullscreen video is not the root: it goes back to the surface it was opened
            // from — the home screen, or whichever list the viewer was in.
            overlay == TvOverlay.NONE -> overlay = lastBrowseOverlay

            // A surface drawn *over* playing video dismisses back to the video, which is what
            // the viewer was looking at behind it — the transport bar, the filmstrip and the
            // zap info card all live there. Only the browse surfaces below rise to Home.
            // (NONE is passthrough too, and is already handled by the rung above.)
            overlay.isPassthrough -> overlay = TvOverlay.NONE

            // A browse surface opened *from* the video — the channel list on its arrow, the guide
            // on GUIDE, yellow or the transport bar's button — was a look away from the picture,
            // and Back puts the picture back (ADR-036). [lastBrowseOverlay] is left alone: the
            // list is still the surface the next Back from the video returns to, and the guide
            // never is one, so that Back goes wherever the viewer was before.
            overlay == openedFromVideo -> overlay = TvOverlay.NONE

            // Every browse surface steps up to the home screen, which is now the root
            // (ADR-016). This replaced a rung that compared against [lastBrowseOverlay] and,
            // with Home as its default, would have dropped the channel list to bare video
            // rather than raising it.
            //
            // [lastBrowseOverlay] is moved here rather than left to the effect that normally
            // tracks it: that effect lands a composition later, and a second Back arriving
            // before it did would read the old value.
            overlay != TvOverlay.HOME -> {
                overlay = TvOverlay.HOME
                lastBrowseOverlay = TvOverlay.HOME
            }

            else -> return false
        }
        return true
    }

    /** Back in Sport mode; see the rung in [stepUpOneOverlay]. */
    private fun stepUpInSport() {
        val sport = vm.sport
        when {
            sport.picking.value != null -> sport.closePicker()
            sport.menu.value != null -> sport.closeMenu()
            sport.expanded.value -> sport.setExpanded(false)
            else -> {
                vm.exitSport()
                overlay = TvOverlay.NONE
            }
        }
    }

    /**
     * Opens [target] from the fullscreen video, so that Back from it returns to the video
     * (ADR-036). The mark is set after the move because the move itself clears it.
     */
    private fun openFromVideo(target: TvOverlay) {
        overlay = target
        openedFromVideo = target
    }

    /** The bottom rung on both shells: confirm, then leave (FR-SET-06). */
    private fun confirmOrExit() {
        if (!ServiceLocator.settings.state.value.confirmExit) {
            finish()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < EXIT_CONFIRM_WINDOW_MS) {
            finish()
        } else {
            lastBackPress = now
            vm.post(UiMessage(text = getString(R.string.exit_confirm)))
        }
    }

    private fun zap(delta: Int) {
        vm.zap(delta)
        showInfoCard()
    }

    private fun showInfoCard() {
        overlay = TvOverlay.INFO
        vm.refreshNowNext()
        val hideAfter = ServiceLocator.settings.state.value.overlayAutoHideSeconds * 1000L
        infoCardHideAt = System.currentTimeMillis() + hideAfter
        window.decorView.postDelayed(
            {
                if (overlay == TvOverlay.INFO && System.currentTimeMillis() >= infoCardHideAt) {
                    overlay = TvOverlay.NONE
                }
            },
            hideAfter,
        )
    }

    /** FR-PLB-19: a short timeout turns the digits typed so far into a channel change. */
    private fun scheduleNumericCommit() {
        val timeout = ServiceLocator.settings.state.value.numericZapTimeoutMs.toLong()
        val snapshot = numericEntry
        window.decorView.postDelayed(
            {
                if (numericEntry == snapshot && snapshot.isNotEmpty()) {
                    snapshot.toIntOrNull()?.let(vm::playByNumber)
                    numericEntry = ""
                    // The phone's player already names the channel it is on.
                    if (isTelevision) showInfoCard()
                }
            },
            timeout,
        )
    }

    // ------------------------------------------------------ mobile gestures

    private fun adjustBrightness(delta: Float) {
        val attributes = window.attributes
        val current = if (attributes.screenBrightness < 0) 0.5f else attributes.screenBrightness
        attributes.screenBrightness = (current + delta).coerceIn(0.01f, 1f)
        window.attributes = attributes
    }

    private fun adjustVolume(delta: Float) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current + (delta * max)).toInt().coerceIn(0, max)
        if (target != current) audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    // ------------------------------------------------------------------- PiP

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Sport mode has no one picture for a floating window to keep showing (ADR-041). Its
        // panels go quiet now, rather than over the launcher, and [onStop] lets them go.
        if (vm.sport.active) {
            vm.sport.silence()
            return
        }
        // On the phone, browsing shows no video, so there is nothing for a PiP window to keep
        // showing — only the open player screen shrinks into one. The TV shell is always video.
        val prefs = ServiceLocator.settings.state.value
        val wantsPip = (isTelevision || playerOpen) && prefs.pipEnabled && prefs.pipOnHome && !signInFirst
        if (wantsPip && enterPip()) return
        // Home with no window to go to: silence it now. [onStop] pauses too, but a television
        // launcher animating its rows holds that off for seconds, and the channel played on
        // over the launcher meanwhile (emulator QA, QA-13: still sounding 4 s after Home) —
        // the same delay [onPause] already works around for Back. A radio station is left to
        // [onStop], which hands it to its background service instead of pausing it.
        if (!vm.isRadioPlaying) pauseInBackground()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // The phone shell renders bare video while in the window; on the way out the player
        // screen is still open, so the user lands back where they left.
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            leftForPip = true
            return
        }
        // Closed with the window's X (device report). The activity is stopped first, while it
        // still counts as in the window, so [onStop] left the stream playing for a window that
        // was on its way out — and nothing ever silenced it. Now that the window is known gone,
        // do what a stop with no window does.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            // [onStop] kept the session for the window; with the window gone, nothing shows the
            // stream any more.
            PlayerMediaSession.release(PlayerMediaSession.HOLDER_ACTIVITY)
            pauseInBackground()
            return
        }
        // Expanded back to full screen. Whether this lands before or after [onResume] differs
        // between Android versions, so both look; a window being closed instead never reaches
        // RESUMED, and must not start the sound again on its way out.
        val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (resumed) resumeIfFocusTookIt()
    }

    /** Whether the system took the activity into a window. */
    private fun enterPip(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        return runCatching { enterPictureInPictureMode(pipParams(vm.playbackStatus.value.playWhenReady)) }
            .getOrDefault(false)
    }

    /**
     * The floating window's controls (device report): the system supplies expand and close on
     * its own, so the one action the app must add is play/pause. The receiver toggles the
     * player; the state change then flows back through [setPictureInPictureParams] above,
     * which is what swaps the icon. [wantsPlay] is the intent flag, not `isPlaying` — a
     * rebuffering stream must show Pause, or the button pauses when the user meant "play".
     */
    private fun pipParams(wantsPlay: Boolean): PictureInPictureParams {
        val status = vm.playbackStatus.value
        // Android only accepts a window between 1:2.39 and 2.39:1 and *throws* outside it, so
        // an ultra-wide stream is clamped rather than silently costing the whole window.
        val raw = if (status.width > 0 && status.height > 0) {
            status.width.toFloat() / status.height
        } else {
            16f / 9f
        }
        val ratio = Rational((raw.coerceIn(0.42f, 2.38f) * 10_000).toInt(), 10_000)
        val label = getString(if (wantsPlay) R.string.action_pause else R.string.action_play)
        val toggle = RemoteAction(
            Icon.createWithResource(
                this,
                if (wantsPlay) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            ),
            label,
            label,
            PendingIntent.getBroadcast(
                this,
                PIP_REQUEST_PLAY_PAUSE,
                Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(listOf(toggle))
            .build()
    }

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PIP_PLAY_PAUSE) return
            ServiceLocator.player.togglePlayPause()
        }
    }

    private fun registerPipActionReceiver() {
        // The action never leaves this package (the PendingIntent is package-scoped), and the
        // receiver takes no data, so not-exported is both sufficient and required on 33+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val filter = IntentFilter(ACTION_PIP_PLAY_PAUSE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipActionReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { unregisterReceiver(pipActionReceiver) }
        }
    }

    override fun onStart() {
        super.onStart()
        // A headset's play/pause reaches this app's playback while it is in front of the viewer
        // (PlayerMediaSession); [onStop] lets go once nothing is showing it.
        PlayerMediaSession.hold(PlayerMediaSession.HOLDER_ACTIVITY, this, ServiceLocator.player)
        // Whatever [onStop] handed off to while backgrounded is no longer needed: the UI is
        // back and drives the player directly again.
        RadioBackgroundService.stop(this)
        // Sport mode's panels start their channels again, if Sport mode was the screen (ADR-041).
        vm.resumeSport()
        // Undo what [onStop] did for the background: the shells' own effects only re-run when
        // their keys change, and coming back from the launcher changes none of them.
        ServiceLocator.player.setVideoOutputWanted(isTelevision || playerOpen || inPip)
        if (pausedInBackground) {
            pausedInBackground = false
            // Not behind the sign-in screen (ADR-059), which stopped the stream on purpose.
            if (!signInFirst) ServiceLocator.player.setPlayWhenReady(true)
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the app is an interaction, whatever key or gesture got here: the idle
        // countdown starts again, and any wake gesture whose key-up went somewhere else — the
        // launcher, a dialog — is forgotten rather than left swallowing that key for good.
        wakingKeys.clear()
        noteInteraction()
        // "Install unknown apps" is granted on a screen of the platform's own, and nothing in
        // this process is told when it happens. Coming back is the moment to look again — which
        // is what lets a refused update pick itself back up instead of sending the user round
        // "check for updates" a second time (issue #29).
        ServiceLocator.updates.refreshInstallPermission()
        // A window recreated in place passes through here too, and is still a window.
        if (!inPip) leftForPip = false
        resumeIfFocusTookIt()
    }

    override fun onPause() {
        super.onPause()
        // Leaving the app for good silences it here rather than in [onStop]. A finishing
        // activity is only stopped once the screen it hands over to reports itself idle, and a
        // television launcher busy animating its rows holds that off for up to ten seconds — the
        // stream played on over the launcher for all of them. Only silenced, not released: the
        // release waits for the decoders, and the launcher waits for this method. [onStop] still
        // releases, as it always has.
        if (isFinishing) {
            // The position first, while the player still has it (FR-PLB-29); see [onStop].
            vm.saveVodProgress()
            ServiceLocator.player.silence()
            vm.sport.silence()
        }
    }

    override fun onStop() {
        super.onStop()
        // A correct PIN holds while the viewer who typed it is here; whoever picks the remote
        // up after the app has been away is asked again (FR-PRF-04).
        vm.lockParental()
        // Where an on-demand title got to, written before the player can be torn down. The
        // position has to be read here, on the thread that owns ExoPlayer, and while it still
        // has one (FR-PLB-29).
        vm.saveVodProgress()
        // Sport mode's panels let go of their decoders and connections whenever the activity is
        // out of sight; [onStart] picks the same channels up again (ADR-041).
        vm.sport.suspend()
        // Playback is released only when the activity really goes away; a recording keeps
        // running in its own foreground service either way.
        if (isFinishing) {
            RadioBackgroundService.stop(this)
            PlayerMediaSession.release(PlayerMediaSession.HOLDER_ACTIVITY)
            ServiceLocator.player.release()
            return
        }
        // Stopped with no picture-in-picture window (the switch is off, the device has no
        // PiP, or a launcher stopped the task instead of finishing it): nothing anywhere is
        // showing this stream.
        val pipNow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (pipNow) return
        // From here the activity shows nothing. A station handed to RadioBackgroundService is
        // held by the service instead; anything else is paused below, and a headset must not be
        // able to start it again behind the viewer's back.
        PlayerMediaSession.release(PlayerMediaSession.HOLDER_ACTIVITY)
        // A window already reported gone, with no resume since, was closed with its X: the
        // viewer asking for quiet, not for the radio to carry on in the background.
        if (vm.isRadioPlaying && !leftForPip) {
            // A radio station has no picture to lose — locking or turning off the screen is not
            // a reason to stop it. RadioBackgroundService keeps the same stream running (see its
            // own doc comment) instead of the pause below.
            //
            // Attempted, not assumed: from Android 12 a foreground service started as the app
            // leaves the foreground can be refused outright, and this is precisely that moment.
            // A refusal is not worth dying on a screen lock, so a throw falls through to the
            // pause below — which is what this app did before the service existed.
            if (runCatching { RadioBackgroundService.start(this) }.isSuccess) return
        }
        pauseInBackground()
    }

    /**
     * A live TV channel kept downloading and decoding video for no surface, and playing sound
     * from a backgrounded app with no notification and no way to stop it. Pause, drop the video
     * track, and let [onStart] undo both.
     */
    private fun pauseInBackground() {
        if (vm.playbackStatus.value.playWhenReady) {
            pausedInBackground = true
            ServiceLocator.player.setPlayWhenReady(false)
        }
        ServiceLocator.player.setVideoOutputWanted(false)
    }

    /**
     * Another app's sound paused the player for good, and this app is back in front of the
     * viewer: on the television the screen *is* the video, so it picks up again rather than
     * standing on a frozen frame — the same as [onStart] undoing its own background pause.
     *
     * Not on the phone, whose browse screens show no video: coming back to read the guide is not
     * a request to take the sound back from whatever is playing now, and the mini player's own
     * button is right there. Never from the floating window either — that is drawn over the very
     * app that took the sound.
     */
    private fun resumeIfFocusTookIt() {
        if (!isTelevision || signInFirst) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return
        ServiceLocator.player.resumeAfterAudioFocusLoss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PLAYER_OPEN, playerOpen)
        outState.putString(KEY_PANELS_OPEN, panelsOpen?.name)
    }

    private companion object {
        /**
         * Keys that work the sound rather than the interface. They are the one thing the
         * screensaver neither wakes for nor swallows.
         */
        /**
         * How finely the idle clock is kept. Coarse on purpose; see [noteInteraction].
         */
        const val INTERACTION_RESOLUTION_MS = 1_000L

        val VOLUME_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
        )

        const val EXIT_CONFIRM_WINDOW_MS = 3_000L
        const val ACTION_PIP_PLAY_PAUSE = "com.tivicopy.player.PIP_PLAY_PAUSE"
        const val PIP_REQUEST_PLAY_PAUSE = 1
        const val KEY_PLAYER_OPEN = "player_open"
        const val KEY_PANELS_OPEN = "panels_open"
    }
}
