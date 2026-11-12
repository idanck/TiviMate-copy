package com.idanck.shidur.ui.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.idanck.shidur.R
import com.idanck.shidur.ui.account.YouTubeAccountWindow
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.account.AccountState
import com.idanck.shidur.data.youtube.LibraryFilter
import com.idanck.shidur.data.youtube.MusicSection
import com.idanck.shidur.data.youtube.YouTubeRepository
import com.idanck.shidur.data.youtube.YtSong
import com.idanck.shidur.data.youtube.YtSearchKind
import com.idanck.shidur.data.youtube.YtSearchResult
import com.idanck.shidur.data.youtube.YtSubsState
import com.idanck.shidur.data.youtube.YtChannelState
import com.idanck.shidur.data.youtube.YtProblem
import com.idanck.shidur.data.youtube.YtVideo
import com.idanck.shidur.data.youtube.YtWatched
import com.idanck.shidur.ui.common.DialogButton
import com.idanck.shidur.ui.common.FocusFill
import com.idanck.shidur.ui.common.LiveBadge
import com.idanck.shidur.ui.common.LiveBadgeSize
import com.idanck.shidur.ui.common.StudioButton
import com.idanck.shidur.ui.common.StudioButtonSize
import com.idanck.shidur.ui.common.StudioButtonVariant
import com.idanck.shidur.ui.common.StudioChip
import com.idanck.shidur.ui.common.StudioDialog
import com.idanck.shidur.ui.common.StudioField
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.common.YouTubePlayerController
import com.idanck.shidur.ui.common.YouTubePlayerView
import com.idanck.shidur.ui.common.claim
import com.idanck.shidur.ui.common.focusLift
import com.idanck.shidur.ui.common.focusSurface
import com.idanck.shidur.ui.theme.StudioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.roundToInt

/*
 * YouTube inside Shidur on the television (ADR-061; the canvas's `TvYouTube`, `TvYouTubeFull`,
 * `TvYouTubeSide` and `TvYouTubeChannel`).
 *
 * YouTube's terms decide the layout: nothing of ours is ever drawn over its player. So the player
 * has a frame of its own at the top of YouTube's screen, with what is on it beside it, and under
 * both, as on home, an opaque bottom third that alone scrolls from rail to rail. Full screen is the
 * player alone; the list of live channels makes room for itself by shrinking the player, never by
 * covering it. A dialog empties the frame and pauses the player.
 *
 * The player is one WebView for the whole visit ([YouTubeSession]), drawn by [YouTubePlayerLayer]
 * at one place in [TvRoot] over both overlays, and only moved and resized as the frame does: it is
 * never re-created between the screen and full screen, so the stream does not start again.
 */

/** A page of the music tab over its parts (ADR-069), over which Back steps. */
internal sealed interface MusicPage {
    /** A playlist or an album and its songs. */
    data class Songs(val id: String) : MusicPage
    /** The player screen, with what plays and what comes next. */
    data object Player : MusicPage
}

/** A page of YouTube's screen shown in place of its home view, over which Back steps. */
internal sealed interface YtPage {
    data class Channel(val id: String) : YtPage
    data object Search : YtPage
}

/** What YouTube's two overlays share while either is up. */
@Stable
internal class YouTubeSession(context: Context, private val repo: YouTubeRepository) {
    val player = YouTubePlayerController(context)

    /** The home view's player frame, in root pixels, while it is laid out. */
    var frame by mutableStateOf<Rect?>(null)

    /** The pages opened over the home view, the last on top; empty on the home view itself. */
    var pages by mutableStateOf<List<YtPage>>(emptyList())
        private set

    /** The page on top, or null on the home view. */
    val page: YtPage? get() = pages.lastOrNull()

    fun open(page: YtPage) {
        pages = pages + page
    }

    /** One page back; false on the home view, where Back is the activity's. */
    fun back(): Boolean {
        if (pages.isEmpty()) return false
        pages = pages.dropLast(1)
        return true
    }

    fun home() {
        pages = emptyList()
    }

    /** Search keeps what was typed and found while a channel opened from it is looked at. */
    var searchQuery by mutableStateOf("")
    var searchKind by mutableStateOf(YtSearchKind.ALL)
    var searchResult by mutableStateOf<YtSearchResult?>(null)
    var searchedFor: Pair<String, YtSearchKind>? = null

    /** A dialog is open: the frame empties and the player waits, paused. */
    var dialog by mutableStateOf(false)

    /** Full screen's list of live channels is open, and the player has shrunk for it. */
    var sideList by mutableStateOf(false)

    /** Something was played on this visit, so the screen does not start a stream of its own again. */
    var started = false

    /** Keys pressed on YouTube's screen this visit: the screen claims the remote only before the first. */
    var keys = 0

    /** Whether the player was playing when something came in front of its frame ([hold]). */
    private var heldPlaying = false

    /** Something takes the frame's place (a channel page, a dialog): the player waits, paused. */
    fun hold() {
        val phase = player.state.value.phase
        heldPlaying = phase == YouTubePlayerController.Phase.PLAYING ||
            phase == YouTubePlayerController.Phase.BUFFERING ||
            phase == YouTubePlayerController.Phase.LOADING
        player.pause()
    }

    /** The frame is back: the player carries on if [hold] paused it. */
    fun resumeHeld() {
        if (heldPlaying) player.play()
        heldPlaying = false
    }

    /** A channel asked for that is not live right now: its title, said where the picture would be. */
    var offAir by mutableStateOf<String?>(null)

    /** A YouTube channel picked in Shidur's channel list: its live stream, or [offAir] when it has none. */
    suspend fun playChannel(id: String) {
        started = true
        val state = repo.channel(id)
        val live = state?.live?.firstOrNull()
        if (live != null) {
            play(live)
        } else {
            noteCurrent()
            player.stop()
            offAir = state?.channel?.title ?: repo.listed.value.firstOrNull { it.id == id }?.title ?: ""
        }
    }

    fun play(video: YtVideo) {
        started = true
        offAir = null
        heldPlaying = false
        noteCurrent()
        playingMusic = false
        player.load(video, repo.positionOf(video.id))
    }

    /** Writes down where the video playing got to, for "continue watching" — never a song (ADR-069). */
    fun noteCurrent() {
        if (playingMusic) return
        val s = player.state.value
        val v = s.video ?: return
        if (s.position > 0) repo.noteWatched(v, s.position)
    }

    // ---- The music tab (ADR-069) ----

    /** "מוזיקה" is chosen in the switch at the top of YouTube's screen. */
    var music by mutableStateOf(false)

    /** Something on the screen asked to connect YouTube again (the music tab's note, ADR-069); the screen opens the window. */
    var connectAsked by mutableStateOf(false)

    var musicSection by mutableStateOf(MusicSection.HOME)
    var libraryFilter by mutableStateOf(LibraryFilter.PLAYLISTS)

    /** The music tab's pages over its parts (a playlist or album, the player screen), the last on top. */
    var musicPages by mutableStateOf<List<MusicPage>>(emptyList())
        private set

    val musicPage: MusicPage? get() = musicPages.lastOrNull()

    fun openMusic(page: MusicPage) {
        musicPages = musicPages + page
    }

    /** One music page back; false when there is none. */
    fun backMusic(): Boolean {
        if (musicPages.isEmpty()) return false
        musicPages = musicPages.dropLast(1)
        return true
    }

    /** The songs playing in order, and where they came from (a playlist or album id), for the history. */
    var queue by mutableStateOf<List<YtSong>>(emptyList())
        private set
    var queueContext by mutableStateOf<String?>(null)
        private set
    var queueTitle by mutableStateOf<String?>(null)
        private set
    var repeat by mutableStateOf(false)

    /** The player holds a song from [queue], not a video. */
    var playingMusic by mutableStateOf(false)
        private set

    /** The song playing, when the player holds one. */
    val currentSong: YtSong?
        get() = if (playingMusic) player.state.value.video?.id?.let { id -> queue.firstOrNull { it.id == id } } else null

    /** Plays [songs] in order from [start] (the first when null), as one queue. */
    fun playSongs(songs: List<YtSong>, start: YtSong?, context: String?, title: String?, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val ordered = if (shuffle) {
            val first = start ?: songs.random()
            listOf(first) + songs.filterNot { it.id == first.id }.shuffled()
        } else {
            songs
        }
        queue = ordered
        queueContext = context
        queueTitle = title
        playSong(if (shuffle) ordered.first() else start ?: ordered.first())
    }

    /** Plays [song] from the start, as a song: noted in the music history, not in "continue watching". */
    fun playSong(song: YtSong) {
        started = true
        offAir = null
        heldPlaying = false
        noteCurrent()
        playingMusic = true
        if (queue.none { it.id == song.id }) queue = queue + song
        ServiceLocator.music.notePlayed(song, queueContext)
        player.load(song.asVideo(), 0)
    }

    fun nextSong() {
        com.idanck.shidur.data.youtube.nextSong(queue, player.state.value.video?.id, repeat)?.let { playSong(it) }
    }

    fun previousSong() {
        // Into the song, the first press goes back to its start, as music players do.
        if (player.state.value.position > RESTART_WITHIN_S) {
            player.seekBy(-player.state.value.position)
            return
        }
        com.idanck.shidur.data.youtube.previousSong(queue, player.state.value.video?.id)?.let { playSong(it) }
    }

    /** The queue in a new random order, the song playing first. */
    fun shuffleQueue() {
        val current = currentSong ?: return
        queue = listOf(current) + queue.filterNot { it.id == current.id }.shuffled()
    }

    /** A song came to its end: the next one in the queue plays, or the queue starts again with [repeat]. */
    fun onEnded() {
        if (playingMusic) nextSong()
    }

    fun release() {
        noteCurrent()
        player.release()
    }
}

/** Every live stream on the saved channels, in the order the channels were saved. */
internal fun liveOf(saved: List<String>, channels: Map<String, YtChannelState>): List<YtVideo> =
    saved.flatMap { channels[it]?.live.orEmpty() }

internal fun openInYouTube(context: Context, video: YtVideo) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${video.id}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Where the player is drawn, over whichever of YouTube's overlays is up: in the home view's frame,
 * on the whole screen, or shrunk beside full screen's list. Placed by absolute pixels so a
 * right-to-left layout mirrors nothing; moves between full screen and the list are animated.
 */
@Composable
internal fun YouTubePlayerLayer(session: YouTubeSession, full: Boolean, modifier: Modifier = Modifier) {
    val state by session.player.state.collectAsState()
    // A song that ends gives way to the next in the queue (ADR-069), wherever the player is drawn.
    LaunchedEffect(state.phase, state.video?.id) {
        if (state.phase == YouTubePlayerController.Phase.ENDED) session.onEnded()
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier = modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val frame = session.frame
        val target: Rect? = when {
            full && !session.sideList -> Rect(0f, 0f, w, h)
            full -> {
                val pw = w * SIDE_PLAYER_FRACTION
                val ph = h * SIDE_PLAYER_FRACTION
                val gap = w * SIDE_PLAYER_GAP
                val left = if (rtl) gap else w - gap - pw
                Rect(left, h * SIDE_PLAYER_TOP, left + pw, h * SIDE_PLAYER_TOP + ph)
            }
            frame != null && session.page == null -> frame.translate(-origin.x, -origin.y)
            else -> null
        }
        // Where it last was, kept while it has nowhere to be (a channel page, a frame not laid out
        // yet), so it does not fly off and back. A plain holder: it changes with `target`, in the
        // same composition.
        val last = remember { arrayOfNulls<Rect>(1) }
        val goal = target ?: last[0] ?: Rect(0f, 0f, w, h)
        last[0] = goal
        val rect by animateRectAsState(targetValue = goal, label = "ytPlayer")
        val shown = target != null && !session.dialog && state.video != null && !state.notEmbeddable && !state.unavailable && !state.liveEnded
        YouTubePlayerView(
            controller = session.player,
            visible = shown,
            modifier = Modifier.layout { measurable, outer ->
                val pw = rect.width.roundToInt().coerceAtLeast(1)
                val ph = rect.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(pw, ph))
                layout(outer.maxWidth, outer.maxHeight) {
                    placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                }
            },
        )
    }
}

/**
 * YouTube's own screen, in the side menu after News: the player's frame with what is on it beside
 * it, and the rails under both; or a channel's page in their place.
 */
@Composable
internal fun TvYouTubeScreen(
    session: YouTubeSession,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = ServiceLocator.youtube
    val saved by repo.saved.collectAsState()
    val channels by repo.channels.collectAsState()
    val history by repo.history.collectAsState()
    val problem by repo.problem.collectAsState()
    val stale by repo.stale.collectAsState()
    val playerState by session.player.state.collectAsState()
    val subs by repo.subs.collectAsState()
    val account by ServiceLocator.account.state.collectAsState()
    val signedIn = account is AccountState.SignedIn
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var accountOpen by remember { mutableStateOf(false) }
    val link by repo.link.collectAsState()
    LaunchedEffect(session.connectAsked) {
        if (session.connectAsked) {
            session.connectAsked = false
            connecting = true
        }
    }

    // The subscriptions rail: whether there is one, and its feed. The server keeps the list of
    // subscriptions for six hours and their videos for an hour, so asking often costs YouTube little.
    LaunchedEffect(signedIn) {
        while (true) {
            repo.refreshSubscriptions(signedIn)
            delay(SUBS_REFRESH_MS)
        }
    }

    // Asked again every few minutes while the screen is up; the server's shared cache decides
    // what that costs YouTube (ten minutes old at most for a saved channel).
    LaunchedEffect(Unit) {
        while (true) {
            repo.refresh()
            delay(REFRESH_MS)
        }
    }
    // A channel just saved is asked for at once.
    LaunchedEffect(saved) {
        if (saved.any { it !in repo.channels.value }) repo.refresh(force = true)
    }
    val live = remember(saved, channels) { liveOf(saved, channels) }
    // The first live stream starts by itself, once a visit: one player playing on the screen.
    LaunchedEffect(live.firstOrNull()?.id) {
        val first = live.firstOrNull()
        // Not on the music tab, where a stream starting by itself would stand in for the songs (ADR-069).
        if (!session.started && !session.music && first != null) session.play(first)
    }
    // The player keeps its place in "continue watching" as it goes.
    LaunchedEffect(session) {
        while (true) {
            delay(NOTE_EVERY_MS)
            session.noteCurrent()
        }
    }
    // A dialog may not sit in front of a YouTube player, and a channel page has no frame for it:
    // either way it waits, paused, and carries on after.
    LaunchedEffect(adding, connecting, accountOpen) { session.dialog = adding || connecting || accountOpen }
    val covered = adding || connecting || accountOpen || session.page != null
    LaunchedEffect(covered) { if (covered) session.hold() else session.resumeHeld() }
    DisposableEffect(Unit) { onDispose { session.dialog = false } }
    BackHandler(enabled = session.page != null) { session.back() }

    val studio = StudioTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(studio.ground)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) session.keys++
                // Play/pause on the remote works the player wherever focus is on this screen.
                val media = e.key == Key.MediaPlayPause || e.key == Key.MediaPlay || e.key == Key.MediaPause
                // Not behind a page or a dialog, where the player waits paused and out of sight.
                if (media && e.type == KeyEventType.KeyDown && session.page == null && !session.dialog) session.player.toggle()
                // The remote's search key searches YouTube while its screen is up.
                val search = e.key == Key.Search
                if (search && e.type == KeyEventType.KeyDown && session.page != YtPage.Search) session.open(YtPage.Search)
                media || search
            },
    ) {
        when (val page = session.page) {
            is YtPage.Channel -> YouTubeChannelPage(
                id = page.id,
                state = channels[page.id],
                saved = page.id in saved,
                playingId = playerState.video?.id,
                onToggleSave = { repo.toggleSaved(page.id) },
                onPlay = { video ->
                    session.home()
                    session.play(video)
                },
            )

            YtPage.Search -> YouTubeSearchPage(
                session = session,
                onOpenChannel = { session.open(YtPage.Channel(it)) },
                onPlay = { video ->
                    session.home()
                    session.play(video)
                },
            )

            null -> Column(modifier = Modifier.fillMaxSize()) {
                // "וידאו | מוזיקה" (ADR-069): the video tab as it was, or the music tab.
                YtModeSwitch(
                    session = session,
                    gutter = sideMenuInset(),
                    // The YouTube account, at the end of the row (ADR-069): once signed in, and once
                    // there is a connection or a way to make one.
                    account = link?.takeIf { signedIn && (it.linked || it.tvReady) },
                    onAccount = { if (link?.linked == true) accountOpen = true else connecting = true },
                )
                if (session.music) {
                    TvMusicScreen(
                        session = session,
                        onConnect = { connecting = true },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                YouTubeHome(
                    session = session,
                    playerState = playerState,
                    saved = saved,
                    channels = channels,
                    live = live,
                    history = history,
                    problem = problem,
                    stale = stale,
                    subs = subs,
                    onFullscreen = onFullscreen,
                    onOpenChannel = { session.open(YtPage.Channel(it)) },
                    onSearch = { session.open(YtPage.Search) },
                    onAdd = { adding = true },
                    onConnect = { connecting = true },
                    onOpenInApp = { video -> openInYouTube(context, video) },
                )
                }
            }
        }
    }

    val current = link
    if (accountOpen && current != null && current.linked) {
        YouTubeAccountWindow(
            link = current,
            onDismiss = { accountOpen = false },
            onConnectAgain = {
                accountOpen = false
                connecting = true
            },
            onDisconnected = {
                accountOpen = false
                scope.launch { repo.refreshSubscriptions(signedIn = true) }
            },
        )
    }

    if (connecting) {
        ConnectYouTubeDialog(
            repo = repo,
            onDismiss = { connecting = false },
            onLinked = {
                connecting = false
                scope.launch { repo.refreshSubscriptions(signedIn = true) }
            },
        )
    }

    if (adding) {
        AddChannelDialog(
            repo = repo,
            onDismiss = { adding = false },
            onAdded = { state ->
                adding = false
                repo.save(state.channel.id)
            },
        )
    }
}

private sealed interface YtRail {
    val key: String
    val title: String

    data class Videos(
        override val key: String,
        override val title: String,
        val caption: String?,
        val videos: List<YtVideo>,
        val progress: Map<String, Float> = emptyMap(),
        val empty: String? = null,
    ) : YtRail

    data class Channels(
        override val key: String,
        override val title: String,
        val caption: String?,
        val channels: List<YtChannelState>,
    ) : YtRail

    /** The subscriptions rail before an account is connected, or after the connection was lost. */
    data class Connect(
        override val key: String,
        override val title: String,
        val revoked: Boolean,
    ) : YtRail
}

@Composable
private fun YouTubeHome(
    session: YouTubeSession,
    playerState: YouTubePlayerController.State,
    saved: List<String>,
    channels: Map<String, YtChannelState>,
    live: List<YtVideo>,
    history: List<YtWatched>,
    problem: YtProblem?,
    stale: Boolean,
    subs: YtSubsState,
    onFullscreen: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onSearch: () -> Unit,
    onAdd: () -> Unit,
    onConnect: () -> Unit,
    onOpenInApp: (YtVideo) -> Unit,
) {
    val studio = StudioTheme.colors
    val gutter = sideMenuInset()
    val panelHeight = (LocalConfiguration.current.screenHeightDp / 3f).dp
    val fullscreenFocus = remember { FocusRequester() }
    val firstCardFocus = remember { FocusRequester() }
    val repo = ServiceLocator.youtube

    val liveTitle = stringResource(R.string.yt_rail_live)
    val liveCaption = stringResource(R.string.yt_rail_live_caption)
    val noLive = stringResource(R.string.yt_no_live)
    val recentTitle = stringResource(R.string.yt_rail_recent)
    val channelsTitle = stringResource(R.string.yt_rail_channels)
    val channelsCaption = stringResource(R.string.yt_rail_channels_caption)
    val continueTitle = stringResource(R.string.yt_rail_continue)
    val subsTitle = stringResource(R.string.yt_rail_subs)
    val subsCaption = stringResource(R.string.yt_rail_subs_caption)
    val subsEmpty = stringResource(R.string.yt_channel_empty)
    val subsFailed = stringResource(R.string.yt_subs_failed)
    val rails = remember(saved, channels, live, history, subs, liveTitle) {
        buildList<YtRail> {
            add(YtRail.Videos("live", liveTitle, liveCaption, live, empty = if (channels.isEmpty()) null else noLive))
            when (subs) {
                is YtSubsState.Feed -> add(YtRail.Videos("subs", subsTitle, subsCaption, subs.videos, empty = if (subs.failed) subsFailed else subsEmpty))
                is YtSubsState.Invite -> add(YtRail.Connect("subs", subsTitle, subs.revoked))
                YtSubsState.Hidden -> Unit
            }
            val recent = saved.flatMap { channels[it]?.recent.orEmpty() }
                .sortedByDescending { it.publishedAt ?: 0L }
                .take(RECENT_RAIL_SIZE)
            if (recent.isNotEmpty()) add(YtRail.Videos("recent", recentTitle, null, recent))
            add(YtRail.Channels("channels", channelsTitle, channelsCaption, saved.mapNotNull { channels[it] }))
            if (history.isNotEmpty()) {
                add(
                    YtRail.Videos(
                        "continue",
                        continueTitle,
                        null,
                        history.map { it.video },
                        progress = history.mapNotNull { w -> w.progress?.let { w.video.id to it } }.toMap(),
                    ),
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = gutter, end = YT_SAFE_X, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            YouTubeNowPlaying(
                state = playerState,
                channel = playerState.video?.let { channels[it.channelId] },
                saved = playerState.video?.channelId?.let { it in saved } ?: false,
                stale = stale,
                problem = problem,
                fullscreenFocus = fullscreenFocus,
                onFullscreen = onFullscreen,
                onOpenChannel = { playerState.video?.channelId?.takeIf { it.isNotEmpty() }?.let(onOpenChannel) },
                onToggleSave = { playerState.video?.channelId?.takeIf { it.isNotEmpty() }?.let { repo.toggleSaved(it) } },
                onOpenInApp = { playerState.video?.let(onOpenInApp) },
                onSearch = onSearch,
                // A live stream that has ended offers the next one on the saved channels.
                nextLive = live.firstOrNull { it.id != playerState.video?.id },
                onPlay = { session.play(it) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            YouTubeFrame(
                session = session,
                state = playerState,
                modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true),
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelHeight)
                .background(studio.ground),
        ) {
            itemsIndexed(rails, key = { _, rail -> rail.key }) { index, rail ->
                YouTubeRailRow(
                    rail = rail,
                    gutter = gutter,
                    playingId = playerState.video?.id,
                    firstFocus = if (index == 0) firstCardFocus else null,
                    onVideo = { video ->
                        if (video.id == playerState.video?.id && playerState.error == null) onFullscreen() else session.play(video)
                    },
                    onChannel = onOpenChannel,
                    onAdd = onAdd,
                    onConnect = onConnect,
                    modifier = Modifier.fillParentMaxHeight(),
                )
            }
        }
    }

    // The remote lands on "full screen" when something plays, and on the first rail otherwise —
    // and moves to "full screen" when a stream starts by itself, unless the viewer has already
    // pressed a key since coming here.
    val hasVideo = playerState.video != null
    val keysAtEntry = remember { session.keys }
    LaunchedEffect(hasVideo) {
        if (session.keys != keysAtEntry) return@LaunchedEffect
        val target = if (hasVideo) fullscreenFocus else firstCardFocus
        target.claim { false }
    }
}

/** The frame the player is drawn in, and what it says while there is nothing to play there. */
@Composable
private fun YouTubeFrame(session: YouTubeSession, state: YouTubePlayerController.State, modifier: Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val shape = RoundedCornerShape(12.dp)
    DisposableEffect(session) { onDispose { session.frame = null } }
    Box(
        modifier = modifier
            .clip(shape)
            .background(studio.surface)
            .onGloballyPositioned { session.frame = it.boundsInRoot() },
        contentAlignment = Alignment.Center,
    ) {
        val message = when {
            state.liveEnded -> stringResource(R.string.yt_live_ended)
            state.notEmbeddable -> stringResource(R.string.yt_not_embeddable)
            state.unavailable || state.error != null -> stringResource(R.string.yt_video_gone)
            state.video == null -> stringResource(R.string.yt_nothing_playing)
            else -> null
        }
        if (message != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(StudioIcons.Stream, contentDescription = null, tint = studio.ink3, modifier = Modifier.size(40.dp))
                Text(message, style = type.meta, color = studio.ink2, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
    }
}

@Composable
private fun YouTubeNowPlaying(
    state: YouTubePlayerController.State,
    channel: YtChannelState?,
    saved: Boolean,
    stale: Boolean,
    problem: YtProblem?,
    fullscreenFocus: FocusRequester,
    onFullscreen: () -> Unit,
    onOpenChannel: () -> Unit,
    onToggleSave: () -> Unit,
    onOpenInApp: () -> Unit,
    onSearch: () -> Unit,
    nextLive: YtVideo?,
    onPlay: (YtVideo) -> Unit,
    modifier: Modifier,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val video = state.video
    Column(modifier = modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (video?.live == true) LiveBadge(label = stringResource(R.string.yt_live_badge), size = LiveBadgeSize.S)
            Text("YouTube", style = type.meta, color = studio.ink2)
        }
        if (video != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ChannelThumb(channel?.channel?.thumb, video.channelTitle, size = 32.dp)
                Text(
                    text = channel?.channel?.title ?: video.channelTitle,
                    style = type.body.copy(fontWeight = FontWeight.Bold),
                    color = studio.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = video.title,
                style = type.title,
                color = studio.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            videoMeta(video)?.let { Text(it, style = type.meta, color = studio.ink2, maxLines = 1) }
            Row(
                modifier = Modifier.padding(top = 5.dp).focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.liveEnded) {
                    StudioButton(
                        label = stringResource(R.string.yt_next_live),
                        onClick = { nextLive?.let(onPlay) },
                        icon = StudioIcons.Forward,
                        variant = StudioButtonVariant.PRIMARY,
                        size = StudioButtonSize.MEDIUM,
                        enabled = nextLive != null,
                        focusRequester = fullscreenFocus,
                    )
                } else if (state.notEmbeddable) {
                    StudioButton(
                        label = stringResource(R.string.yt_open_in_app),
                        onClick = onOpenInApp,
                        icon = StudioIcons.Stream,
                        variant = StudioButtonVariant.PRIMARY,
                        size = StudioButtonSize.MEDIUM,
                        focusRequester = fullscreenFocus,
                    )
                } else {
                    StudioButton(
                        label = stringResource(R.string.yt_fullscreen),
                        onClick = onFullscreen,
                        icon = StudioIcons.Fullscreen,
                        size = StudioButtonSize.MEDIUM,
                        enabled = state.error == null,
                        focusRequester = fullscreenFocus,
                    )
                }
                StudioButton(
                    label = stringResource(R.string.yt_to_channel),
                    onClick = onOpenChannel,
                    icon = StudioIcons.Stream,
                    size = StudioButtonSize.MEDIUM,
                )
                StudioButton(
                    label = stringResource(if (saved) R.string.yt_saved_channel else R.string.yt_save_channel),
                    onClick = onToggleSave,
                    icon = StudioIcons.star(saved),
                    iconOnly = true,
                    size = StudioButtonSize.MEDIUM,
                    active = saved,
                )
                StudioButton(
                    label = stringResource(R.string.yt_search),
                    onClick = onSearch,
                    icon = StudioIcons.Search,
                    iconOnly = true,
                    size = StudioButtonSize.MEDIUM,
                )
            }
        } else {
            Text(stringResource(R.string.yt_nothing_playing), style = type.title, color = studio.ink)
            StudioButton(
                label = stringResource(R.string.yt_search),
                onClick = onSearch,
                icon = StudioIcons.Search,
                size = StudioButtonSize.MEDIUM,
            )
        }
        val note = when {
            problem != null -> problemText(problem)
            stale -> problemText(YtProblem.QUOTA)
            else -> null
        }
        if (note != null) {
            Spacer(Modifier.weight(1f))
            Text(note, style = type.caption, color = studio.ink3, maxLines = 2)
        }
    }
}

@Composable
internal fun problemText(problem: YtProblem): String = stringResource(
    when (problem) {
        YtProblem.UNAVAILABLE -> R.string.yt_problem_unavailable
        YtProblem.OFFLINE -> R.string.yt_problem_offline
        YtProblem.QUOTA, YtProblem.SEARCH_QUOTA -> R.string.yt_problem_quota
        YtProblem.NOT_FOUND -> R.string.yt_add_not_found
        YtProblem.SIGNED_OUT -> R.string.yt_problem_signed_out
        YtProblem.NOT_CONFIGURED -> R.string.yt_problem_not_configured
        YtProblem.OTHER -> R.string.yt_problem_other
    },
)

/** "1,234 watching now · started at 18:00", "scheduled for 21:00", or "3 hours ago". */
@Composable
internal fun videoMeta(video: YtVideo): String? {
    val parts = mutableListOf<String>()
    when {
        video.live -> {
            video.viewers?.let { parts += stringResource(R.string.yt_viewers, NumberFormat.getIntegerInstance().format(it)) }
            video.startedAt?.let { parts += stringResource(R.string.yt_started_at, TimeModel.ltr(TimeModel.formatClock(it))) }
        }
        video.upcoming -> video.scheduledAt?.let { parts += stringResource(R.string.yt_scheduled_at, TimeModel.ltr(TimeModel.formatDayTime(it))) }
        else -> video.publishedAt?.let { parts += relativeTime(it) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun relativeTime(at: Long): String =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** "1:58:04" or "08:42". */
private fun durationText(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun YouTubeRailRow(
    rail: YtRail,
    gutter: Dp,
    playingId: String?,
    firstFocus: FocusRequester?,
    onVideo: (YtVideo) -> Unit,
    onChannel: (String) -> Unit,
    onAdd: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.padding(start = gutter, end = YT_SAFE_X),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(rail.title, style = type.h1, color = studio.ink)
            val caption = when (rail) {
                is YtRail.Videos -> rail.caption
                is YtRail.Channels -> rail.caption
                is YtRail.Connect -> null
            }
            caption?.let { Text(it, style = type.caption, color = studio.ink3) }
        }
        val padding = PaddingValues(start = gutter, end = YT_SAFE_X, top = 10.dp, bottom = 10.dp)
        when (rail) {
            is YtRail.Videos -> if (rail.videos.isEmpty()) {
                // A rail with nothing in it still takes the remote, so the list can be scrolled past it.
                Box(
                    modifier = Modifier
                        .padding(start = gutter, top = 12.dp)
                        .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                        .focusable(),
                ) {
                    rail.empty?.let { Text(it, style = type.meta, color = studio.ink3) }
                }
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().focusGroup(),
                    contentPadding = padding,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(rail.videos, key = { _, v -> v.id }) { index, video ->
                        YtVideoCard(
                            video = video,
                            onScreen = video.id == playingId,
                            progress = rail.progress[video.id],
                            width = YT_CARD_WIDTH,
                            onClick = { onVideo(video) },
                            modifier = if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
            }

            is YtRail.Connect -> YouTubeConnectCard(
                revoked = rail.revoked,
                onConnect = onConnect,
                modifier = Modifier.padding(start = gutter, end = YT_SAFE_X, top = 10.dp),
            )

            is YtRail.Channels -> LazyRow(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(rail.channels, key = { it.channel.id }) { state ->
                    YtChannelDisc(
                        label = state.channel.title,
                        thumb = state.channel.thumb,
                        live = state.live.isNotEmpty(),
                        onClick = { onChannel(state.channel.id) },
                    )
                }
                item(key = "add") {
                    YtChannelDisc(
                        label = stringResource(R.string.yt_add_channel),
                        thumb = null,
                        live = false,
                        add = true,
                        onClick = onAdd,
                    )
                }
            }
        }
    }
}

/** A video's card (`YtCard`): its thumbnail, live or its length on it, and its title and line under. */
@Composable
internal fun YtVideoCard(
    video: YtVideo,
    onScreen: Boolean,
    progress: Float?,
    width: Dp?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    var focused by remember { mutableStateOf(false) }
    val shape = tvCardShape()
    Column(
        modifier = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .focusLift(focused, shape = shape)
                .tvCard(focused = focused, playing = onScreen)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .semantics { contentDescription = video.title },
        ) {
            if (!video.thumb.isNullOrBlank()) {
                AsyncImage(
                    model = video.thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            when {
                video.live -> LiveBadge(
                    label = stringResource(R.string.yt_live_badge),
                    size = LiveBadgeSize.S,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                )
                video.upcoming -> CardTag(stringResource(R.string.yt_upcoming_badge), Modifier.align(Alignment.TopStart).padding(6.dp))
                video.duration != null -> CardTag(TimeModel.ltr(durationText(video.duration)), Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(studio.track),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(studio.current))
                }
            }
        }
        Text(
            text = video.title,
            style = type.caption.copy(fontWeight = FontWeight.Bold),
            color = studio.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(video.channelTitle.takeIf { it.isNotEmpty() }, videoMeta(video)).joinToString(" · "),
            style = type.caption,
            color = studio.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CardTag(text: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = StudioTheme.type.caption.copy(fontWeight = FontWeight.Bold), color = androidx.compose.ui.graphics.Color.White)
    }
}

/** A channel's round picture, or its first letter where it has none. */
@Composable
internal fun ChannelThumb(thumb: String?, label: String, size: Dp) {
    val studio = StudioTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(studio.surface)
            .border(1.dp, studio.hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!thumb.isNullOrBlank()) {
            AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(label.take(1), style = StudioTheme.type.body.copy(fontWeight = FontWeight.Bold), color = studio.ink2)
        }
    }
}

/** A saved channel on "your channels" (`StationDisc`), or the disc that adds one. */
@Composable
internal fun YtChannelDisc(
    label: String,
    thumb: String?,
    live: Boolean,
    onClick: () -> Unit,
    add: Boolean = false,
) {
    val studio = StudioTheme.colors
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.width(YT_DISC_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(YT_DISC_SIZE)
                .focusLift(focused, shape = CircleShape)
                .focusSurface(focused = focused, shape = CircleShape, selected = live, fill = FocusFill.RAISE)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            when {
                add -> Icon(StudioIcons.Plus, contentDescription = null, tint = studio.ink2, modifier = Modifier.size(28.dp))
                !thumb.isNullOrBlank() -> AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
                else -> Text(label.take(1), style = StudioTheme.type.h1, color = studio.ink2)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (live) Box(Modifier.size(6.dp).clip(CircleShape).background(studio.tally))
            Text(label, style = StudioTheme.type.caption, color = studio.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A channel's page (`TvYouTubeChannel`): what it is, saving it, and its videos under two tabs. */
@Composable
private fun YouTubeChannelPage(
    id: String,
    state: YtChannelState?,
    saved: Boolean,
    playingId: String?,
    onToggleSave: () -> Unit,
    onPlay: (YtVideo) -> Unit,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val gutter = sideMenuInset()
    val repo = ServiceLocator.youtube
    LaunchedEffect(id) { if (state == null) repo.channel(id) }
    var tab by remember(id) { mutableStateOf(if (state?.live.isNullOrEmpty() && state?.upcoming.isNullOrEmpty()) 1 else 0) }
    val saveFocus = remember { FocusRequester() }
    LaunchedEffect(id) { saveFocus.claim { false } }
    val numbers = NumberFormat.getIntegerInstance()

    Column(
        modifier = Modifier.fillMaxSize().padding(start = gutter, end = YT_SAFE_X, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ChannelThumb(state?.channel?.thumb, state?.channel?.title.orEmpty(), size = 88.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.yt_channel_kind), style = type.meta, color = studio.ink2)
                Text(state?.channel?.title.orEmpty(), style = type.title, color = studio.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(
                    state?.channel?.subscribers?.let { stringResource(R.string.yt_subscribers, numbers.format(it)) },
                    state?.channel?.videos?.let { stringResource(R.string.yt_video_count, numbers.format(it)) },
                )
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = type.meta, color = studio.ink2)
            }
            StudioButton(
                label = stringResource(if (saved) R.string.yt_remove_channel else R.string.yt_save_channel),
                onClick = onToggleSave,
                icon = StudioIcons.star(saved),
                size = StudioButtonSize.MEDIUM,
                active = saved,
                focusRequester = saveFocus,
            )
            // Also into Shidur's channel list and guide, with a number from 900 (`TvYouTubeInList`).
            val listed by repo.listed.collectAsState()
            val inList = listed.any { it.id == id }
            StudioButton(
                label = stringResource(if (inList) R.string.yt_remove_from_list else R.string.yt_add_to_list),
                onClick = { if (inList) repo.removeFromList(id) else state?.let(repo::addToList) },
                icon = if (inList) StudioIcons.Check else StudioIcons.Plus,
                size = StudioButtonSize.MEDIUM,
                active = inList,
                enabled = inList || state != null,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.focusGroup()) {
            listOf(R.string.yt_tab_live, R.string.yt_tab_videos).forEachIndexed { index, label ->
                StudioChip(
                    label = stringResource(label),
                    selected = tab == index,
                    onClick = { tab = index },
                    // The tabs follow the remote, as on the radio screen.
                    modifier = Modifier.onFocusChanged { if (it.hasFocus || it.isFocused) tab = index },
                )
            }
        }
        val videos = when {
            state == null -> emptyList()
            tab == 0 -> state.live + state.upcoming
            else -> state.recent
        }
        if (state != null && videos.isEmpty()) {
            Text(stringResource(R.string.yt_channel_empty), style = type.meta, color = studio.ink3)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(videos, key = { it.id }) { video ->
                YtVideoCard(
                    video = video,
                    onScreen = video.id == playingId,
                    progress = null,
                    width = null,
                    onClick = { onPlay(video) },
                )
            }
        }
    }
}

/** Adding a channel by its address, @handle or ID, typed on the television's keyboard. */
@Composable
internal fun AddChannelDialog(
    repo: YouTubeRepository,
    onDismiss: () -> Unit,
    onAdded: (YtChannelState) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<YtProblem?>(null) }
    var already by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.claim { false } }
    val submit: () -> Unit = submit@{
        if (busy || text.isBlank()) return@submit
        busy = true
        scope.launch {
            repo.resolve(text)
                .onSuccess { state ->
                    if (repo.isSaved(state.channel.id)) already = true else onAdded(state)
                }
                .onFailure { e -> error = (e as? YouTubeRepository.YtException)?.problem ?: YtProblem.OTHER }
            busy = false
        }
    }
    val helper = when {
        already -> stringResource(R.string.yt_add_already)
        error != null -> problemText(error ?: YtProblem.OTHER)
        else -> null
    }
    StudioDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.yt_add_title),
        icon = StudioIcons.Plus,
        buttons = {
            DialogButton(
                label = stringResource(R.string.yt_add_action),
                onClick = submit,
                variant = StudioButtonVariant.PRIMARY,
                icon = StudioIcons.Plus,
                enabled = !busy && text.isNotBlank(),
            )
            DialogButton(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    ) {
        StudioField(
            value = text,
            onValueChange = {
                text = it
                error = null
                already = false
            },
            placeholder = stringResource(R.string.yt_add_hint),
            helper = helper,
            isError = error != null || already,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            focusRequester = field,
        )
    }
}

/**
 * Full screen (`TvYouTubeFull`, `TvYouTubeSide`): the player alone, worked through its API. OK
 * plays and pauses, ◀▶ jump ten seconds (not on a live stream), and ▲▼ or Menu bring up the
 * live channels at the start edge, for which the player shrinks. The list closes after four
 * seconds without a key, on Back, or towards the player. Back otherwise returns to YouTube's
 * screen (MainActivity).
 */
@Composable
internal fun TvYouTubeFull(session: YouTubeSession, modifier: Modifier = Modifier) {
    val repo = ServiceLocator.youtube
    val saved by repo.saved.collectAsState()
    val channels by repo.channels.collectAsState()
    val state by session.player.state.collectAsState()
    val live = remember(saved, channels) { liveOf(saved, channels) }
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    var keyAt by remember { mutableLongStateOf(0L) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val towardPlayer = if (rtl) Key.DirectionLeft else Key.DirectionRight

    BackHandler(enabled = session.sideList) { session.sideList = false }
    DisposableEffect(Unit) { onDispose { session.sideList = false } }
    LaunchedEffect(session.sideList, keyAt) {
        if (session.sideList) {
            delay(SIDE_LIST_IDLE_MS)
            session.sideList = false
        }
    }
    LaunchedEffect(session.sideList) {
        if (session.sideList && live.isNotEmpty()) rowFocus.claim { false } else focus.claim { false }
    }

    fun zap(step: Int) {
        if (live.isEmpty()) return
        val at = live.indexOfFirst { it.id == state.video?.id }
        val next = live[((if (at < 0) 0 else at + step) % live.size + live.size) % live.size]
        session.play(next)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Before the focus target, so it sees the keys of the box itself and of the list's rows.
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) keyAt = System.nanoTime()
                val down = e.type == KeyEventType.KeyDown
                if (session.sideList) {
                    if (e.key == towardPlayer || e.key == Key.Menu) {
                        if (down) session.sideList = false
                        true
                    } else {
                        false
                    }
                } else {
                    when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                            if (down) session.player.toggle()
                            true
                        }
                        // The remote's arrows are physical: left is back in time in either language.
                        Key.DirectionLeft -> {
                            if (down) session.player.seekBy(-SEEK_STEP_S)
                            true
                        }
                        Key.DirectionRight -> {
                            if (down) session.player.seekBy(SEEK_STEP_S)
                            true
                        }
                        Key.MediaRewind -> {
                            if (down) session.player.seekBy(-SEEK_STEP_S)
                            true
                        }
                        Key.MediaFastForward -> {
                            if (down) session.player.seekBy(SEEK_STEP_S)
                            true
                        }
                        Key.DirectionUp, Key.DirectionDown, Key.Menu -> {
                            if (down && live.isNotEmpty()) session.sideList = true
                            true
                        }
                        Key.ChannelUp -> {
                            if (down) zap(1)
                            true
                        }
                        Key.ChannelDown -> {
                            if (down) zap(-1)
                            true
                        }
                        else -> false
                    }
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        // The player could not play this here: say so where it would have been, and offer YouTube.
        session.offAir?.takeIf { state.video == null }?.let { title ->
            val next = live.firstOrNull()
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(stringResource(R.string.yt_off_air, title), style = StudioTheme.type.title, color = StudioTheme.colors.ink)
                if (next != null) {
                    StudioButton(
                        label = stringResource(R.string.yt_next_live),
                        onClick = { session.play(next) },
                        icon = StudioIcons.Forward,
                        variant = StudioButtonVariant.PRIMARY,
                        size = StudioButtonSize.MEDIUM,
                    )
                }
            }
        }
        if (state.liveEnded) {
            val next = live.firstOrNull { it.id != state.video?.id }
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(stringResource(R.string.yt_live_ended), style = StudioTheme.type.title, color = StudioTheme.colors.ink)
                if (next != null) {
                    StudioButton(
                        label = stringResource(R.string.yt_next_live),
                        onClick = { session.play(next) },
                        icon = StudioIcons.Forward,
                        variant = StudioButtonVariant.PRIMARY,
                        size = StudioButtonSize.MEDIUM,
                    )
                }
            }
        }
        if (state.notEmbeddable || state.unavailable) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    stringResource(if (state.notEmbeddable) R.string.yt_not_embeddable else R.string.yt_video_gone),
                    style = StudioTheme.type.title,
                    color = StudioTheme.colors.ink,
                )
                if (state.notEmbeddable) {
                    StudioButton(
                        label = stringResource(R.string.yt_open_in_app),
                        onClick = { state.video?.let { openInYouTube(context, it) } },
                        icon = StudioIcons.Stream,
                        variant = StudioButtonVariant.PRIMARY,
                        size = StudioButtonSize.MEDIUM,
                    )
                }
            }
        }
        if (session.sideList) {
            YouTubeLiveList(
                live = live,
                channels = channels,
                playingId = state.video?.id,
                firstFocus = rowFocus,
                onPlay = { video ->
                    session.sideList = false
                    if (video.id != state.video?.id) session.play(video)
                },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}

/** Full screen's list of live channels, on an opaque drawer at the start edge (`TvYouTubeSide`). */
@Composable
private fun YouTubeLiveList(
    live: List<YtVideo>,
    channels: Map<String, YtChannelState>,
    playingId: String?,
    firstFocus: FocusRequester,
    onPlay: (YtVideo) -> Unit,
    modifier: Modifier,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val focusIndex = live.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(YT_SIDE_LIST_WIDTH)
            .background(studio.drawer)
            .padding(start = 20.dp, end = 16.dp, top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.yt_live_list), style = type.h1, color = studio.ink, modifier = Modifier.weight(1f))
            Text(TimeModel.ltr(TimeModel.formatClock(now)), style = type.body.copy(fontWeight = FontWeight.SemiBold), color = studio.ink)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            itemsIndexed(live, key = { _, v -> v.id }) { index, video ->
                YouTubeLiveRow(
                    video = video,
                    thumb = channels[video.channelId]?.channel?.thumb,
                    playing = video.id == playingId,
                    onClick = { onPlay(video) },
                    modifier = if (index == focusIndex) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        Text(stringResource(R.string.yt_live_list_hint), style = type.caption, color = studio.ink3)
    }
}

@Composable
private fun YouTubeLiveRow(video: YtVideo, thumb: String?, playing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .focusLift(focused, scale = 1.02f, shape = shape)
            .focusSurface(focused = focused, shape = shape, selected = playing, fill = FocusFill.FILLED)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChannelThumb(thumb, video.channelTitle, size = 36.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val ink = if (focused) studio.onCue else studio.ink
            Text(video.channelTitle, style = type.body.copy(fontWeight = FontWeight.Bold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(video.title, style = type.caption, color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (playing) Box(Modifier.size(6.dp).clip(CircleShape).background(studio.tally))
    }
}

/** How often the screen asks for the saved channels again while it is up. */
private const val REFRESH_MS = 5 * 60 * 1000L

/** How often the subscriptions rail is asked for again while the screen is up. */
private const val SUBS_REFRESH_MS = 10 * 60 * 1000L

/** How often the position reached is written down for "continue watching". */
private const val NOTE_EVERY_MS = 30_000L

private const val RECENT_RAIL_SIZE = 20
private const val SEEK_STEP_S = 10
/** Previous goes back to a song's start when it is further in than this, as music players do. */
private const val RESTART_WITHIN_S = 3
private const val SIDE_LIST_IDLE_MS = 4_000L

/** The shrunk player beside full screen's list: 1344×756 at 1080p, 162px from the top, 48px from the end edge. */
private const val SIDE_PLAYER_FRACTION = 0.7f
private const val SIDE_PLAYER_TOP = 0.15f
private const val SIDE_PLAYER_GAP = 0.025f

/** `--st-safe-x`: 96px at 1080p. */
internal val YT_SAFE_X = 48.dp
private val YT_CARD_WIDTH = 160.dp
private val YT_DISC_SIZE = 72.dp
private val YT_DISC_WIDTH = 92.dp
private val YT_SIDE_LIST_WIDTH = 240.dp
