package com.idanck.shidur.ui.tv

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.account.AccountState
import com.idanck.shidur.data.youtube.LibraryFilter
import com.idanck.shidur.data.youtube.MusicSection
import com.idanck.shidur.data.youtube.PlayedSong
import com.idanck.shidur.data.youtube.YtChannelHit
import com.idanck.shidur.data.youtube.YtLinkStatus
import com.idanck.shidur.data.youtube.YtMusicList
import com.idanck.shidur.data.youtube.YtSong
import com.idanck.shidur.data.youtube.YtSubsState
import com.idanck.shidur.data.youtube.mergeLiked
import com.idanck.shidur.data.youtube.quickPicks
import com.idanck.shidur.ui.account.YouTubeAvatar
import com.idanck.shidur.ui.account.youTubeAccountName
import com.idanck.shidur.ui.common.FocusFill
import com.idanck.shidur.ui.common.StudioButton
import com.idanck.shidur.ui.common.StudioButtonSize
import com.idanck.shidur.ui.common.StudioButtonVariant
import com.idanck.shidur.ui.common.StudioChip
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.common.YouTubePlayerController
import com.idanck.shidur.ui.common.claim
import com.idanck.shidur.ui.common.filledContentColour
import com.idanck.shidur.ui.common.focusLift
import com.idanck.shidur.ui.common.focusSurface
import com.idanck.shidur.ui.theme.StudioTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * YouTube Music on the television (ADR-069; the canvas's page "טלוויזיה · YouTube Music").
 *
 * A tab inside YouTube's screen, chosen in the "וידאו | מוזיקה" switch at its top, with two parts
 * as chips: "בשבילך" and "הספרייה שלי". Over them, two pages: a playlist or an album, and the
 * player screen.
 *
 * YouTube's terms are the video tab's (ADR-061): the player is YouTube's own, always visible in a
 * frame of its own, nothing of ours over it, and nothing plays in the background. A YouTube player
 * must be at least 200×200; the page's CSS pixels are dp, so the frame is never less than 200dp
 * high. On "בשבילך", the library and a list's page it is the mini player, a column at the end edge
 * with the song and its buttons under the frame — on "בשבילך" only while something is in the player,
 * the rails taking the whole screen otherwise (the browsing layout of 12/11); on the player screen it
 * is large, the queue beside it.
 */

/** The switch at the top of YouTube's screen, and the music tab's parts after it. */
@Composable
internal fun YtModeSwitch(
    session: YouTubeSession,
    gutter: Dp,
    modifier: Modifier = Modifier,
    account: YtLinkStatus? = null,
    onAccount: () -> Unit = {},
) {
    val studio = StudioTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(start = gutter, end = YT_SAFE_X, top = 2.dp, bottom = 8.dp).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StudioChip(
            label = stringResource(R.string.ytm_mode_video),
            selected = !session.music,
            onClick = { session.music = false },
        )
        StudioChip(
            label = stringResource(R.string.ytm_mode_music),
            selected = session.music,
            onClick = { session.music = true },
        )
        if (session.music) {
            Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(20.dp).background(studio.hairline))
            listOf(MusicSection.HOME to R.string.ytm_section_home, MusicSection.LIBRARY to R.string.ytm_section_library).forEach { (section, label) ->
                StudioChip(
                    label = stringResource(label),
                    selected = session.musicSection == section && session.musicPage == null,
                    onClick = { session.musicSection = section },
                    // The parts follow the remote, as the podcasts' do.
                    modifier = Modifier.onFocusChanged {
                        if ((it.hasFocus || it.isFocused) && session.musicPage == null) session.musicSection = section
                    },
                )
            }
        }
        if (account != null) {
            Spacer(Modifier.weight(1f))
            YtAccountChip(account, onClick = onAccount)
        }
    }
}

/**
 * The YouTube account at the end of the top row (`canvas/YtAccountChip`): the channel's picture
 * and name, an amber dot when the connection cannot give likes, or "חיבור YouTube" with none.
 */
@Composable
private fun YtAccountChip(link: YtLinkStatus, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = CircleShape
    Row(
        modifier = Modifier
            .height(32.dp)
            .focusLift(focused, shape = shape)
            .focusSurface(focused = focused, shape = shape, fill = FocusFill.FILLED)
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 5.dp, end = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box {
            YouTubeAvatar(if (link.linked) link.channel ?: YtChannelHit("", "", null) else null, size = 22.dp)
            if (link.linked && !link.canRate) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StudioTheme.colors.current),
                )
            }
        }
        Text(
            text = if (link.linked) youTubeAccountName(link) else stringResource(R.string.yt_account_connect),
            style = StudioTheme.type.meta.copy(fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold),
            color = filledContentColour(focused = focused),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
    }
}

/** The music tab: its part, or a page over it. */
@Composable
internal fun TvMusicScreen(session: YouTubeSession, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val music = ServiceLocator.music
    val account by ServiceLocator.account.state.collectAsState()
    val signedIn = account is AccountState.SignedIn

    LaunchedEffect(Unit) { music.refreshChart() }
    LaunchedEffect(signedIn) { if (signedIn) music.refreshLibrary() }
    BackHandler(enabled = session.musicPage != null) { session.backMusic() }

    Box(modifier = modifier.fillMaxSize()) {
        when (val page = session.musicPage) {
            is MusicPage.Songs -> MusicListPage(session, page.id)
            MusicPage.Player -> MusicPlayerPage(session)
            null -> when (session.musicSection) {
                MusicSection.HOME -> MusicHome(session, signedIn = signedIn, onConnect = onConnect)
                else -> MusicLibrary(session, signedIn = signedIn, onConnect = onConnect)
            }
        }
    }
}

// ---- "בשבילך" ----

private sealed interface MusicRail {
    val key: String

    data class Songs(override val key: String, val title: String, val caption: String?, val songs: List<YtSong>, val context: String?) : MusicRail
    data class Lists(override val key: String, val title: String, val caption: String?, val lists: List<YtMusicList>) : MusicRail
    data class Artists(override val key: String, val title: String, val artists: List<com.idanck.shidur.data.youtube.YtChannelHit>) : MusicRail
    data class Connect(override val key: String, val title: String) : MusicRail
}

@Composable
private fun MusicHome(session: YouTubeSession, signedIn: Boolean, onConnect: () -> Unit) {
    val music = ServiceLocator.music
    val youtube = ServiceLocator.youtube
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val gutter = sideMenuInset()
    val playerState by session.player.state.collectAsState()
    val history by music.history.collectAsState()
    val likes by music.likes.collectAsState()
    val youtubeLiked by music.youtubeLiked.collectAsState()
    val chart by music.chart.collectAsState()
    val playlists by music.playlists.collectAsState()
    val linked by music.linked.collectAsState()
    val albums by music.albums.collectAsState()
    val artists by youtube.subChannels.collectAsState()
    val subs by youtube.subs.collectAsState()
    val firstFocus = remember { FocusRequester() }
    val playingMusic = session.playingMusic

    val continueTitle = stringResource(R.string.ytm_continue)
    val quickTitle = stringResource(R.string.ytm_rail_quick)
    val quickCaption = stringResource(R.string.ytm_rail_quick_caption)
    val listsTitle = stringResource(R.string.ytm_rail_playlists)
    val listsCaption = stringResource(R.string.ytm_src_playlists)
    val artistsTitle = stringResource(R.string.ytm_rail_artists)
    val popularTitle = stringResource(R.string.ytm_rail_popular)
    val popularCaption = stringResource(R.string.ytm_rail_popular_caption)
    val albumsTitle = stringResource(R.string.ytm_rail_albums)
    val rails = remember(history, likes, youtubeLiked, chart, playlists, linked, albums, artists, subs, signedIn, playingMusic) {
        buildList<MusicRail> {
            // Nothing playing: where you stopped is the first rail, not a hero (12/11, the canvas's
            // `TvYtMusicBrowseIdle`), on this television or another device of the account.
            if (!playingMusic) {
                val recent = history.map { it.song }.distinctBy { it.id }.take(CONTINUE_RAIL_SIZE)
                if (recent.isNotEmpty()) add(MusicRail.Songs("continue", continueTitle, null, recent, null))
            }
            val quick = quickPicks(history, mergeLiked(likes, youtubeLiked))
            // With no history yet there is nothing to pick from; the chart leads instead (ADR-069).
            if (quick.isNotEmpty()) add(MusicRail.Songs("quick", quickTitle, quickCaption, quick, null))
            when {
                playlists.isNotEmpty() -> add(MusicRail.Lists("lists", listsTitle, listsCaption, playlists))
                signedIn && !linked && subs is YtSubsState.Invite -> add(MusicRail.Connect("lists", listsTitle))
            }
            if (artists.isNotEmpty()) add(MusicRail.Artists("artists", artistsTitle, artists))
            if (chart.isNotEmpty()) add(MusicRail.Songs("popular", popularTitle, popularCaption, chart, null))
            if (albums.isNotEmpty()) add(MusicRail.Lists("albums", albumsTitle, null, albums.map { it.list }))
        }
    }

    // Rails on the whole height, and the whole list scrolls (the canvas's `TvYtMusicBrowse`). While
    // something is in the player its column stands at the end edge: the frame, 356×200, and the
    // song and its buttons under it. With nothing in it there is no player and no column, and the
    // rails run to the edge.
    val showColumn = playerState.video != null
    val listState = rememberLazyListState()
    ReportRailsScrolled(session, listState)
    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // Beside the column the rails are cut a gap short of it, not against it.
            modifier = Modifier.weight(1f).fillMaxHeight().padding(end = if (showColumn) BROWSE_COLUMN_GAP else 0.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (rails.isEmpty()) {
                item(key = "empty") {
                    // It still takes the remote, so the switch above is not the end of the way down.
                    Box(Modifier.padding(start = gutter, top = 12.dp).focusRequester(firstFocus).focusable()) {
                        Text(stringResource(R.string.ytm_loading), style = type.meta, color = studio.ink3)
                    }
                }
            }
            itemsIndexed(rails, key = { _, rail -> rail.key }) { index, rail ->
                MusicRailRow(
                    rail = rail,
                    gutter = gutter,
                    end = if (showColumn) 0.dp else YT_SAFE_X,
                    playingId = if (session.playingMusic) playerState.video?.id else null,
                    firstFocus = if (index == 0) firstFocus else null,
                    onSong = { songs, song, context ->
                        if (session.playingMusic && song.id == playerState.video?.id) session.openMusic(MusicPage.Player)
                        else session.playSongs(songs, song, context, null)
                    },
                    onList = { session.openMusic(MusicPage.Songs(it.id)) },
                    onArtist = { session.open(YtPage.Channel(it)) },
                    onConnect = onConnect,
                )
            }
        }
        if (showColumn) MusicMiniColumn(session, modifier = Modifier.padding(end = YT_SAFE_X, top = 4.dp, bottom = 12.dp))
    }
    // The remote lands on the first card; again when the first rail arrives, unless a key was pressed.
    val keysAtEntry = remember { session.keys }
    LaunchedEffect(rails.isEmpty()) {
        if (session.keys == keysAtEntry) firstFocus.claim { false }
    }
}

/** Tells [session] whether [state] has scrolled past its first rail, so the switch above gives its row to the rails. */
@Composable
internal fun ReportRailsScrolled(session: YouTubeSession, state: LazyListState) {
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex > 0 }.collect { session.railsScrolled = it }
    }
    DisposableEffect(session) { onDispose { session.railsScrolled = false } }
}

/** Previous, play or pause, next. [focus] goes to play/pause. */
@Composable
private fun MusicTransport(session: YouTubeSession, state: YouTubePlayerController.State, focus: FocusRequester?) {
    val playing = state.phase == YouTubePlayerController.Phase.PLAYING ||
        state.phase == YouTubePlayerController.Phase.BUFFERING ||
        state.phase == YouTubePlayerController.Phase.LOADING
    StudioButton(
        label = stringResource(R.string.ytm_prev),
        onClick = { session.previousSong() },
        icon = StudioIcons.Rewind,
        iconOnly = true,
        size = StudioButtonSize.MEDIUM,
    )
    StudioButton(
        label = stringResource(if (playing) R.string.ytm_pause else R.string.ytm_play),
        onClick = { session.player.toggle() },
        icon = if (playing) StudioIcons.Pause else StudioIcons.Play,
        iconOnly = true,
        variant = StudioButtonVariant.PRIMARY,
        size = StudioButtonSize.MEDIUM,
        focusRequester = focus,
    )
    StudioButton(
        label = stringResource(R.string.ytm_next),
        onClick = { session.nextSong() },
        icon = StudioIcons.Forward,
        iconOnly = true,
        size = StudioButtonSize.MEDIUM,
    )
}

/** Where the song is: a thin bar with the time on either side. */
@Composable
private fun SongProgress(state: YouTubePlayerController.State, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val duration = state.duration.takeIf { it > 0 } ?: state.video?.duration ?: 0
    val fraction = if (duration > 0) (state.position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(TimeModel.ltr(clock(state.position)), style = type.caption, color = studio.ink2)
        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(studio.track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(studio.ink))
        }
        Text(TimeModel.ltr(clock(duration)), style = type.caption, color = studio.ink3)
    }
}

/** A like that could not reach YouTube: the connection was made without the scope for it (ADR-069). */
@Composable
private fun ReconnectNote(session: YouTubeSession) {
    val music = ServiceLocator.music
    val reconnect by music.reconnect.collectAsState()
    if (reconnect) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.ytm_reconnect),
                style = StudioTheme.type.caption,
                color = StudioTheme.colors.ink3,
                maxLines = 2,
                modifier = Modifier.weight(1f, fill = false),
            )
            StudioButton(
                label = stringResource(R.string.yt_account_reconnect),
                onClick = { session.connectAsked = true },
                icon = StudioIcons.Link,
                size = StudioButtonSize.MEDIUM,
            )
        }
    }
}

/** The frame YouTube's player is drawn in on a music view, and what it says while nothing plays there. */
@Composable
private fun MusicFrame(session: YouTubeSession, state: YouTubePlayerController.State, modifier: Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    DisposableEffect(session) { onDispose { session.frame = null } }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(studio.surface)
            .onGloballyPositioned { session.frame = it.boundsInRoot() },
        contentAlignment = Alignment.Center,
    ) {
        val message = when {
            state.notEmbeddable -> stringResource(R.string.yt_not_embeddable)
            state.unavailable || state.error != null -> stringResource(R.string.yt_video_gone)
            state.video == null -> stringResource(R.string.ytm_nothing_playing)
            else -> null
        }
        if (message != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(StudioIcons.Music, contentDescription = null, tint = studio.ink3, modifier = Modifier.size(32.dp))
                Text(message, style = type.caption, color = studio.ink2, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun MusicRailRow(
    rail: MusicRail,
    gutter: Dp,
    end: Dp,
    playingId: String?,
    firstFocus: FocusRequester?,
    onSong: (List<YtSong>, YtSong, String?) -> Unit,
    onList: (YtMusicList) -> Unit,
    onArtist: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val title = when (rail) {
        is MusicRail.Songs -> rail.title
        is MusicRail.Lists -> rail.title
        is MusicRail.Artists -> rail.title
        is MusicRail.Connect -> rail.title
    }
    val caption = when (rail) {
        is MusicRail.Songs -> rail.caption
        is MusicRail.Lists -> rail.caption
        else -> null
    }
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.padding(start = gutter, end = end),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(title, style = type.h1, color = studio.ink)
            caption?.let { Text(it, style = type.caption, color = studio.ink3) }
        }
        val padding = PaddingValues(start = gutter, end = end, top = 10.dp, bottom = 10.dp)
        when (rail) {
            is MusicRail.Songs -> LazyRow(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(rail.songs, key = { _, song -> song.id }) { index, song ->
                    MusicCard(
                        title = song.title,
                        meta = song.artist,
                        thumb = song.thumb,
                        playing = song.id == playingId,
                        onClick = { onSong(rail.songs, song, rail.context) },
                        size = MUSIC_BROWSE_CARD,
                        modifier = firstOf(index, firstFocus),
                    )
                }
            }

            is MusicRail.Lists -> LazyRow(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(rail.lists, key = { _, list -> list.id }) { index, list ->
                    MusicCard(
                        title = list.title,
                        meta = listMeta(list),
                        thumb = list.thumb,
                        playing = false,
                        onClick = { onList(list) },
                        size = MUSIC_BROWSE_CARD,
                        modifier = firstOf(index, firstFocus),
                    )
                }
            }

            is MusicRail.Artists -> LazyRow(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(rail.artists, key = { _, artist -> artist.id }) { index, artist ->
                    Box(modifier = firstOf(index, firstFocus)) {
                        YtChannelDisc(label = artist.title, thumb = artist.thumb, live = false, onClick = { onArtist(artist.id) })
                    }
                }
            }

            is MusicRail.Connect -> YouTubeConnectCard(
                revoked = false,
                onConnect = onConnect,
                modifier = Modifier
                    .padding(start = gutter, end = end, top = 10.dp)
                    .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
            )
        }
    }
}

/** The first card of a rail takes [firstFocus], where it is given. */
private fun firstOf(index: Int, firstFocus: FocusRequester?): Modifier =
    if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier

/** "Album · 12 songs", "Playlist · Artist". */
@Composable
private fun listMeta(list: YtMusicList): String = listOfNotNull(
    stringResource(if (list.album) R.string.ytm_album else R.string.ytm_playlist),
    list.count?.let { stringResource(R.string.ytm_song_count, it) } ?: list.owner.takeIf { it.isNotEmpty() },
).joinToString(" · ")

/** A song, a playlist or an album on a rail or in a grid (`MusicCard`): square art, title, a line under it. */
@Composable
private fun MusicCard(
    title: String,
    meta: String,
    thumb: String?,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = MUSIC_CARD,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    var focused by remember { mutableStateOf(false) }
    val shape = tvCardShape()
    Column(modifier = Modifier.width(size), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = modifier
                .size(size)
                .focusLift(focused, shape = shape)
                .tvCard(focused = focused, playing = playing)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .semantics { contentDescription = title },
            contentAlignment = Alignment.Center,
        ) {
            if (!thumb.isNullOrBlank()) {
                AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(StudioIcons.Music, contentDescription = null, tint = studio.ink3, modifier = Modifier.size(32.dp))
            }
        }
        Text(title, style = type.caption.copy(fontWeight = FontWeight.Bold), color = studio.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(meta, style = type.caption, color = studio.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One song in a list (`SongRow`): art, title, artist and a line, its length; the one playing in the tally. */
@Composable
private fun MusicSongRow(song: YtSong, meta: String, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .focusSurface(focused = focused, shape = shape, selected = playing, fill = FocusFill.LIT)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(studio.surface)) {
            if (!song.thumb.isNullOrBlank()) {
                AsyncImage(model = song.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, style = type.body.copy(fontWeight = FontWeight.Bold), color = if (focused) studio.onCue else studio.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = type.caption, color = if (focused) studio.onCue else studio.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (playing) Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(studio.tally))
        song.duration?.let { Text(TimeModel.ltr(clock(it)), style = type.caption, color = if (focused) studio.onCue else studio.ink3) }
    }
}

// ---- The mini player: a column at the end edge ----

/**
 * The music tab's mini player beside "בשבילך", the library and a list's page: YouTube's player itself in a
 * frame of 356×200dp — at least the 200 YouTube asks for — with the song and its buttons under it.
 * Never over anything, and gone with YouTube's screen, which stops the music.
 */
@Composable
private fun MusicMiniColumn(session: YouTubeSession, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val state by session.player.state.collectAsState()
    val song = session.currentSong
    Column(modifier = modifier.width(MINI_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MusicFrame(session = session, state = state, modifier = Modifier.width(MINI_WIDTH).height(MINI_HEIGHT))
        if (song != null) {
            Text(song.title, style = type.body.copy(fontWeight = FontWeight.Bold), color = studio.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = type.meta, color = studio.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SongProgress(state)
            Row(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicTransport(session, state, focus = null)
                StudioButton(
                    label = stringResource(R.string.ytm_open_player),
                    onClick = { session.openMusic(MusicPage.Player) },
                    icon = StudioIcons.Fullscreen,
                    iconOnly = true,
                    size = StudioButtonSize.MEDIUM,
                )
            }
            // On "בשבילך" this column is where the song is, so the note to connect again is said here too.
            ReconnectNote(session)
        }
    }
}

// ---- "הספרייה שלי" ----

@Composable
private fun MusicLibrary(session: YouTubeSession, signedIn: Boolean, onConnect: () -> Unit) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val gutter = sideMenuInset()
    val music = ServiceLocator.music
    val playlists by music.playlists.collectAsState()
    val likes by music.likes.collectAsState()
    val youtubeLiked by music.youtubeLiked.collectAsState()
    val albums by music.albums.collectAsState()
    val history by music.history.collectAsState()
    val linked by music.linked.collectAsState()
    val artists by ServiceLocator.youtube.subChannels.collectAsState()
    val playerState by session.player.state.collectAsState()
    val playingId = if (session.playingMusic) playerState.video?.id else null
    val filter = session.libraryFilter

    Row(
        modifier = Modifier.fillMaxSize().padding(start = gutter, end = YT_SAFE_X, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                LibraryFilter.entries.forEach { f ->
                    StudioChip(
                        label = stringResource(filterLabel(f)),
                        selected = filter == f,
                        onClick = { session.libraryFilter = f },
                        modifier = Modifier.onFocusChanged { if (it.hasFocus || it.isFocused) session.libraryFilter = f },
                    )
                }
            }
            Text(
                stringResource(
                    when (filter) {
                        LibraryFilter.PLAYLISTS -> R.string.ytm_src_playlists
                        LibraryFilter.LIKED -> if (linked) R.string.ytm_src_liked else R.string.ytm_src_liked_local
                        LibraryFilter.ARTISTS -> R.string.ytm_src_artists
                        LibraryFilter.ALBUMS -> R.string.ytm_src_albums
                        LibraryFilter.HISTORY -> R.string.ytm_src_history
                    },
                ),
                style = type.caption,
                color = studio.ink3,
                maxLines = 1,
            )
            when (filter) {
                LibraryFilter.PLAYLISTS -> when {
                    playlists.isNotEmpty() -> ListGrid(playlists) { session.openMusic(MusicPage.Songs(it.id)) }
                    signedIn && !linked -> YouTubeConnectCard(revoked = false, onConnect = onConnect, modifier = Modifier)
                    else -> EmptyNote()
                }
                LibraryFilter.LIKED -> {
                    val songs = remember(likes, youtubeLiked) { mergeLiked(likes, youtubeLiked) }
                    SongList(songs, playingId) { song -> session.playSongs(songs, song, null, null) }
                }
                LibraryFilter.ARTISTS -> if (artists.isEmpty()) {
                    if (signedIn && !linked) YouTubeConnectCard(revoked = false, onConnect = onConnect, modifier = Modifier) else EmptyNote()
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(YT_DISC_CELL),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(artists, key = { it.id }) { artist ->
                            YtChannelDisc(label = artist.title, thumb = artist.thumb, live = false, onClick = { session.open(YtPage.Channel(artist.id)) })
                        }
                    }
                }
                LibraryFilter.ALBUMS -> if (albums.isEmpty()) EmptyNote() else ListGrid(albums.map { it.list }) { session.openMusic(MusicPage.Songs(it.id)) }
                LibraryFilter.HISTORY -> HistoryList(history, playingId) { played ->
                    val songs = history.map { it.song }.distinctBy { it.id }
                    session.playSongs(songs, played.song, played.context, null)
                }
            }
        }
        MusicMiniColumn(session)
    }
}

private fun filterLabel(f: LibraryFilter): Int = when (f) {
    LibraryFilter.PLAYLISTS -> R.string.ytm_filter_playlists
    LibraryFilter.LIKED -> R.string.ytm_filter_liked
    LibraryFilter.ARTISTS -> R.string.ytm_filter_artists
    LibraryFilter.ALBUMS -> R.string.ytm_filter_albums
    LibraryFilter.HISTORY -> R.string.ytm_filter_history
}

@Composable
private fun EmptyNote() {
    // It still takes the remote, so the chips above are not the end of the way down.
    Box(Modifier.padding(top = 12.dp).focusable()) {
        Text(stringResource(R.string.ytm_empty_list), style = StudioTheme.type.meta, color = StudioTheme.colors.ink3)
    }
}

@Composable
private fun ListGrid(lists: List<YtMusicList>, onOpen: (YtMusicList) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(MUSIC_CARD + 14.dp),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(lists, key = { it.id }) { list ->
            MusicCard(title = list.title, meta = listMeta(list), thumb = list.thumb, playing = false, onClick = { onOpen(list) })
        }
    }
}

@Composable
private fun SongList(songs: List<YtSong>, playingId: String?, onPlay: (YtSong) -> Unit) {
    if (songs.isEmpty()) {
        EmptyNote()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().focusGroup(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(songs, key = { it.id }) { song ->
            MusicSongRow(song = song, meta = song.artist, playing = song.id == playingId, onClick = { onPlay(song) })
        }
    }
}

/** The history by day, with the time and the device each song played on. */
@Composable
private fun HistoryList(history: List<PlayedSong>, playingId: String?, onPlay: (PlayedSong) -> Unit) {
    if (history.isEmpty()) {
        EmptyNote()
        return
    }
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val context = LocalContext.current
    val today = stringResource(R.string.ytm_today)
    val yesterday = stringResource(R.string.ytm_yesterday)
    val onTv = stringResource(R.string.ytm_on_tv)
    val onPhone = stringResource(R.string.ytm_on_phone)
    val zone = ZoneId.systemDefault()
    val now = LocalDate.now(zone)
    val days = remember(history) { history.groupBy { Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate() }.toList() }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().focusGroup(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        days.forEach { (day, played) ->
            item(key = "day-$day") {
                val label = when (day) {
                    now -> today
                    now.minusDays(1) -> yesterday
                    else -> DateUtils.formatDateTime(context, played.first().playedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
                }
                Text(label, style = type.h1, color = studio.ink, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            }
            itemsIndexed(played, key = { _, p -> p.docId }) { _, p ->
                val meta = listOf(p.song.artist, TimeModel.ltr(TimeModel.formatClock(p.playedAt)), if (p.device == "phone") onPhone else onTv)
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
                MusicSongRow(song = p.song, meta = meta, playing = p.song.id == playingId, onClick = { onPlay(p) })
            }
        }
    }
}

// ---- A playlist or an album ----

@Composable
private fun MusicListPage(session: YouTubeSession, id: String) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val gutter = sideMenuInset()
    val music = ServiceLocator.music
    val albums by music.albums.collectAsState()
    val playerState by session.player.state.collectAsState()
    val playingId = if (session.playingMusic) playerState.video?.id else null
    var loaded by remember(id) { mutableStateOf<Pair<YtMusicList, List<YtSong>>?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(id) {
        music.list(id).onSuccess { loaded = it }.onFailure { failed = true }
    }
    LaunchedEffect(loaded != null) { if (loaded != null) playFocus.claim { false } }

    Row(
        modifier = Modifier.fillMaxSize().padding(start = gutter, end = YT_SAFE_X, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val data = loaded
            if (data == null) {
                Text(
                    stringResource(if (failed) R.string.ytm_list_failed else R.string.ytm_loading),
                    style = type.meta,
                    color = studio.ink2,
                    modifier = Modifier.focusable(),
                )
            } else {
                val (list, songs) = data
                val saved = albums.any { it.list.id == list.id }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(studio.surface)) {
                        if (!list.thumb.isNullOrBlank()) {
                            AsyncImage(model = list.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(if (list.album) R.string.ytm_album else R.string.ytm_playlist), style = type.meta, color = studio.ink2)
                        Text(list.title, style = type.title, color = studio.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(list.owner.takeIf { it.isNotEmpty() }, stringResource(R.string.ytm_song_count, songs.size)).joinToString(" · "),
                            style = type.meta,
                            color = studio.ink2,
                        )
                    }
                }
                Row(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioButton(
                        label = stringResource(R.string.ytm_play),
                        onClick = { session.playSongs(songs, null, list.id, list.title) },
                        icon = StudioIcons.Play,
                        variant = StudioButtonVariant.PRIMARY,
                        size = StudioButtonSize.MEDIUM,
                        enabled = songs.isNotEmpty(),
                        focusRequester = playFocus,
                    )
                    StudioButton(
                        label = stringResource(R.string.ytm_shuffle),
                        onClick = { session.playSongs(songs, null, list.id, list.title, shuffle = true) },
                        icon = StudioIcons.Swap,
                        size = StudioButtonSize.MEDIUM,
                        enabled = songs.isNotEmpty(),
                    )
                    if (list.album) {
                        StudioButton(
                            label = stringResource(if (saved) R.string.ytm_saved_album else R.string.ytm_save_album),
                            onClick = { music.toggleAlbum(list) },
                            icon = StudioIcons.star(saved),
                            size = StudioButtonSize.MEDIUM,
                            active = saved,
                        )
                    }
                }
                if (list.album) Text(stringResource(R.string.ytm_saved_where), style = type.caption, color = studio.ink3)
                SongList(songs, playingId) { song -> session.playSongs(songs, song, list.id, list.title) }
            }
        }
        MusicMiniColumn(session)
    }
}

// ---- The player screen ----

@Composable
private fun MusicPlayerPage(session: YouTubeSession) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val music = ServiceLocator.music
    val likes by music.likes.collectAsState()
    val youtubeLiked by music.youtubeLiked.collectAsState()
    val state by session.player.state.collectAsState()
    val gutter = sideMenuInset()
    val song = session.currentSong
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.claim { false } }

    Row(
        modifier = Modifier.fillMaxSize().padding(start = gutter, end = YT_SAFE_X, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (song == null) {
                Text(stringResource(R.string.ytm_nothing_playing), style = type.title, color = studio.ink)
                Box(Modifier.size(1.dp).focusRequester(focus).focusable())
            } else {
                val liked = likes.any { it.song.id == song.id } || youtubeLiked.any { it.id == song.id }
                session.queueTitle?.let { Text(stringResource(R.string.ytm_from_list, it), style = type.meta, color = studio.ink2, maxLines = 1) }
                Text(song.title, style = type.titleSmall, color = studio.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = type.body.copy(fontWeight = FontWeight.Bold), color = studio.ink2, maxLines = 1)
                SongProgress(state)
                Row(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicTransport(session, state, focus = focus)
                    StudioButton(
                        label = stringResource(if (liked) R.string.ytm_liked else R.string.ytm_like),
                        onClick = { music.toggleLike(song) },
                        icon = StudioIcons.star(liked),
                        iconOnly = true,
                        size = StudioButtonSize.MEDIUM,
                        active = liked,
                    )
                    StudioButton(
                        label = stringResource(R.string.ytm_shuffle),
                        onClick = { session.shuffleQueue() },
                        icon = StudioIcons.Swap,
                        iconOnly = true,
                        size = StudioButtonSize.MEDIUM,
                    )
                    StudioButton(
                        label = stringResource(R.string.ytm_repeat),
                        onClick = { session.repeat = !session.repeat },
                        icon = StudioIcons.Restart,
                        iconOnly = true,
                        size = StudioButtonSize.MEDIUM,
                        active = session.repeat,
                    )
                }
                ReconnectNote(session)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.ytm_queue), style = type.h1, color = studio.ink)
                val upNext = session.queue.dropWhile { it.id != song.id }.drop(1)
                if (upNext.isEmpty()) {
                    Text(stringResource(R.string.ytm_queue_end), style = type.meta, color = studio.ink3)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(upNext, key = { it.id }) { next ->
                            MusicSongRow(song = next, meta = next.artist, playing = false, onClick = { session.playSong(next) })
                        }
                    }
                }
            }
        }
        MusicFrame(
            session = session,
            state = state,
            modifier = Modifier.width(PLAYER_WIDTH).aspectRatio(16f / 9f),
        )
    }
}

/** "3:42", "1:02:04". */
private fun clock(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

private val MUSIC_CARD = 96.dp

/** A card on a "בשבילך" rail: smaller than the library's, so the rails beside the player's column hold five and a part. */
private val MUSIC_BROWSE_CARD = 80.dp

/** "המשך האזנה": the songs last played, newest first. */
private const val CONTINUE_RAIL_SIZE = 20
private val YT_DISC_CELL = 104.dp

/** The mini player's frame: 16:9, and at least the 200 YouTube asks for in each direction's shorter side. */
private val MINI_WIDTH = 356.dp
private val MINI_HEIGHT = 200.dp

/** The player screen's frame: a little under the canvas's 576 (`TvYtMusicPlayer`), so the queue beside it keeps room for its buttons. */
private val PLAYER_WIDTH = 512.dp
