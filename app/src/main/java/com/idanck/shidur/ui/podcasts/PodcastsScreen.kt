package com.idanck.shidur.ui.podcasts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.AppError
import com.idanck.shidur.core.Outcome
import com.idanck.shidur.data.podcast.DirectoryProblem
import com.idanck.shidur.data.podcast.DirectoryResult
import com.idanck.shidur.data.podcast.EpisodeFilter
import com.idanck.shidur.data.podcast.FoundPodcast
import com.idanck.shidur.data.podcast.Podcast
import com.idanck.shidur.data.podcast.PodcastEpisode
import com.idanck.shidur.ui.AppViewModel
import com.idanck.shidur.ui.NowPlayingInfo
import com.idanck.shidur.ui.UiMessage
import com.idanck.shidur.ui.common.EmptyState
import com.idanck.shidur.ui.common.ListRow
import com.idanck.shidur.ui.common.RowTrail
import com.idanck.shidur.ui.common.StudioButton
import com.idanck.shidur.ui.common.StudioButtonSize
import com.idanck.shidur.ui.common.StudioButtonVariant
import com.idanck.shidur.ui.common.StudioChip
import com.idanck.shidur.ui.common.StudioField
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.common.StudioProgress
import com.idanck.shidur.ui.common.StudioSheet
import com.idanck.shidur.ui.theme.StudioTheme
import kotlinx.coroutines.launch

/**
 * Podcasts (ADR-067), one screen for both shells: the television's side-menu entry after YouTube,
 * and the phone's tab. Its parts are chips at the top — `בשבילך`, the library, favourites, history,
 * discover and add — and from them a show's page and the player open over it, each a step Back
 * undoes ([PodcastNav]).
 *
 * Drawn with the shared controls — rows, chips, buttons, fields, sheets — which take each shell's
 * own focus and touch treatment, so the canvas's two pages (`tv-podcasts`, `phone-podcasts`) are
 * one composition at two scales.
 */
@Composable
fun PodcastsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val nav = vm.podcastNav
    val page by nav.page.collectAsState()
    var options by remember { mutableStateOf<PodcastEpisode?>(null) }
    var settingsFor by remember { mutableStateOf<Podcast?>(null) }
    val play: (PodcastEpisode, Boolean) -> Unit = { episode, fromStart ->
        vm.playPodcast(episode, fromStart)
        nav.open(PodcastPage.Player)
    }
    Box(modifier = modifier) {
        when (val p = page) {
            is PodcastPage.Home -> PodcastHome(
                vm = vm,
                tab = p.tab,
                onTab = { nav.open(PodcastPage.Home(it)) },
                onPlay = play,
                onOptions = { options = it },
                onOpenShow = { nav.open(PodcastPage.Show(it)) },
            )
            is PodcastPage.Show -> PodcastShowPage(
                vm = vm,
                podcastId = p.podcastId,
                onPlay = play,
                onOptions = { options = it },
                onSettings = { settingsFor = it },
            )
            PodcastPage.Player -> PodcastPlayerPage(
                vm = vm,
                onOpenShow = { nav.open(PodcastPage.Show(it)) },
                onOptions = { options = it },
                onPlay = play,
            )
        }
    }
    options?.let { episode ->
        EpisodeOptions(
            vm = vm,
            episode = episode,
            onPlay = { fromStart -> options = null; play(episode, fromStart) },
            onOpenShow = { options = null; nav.open(PodcastPage.Show(episode.podcastId)) },
            onDismiss = { options = null },
        )
    }
    settingsFor?.let { podcast ->
        ShowSettings(
            podcast = podcast,
            onLeft = { settingsFor = null; nav.back() },
            onDismiss = { settingsFor = null },
        )
    }
}

// ---------------------------------------------------------------------- home

@Composable
private fun PodcastHome(
    vm: AppViewModel,
    tab: PodcastTab,
    onTab: (PodcastTab) -> Unit,
    onPlay: (PodcastEpisode, Boolean) -> Unit,
    onOptions: (PodcastEpisode) -> Unit,
    onOpenShow: (Long) -> Unit,
) {
    val tv = StudioTheme.television
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = if (tv) 0.dp else 16.dp, vertical = if (tv) 6.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 8.dp),
        ) {
            listOf(
                PodcastTab.FOR_YOU to R.string.pod_tab_for_you,
                PodcastTab.LIBRARY to R.string.pod_tab_library,
                PodcastTab.FAVOURITES to R.string.pod_tab_favourites,
                PodcastTab.HISTORY to R.string.pod_tab_history,
                PodcastTab.DISCOVER to R.string.pod_tab_discover,
            ).forEach { (t, label) ->
                StudioChip(label = stringResource(label), selected = t == tab, onClick = { onTab(t) })
            }
        }
        Spacer(modifier = Modifier.height(if (tv) 8.dp else 8.dp))
        when (tab) {
            PodcastTab.FOR_YOU -> ForYou(vm, onPlay, onOptions, onOpenShow, onDiscover = { onTab(PodcastTab.DISCOVER) })
            PodcastTab.LIBRARY -> Library(onOpenShow, onDiscover = { onTab(PodcastTab.DISCOVER) })
            PodcastTab.FAVOURITES -> EpisodeList(vm, caption = stringResource(R.string.pod_favourites_caption), load = { ServiceLocator.podcasts.favourites() }, onPlay = onPlay, onOptions = onOptions)
            PodcastTab.HISTORY -> History(vm, onPlay, onOptions)
            PodcastTab.DISCOVER -> Discover(vm, onOpenShow)
        }
    }
}

/** What playing now is, as an episode key, so its row can be lit. */
@Composable
private fun playingKey(vm: AppViewModel): String? {
    val state by vm.state.collectAsState()
    return (state.nowPlaying as? NowPlayingInfo.Podcast)?.episode?.let { it.feedUrl + "\u0000" + it.guid }
}

private fun PodcastEpisode.keyString() = feedUrl + "\u0000" + guid

@Composable
private fun ForYou(
    vm: AppViewModel,
    onPlay: (PodcastEpisode, Boolean) -> Unit,
    onOptions: (PodcastEpisode) -> Unit,
    onOpenShow: (Long) -> Unit,
    onDiscover: () -> Unit,
) {
    val repo = ServiceLocator.podcasts
    val changes by repo.changes.collectAsState()
    val data by produceState(initialValue = ForYouData(), changes) {
        value = ForYouData(repo.inProgress(), repo.newEpisodes(), repo.library(), loaded = true)
    }
    val tv = StudioTheme.television
    val playing = playingKey(vm)
    if (data.loaded && data.library.isEmpty() && data.inProgress.isEmpty()) {
        EmptyState(
            text = stringResource(R.string.pod_empty_library_body),
            title = stringResource(R.string.pod_empty_library),
            icon = StudioIcons.Podcast,
            action = stringResource(R.string.pod_tab_discover),
            onAction = onDiscover,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (tv) 0.dp else 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 4.dp else 2.dp),
    ) {
        data.inProgress.firstOrNull()?.let { hero ->
            item(key = "hero") { ContinueHero(hero, onPlay = onPlay, onOpenShow = onOpenShow) }
        }
        if (data.fresh.isNotEmpty()) {
            item(key = "new-h") { Heading(stringResource(R.string.pod_new_episodes)) }
            items(data.fresh.take(12), key = { "n" + it.id }) { e ->
                EpisodeRow(e, onPlay = { onPlay(e, false) }, onOptions = { onOptions(e) }, playing = e.keyString() == playing)
            }
        }
        val rest = data.inProgress.drop(1)
        if (rest.isNotEmpty()) {
            item(key = "cont-h") { Heading(stringResource(R.string.pod_continue)) }
            items(rest, key = { "c" + it.id }) { e ->
                EpisodeRow(e, onPlay = { onPlay(e, false) }, onOptions = { onOptions(e) }, playing = e.keyString() == playing)
            }
        }
        if (data.library.isNotEmpty()) {
            item(key = "lib-h") { Heading(stringResource(R.string.pod_tab_library)) }
            item(key = "lib") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(if (tv) 18.dp else 12.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(data.library, key = { it.id }) { p ->
                        PodcastTile(
                            title = p.title,
                            caption = p.author,
                            imageUrl = p.imageUrl,
                            size = if (tv) 96.dp else 80.dp,
                            badge = p.newCount,
                            onClick = { onOpenShow(p.id) },
                        )
                    }
                }
            }
        }
    }
}

private data class ForYouData(
    val inProgress: List<PodcastEpisode> = emptyList(),
    val fresh: List<PodcastEpisode> = emptyList(),
    val library: List<Podcast> = emptyList(),
    val loaded: Boolean = false,
)

/** `TvPodcasts`'s top: the episode the viewer stopped in, and "המשך מ־" to carry on. */
@Composable
private fun ContinueHero(episode: PodcastEpisode, onPlay: (PodcastEpisode, Boolean) -> Unit, onOpenShow: (Long) -> Unit) {
    val tv = StudioTheme.television
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = if (tv) 6.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tv) 24.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PodcastCover(title = episode.podcastTitle, imageUrl = episode.coverUrl, size = if (tv) 150.dp else 84.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 4.dp)) {
            Text(
                text = stringResource(R.string.pod_continue) + " · " + episode.podcastTitle,
                style = type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = studio.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = episode.title, style = if (tv) type.titleSmall else type.h1, color = studio.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val length = episode.lengthMs
            if (length > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioProgress(fraction = episode.mark.positionMs.toFloat() / length, modifier = Modifier.weight(1f))
                    episode.remainingMs?.let { Text(text = stringResource(R.string.pod_left, lengthText(it)), style = type.caption, color = studio.ink2) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(if (tv) 10.dp else 8.dp)) {
                StudioButton(
                    label = stringResource(R.string.pod_resume_from, com.idanck.shidur.core.TimeModel.ltr(clock(episode.mark.positionMs))),
                    onClick = { onPlay(episode, false) },
                    icon = StudioIcons.Play,
                    variant = StudioButtonVariant.PRIMARY,
                    size = StudioButtonSize.MEDIUM,
                )
                if (tv) {
                    StudioButton(label = stringResource(R.string.pod_from_start), onClick = { onPlay(episode, true) }, icon = StudioIcons.Restart, size = StudioButtonSize.MEDIUM)
                }
                StudioButton(
                    label = stringResource(R.string.pod_open_show),
                    onClick = { onOpenShow(episode.podcastId) },
                    icon = StudioIcons.Podcast,
                    iconOnly = !tv,
                    size = StudioButtonSize.MEDIUM,
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String, caption: String? = null) {
    val tv = StudioTheme.television
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (tv) 12.dp else 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = text, style = StudioTheme.type.h1, color = StudioTheme.colors.ink)
        if (caption != null) Text(text = caption, style = StudioTheme.type.caption, color = StudioTheme.colors.ink3)
    }
}

// ------------------------------------------------------------------- library

private enum class LibrarySort { LATEST, AZ, MOST_NEW }

@Composable
private fun Library(onOpenShow: (Long) -> Unit, onDiscover: () -> Unit) {
    val repo = ServiceLocator.podcasts
    val changes by repo.changes.collectAsState()
    val library by produceState<List<Podcast>?>(initialValue = null, changes) { value = repo.library() }
    var sort by remember { mutableStateOf(LibrarySort.LATEST) }
    val tv = StudioTheme.television
    val list = library ?: return
    if (list.isEmpty()) {
        EmptyState(
            text = stringResource(R.string.pod_empty_library_body),
            title = stringResource(R.string.pod_empty_library),
            icon = StudioIcons.Podcast,
            action = stringResource(R.string.pod_tab_discover),
            onAction = onDiscover,
        )
        return
    }
    val sorted = when (sort) {
        LibrarySort.LATEST -> list.sortedByDescending { it.latestUtc }
        LibrarySort.AZ -> list.sortedBy { it.title.lowercase() }
        LibrarySort.MOST_NEW -> list.sortedByDescending { it.newCount }
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = if (tv) 0.dp else 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = stringResource(R.string.pod_library_count, list.size), style = StudioTheme.type.meta, color = StudioTheme.colors.ink2)
            Spacer(modifier = Modifier.width(8.dp))
            listOf(LibrarySort.LATEST to R.string.pod_sort_latest, LibrarySort.AZ to R.string.pod_sort_az, LibrarySort.MOST_NEW to R.string.pod_sort_most_new)
                .forEach { (s, label) -> StudioChip(label = stringResource(label), selected = s == sort, onClick = { sort = s }) }
        }
        Spacer(modifier = Modifier.height(10.dp))
        val size = if (tv) 104.dp else 104.dp
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = size + 8.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 18.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (tv) 18.dp else 16.dp),
            contentPadding = PaddingValues(bottom = 16.dp, top = 6.dp),
        ) {
            items(sorted, key = { it.id }) { p ->
                PodcastTile(
                    title = p.title,
                    caption = if (p.newCount > 0) stringResource(R.string.pod_new_count, p.newCount) else stringResource(R.string.pod_all_heard),
                    imageUrl = p.imageUrl,
                    size = size,
                    badge = p.newCount,
                    onClick = { onOpenShow(p.id) },
                )
            }
        }
    }
}

// ------------------------------------------------- favourites and history

@Composable
private fun EpisodeList(
    vm: AppViewModel,
    caption: String,
    load: suspend () -> List<PodcastEpisode>,
    onPlay: (PodcastEpisode, Boolean) -> Unit,
    onOptions: (PodcastEpisode) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val repo = ServiceLocator.podcasts
    val changes by repo.changes.collectAsState()
    val list by produceState<List<PodcastEpisode>?>(initialValue = null, changes) { value = load() }
    val tv = StudioTheme.television
    val playing = playingKey(vm)
    val episodes = list ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (tv) 0.dp else 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 4.dp else 2.dp),
    ) {
        item(key = "caption") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Text(text = caption, style = StudioTheme.type.caption, color = StudioTheme.colors.ink3, modifier = Modifier.weight(1f))
                header?.invoke()
            }
        }
        if (episodes.isEmpty()) {
            item(key = "empty") { EmptyState(text = stringResource(R.string.pod_empty_list), icon = StudioIcons.Podcast) }
        }
        items(episodes, key = { it.id }) { e ->
            EpisodeRow(e, onPlay = { onPlay(e, false) }, onOptions = { onOptions(e) }, playing = e.keyString() == playing)
        }
    }
}

@Composable
private fun History(vm: AppViewModel, onPlay: (PodcastEpisode, Boolean) -> Unit, onOptions: (PodcastEpisode) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    EpisodeList(
        vm = vm,
        caption = stringResource(R.string.pod_history_caption),
        load = { ServiceLocator.podcasts.history() },
        onPlay = onPlay,
        onOptions = onOptions,
        header = {
            StudioButton(
                label = stringResource(R.string.pod_clear_history),
                onClick = { confirm = true },
                icon = StudioIcons.Trash,
                variant = StudioButtonVariant.GHOST,
                size = StudioButtonSize.MEDIUM,
            )
        },
    )
    if (confirm) {
        StudioSheet(onDismissRequest = { confirm = false }, title = stringResource(R.string.pod_clear_history), caption = stringResource(R.string.pod_clear_history_confirm)) {
            ListRow(
                label = stringResource(R.string.pod_clear_history),
                icon = StudioIcons.Trash,
                danger = true,
                onClick = {
                    confirm = false
                    scope.launch { ServiceLocator.podcasts.clearHistory() }
                },
            )
        }
    }
}

// ------------------------------------------------------------------ discover

@Composable
private fun Discover(vm: AppViewModel, onOpenShow: (Long) -> Unit) {
    val repo = ServiceLocator.podcasts
    val directory = ServiceLocator.podcastDirectory
    val scope = rememberCoroutineScope()
    val tv = StudioTheme.television
    var query by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<DirectoryResult?>(null) }
    val popular by produceState<DirectoryResult?>(initialValue = null) { value = directory.trending() }
    val changes by repo.changes.collectAsState()
    val known by produceState(initialValue = emptyMap<String, Podcast>(), changes) {
        value = repo.allPodcasts().associateBy { it.feedUrl }
    }
    val addedText = stringResource(R.string.pod_added)
    val notFeedText = stringResource(R.string.pod_add_failed)
    val networkText = stringResource(R.string.pod_add_failed_network)

    fun add(url: String, indexId: Long?) {
        if (adding) return
        adding = true
        scope.launch {
            when (val r = repo.add(url, inLibrary = true, indexId = indexId)) {
                is Outcome.Ok -> {
                    vm.post(UiMessage(text = addedText))
                    onOpenShow(r.value.id)
                }
                is Outcome.Err -> vm.post(
                    UiMessage(
                        text = if (r.error.kind == AppError.Kind.UnsupportedFormat || r.error.kind == AppError.Kind.ParseFailed) notFeedText else networkText,
                    ),
                )
            }
            adding = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (tv) 0.dp else 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 8.dp),
    ) {
        item(key = "search") {
            StudioField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.pod_search_hint),
                icon = StudioIcons.Search,
                editOnPress = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    val q = query
                    scope.launch { results = directory.search(q) }
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "link") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioField(
                    value = link,
                    onValueChange = { link = it },
                    label = stringResource(R.string.pod_add_link),
                    placeholder = stringResource(R.string.pod_add_link_hint),
                    icon = StudioIcons.Link,
                    editOnPress = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (link.isNotBlank()) add(link, null) }),
                    modifier = Modifier.weight(1f),
                )
                StudioButton(
                    label = stringResource(R.string.pod_add_link_action),
                    onClick = { if (link.isNotBlank()) add(link, null) },
                    icon = StudioIcons.Plus,
                    size = StudioButtonSize.MEDIUM,
                    enabled = !adding && link.isNotBlank(),
                )
            }
        }
        results?.let { r ->
            item(key = "res-h") { Heading(stringResource(R.string.pod_search_results)) }
            when (r) {
                is DirectoryResult.Problem -> item(key = "res-p") { ProblemLine(r.problem) }
                is DirectoryResult.Found -> items(r.podcasts, key = { "r" + it.feedUrl }) { p ->
                    FoundRow(p, known[p.feedUrl], onOpen = { id -> onOpenShow(id) }, onAdd = { add(p.feedUrl, p.indexId) })
                }
            }
        }
        item(key = "pop-h") { Heading(stringResource(R.string.pod_popular)) }
        when (val p = popular) {
            null -> Unit
            is DirectoryResult.Problem -> item(key = "pop-p") { ProblemLine(p.problem) }
            is DirectoryResult.Found -> item(key = "pop") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(if (tv) 18.dp else 12.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(p.podcasts, key = { it.feedUrl }) { f ->
                        val have = known[f.feedUrl]
                        PodcastTile(
                            title = f.title,
                            caption = if (have?.inLibrary == true) stringResource(R.string.pod_in_library) else f.author,
                            imageUrl = f.imageUrl,
                            size = if (tv) 96.dp else 96.dp,
                            onClick = { if (have != null) onOpenShow(have.id) else add(f.feedUrl, f.indexId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FoundRow(p: FoundPodcast, have: Podcast?, onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val inLibrary = have?.inLibrary == true
    ListRow(
        label = p.title,
        sub = listOfNotNull(p.author, p.episodeCount?.let { stringResource(R.string.pod_episodes_count, it) }).joinToString(" · ").ifBlank { null },
        icon = if (inLibrary) StudioIcons.Check else StudioIcons.Plus,
        trail = if (inLibrary) RowTrail.VALUE else RowTrail.NONE,
        value = if (inLibrary) stringResource(R.string.pod_in_library) else null,
        onClick = { if (have != null) onOpen(have.id) else onAdd() },
    )
}

@Composable
private fun ProblemLine(problem: DirectoryProblem) {
    val text = when (problem) {
        DirectoryProblem.NOT_CONFIGURED -> R.string.pod_directory_off
        DirectoryProblem.BUSY -> R.string.pod_directory_busy
        DirectoryProblem.OFFLINE, DirectoryProblem.OTHER -> R.string.pod_directory_offline
    }
    Text(text = stringResource(text), style = StudioTheme.type.meta, color = StudioTheme.colors.ink2, modifier = Modifier.padding(vertical = 6.dp))
}

// ---------------------------------------------------------------- show page

@Composable
private fun PodcastShowPage(
    vm: AppViewModel,
    podcastId: Long,
    onPlay: (PodcastEpisode, Boolean) -> Unit,
    onOptions: (PodcastEpisode) -> Unit,
    onSettings: (Podcast) -> Unit,
) {
    val repo = ServiceLocator.podcasts
    val changes by repo.changes.collectAsState()
    var filter by remember { mutableStateOf(EpisodeFilter.ALL) }
    val podcast by produceState<Podcast?>(initialValue = null, changes, podcastId) { value = repo.podcast(podcastId) }
    val episodes by produceState(initialValue = emptyList<PodcastEpisode>(), changes, podcastId, filter) {
        value = repo.episodes(podcastId, filter)
    }
    // A show's page reads its feed again when it opens, if it is due.
    LaunchedEffect(podcastId) {
        val p = repo.podcast(podcastId) ?: return@LaunchedEffect
        if (System.currentTimeMillis() - p.lastRefreshUtc > 30 * 60_000L) repo.refresh(podcastId)
    }
    val scope = rememberCoroutineScope()
    val tv = StudioTheme.television
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val playing = playingKey(vm)
    val show = podcast ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (tv) 0.dp else 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 4.dp else 2.dp),
    ) {
        item(key = "head") {
            Row(horizontalArrangement = Arrangement.spacedBy(if (tv) 20.dp else 14.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                PodcastCover(title = show.title, imageUrl = show.imageUrl, size = if (tv) 145.dp else 112.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 4.dp)) {
                    listOfNotNull(show.author, show.categories.firstOrNull()).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                        Text(text = it, style = type.caption.copy(fontWeight = FontWeight.SemiBold), color = studio.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(text = show.title, style = if (tv) type.title else type.h1, color = studio.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    show.description?.let { Text(text = it, style = type.meta, color = studio.ink2, maxLines = if (tv) 2 else 3, overflow = TextOverflow.Ellipsis) }
                    if (!show.lastRefreshOk) Text(text = stringResource(R.string.pod_feed_failed), style = type.caption, color = studio.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        StudioButton(
                            label = stringResource(if (show.inLibrary) R.string.pod_in_library else R.string.pod_add_to_library),
                            onClick = { scope.launch { repo.setInLibrary(show.id, !show.inLibrary) } },
                            icon = if (show.inLibrary) StudioIcons.Check else StudioIcons.Plus,
                            active = show.inLibrary,
                            variant = if (show.inLibrary) StudioButtonVariant.PRIMARY else StudioButtonVariant.SECONDARY,
                            size = StudioButtonSize.MEDIUM,
                        )
                        episodes.firstOrNull()?.let { newest ->
                            StudioButton(
                                label = stringResource(R.string.pod_play_newest),
                                onClick = { onPlay(newest, false) },
                                icon = StudioIcons.Play,
                                iconOnly = !tv,
                                size = StudioButtonSize.MEDIUM,
                            )
                        }
                        StudioButton(
                            label = stringResource(R.string.pod_settings),
                            onClick = { onSettings(show) },
                            icon = StudioIcons.Settings,
                            iconOnly = true,
                            size = StudioButtonSize.MEDIUM,
                        )
                    }
                }
            }
        }
        item(key = "filters") {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    EpisodeFilter.ALL to R.string.pod_filter_all,
                    EpisodeFilter.UNPLAYED to R.string.pod_filter_unplayed,
                    EpisodeFilter.STARTED to R.string.pod_filter_started,
                    EpisodeFilter.FAVOURITES to R.string.pod_tab_favourites,
                ).forEach { (f, label) -> StudioChip(label = stringResource(label), selected = f == filter, onClick = { filter = f }) }
            }
        }
        if (episodes.isEmpty()) item(key = "empty") { EmptyState(text = stringResource(R.string.pod_empty_list), icon = StudioIcons.Podcast) }
        items(episodes, key = { it.id }) { e ->
            EpisodeRow(e, onPlay = { onPlay(e, false) }, onOptions = { onOptions(e) }, showPodcast = false, playing = e.keyString() == playing)
        }
    }
}

// ------------------------------------------------------------------- sheets

@Composable
private fun EpisodeOptions(
    vm: AppViewModel,
    episode: PodcastEpisode,
    onPlay: (fromStart: Boolean) -> Unit,
    onOpenShow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val repo = ServiceLocator.podcasts
    val scope = rememberCoroutineScope()
    val queued by produceState(initialValue = false, episode.key) { value = repo.queue().any { it.key == episode.key } }
    val act: (suspend () -> Unit) -> Unit = { block -> onDismiss(); scope.launch { block() } }
    StudioSheet(onDismissRequest = onDismiss, title = episode.title, caption = episode.podcastTitle, drawer = true) {
        if (episode.mark.positionMs > 0 && !episode.mark.played) {
            ListRow(label = stringResource(R.string.pod_resume_from, com.idanck.shidur.core.TimeModel.ltr(clock(episode.mark.positionMs))), icon = StudioIcons.Play, onClick = { onPlay(false) })
            ListRow(label = stringResource(R.string.pod_from_start), icon = StudioIcons.Restart, onClick = { onPlay(true) })
        } else {
            ListRow(label = stringResource(R.string.pod_play), icon = StudioIcons.Play, onClick = { onPlay(false) })
        }
        if (queued) {
            ListRow(label = stringResource(R.string.pod_remove_queue), icon = StudioIcons.Queue, onClick = { act { repo.dequeue(episode.key) } })
        } else {
            ListRow(label = stringResource(R.string.pod_add_queue), icon = StudioIcons.Queue, onClick = { act { repo.enqueue(episode.key, next = false) } })
            ListRow(label = stringResource(R.string.pod_play_next), icon = StudioIcons.SkipForward, onClick = { act { repo.enqueue(episode.key, next = true) } })
        }
        ListRow(
            label = stringResource(if (episode.mark.favourite) R.string.pod_favourite_remove else R.string.pod_favourite_add),
            icon = if (episode.mark.favourite) StudioIcons.StarOn else StudioIcons.Star,
            onClick = { act { repo.setFavourite(episode.key, !episode.mark.favourite) } },
        )
        ListRow(
            label = stringResource(if (episode.mark.played) R.string.pod_mark_unplayed else R.string.pod_mark_played),
            icon = StudioIcons.Played,
            onClick = { act { repo.setPlayed(episode.key, !episode.mark.played) } },
        )
        if (episode.mark.historyUtc > 0) {
            ListRow(label = stringResource(R.string.pod_remove_history), icon = StudioIcons.History, onClick = { act { repo.removeFromHistory(episode.key) } })
        }
        ListRow(label = stringResource(R.string.pod_open_show), icon = StudioIcons.Podcast, trail = RowTrail.CHEVRON, onClick = onOpenShow)
    }
}

/** `TvPodcastShowSettings`: each row steps through its values on OK; they apply at once. */
@Composable
private fun ShowSettings(podcast: Podcast, onLeft: () -> Unit, onDismiss: () -> Unit) {
    val repo = ServiceLocator.podcasts
    val scope = rememberCoroutineScope()
    var prefs by remember(podcast.id) { mutableStateOf(podcast.prefs) }
    val save: (com.idanck.shidur.data.podcast.PodcastPrefs) -> Unit = { next ->
        prefs = next
        scope.launch { repo.setPrefs(podcast.id, next) }
    }
    val speeds = listOf(0f, 0.8f, 1.25f, 1.5f, 1.75f, 2f)
    val skips = listOf(0, 15, 30, 45, 60, 90, 120)
    val secondsText: @Composable (Int) -> String = { s -> if (s == 0) stringResource(R.string.pod_off) else stringResource(R.string.pod_seconds, s) }
    StudioSheet(onDismissRequest = onDismiss, title = stringResource(R.string.pod_settings), caption = podcast.title, drawer = true) {
        ListRow(
            label = stringResource(R.string.pod_speed),
            icon = StudioIcons.Speed,
            trail = RowTrail.VALUE,
            value = if (prefs.speed == 0f) stringResource(R.string.pod_speed_default) else speedText(prefs.speed),
            onClick = { save(prefs.copy(speed = speeds[(speeds.indexOf(prefs.speed) + 1).mod(speeds.size)])) },
        )
        ListRow(
            label = stringResource(R.string.pod_skip_intro),
            icon = StudioIcons.SkipForward,
            trail = RowTrail.VALUE,
            value = secondsText(prefs.skipIntroSeconds),
            onClick = { save(prefs.copy(skipIntroSeconds = skips[(skips.indexOf(prefs.skipIntroSeconds) + 1).mod(skips.size)])) },
        )
        ListRow(
            label = stringResource(R.string.pod_skip_outro),
            icon = StudioIcons.SkipForward,
            trail = RowTrail.VALUE,
            value = secondsText(prefs.skipOutroSeconds),
            onClick = { save(prefs.copy(skipOutroSeconds = skips[(skips.indexOf(prefs.skipOutroSeconds) + 1).mod(skips.size)])) },
        )
        ListRow(
            label = stringResource(R.string.pod_order),
            icon = StudioIcons.Move,
            trail = RowTrail.VALUE,
            value = stringResource(if (prefs.oldestFirst) R.string.pod_order_oldest else R.string.pod_order_newest),
            onClick = { save(prefs.copy(oldestFirst = !prefs.oldestFirst)) },
        )
        if (podcast.inLibrary) {
            ListRow(
                label = stringResource(R.string.pod_remove_library),
                sub = stringResource(R.string.pod_remove_library_sub),
                icon = StudioIcons.Trash,
                danger = true,
                onClick = {
                    scope.launch { repo.setInLibrary(podcast.id, false) }
                    onLeft()
                },
            )
        }
    }
}

internal fun speedText(speed: Float): String =
    com.idanck.shidur.core.TimeModel.ltr((if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×")
