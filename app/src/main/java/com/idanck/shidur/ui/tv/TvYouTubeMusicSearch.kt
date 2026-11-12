package com.idanck.shidur.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.data.youtube.MusicSearchKind
import com.idanck.shidur.data.youtube.MusicSearchResult
import com.idanck.shidur.data.youtube.MusicTop
import com.idanck.shidur.data.youtube.YouTubeRepository
import com.idanck.shidur.data.youtube.YtMusicList
import com.idanck.shidur.data.youtube.YtProblem
import com.idanck.shidur.data.youtube.YtSong
import com.idanck.shidur.data.youtube.topResult
import com.idanck.shidur.ui.common.StudioButton
import com.idanck.shidur.ui.common.StudioButtonSize
import com.idanck.shidur.ui.common.StudioButtonVariant
import com.idanck.shidur.ui.common.StudioChip
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.common.YouTubePlayerController
import com.idanck.shidur.ui.theme.StudioTheme
import kotlinx.coroutines.delay

/*
 * The music tab's search on the television (ADR-069 stage 3; the canvas's `TvYtMusicSearch`).
 */

/**
 * Search inside the music tab: the video search's keyboard at the start edge, the results beside
 * it — the kind chips, a top result, then songs, albums or playlists, and artists. The results fill
 * in once typing pauses, out of the same searches a day as the video tab's; the server keeps every
 * answer for six hours. While something is loaded in the player its frame sits above the results
 * (356×200dp, the mini player's), never over them; the canvas's strip at the foot of the screen is
 * below YouTube's 200×200.
 */
@Composable
internal fun MusicSearchPage(session: YouTubeSession) {
    val music = ServiceLocator.music
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val gutter = sideMenuInset()
    val recent by music.recentSearches.collectAsState()
    val playerState by session.player.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<YtProblem?>(null) }
    val query = session.musicQuery
    val kind = session.musicSearchKind
    val typed = query.trim().length >= MIN_QUERY

    LaunchedEffect(query, kind) {
        val q = query.trim()
        if (q.length < MIN_QUERY) {
            problem = null
            return@LaunchedEffect
        }
        // Coming back from an album or an artist opened from here: what is on screen is the answer.
        if (session.musicSearchedFor == (q to kind) && session.musicResult != null) return@LaunchedEffect
        delay(SEARCH_PAUSE_MS)
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

    Row(
        modifier = Modifier.fillMaxSize().padding(start = gutter, end = YT_SAFE_X, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        YouTubeKeyboard(
            text = query,
            onText = { session.musicQuery = it.take(MAX_QUERY) },
            modifier = Modifier.width(KEYBOARD_WIDTH),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.focusGroup()) {
                SEARCH_KINDS.forEach { (k, label) ->
                    StudioChip(label = stringResource(label), selected = kind == k, onClick = { session.musicSearchKind = k })
                }
            }
            if (playerState.video != null) SearchNowPlaying(session, playerState)

            val result = session.musicResult?.takeIf { typed }
            val note = when {
                !typed -> stringResource(R.string.yt_search_hint)
                problem != null -> problemText(problem ?: YtProblem.OTHER)
                busy && result == null -> stringResource(R.string.yt_search_busy)
                result != null && result.isEmpty -> stringResource(R.string.yt_search_none)
                else -> null
            }
            note?.let { Text(it, style = type.meta, color = studio.ink3) }
            if (!typed && recent.isNotEmpty()) {
                RecentSearches(recent, onPick = { session.musicQuery = it }, onClear = { music.clearRecentSearches() })
            }
            if (result != null && !result.isEmpty) {
                SearchResults(session, result, query, playingId = if (session.playingMusic) playerState.video?.id else null, modifier = Modifier.weight(1f))
            }
        }
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

/** What plays, above the results: the player's frame and, beside it, the song and its buttons. */
@Composable
private fun SearchNowPlaying(session: YouTubeSession, state: YouTubePlayerController.State) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val song = session.currentSong
    Row(modifier = Modifier.fillMaxWidth().height(MINI_HEIGHT), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        MusicFrame(session = session, state = state, modifier = Modifier.width(MINI_WIDTH).height(MINI_HEIGHT))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                song?.title ?: state.video?.title.orEmpty(),
                style = type.body.copy(fontWeight = FontWeight.Bold),
                color = studio.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            song?.let { Text(it.artist, style = type.meta, color = studio.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            SongProgress(state)
            if (song != null) {
                Row(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MusicTransport(session, state, focus = null)
                }
                StudioButton(
                    label = stringResource(R.string.ytm_open_player),
                    onClick = { session.openMusic(MusicPage.Player) },
                    icon = StudioIcons.Fullscreen,
                    iconOnly = true,
                    size = StudioButtonSize.MEDIUM,
                )
            }
        }
    }
}

/** Before anything is typed: what was searched for on this device, to search again with one press. */
@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ytm_search_recent), style = type.h1, color = studio.ink)
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(recent, key = { it }) { q -> StudioChip(label = q, selected = false, onClick = { onPick(q) }) }
            item(key = "clear") {
                StudioButton(
                    label = stringResource(R.string.ytm_search_clear),
                    onClick = onClear,
                    variant = StudioButtonVariant.GHOST,
                    size = StudioButtonSize.MEDIUM,
                )
            }
        }
    }
}

/** The answer: the top result, then songs, lists and artists, each where it has any. */
@Composable
private fun SearchResults(session: YouTubeSession, result: MusicSearchResult, query: String, playingId: String?, modifier: Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val top = remember(result, query) { topResult(result, query) }
    val play: (YtSong) -> Unit = { song ->
        if (session.playingMusic && song.id == playingId) session.openMusic(MusicPage.Player)
        else session.playSongs(result.songs, song, null, query.trim())
    }
    val open: (YtMusicList) -> Unit = { session.openMusic(MusicPage.Songs(it.id)) }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        top?.let { t -> item(key = "top") { TopResultCard(t, onPlay = play, onOpenList = open, onOpenArtist = { session.open(YtPage.Channel(it)) }) } }
        if (result.songs.isNotEmpty()) {
            item(key = "songs-title") { Text(stringResource(R.string.ytm_search_songs), style = type.h1, color = studio.ink, modifier = Modifier.padding(top = 6.dp)) }
            items(result.songs, key = { "song-${it.id}" }) { song ->
                MusicSongRow(song = song, meta = song.artist, playing = song.id == playingId, onClick = { play(song) })
            }
        }
        if (result.lists.isNotEmpty()) {
            val albumsOnly = result.lists.all { it.album }
            item(key = "lists-title") {
                Text(
                    stringResource(if (albumsOnly) R.string.ytm_search_albums else R.string.ytm_search_lists),
                    style = type.h1,
                    color = studio.ink,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item(key = "lists") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().focusGroup(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(result.lists, key = { it.id }) { list ->
                        MusicCard(title = list.title, meta = listMeta(list), thumb = list.thumb, playing = false, onClick = { open(list) })
                    }
                }
            }
        }
        if (result.artists.isNotEmpty()) {
            item(key = "artists-title") { Text(stringResource(R.string.ytm_search_artists), style = type.h1, color = studio.ink, modifier = Modifier.padding(top = 6.dp)) }
            item(key = "artists") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().focusGroup(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(result.artists, key = { it.id }) { artist ->
                        YtChannelDisc(label = artist.title, thumb = artist.thumb, live = false, onClick = { session.open(YtPage.Channel(artist.id)) })
                    }
                }
            }
        }
    }
}

/** The top result on a card of its own: an artist, a song or a list, and the one thing to do with it. */
@Composable
private fun TopResultCard(top: MusicTop, onPlay: (YtSong) -> Unit, onOpenList: (YtMusicList) -> Unit, onOpenArtist: (String) -> Unit) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val songWord = stringResource(R.string.ytm_search_song)
    val artistMeta = stringResource(R.string.ytm_search_artist_meta)
    val listLine = (top as? MusicTop.OfList)?.let { listMeta(it.list) }
    // Its title, the line under it, its picture, and whether the picture is round (an artist).
    val (title, meta, thumb, round) = when (top) {
        is MusicTop.OfArtist -> CardText(top.artist.title, artistMeta, top.artist.thumb, true)
        is MusicTop.OfSong -> CardText(top.song.title, listOf(songWord, top.song.artist).filter { it.isNotEmpty() }.joinToString(" · "), top.song.thumb, false)
        is MusicTop.OfList -> CardText(top.list.title, listLine.orEmpty(), top.list.thumb, false)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(studio.card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
        Box(Modifier.size(56.dp).clip(shape).background(studio.surface)) {
            if (!thumb.isNullOrBlank()) {
                AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.ytm_search_top), style = type.caption, color = studio.ink2)
            Text(title, style = type.body.copy(fontWeight = FontWeight.ExtraBold), color = studio.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = type.meta, color = studio.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (top) {
            is MusicTop.OfArtist -> StudioButton(
                label = stringResource(R.string.ytm_search_to_artist),
                onClick = { onOpenArtist(top.artist.id) },
                icon = StudioIcons.ChevronEnd,
                size = StudioButtonSize.MEDIUM,
            )
            is MusicTop.OfSong -> StudioButton(
                label = stringResource(R.string.ytm_play),
                onClick = { onPlay(top.song) },
                icon = StudioIcons.Play,
                variant = StudioButtonVariant.PRIMARY,
                size = StudioButtonSize.MEDIUM,
            )
            is MusicTop.OfList -> StudioButton(
                label = stringResource(R.string.ytm_search_open),
                onClick = { onOpenList(top.list) },
                icon = StudioIcons.ChevronEnd,
                size = StudioButtonSize.MEDIUM,
            )
        }
    }
}

private data class CardText(val title: String, val meta: String, val thumb: String?, val round: Boolean)
