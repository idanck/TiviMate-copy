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
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.data.prefs.AppLocale
import com.tivicopy.player.data.prefs.StartBehaviour
import com.tivicopy.player.ui.common.describe
import com.tivicopy.player.ui.mobile.MobileRoot
import com.tivicopy.player.ui.mobile.PlayerScreen
import com.tivicopy.player.ui.settings.SettingsSection
import com.tivicopy.player.ui.theme.TiviCopyTheme
import com.tivicopy.player.ui.tv.TvOverlay
import com.tivicopy.player.ui.tv.TvRoot

/**
 * The single activity for both form factors.
 *
 * ADR-002 chose one adaptive binary over separate TV and mobile modules, so the split happens
 * here: [isTelevision] picks a shell, and everything below it is shared. That is what keeps a
 * channel hidden on the phone hidden on the TV, with no synchronisation code at all.
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private var overlay by mutableStateOf(TvOverlay.NONE)
    private var settingsSection by mutableStateOf(SettingsSection.ROOT)
    private var numericEntry by mutableStateOf("")
    private var lastBackPress = 0L
    private var infoCardHideAt = 0L

    // Phone-shell navigation, hoisted like [overlay] because the activity itself needs it:
    // [onUserLeaveHint] gates PiP on the player screen being the thing on screen.
    private var playerOpen by mutableStateOf(false)
    private var inPip by mutableStateOf(false)

    /** Set when [onStop] paused a stream nothing was showing, so [onStart] can resume it. */
    private var pausedInBackground = false

    /**
     * Read per press rather than cached: the layout direction follows the interface language, and
     * a change to that recreates the activity anyway, but the system locale can also change under
     * a running process.
     */
    private val isRtl: Boolean
        get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private val isTelevision: Boolean by lazy {
        val pm = packageManager
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("android.software.leanback_only") ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /**
     * FR-UI-23: the interface language is applied here, before any resource is resolved.
     * [android.app.Activity.recreate] runs this again, which is how a change in Settings takes
     * effect without restarting the process.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.init(this)
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        registerPipActionReceiver()

        // A recreation must come back where it was. Locale is not in the manifest's
        // `configChanges`, so a device-language change recreates the activity — and doing
        // that inside a PiP window without these left the browse shell squeezed into it,
        // with the video track disabled underneath.
        playerOpen = savedInstanceState?.getBoolean(KEY_PLAYER_OPEN) ?: false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) inPip = isInPictureInPictureMode
        if (inPip) playerOpen = true

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
            LaunchedEffect(playerOpen, inPip) {
                if (isTelevision || playerOpen || inPip) {
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
                if (!text.isNullOrBlank()) snackbar.showSnackbar(text)
                vm.consumeMessage()
            }

            LaunchedEffect(Unit) {
                // Overlays belong to the TV shell; the phone has its own navigation. Setting
                // one here anyway left an invisible overlay open on the phone, which then ate
                // the first Back press without changing anything on screen.
                if (isTelevision) {
                    when (prefs.startBehaviour) {
                        StartBehaviour.CHANNEL_LIST -> overlay = TvOverlay.CHANNELS
                        StartBehaviour.GUIDE -> overlay = TvOverlay.GUIDE
                        else -> Unit
                    }
                }
                val resumed = vm.resumeStartChannel()
                // The phone browses full screen, so a resumed channel opens the player screen —
                // otherwise it would be audible behind the channel list with no video anywhere.
                if (!isTelevision && resumed) playerOpen = true
            }

            TiviCopyTheme(mode = prefs.theme, fontScale = prefs.fontScale) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbar) },
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
                            } else {
                                TvRoot(
                                    vm = vm,
                                    overlay = if (state.playlists.isEmpty() && !state.loading) TvOverlay.ADD_SOURCE else overlay,
                                    settingsSection = settingsSection,
                                    onOverlayChange = { overlay = it },
                                    onSettingsSection = { settingsSection = it },
                                    numericEntry = numericEntry,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else {
                            MobileRoot(
                                vm = vm,
                                settingsSection = settingsSection,
                                onSettingsSection = { settingsSection = it },
                                playerOpen = playerOpen,
                                onPlayerOpenChange = { playerOpen = it },
                                inPip = inPip,
                                onBrightness = ::adjustBrightness,
                                onVolume = ::adjustVolume,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- remote keys

    /**
     * Default D-pad bindings from `docs/03` section 3.4: Up/Down zap, Left opens the channel
     * list, Right opens the mini guide, OK shows the info card, a long OK opens the playback
     * menu, Back goes up one level and a second Back exits.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isTelevision) return super.onKeyDown(keyCode, event)

        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            numericEntry = (numericEntry + (keyCode - KeyEvent.KEYCODE_0)).take(4)
            scheduleNumericCommit()
            return true
        }

        // While an overlay owns the screen, the D-pad belongs to it.
        if (overlay != TvOverlay.NONE && overlay != TvOverlay.INFO) {
            return super.onKeyDown(keyCode, event)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); true }
            // FR-UI-21: in a right-to-left layout the two horizontal bindings swap, so the key
            // pointing at the channel column still opens the channel column. Without this a
            // Hebrew interface has a mirrored screen and an unmirrored remote.
            KeyEvent.KEYCODE_DPAD_LEFT ->
                { overlay = if (isRtl) TvOverlay.MINI_GUIDE else TvOverlay.CHANNELS; true }
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                { overlay = if (isRtl) TvOverlay.CHANNELS else TvOverlay.MINI_GUIDE; true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { event.startTracking(); true }
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_YELLOW -> { overlay = TvOverlay.GUIDE; true }
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
     * The short OK press is resolved on key-up, because [onKeyLongPress] only fires for a key
     * the activity asked to track and `isCanceled` is how the platform reports that the long
     * press already consumed it.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        if (isTelevision && isOk && (overlay == TvOverlay.NONE || overlay == TvOverlay.INFO)) {
            if (!event.isCanceled) showInfoCard()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isTelevision && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            overlay = TvOverlay.SETTINGS
            settingsSection = SettingsSection.PLAYBACK
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    /** Back goes up one level; a second press within a short window exits (FR-SET-06). */
    private fun handleBack() {
        when {
            overlay == TvOverlay.SETTINGS && settingsSection != SettingsSection.ROOT ->
                settingsSection = SettingsSection.ROOT

            overlay != TvOverlay.NONE -> overlay = TvOverlay.NONE

            !ServiceLocator.settings.state.value.confirmExit -> finish()

            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPress < EXIT_CONFIRM_WINDOW_MS) {
                    finish()
                } else {
                    lastBackPress = now
                    vm.post(UiMessage(text = getString(R.string.exit_confirm)))
                }
            }
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
                    showInfoCard()
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
        // On the phone, browsing shows no video, so there is nothing for a PiP window to keep
        // showing — only the open player screen shrinks into one. The TV shell is always video.
        if (!isTelevision && !playerOpen) return
        val prefs = ServiceLocator.settings.state.value
        if (prefs.pipEnabled && prefs.pipOnHome) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // The phone shell renders bare video while in the window; on the way out the player
        // screen is still open, so the user lands back where they left.
        inPip = isInPictureInPictureMode
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { enterPictureInPictureMode(pipParams(vm.playbackStatus.value.playWhenReady)) }
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
        // Undo what [onStop] did for the background: the shells' own effects only re-run when
        // their keys change, and coming back from the launcher changes none of them.
        ServiceLocator.player.setVideoOutputWanted(isTelevision || playerOpen || inPip)
        if (pausedInBackground) {
            pausedInBackground = false
            ServiceLocator.player.setPlayWhenReady(true)
        }
    }

    override fun onStop() {
        super.onStop()
        // Where an on-demand title got to, written before the player can be torn down. The
        // position has to be read here, on the thread that owns ExoPlayer, and while it still
        // has one (FR-PLB-29).
        vm.saveVodProgress()
        // Playback is released only when the activity really goes away; a recording keeps
        // running in its own foreground service either way.
        if (isFinishing) {
            ServiceLocator.player.release()
            return
        }
        // Stopped with no picture-in-picture window (the switch is off, the device has no
        // PiP, or a launcher stopped the task instead of finishing it): nothing anywhere is
        // showing this stream. Left alone it kept downloading and decoding video for no
        // surface, and playing sound from a backgrounded app with no notification and no way
        // to stop it. Pause, drop the video track, and let [onStart] undo both.
        val pipNow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!pipNow) {
            if (vm.playbackStatus.value.playWhenReady) {
                pausedInBackground = true
                ServiceLocator.player.setPlayWhenReady(false)
            }
            ServiceLocator.player.setVideoOutputWanted(false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PLAYER_OPEN, playerOpen)
    }

    private companion object {
        const val EXIT_CONFIRM_WINDOW_MS = 3_000L
        const val ACTION_PIP_PLAY_PAUSE = "com.tivicopy.player.PIP_PLAY_PAUSE"
        const val PIP_REQUEST_PLAY_PAUSE = 1
        const val KEY_PLAYER_OPEN = "player_open"
    }
}
