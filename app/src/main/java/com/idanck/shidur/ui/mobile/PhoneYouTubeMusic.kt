package com.idanck.shidur.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.text.format.DateUtils
import coil.compose.AsyncImage
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.account.AccountState
import com.idanck.shidur.data.youtube.LibraryFilter
import com.idanck.shidur.data.youtube.MusicSearchKind
import com.idanck.shidur.data.youtube.MusicSearchResult
import com.idanck.shidur.data.youtube.MusicSection
import com.idanck.shidur.data.youtube.MusicTop
import com.idanck.shidur.data.youtube.PlayedSong
import com.idanck.shidur.data.youtube.YouTubeRepository
import com.idanck.shidur.data.youtube.YtMusicList
import com.idanck.shidur.data.youtube.YtProblem
import com.idanck.shidur.data.youtube.YtSong
import com.idanck.shidur.data.youtube.YtSubsState
import com.idanck.shidur.data.youtube.mergeLiked
import com.idanck.shidur.data.youtube.quickPicks
import com.idanck.shidur.data.youtube.topResult
import com.idanck.shidur.ui.common.StudioButton
import com.idanck.shidur.ui.common.StudioButtonSize
import com.idanck.shidur.ui.common.StudioButtonVariant
import com.idanck.shidur.ui.common.StudioChip
import com.idanck.shidur.ui.common.StudioField
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.common.YouTubePlayerController
import com.idanck.shidur.ui.common.YouTubePlayerView
import com.idanck.shidur.ui.common.claim
import com.idanck.shidur.ui.theme.StudioTheme
import com.idanck.shidur.ui.tv.MusicPage
import com.idanck.shidur.ui.tv.SongProgress
import com.idanck.shidur.ui.tv.YouTubeSession
import com.idanck.shidur.ui.tv.YtChannelDisc
import com.idanck.shidur.ui.tv.YtPage
import com.idanck.shidur.ui.tv.clock
import com.idanck.shidur.ui.tv.listMeta
import com.idanck.shidur.ui.tv.problemText
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The music tab on the phone (ADR-069 stage 4; the canvas's `PYtMusic…` boards): the switch at the
 * top of YouTube's tab, "בשבילך", the library, search, a list's page, the player screen and the
 * mini player. The player is YouTube's own, never covered and never smaller than 200×200: the mini
 * player is a card with a 200×200 frame above the bottom bar, and leaving YouTube stops the music.
 */

/** "וידאו | מוזיקה" (`PYtSwitch`): the YouTube tab as it was, or the music tab. */
@Composable
internal fun PhoneYtSwitch(session: YouTubeSession, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StudioChip(label = stringResource(R.string.ytm_mode_video), selected = !session.music, onClick = { session.music = false })
        StudioChip(label = stringResource(R.string.ytm_mode_music), selected = session.music, onClick = { session.music = true })
    }
}

/** The music tab: its part, or a page over it. */
@Composable
internal fun PhoneMusicTab(session: YouTubeSession, modifier: Modifier = Modifier) {
    val music = ServiceLocator.music
    val account by ServiceLocator.account.state.collectAsState()
    val signedIn = account is AccountState.SignedIn
    LaunchedEffect(Unit) { music.refreshChart() }
    LaunchedEffect(signedIn) { if (signedIn) music.refreshLibrary() }

    Box(modifier = modifier.fillMaxSize()) {
        when (val page = session.musicPage) {
            is MusicPage.Songs -> PhoneMusicList(session, page.id)
            MusicPage.Player -> PhoneMusicPlayer(session)
            null -> Column(modifier = Modifier.fillMaxSize()) {
                if (session.musicSection != MusicSection.SEARCH) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(MusicSection.HOME to R.string.ytm_section_home, MusicSection.LIBRARY to R.string.ytm_section_library).forEach { (section, label) ->
                            StudioChip(label = stringResource(label), selected = session.musicSection == section, onClick = { session.musicSection = section })
                        }
                    }
                }
                when (session.musicSection) {
                    MusicSection.HOME -> PhoneMusicHome(session, signedIn, Modifier.weight(1f))
                    MusicSection.LIBRARY -> PhoneMusicLibrary(session, signedIn, Modifier.weight(1f))
                    MusicSection.SEARCH -> PhoneMusicSearch(session, Modifier.weight(1f))
                }
            }
        }
    }
}

// ---- "בשבילך" ----

@Composable
private fun PhoneMusicHome(session: YouTubeSession, signedIn: Boolean, modifier: Modifier) {
    val music = ServiceLocator.music
    val repo = ServiceLocator.youtube
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val history by music.history.collectAsState()
    val likes by music.likes.collectAsState()
    val youtubeLiked by music.youtubeLiked.collectAsState()
    val chart by music.chart.collectAsState()
    val playlists by music.playlists.collectAsState()
    val albums by music.albums.collectAsState()
    val linked by music.linked.collectAsState()
    val subs by repo.subs.collectAsState()
    val artists by repo.subChannels.collectAsState()
    val state by session.player.state.collectAsState()
    val playingId = if (session.playingMusic) state.video?.id else null
    val liked = remember(likes, youtubeLiked) { mergeLiked(likes, youtubeLiked) }
    val quick = remember(history, liked) { quickPicks(history, liked) }
    val last = history.firstOrNull()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "reconnect") { ReconnectNote() }
        if (!session.playingMusic && last != null) {
            item(key = "continue") {
                ContinueCard(last) {
                    val songs = history.map { it.song }.distinctBy { it.id }
                    session.playSongs(songs, last.song, last.context, null)
                }
            }
        }
        if (quick.isNotEmpty()) {
            item(key = "quick-title") { ShelfTitle(stringResource(R.string.ytm_rail_quick), stringResource(R.string.ytm_rail_quick_caption)) }
            items(quick.take(QUICK_ROWS), key = { "quick-${it.id}" }) { song ->
                PhoneSongRow(song, song.artist, song.id == playingId) { session.playSongs(quick, song, null, null) }
            }
        }
        item(key = "lists") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShelfTitle(stringResource(R.string.ytm_rail_playlists), null)
                when {
                    playlists.isNotEmpty() -> CoverRow(playlists) { session.openMusic(MusicPage.Songs(it.id)) }
                    signedIn && !linked && subs is YtSubsState.Invite -> PhoneConnectCard(revoked = false, signedIn = true)
                    else -> EmptyLine()
                }
            }
        }
        if (artists.isNotEmpty()) {
            item(key = "artists") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShelfTitle(stringResource(R.string.ytm_rail_artists), null)
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(artists, key = { it.id }) { a ->
                            YtChannelDisc(label = a.title, thumb = a.thumb, live = false, onClick = { session.open(YtPage.Channel(a.id)) })
                        }
                    }
                }
            }
        }
        if (chart.isNotEmpty()) {
            item(key = "popular-title") { ShelfTitle(stringResource(R.string.ytm_rail_popular), stringResource(R.string.ytm_rail_popular_caption)) }
            items(chart.take(POPULAR_ROWS), key = { "popular-${it.id}" }) { song ->
                PhoneSongRow(song, song.artist, song.id == playingId) { session.playSongs(chart, song, null, null) }
            }
        }
        if (albums.isNotEmpty()) {
            item(key = "albums") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShelfTitle(stringResource(R.string.ytm_rail_albums), null)
                    CoverRow(albums.map { it.list }) { session.openMusic(MusicPage.Songs(it.id)) }
                }
            }
        }
        if (quick.isEmpty() && chart.isEmpty() && playlists.isEmpty()) {
            item(key = "loading") { Text(stringResource(R.string.ytm_loading), style = type.meta, color = studio.ink3, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
    }
}

/** "המשך האזנה": where the last song was left, on any device of the account. */
@Composable
private fun ContinueCard(last: PlayedSong, onPlay: () -> Unit) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(studio.card)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Art(last.song.thumb, 64.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.ytm_continue), style = type.caption, color = studio.ink2)
            Text(last.song.title, style = type.body.copy(fontWeight = FontWeight.Bold), color = studio.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(last.song.artist, style = type.caption, color = studio.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StudioButton(
            label = stringResource(R.string.ytm_continue_action),
            onClick = onPlay,
            icon = StudioIcons.Play,
            variant = StudioButtonVariant.PRIMARY,
            size = StudioButtonSize.MEDIUM,
        )
    }
}

/** A like stayed in Shidur because the connection cannot give likes: the way to connect again. */
@Composable
private fun ReconnectNote() {
    val music = ServiceLocator.music
    val repo = ServiceLocator.youtube
    val reconnect by music.reconnect.collectAsState()
    val connect = rememberPhoneYouTubeConnect { repo.refreshSubscriptions(signedIn = true, phone = true) }
    if (!reconnect) return
    Row(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.ytm_reconnect),
            style = StudioTheme.type.caption,
            color = StudioTheme.colors.ink2,
            modifier = Modifier.weight(1f),
        )
        StudioButton(
            label = stringResource(R.string.yt_account_reconnect),
            onClick = connect.start,
            icon = StudioIcons.Link,
            size = StudioButtonSize.MEDIUM,
            enabled = !connect.busy,
        )
    }
}

// ---- "ספרייה" ----

@Composable
private fun PhoneMusicLibrary(session: YouTubeSession, signedIn: Boolean, modifier: Modifier) {
    val music = ServiceLocator.music
    val repo = ServiceLocator.youtube
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val playlists by music.playlists.collectAsState()
    val likes by music.likes.collectAsState()
    val youtubeLiked by music.youtubeLiked.collectAsState()
    val albums by music.albums.collectAsState()
    val history by music.history.collectAsState()
    val linked by music.linked.collectAsState()
    val artists by repo.subChannels.collectAsState()
    val state by session.player.state.collectAsState()
    val playingId = if (session.playingMusic) state.video?.id else null
    val filter = session.libraryFilter
    val liked = remember(likes, youtubeLiked) { mergeLiked(likes, youtubeLiked) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LibraryFilter.values().forEach { f ->
                StudioChip(label = stringResource(filterLabel(f)), selected = filter == f, onClick = { session.libraryFilter = f })
            }
        }
        val caption = when (filter) {
            LibraryFilter.PLAYLISTS -> R.string.ytm_src_playlists
            LibraryFilter.LIKED -> if (linked) R.string.ytm_src_liked else R.string.ytm_src_liked_local
            LibraryFilter.ARTISTS -> R.string.ytm_src_artists
            LibraryFilter.ALBUMS -> R.string.ytm_src_albums
            LibraryFilter.HISTORY -> R.string.ytm_src_history
        }
        Text(stringResource(caption), style = type.caption, color = studio.ink2, maxLines = 2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (filter) {
                LibraryFilter.PLAYLISTS -> when {
                    playlists.isNotEmpty() -> items(playlists, key = { it.id }) { list ->
                        PhoneListRow(list.title, phoneListMeta(list), list.thumb, round = false) { session.openMusic(MusicPage.Songs(list.id)) }
                    }
                    signedIn && !linked -> item(key = "connect") { PhoneConnectCard(revoked = false, signedIn = true) }
                    else -> item(key = "empty") { EmptyLine() }
                }
                LibraryFilter.LIKED -> if (liked.isEmpty()) {
                    item(key = "empty") { EmptyLine() }
                } else {
                    items(liked, key = { it.id }) { song ->
                        PhoneSongRow(song, song.artist, song.id == playingId) { session.playSongs(liked, song, null, null) }
                    }
                }
                LibraryFilter.ARTISTS -> if (artists.isEmpty()) {
                    item(key = "empty") { EmptyLine() }
                } else {
                    items(artists, key = { it.id }) { a ->
                        PhoneListRow(a.title, "", a.thumb, round = true) { session.open(YtPage.Channel(a.id)) }
                    }
                }
                LibraryFilter.ALBUMS -> if (albums.isEmpty()) {
                    item(key = "empty") { EmptyLine() }
                } else {
                    items(albums, key = { it.list.id }) { saved ->
                        PhoneListRow(saved.list.title, phoneListMeta(saved.list), saved.list.thumb, round = false) { session.openMusic(MusicPage.Songs(saved.list.id)) }
                    }
                }
                LibraryFilter.HISTORY -> if (history.isEmpty()) {
                    item(key = "empty") { EmptyLine() }
                } else {
                    historyItems(history, playingId) { played ->
                        val songs = history.map { it.song }.distinctBy { it.id }
                        session.playSongs(songs, played.song, played.context, null)
                    }
                }
            }
        }
    }
}

private fun filterLabel(f: LibraryFilter): Int = when (f) {
    LibraryFilter.PLAYLISTS -> R.string.ytm_filter_playlists
    LibraryFilter.LIKED -> R.string.ytm_filter_liked
    LibraryFilter.ARTISTS -> R.string.ytm_filter_artists
    LibraryFilter.ALBUMS -> R.string.ytm_filter_albums
    LibraryFilter.HISTORY -> R.string.ytm_filter_history
}

/** The history by day, with the time and the device each song played on. */
private fun LazyListScope.historyItems(history: List<PlayedSong>, playingId: String?, onPlay: (PlayedSong) -> Unit) {
    val zone = ZoneId.systemDefault()
    val days = history.groupBy { Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate() }.toList()
    days.forEach { (day, played) ->
        item(key = "day-$day") { DayTitle(day, played.first().playedAt) }
        items(played, key = { it.docId }) { p ->
            val onTv = stringResource(R.string.ytm_on_tv)
            val onPhone = stringResource(R.string.ytm_on_phone)
            val meta = listOf(p.song.artist, TimeModel.ltr(TimeModel.formatClock(p.playedAt)), if (p.device == "phone") onPhone else onTv)
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            PhoneSongRow(p.song, meta, p.song.id == playingId) { onPlay(p) }
        }
    }
}

@Composable
private fun DayTitle(day: LocalDate, at: Long) {
    val context = LocalContext.current
    val now = LocalDate.now(ZoneId.systemDefault())
    val label = when (day) {
        now -> stringResource(R.string.ytm_today)
        now.minusDays(1) -> stringResource(R.string.ytm_yesterday)
        else -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
    }
    Text(
        label,
        style = StudioTheme.type.body.copy(fontWeight = FontWeight.Bold),
        color = StudioTheme.colors.ink2,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

// ---- A playlist's or an album's page ----

@Composable
private fun PhoneMusicList(session: YouTubeSession, id: String) {
    val music = ServiceLocator.music
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val albums by music.albums.collectAsState()
    val state by session.player.state.collectAsState()
    val playingId = if (session.playingMusic) state.video?.id else null
    var loaded by remember(id) { mutableStateOf<Pair<YtMusicList, List<YtSong>>?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) { music.list(id).onSuccess { loaded = it }.onFailure { failed = true } }

    val data = loaded
    if (data == null) {
        Text(
            stringResource(if (failed) R.string.ytm_list_failed else R.string.ytm_loading),
            style = type.meta,
            color = studio.ink2,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    val (list, songs) = data
    val saved = albums.any { it.list.id == list.id }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "head") {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Art(list.thumb, 132.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(if (list.album) R.string.ytm_album else R.string.ytm_playlist), style = type.caption, color = studio.ink2)
                        Text(list.title, style = type.titleSmall, color = studio.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(list.owner.takeIf { it.isNotEmpty() }, stringResource(R.string.ytm_song_count, songs.size)).joinToString(" · "),
                            style = type.meta,
                            color = studio.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioButton(
                        label = stringResource(R.string.ytm_play),
                        onClick = { session.playSongs(songs, null, list.id, list.title) },
                        icon = StudioIcons.Play,
                        variant = StudioButtonVariant.PRIMARY,
                        enabled = songs.isNotEmpty(),
                    )
                    StudioButton(
                        label = stringResource(R.string.ytm_shuffle),
                        onClick = { session.playSongs(songs, null, list.id, list.title, shuffle = true) },
                        icon = StudioIcons.Swap,
                        iconOnly = true,
                        enabled = songs.isNotEmpty(),
                    )
                    if (list.album) {
                        StudioButton(
                            label = stringResource(if (saved) R.string.ytm_saved_album else R.string.ytm_save_album),
                            onClick = { music.toggleAlbum(list) },
                            icon = StudioIcons.star(saved),
                            active = saved,
                        )
                    }
                }
                if (list.album) Text(stringResource(R.string.ytm_saved_where), style = type.caption, color = studio.ink3)
            }
        }
        if (songs.isEmpty()) {
            item(key = "empty") { EmptyLine() }
        } else {
            items(songs, key = { it.id }) { song ->
                PhoneSongRow(song, song.artist, song.id == playingId) { session.playSongs(songs, song, list.id, list.title) }
            }
        }
    }
}

// ---- The player screen ----

/**
 * The player screen (`PYtMusicPlayer`): YouTube's frame across the top at the full width, never
 * covered, then the song, its buttons and what comes next. Back returns to the tab, the song
 * carrying on in the mini player.
 */
@Composable
private fun PhoneMusicPlayer(session: YouTubeSession) {
    val music = ServiceLocator.music
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val state by session.player.state.collectAsState()
    val likes by music.likes.collectAsState()
    val youtubeLiked by music.youtubeLiked.collectAsState()
    val song = session.currentSong
    Column(modifier = Modifier.fillMaxSize()) {
        PlayerFrame(
            session,
            state,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (song != null) {
                item(key = "song") {
                    val liked = likes.any { it.song.id == song.id } || youtubeLiked.any { it.id == song.id }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(song.title, style = type.titleSmall, color = studio.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(song.artist.takeIf { it.isNotEmpty() }, session.queueTitle).joinToString(" · "),
                                    style = type.meta,
                                    color = studio.ink2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            StudioButton(
                                label = stringResource(if (liked) R.string.ytm_liked else R.string.ytm_like),
                                onClick = { music.toggleLike(song) },
                                icon = StudioIcons.star(liked),
                                iconOnly = true,
                                active = liked,
                            )
                        }
                        SongProgress(state)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            StudioButton(
                                label = stringResource(R.string.ytm_shuffle),
                                onClick = { session.shuffleQueue() },
                                icon = StudioIcons.Swap,
                                iconOnly = true,
                                variant = StudioButtonVariant.GHOST,
                            )
                            StudioButton(
                                label = stringResource(R.string.ytm_prev),
                                onClick = { session.previousSong() },
                                icon = StudioIcons.SkipBack,
                                iconOnly = true,
                            )
                            PlayPause(session, state, large = true)
                            StudioButton(
                                label = stringResource(R.string.ytm_next),
                                onClick = { session.nextSong() },
                                icon = StudioIcons.SkipForward,
                                iconOnly = true,
                            )
                            StudioButton(
                                label = stringResource(R.string.ytm_repeat),
                                onClick = { session.repeat = !session.repeat },
                                icon = StudioIcons.Restart,
                                iconOnly = true,
                                variant = StudioButtonVariant.GHOST,
                                active = session.repeat,
                            )
                        }
                        ReconnectNote()
                        Text(stringResource(R.string.ytm_queue), style = type.h1, color = studio.ink, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                val upNext = session.queue.dropWhile { it.id != song.id }.drop(1)
                if (upNext.isEmpty()) {
                    item(key = "end") { Text(stringResource(R.string.ytm_queue_end), style = type.meta, color = studio.ink3) }
                } else {
                    items(upNext, key = { "next-${it.id}" }) { next ->
                        PhoneSongRow(next, next.artist, playing = false, horizontalPadding = 0.dp) { session.playSong(next) }
                    }
                }
            } else {
                item(key = "nothing") { Text(stringResource(R.string.ytm_nothing_playing), style = type.meta, color = studio.ink2) }
            }
        }
    }
}

/** Play or pause, through the player's API. */
@Composable
private fun PlayPause(session: YouTubeSession, state: YouTubePlayerController.State, large: Boolean) {
    val playing = state.phase == YouTubePlayerController.Phase.PLAYING ||
        state.phase == YouTubePlayerController.Phase.BUFFERING ||
        state.phase == YouTubePlayerController.Phase.LOADING
    StudioButton(
        label = stringResource(if (playing) R.string.ytm_pause else R.string.ytm_play),
        onClick = { session.player.toggle() },
        icon = if (playing) StudioIcons.Pause else StudioIcons.Play,
        iconOnly = true,
        variant = StudioButtonVariant.PRIMARY,
        size = if (large) StudioButtonSize.LARGE else StudioButtonSize.MEDIUM,
    )
}

/**
 * YouTube's player in [modifier]'s box, or what it says when it cannot play there. There is one
 * player: it moves into whichever frame is composed, the mini player's or the player screen's.
 */
@Composable
private fun PlayerFrame(session: YouTubeSession, state: YouTubePlayerController.State, modifier: Modifier) {
    val studio = StudioTheme.colors
    val playable = state.video != null && !state.notEmbeddable && !state.unavailable && !state.liveEnded
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        YouTubePlayerView(controller = session.player, visible = playable, modifier = Modifier.fillMaxSize())
        if (!playable) {
            val message = when {
                state.notEmbeddable -> stringResource(R.string.yt_not_embeddable)
                state.unavailable || state.error != null -> stringResource(R.string.yt_video_gone)
                state.video == null -> stringResource(R.string.ytm_nothing_playing)
                else -> null
            }
            message?.let {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(StudioIcons.Music, contentDescription = null, tint = studio.ink3, modifier = Modifier.size(28.dp))
                    Text(it, style = StudioTheme.type.caption, color = studio.ink2, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

// ---- The mini player ----

/**
 * The mini player (`PYtMusicMini`), above the bottom bar on YouTube's tab while a song is loaded:
 * the player itself in a 200×200 frame at the start edge, in the amber frame of the radio and
 * podcast mini players, with the song and its buttons beside it. A tap opens the player screen.
 */
@Composable
internal fun PhoneMusicMini(session: YouTubeSession, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val state by session.player.state.collectAsState()
    val song = session.currentSong
    val shape = RoundedCornerShape(20.dp)
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MINI_CARD_HEIGHT)
                .clip(shape)
                .background(studio.surface)
                .border(1.5.dp, studio.current.copy(alpha = 0.7f), shape)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlayerFrame(session, state, Modifier.size(MINI_FRAME).clip(RoundedCornerShape(16.dp)))
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.ytm_now_playing), style = type.caption.copy(fontWeight = FontWeight.SemiBold), color = studio.current)
                Text(song?.title.orEmpty(), style = type.body.copy(fontWeight = FontWeight.Bold), color = studio.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song?.artist.orEmpty(), style = type.caption, color = studio.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PlayPause(session, state, large = false)
                    StudioButton(
                        label = stringResource(R.string.ytm_next),
                        onClick = { session.nextSong() },
                        icon = StudioIcons.SkipForward,
                        iconOnly = true,
                        variant = StudioButtonVariant.GHOST,
                        size = StudioButtonSize.MEDIUM,
                    )
                    StudioButton(
                        label = stringResource(R.string.ytm_open_player),
                        onClick = onOpen,
                        icon = StudioIcons.ChevronUp,
                        iconOnly = true,
                        variant = StudioButtonVariant.GHOST,
                        size = StudioButtonSize.MEDIUM,
                    )
                }
            }
        }
    }
}

// ---- Search ----

/**
 * Search (`PYtMusicSearch`), from the top bar's magnifier while the music tab is up: the system
 * keyboard, the kind chips, and the results once typing pauses — a top result, songs, lists and
 * artists — out of the same searches a day as the video tab's.
 */
@Composable
private fun PhoneMusicSearch(session: YouTubeSession, modifier: Modifier) {
    val music = ServiceLocator.music
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val recent by music.recentSearches.collectAsState()
    val state by session.player.state.collectAsState()
    val playingId = if (session.playingMusic) state.video?.id else null
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<YtProblem?>(null) }
    val query = session.musicQuery
    val kind = session.musicSearchKind
    val typed = query.trim().length >= MIN_QUERY
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.claim { false } }
    LaunchedEffect(query, kind) {
        val q = query.trim()
        if (q.length < MIN_QUERY) {
            problem = null
            return@LaunchedEffect
        }
        if (session.musicSearchedFor == (q to kind) && session.musicResult != null) return@LaunchedEffect
        delay(MUSIC_SEARCH_PAUSE_MS)
        busy = true
        music.search(q, kind)
            .onSuccess {
                session.musicResult = it
                session.musicSearchedFor = q to kind
                problem = null
                music.noteSearch(q)
            }
            .onFailure { problem = (it as? YouTubeRepository.YtException)?.problem ?: YtProblem.OTHER }
        busy = false
    }

    val result = session.musicResult?.takeIf { typed }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "field") {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioField(
                    value = query,
                    onValueChange = { session.musicQuery = it.take(MAX_QUERY) },
                    placeholder = stringResource(R.string.ytm_search_field),
                    icon = StudioIcons.Search,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    focusRequester = field,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SEARCH_KINDS.forEach { (k, label) ->
                        StudioChip(label = stringResource(label), selected = kind == k, onClick = { session.musicSearchKind = k })
                    }
                }
                val note = when {
                    !typed -> stringResource(R.string.yt_search_hint)
                    problem != null -> problemText(problem ?: YtProblem.OTHER)
                    busy && result == null -> stringResource(R.string.yt_search_busy)
                    result != null && result.isEmpty -> stringResource(R.string.yt_search_none)
                    else -> null
                }
                note?.let { Text(it, style = type.meta, color = studio.ink3) }
            }
        }
        if (!typed && recent.isNotEmpty()) {
            item(key = "recent") {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ytm_search_recent), style = type.h1, color = studio.ink)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recent.forEach { q -> StudioChip(label = q, selected = false, onClick = { session.musicQuery = q }) }
                        StudioButton(
                            label = stringResource(R.string.ytm_search_clear),
                            onClick = { music.clearRecentSearches() },
                            variant = StudioButtonVariant.GHOST,
                            size = StudioButtonSize.MEDIUM,
                        )
                    }
                }
            }
        }
        if (result != null && !result.isEmpty) searchItems(session, result, query, playingId)
    }
}

/** The kinds a music search asks for, in the canvas's order. */
private val SEARCH_KINDS = listOf(
    MusicSearchKind.ALL to R.string.ytm_search_all,
    MusicSearchKind.SONGS to R.string.ytm_search_songs,
    MusicSearchKind.ALBUMS to R.string.ytm_search_albums,
    MusicSearchKind.ARTISTS to R.string.ytm_search_artists,
    MusicSearchKind.PLAYLISTS to R.string.ytm_search_playlists,
)

private fun LazyListScope.searchItems(session: YouTubeSession, result: MusicSearchResult, query: String, playingId: String?) {
    val play: (YtSong) -> Unit = { song ->
        if (session.playingMusic && song.id == playingId) session.openMusic(MusicPage.Player)
        else session.playSongs(result.songs, song, null, query.trim())
    }
    topResult(result, query)?.let { top ->
        item(key = "top") { TopResult(top, onPlay = play, onOpenList = { session.openMusic(MusicPage.Songs(it.id)) }, onOpenArtist = { session.open(YtPage.Channel(it)) }) }
    }
    if (result.songs.isNotEmpty()) {
        item(key = "songs-title") { ShelfTitle(searchTitle(R.string.ytm_search_songs), null) }
        items(result.songs, key = { "song-${it.id}" }) { song -> PhoneSongRow(song, song.artist, song.id == playingId) { play(song) } }
    }
    if (result.lists.isNotEmpty()) {
        item(key = "lists-title") { ShelfTitle(searchTitle(if (result.lists.all { it.album }) R.string.ytm_search_albums else R.string.ytm_search_lists), null) }
        items(result.lists, key = { "list-${it.id}" }) { list ->
            PhoneListRow(list.title, phoneListMeta(list), list.thumb, round = false) { session.openMusic(MusicPage.Songs(list.id)) }
        }
    }
    if (result.artists.isNotEmpty()) {
        item(key = "artists-title") { ShelfTitle(searchTitle(R.string.ytm_search_artists), null) }
        items(result.artists, key = { "artist-${it.id}" }) { a ->
            PhoneListRow(a.title, "", a.thumb, round = true) { session.open(YtPage.Channel(a.id)) }
        }
    }
}

@Composable
private fun searchTitle(res: Int): String = stringResource(res)

@Composable
private fun TopResult(top: MusicTop, onPlay: (YtSong) -> Unit, onOpenList: (YtMusicList) -> Unit, onOpenArtist: (String) -> Unit) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val songWord = stringResource(R.string.ytm_search_song)
    val artistMeta = stringResource(R.string.ytm_search_artist_meta)
    val listLine = (top as? MusicTop.OfList)?.let { phoneListMeta(it.list) }
    val text = when (top) {
        is MusicTop.OfArtist -> TopText(top.artist.title, artistMeta, top.artist.thumb, true)
        is MusicTop.OfSong -> TopText(top.song.title, listOf(songWord, top.song.artist).filter { it.isNotEmpty() }.joinToString(" · "), top.song.thumb, false)
        is MusicTop.OfList -> TopText(top.list.title, listLine.orEmpty(), top.list.thumb, false)
    }
    val onClick: () -> Unit = when (top) {
        is MusicTop.OfArtist -> ({ onOpenArtist(top.artist.id) })
        is MusicTop.OfSong -> ({ onPlay(top.song) })
        is MusicTop.OfList -> ({ onOpenList(top.list) })
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(studio.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Art(text.thumb, 64.dp, round = text.round)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.ytm_search_top), style = type.caption, color = studio.ink2)
            Text(text.title, style = type.body.copy(fontWeight = FontWeight.ExtraBold), color = studio.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text.meta, style = type.caption, color = studio.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StudioButton(
            label = stringResource(
                when (top) {
                    is MusicTop.OfArtist -> R.string.ytm_search_to_artist
                    is MusicTop.OfSong -> R.string.ytm_play
                    is MusicTop.OfList -> R.string.ytm_search_open
                },
            ),
            onClick = onClick,
            icon = if (top is MusicTop.OfSong) StudioIcons.Play else StudioIcons.ChevronEnd,
            iconOnly = true,
            variant = if (top is MusicTop.OfSong) StudioButtonVariant.PRIMARY else StudioButtonVariant.SECONDARY,
            size = StudioButtonSize.MEDIUM,
        )
    }
}

private data class TopText(val title: String, val meta: String, val thumb: String?, val round: Boolean)

// ---- Rows, covers and headings ----

/** A shelf's heading, with a caption beside it. */
@Composable
private fun ShelfTitle(title: String, caption: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = StudioTheme.type.h1, color = StudioTheme.colors.ink)
        caption?.let { Text(it, style = StudioTheme.type.caption, color = StudioTheme.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun EmptyLine() {
    Text(
        stringResource(R.string.ytm_empty_list),
        style = StudioTheme.type.meta,
        color = StudioTheme.colors.ink3,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** A song (`PSongRow`): its art, title, a line under it and its length; the one playing is marked. */
@Composable
private fun PhoneSongRow(song: YtSong, meta: String, playing: Boolean, horizontalPadding: Dp = 16.dp, onClick: () -> Unit) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .clip(RoundedCornerShape(12.dp))
            .background(if (playing) studio.current.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .height(60.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Art(song.thumb, 48.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, style = type.body.copy(fontWeight = FontWeight.SemiBold), color = if (playing) studio.current else studio.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, style = type.caption, color = studio.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        song.duration?.let { Text(TimeModel.ltr(clock(it)), style = type.caption, color = studio.ink3) }
    }
}

/** A playlist, an album or an artist as a row; an artist's picture is round. */
@Composable
private fun PhoneListRow(title: String, meta: String, thumb: String?, round: Boolean, onClick: () -> Unit) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .height(64.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Art(thumb, 52.dp, round = round)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = type.body.copy(fontWeight = FontWeight.SemiBold), color = studio.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, style = type.caption, color = studio.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Playlists or albums as covers, in a row that scrolls. */
@Composable
private fun CoverRow(lists: List<YtMusicList>, onOpen: (YtMusicList) -> Unit) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(lists, key = { it.id }) { list ->
            Column(
                modifier = Modifier.width(COVER).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onOpen(list) },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Art(list.thumb, COVER)
                Text(list.title, style = type.caption.copy(fontWeight = FontWeight.SemiBold), color = studio.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Art(thumb: String?, size: Dp, round: Boolean = false) {
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    Box(Modifier.size(size).clip(shape).background(StudioTheme.colors.surface), contentAlignment = Alignment.Center) {
        if (thumb.isNullOrBlank()) {
            Icon(StudioIcons.Music, contentDescription = null, tint = StudioTheme.colors.ink3, modifier = Modifier.size(size * 0.4f))
        } else {
            AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun phoneListMeta(list: YtMusicList): String = listMeta(list)

private const val QUICK_ROWS = 5
private const val POPULAR_ROWS = 10
private const val MIN_QUERY = 2
private const val MAX_QUERY = 100
private const val MUSIC_SEARCH_PAUSE_MS = 900L
private val COVER = 104.dp
private val MINI_FRAME = 200.dp
/** The frame and 4dp of the card's edge above and below it: the canvas's 212. */
private val MINI_CARD_HEIGHT = 208.dp
