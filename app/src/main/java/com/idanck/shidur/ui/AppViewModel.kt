package com.idanck.shidur.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.data.youtube.YouTubeChannels
import com.idanck.shidur.data.youtube.youtubeChannelId
import com.idanck.shidur.core.AppError
import com.idanck.shidur.core.Outcome
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.builtin.IsraelPublicChannels
import com.idanck.shidur.data.builtin.IsraelRadioStations
import com.idanck.shidur.tv.HomeRow
import com.idanck.shidur.tv.WatchNext
import com.idanck.shidur.ui.home.HomeData
import com.idanck.shidur.ui.home.HomeRails
import com.idanck.shidur.data.catchup.CatchupUrlBuilder
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.ChannelGroup
import com.idanck.shidur.data.model.ChannelSort
import com.idanck.shidur.data.model.DecoderMode
import com.idanck.shidur.data.model.EpgMatchStats
import com.idanck.shidur.data.model.Playlist
import com.idanck.shidur.data.model.Programme
import com.idanck.shidur.data.model.Recording
import com.idanck.shidur.data.model.RefreshReport
import com.idanck.shidur.data.model.VodItem
import com.idanck.shidur.data.podcast.PodcastEpisode
import com.idanck.shidur.data.prefs.ParentalGate
import com.idanck.shidur.data.prefs.ParentalPin
import com.idanck.shidur.data.prefs.PinAttemptLimiter
import com.idanck.shidur.data.radio.RadioCategoryClassifier
import com.idanck.shidur.data.radio.RadioShelf
import com.idanck.shidur.data.repo.EpgProgress
import com.idanck.shidur.data.news.FreeCopies
import com.idanck.shidur.data.news.IsraeliNewsChannels
import com.idanck.shidur.data.sport.IsraeliSportChannels
import com.idanck.shidur.ui.tv.SportLayout
import com.idanck.shidur.data.xtream.isEpisode
import com.idanck.shidur.data.xtream.isSeriesContainer
import com.idanck.shidur.player.BackgroundPlayback
import com.idanck.shidur.player.PlaybackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Below this, resuming would rewind to somewhere the viewer had barely arrived. */
/**
 * How many recently watched channels the home screen reads.
 *
 * Wider than the rail shows, because the pure builder screens them against what is actually
 * visible and a short read would leave the rail shorter than it needs to be.
 */
private const val RECENT_LIMIT = 30

private const val MIN_RESUME_MS = 60_000L

/** The last twentieth of a title counts as watched. */
private const val FINISHED_TAIL_FRACTION = 20L

/**
 * What to store as the resume mark for a title stopped at [position] of [duration], or null to
 * leave whatever is already stored alone.
 *
 * Extracted because this one decision is the only thing standing between a viewer and losing
 * their place, and it is provable here without a player. Answering null rather than zero for an
 * early position is the point of it: a failover restarts the source from zero
 * (`PlayerEngine.startCurrentSource` defaults to it), so writing that back would spend one
 * network blip to erase forty minutes.
 */
internal fun resumeMark(position: Long, duration: Long): Long? = when {
    // Finishing clears the mark, so the title leaves continue-watching rather than sitting at
    // 99% for good.
    duration > 0 && position >= duration - duration / FINISHED_TAIL_FRACTION -> 0L
    position < MIN_RESUME_MS -> null
    else -> position
}

/** A transient message for the user: a toast-style line with an optional error behind it. */
data class UiMessage(val text: String? = null, val error: AppError? = null, val id: Long = System.nanoTime())

/** Pseudo-group ids for the synthetic views in the channel list. */
object VirtualGroup {
    const val ALL = -1L
    const val FAVOURITES = -2L
    const val RECENT = -3L

    /**
     * The only way back from hiding a channel (FR-ORG-03). A virtual group rather than a screen of
     * its own: it is reachable with a D-pad, identical on both shells, and needs no navigation.
     */
    const val HIDDEN = -4L

    /** The YouTube channels added to the channel list (ADR-061), numbered from 900. */
    const val YOUTUBE = -5L
}

data class ChannelListState(
    val groups: List<ChannelGroup> = emptyList(),
    val selectedGroupId: Long = VirtualGroup.ALL,
    val channels: List<Channel> = emptyList(),
    /** The channel-list filter box. Scoped to the selected group, and always visible when set. */
    val search: String = "",
    /**
     * Radio only: narrows to one shelf of the category menu (ADR-050), alongside [selectedGroupId]
     * which stays at [VirtualGroup.ALL] while this is set — see [AppViewModel.selectRadioShelf]. Null shows
     * every (non-Russian) station.
     */
    val selectedRadioShelf: RadioShelf? = null,
)

/**
 * The search screen's own results (FR-UI-09).
 *
 * Deliberately separate from [ChannelListState.search]. They were one field, so opening search
 * filtered the Live list and closing it left the filter in place with nothing on screen to explain
 * it — and results were silently limited to whichever group happened to be selected. A filter and
 * a search are different questions.
 */
data class SearchState(
    val query: String = "",
    val running: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val programmes: List<Programme> = emptyList(),
    val vod: List<com.idanck.shidur.data.model.VodItem> = emptyList(),
) {
    val isEmpty: Boolean get() = channels.isEmpty() && programmes.isEmpty() && vod.isEmpty()
}

/**
 * What the player is actually carrying, for the surfaces that describe it — the phone's
 * Now Playing screen and mini bar. [AppUiState.currentChannel] is not enough: it deliberately
 * survives an on-demand session (so live state can be restored afterwards), which would put a
 * live channel's name and guide over a film.
 */
sealed interface NowPlayingInfo {
    data class Live(val channel: Channel) : NowPlayingInfo
    data class Catchup(val channel: Channel, val programme: Programme) : NowPlayingInfo
    data class OnDemand(val item: VodItem) : NowPlayingInfo
    data class RecordingFile(val recording: Recording) : NowPlayingInfo
    /** A podcast episode (ADR-067); [PodcastSession] holds what belongs to it. */
    data class Podcast(val episode: PodcastEpisode) : NowPlayingInfo
}

data class AppUiState(
    val loading: Boolean = true,
    val playlists: List<Playlist> = emptyList(),
    val activePlaylist: Playlist? = null,
    val list: ChannelListState = ChannelListState(),
    val currentChannel: Channel? = null,
    val nowPlaying: NowPlayingInfo? = null,
    val now: Programme? = null,
    val next: Programme? = null,
    val refreshing: Boolean = false,
    val guideProgress: EpgProgress? = null,
    val guideStats: EpgMatchStats = EpgMatchStats(),
    val lastRefresh: RefreshReport? = null,
    val message: UiMessage? = null,
    /**
     * Every station in the radio playlist, Russian-language ones already excluded
     * ([RadioCategoryClassifier.isRussian]), independent of [ChannelListState.selectedGroupId] or
     * [ChannelListState.selectedRadioShelf] — this is what the radio category menu itself is
     * built from, not the currently filtered [ChannelListState.channels]. Empty outside Radio.
     */
    val radioAllChannels: List<Channel> = emptyList(),
)

/**
 * The single state holder shared by the TV and mobile front-ends.
 *
 * Keeping one view model for both means a channel hidden on the phone is hidden on the TV
 * (docs/03 section 3.1) without any synchronisation code: there is only one source of truth,
 * and the two shells are pure presentation.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val dao get() = ServiceLocator.dao
    private val sources get() = ServiceLocator.sources
    private val epg get() = ServiceLocator.epg
    private val settings get() = ServiceLocator.settings
    private val player get() = ServiceLocator.player

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state

    val playbackStatus: StateFlow<PlaybackStatus> get() = player.status
    val settingsState get() = settings.state

    private val main = Handler(Looper.getMainLooper())

    private var listJob: Job? = null

    // Parental state (FR-PRF-04/07), declared ahead of `init` so nothing it starts can find it
    // unset; the rest of it is with the other parental functions below.
    private val _pinRequest = MutableStateFlow<PinRequest?>(null)

    /** A playback or an unlock waiting for the PIN; the root of either shell shows the prompt. */
    val pinRequest: StateFlow<PinRequest?> = _pinRequest

    private val _parentalUnlocked = MutableStateFlow(false)

    /**
     * Set by a correct PIN and kept until the app leaves the screen ([lockParental]): asking
     * again on every zap would make a locked channel unwatchable for the parent too.
     */
    val parentalUnlocked: StateFlow<Boolean> = _parentalUnlocked

    private val pinLimiter = PinAttemptLimiter()

    /** Sport mode's and News mode's panels (ADR-041, ADR-042), beside the full-screen player they stand in for. */
    val sport = SportSession(app, settings, ::profileFor, viewModelScope)

    /**
     * Held, so [BackgroundPlayback.returnSkip] is handed back the very function it was lent; and
     * written above `init`, which lends it, as properties are set in the order they are written.
     */
    private val skipStation: (Int) -> Unit = { delta -> zap(delta) }

    init {
        player.onScheduleRetry = { delay, action -> main.postDelayed(action, delay) }
        player.onNotice = { error, what ->
            post(UiMessage(text = noticeText(error, what), error = error))
        }
        reload()
        // YouTube channels added to or taken off the list show at once (ADR-061).
        viewModelScope.launch {
            ServiceLocator.youtube.listed.drop(1).collect { loadChannels() }
        }
        // The background player's media card (ADR-068): what is playing, named and pictured as
        // the shells show it, and the station list its previous/next buttons move along.
        BackgroundPlayback.lendSkip(skipStation)
        viewModelScope.launch {
            _state.map(::backgroundCardOf).distinctUntilChanged().collect(BackgroundPlayback::publish)
        }
    }

    private fun backgroundCardOf(state: AppUiState): BackgroundPlayback.Card? =
        when (val playing = state.nowPlaying) {
            is NowPlayingInfo.Live -> BackgroundPlayback.Card(
                title = playing.channel.displayName,
                subtitle = null,
                artworkUrl = playing.channel.displayLogo,
                stations = isRadioChannel(playing.channel, state),
            )
            is NowPlayingInfo.Podcast -> BackgroundPlayback.Card(
                title = playing.episode.title,
                subtitle = playing.episode.podcastTitle,
                artworkUrl = playing.episode.imageUrl ?: playing.episode.podcastImageUrl,
                stations = false,
            )
            else -> null
        }

    private fun isRadioChannel(channel: Channel, state: AppUiState): Boolean {
        val radioPlaylistId = state.playlists.firstOrNull { it.url == IsraelRadioStations.M3U_URL }?.id
        return radioPlaylistId != null && channel.playlistId == radioPlaylistId
    }

    // `ServiceLocator.locale`, not the application context: `Application` is attached once and
    // never rebuilt, so after a language change it still resolves the previous one until the
    // process dies (FR-UI-23).
    private fun noticeText(error: AppError, what: String): String {
        val strings = ServiceLocator.locale
        return when {
            what == "software" -> strings.getString(com.idanck.shidur.R.string.decoder_fell_back)
            else -> strings.getString(com.idanck.shidur.R.string.failover_switched, what)
        }
    }

    // ------------------------------------------------------------------ load

    fun reload() {
        viewModelScope.launch { reloadInternal() }
    }

    private suspend fun reloadInternal() {
        val data = withContext(Dispatchers.IO) {
            val playlists = dao.playlists()
            val activeId = settings.state.value.activePlaylistId
            // Radio is its own destination (see openRadio/closeRadio), never a candidate for
            // the Channels tab's default playlist — otherwise a stored activePlaylistId left
            // pointing at it (or a fresh install that happens to bootstrap it first) would put
            // radio stations back inside Channels, exactly what the split exists to prevent.
            // A playlist turned off in Settings is not a candidate either: it shows nowhere.
            val candidates = playlists.filter { it.enabled && it.url != IsraelRadioStations.M3U_URL }
            val active = candidates.firstOrNull { it.id == activeId } ?: candidates.firstOrNull()
            // Hidden groups are listed too, drawn dimmed, so that hiding one is not a one-way
            // door — there would otherwise be nothing left on screen to show it again.
            val groups = active?.let { dao.groups(it.id, includeHidden = true) }.orEmpty()
            val stats = active?.let { epg.stats(it.id) } ?: EpgMatchStats()
            Triple(playlists, active, groups) to stats
        }
        val (triple, stats) = data
        val (playlists, active, groups) = triple
        _state.value = _state.value.copy(
            loading = false,
            playlists = playlists,
            activePlaylist = active,
            guideStats = stats,
            list = _state.value.list.copy(groups = groups),
        )
        if (active != null && settings.state.value.activePlaylistId != active.id) {
            settings.setActivePlaylist(active.id)
        }
        loadChannels()
    }

    /**
     * Turns a playlist off or back on from Settings. An off playlist keeps everything in it, but is
     * not refreshed and shows on no screen (see `IN_ENABLED_PLAYLIST` in the DAO); turning one on
     * that was never loaded loads it straight away rather than waiting for the background refresh.
     */
    fun setPlaylistEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            sources.setPlaylistEnabled(id, enabled)
            reloadInternal()
            val playlist = _state.value.playlists.firstOrNull { it.id == id }
            if (enabled && playlist != null && playlist.lastRefreshUtc == 0L) refreshPlaylist(id)
        }
    }

    /**
     * Deletes a playlist and everything it carries. A bundled one stays away until
     * [restoreBuiltInPlaylists], rather than being put back on the next cold start.
     */
    fun removePlaylist(id: Long) {
        viewModelScope.launch {
            sources.removePlaylist(id)
            reloadInternal()
        }
    }

    /**
     * Whether the Radio destination is offered: not once the bundled radio playlist has been
     * removed or turned off in Settings. A playlist list that does not hold it yet (a cold start,
     * before bootstrap) still shows it, so the entry does not blink in and out on launch.
     */
    fun isRadioShown(playlists: List<Playlist>): Boolean =
        !settings.isBuiltInRemoved(IsraelRadioStations.M3U_URL) &&
            playlists.none { it.url == IsraelRadioStations.M3U_URL && !it.enabled }

    /** Puts back whichever bundled playlists were removed, and loads them. */
    fun restoreBuiltInPlaylists() {
        viewModelScope.launch {
            val before = _state.value.playlists.map { it.id }.toSet()
            sources.restoreBuiltInPlaylists()
            reloadInternal()
            _state.value.playlists.filter { it.id !in before }.forEach { refreshPlaylist(it.id) }
        }
    }

    fun selectPlaylist(id: Long) {
        // Mirrors the guard in `reload`: the radio playlist is never the Channels tab's active
        // playlist, so persisting it here would only be silently undone on the next reload.
        if (_state.value.playlists.firstOrNull { it.id == id }?.url == IsraelRadioStations.M3U_URL) return
        settings.setActivePlaylist(id)
        reload()
    }

    // Shared by openRadio/closeRadio so a fast Radio/Channels tab flip cancels whichever of the
    // two is still in flight rather than letting it land after the one that superseded it —
    // otherwise a stale openRadio could commit its state after a closeRadio the user meant to be
    // the final word, leaving the Channels tab selected while the list still shows radio.
    private var radioNavJob: Job? = null

    /**
     * Opens the Radio destination: points the shared channel list at the bundled radio playlist
     * (ADR-015) without touching the persisted "active" playlist, so [closeRadio] can hand the
     * list straight back to whatever Channels was showing. Reusing [ChannelListState] this way
     * — rather than a second, parallel copy of groups/favourites/search — is what lets Radio offer
     * the same group filters, favourites and per-channel menu as Channels for free.
     *
     * Ensures the playlist exists first: Android Auto's [com.idanck.shidur.auto.RadioBrowseTree]
     * can be the first thing in the process to need it, and so can this, on a cold start that
     * reaches here before `App.onCreate`'s fire-and-forget bootstrap coroutine lands.
     */
    fun openRadio() {
        radioNavJob?.cancel()
        radioNavJob = viewModelScope.launch {
            val playlist = withContext(Dispatchers.IO) {
                sources.ensureRadioPlaylist()
                dao.playlists().firstOrNull { it.enabled && it.url == IsraelRadioStations.M3U_URL }
            } ?: return@launch
            val groups = withContext(Dispatchers.IO) { dao.groups(playlist.id, includeHidden = true) }
            val stats = withContext(Dispatchers.IO) { epg.stats(playlist.id) }
            // Built once here, not derived from `list.channels` on every recomposition: the
            // category menu needs every (non-Russian) station regardless of which one is
            // currently selected, and re-classifying on every filter change would be wasted work.
            val allChannels = withContext(Dispatchers.IO) { dao.channels(playlist.id) }
                .filterNot { RadioCategoryClassifier.isRussian(it.displayName, groups.firstOrNull { g -> g.id == it.groupId }?.name) }
            _state.value = _state.value.copy(
                playlists = if (_state.value.playlists.any { it.id == playlist.id }) {
                    _state.value.playlists
                } else {
                    _state.value.playlists + playlist
                },
                activePlaylist = playlist,
                guideStats = stats,
                list = ChannelListState(groups = groups),
                radioAllChannels = allChannels,
            )
            loadChannels()
        }
    }

    /**
     * Every bundled station but the Russian ones, each with the shelves of the radio menu it is on
     * (ADR-050: where it is heard, and its genre if its name says one), for the radio screen's
     * station strip (`canvas/Radio.dc.html`). Read straight from the database, so it does not
     * depend on the Radio list being the one open. Empty until the radio playlist exists.
     *
     * Until 0.21.7 the strip sorted by `RadioCategory`, the feed's `group-title`s — genres now, with
     * only a couple of stations filed as regional — so nearly every station landed under "other"
     * and the regions never showed (owner's report, 2026-11-09).
     */
    suspend fun radioStations(): List<Pair<Channel, List<RadioShelf>>> = withContext(Dispatchers.IO) {
        val playlist = dao.playlists().firstOrNull { it.enabled && it.url == IsraelRadioStations.M3U_URL }
            ?: return@withContext emptyList()
        val groups = dao.groups(playlist.id, includeHidden = true)
        dao.channels(playlist.id).mapNotNull { channel ->
            val group = groups.firstOrNull { it.id == channel.groupId }?.name
            if (RadioCategoryClassifier.isRussian(channel.displayName, group)) {
                null
            } else {
                channel to RadioCategoryClassifier.shelves(group, channel.displayName)
            }
        }
    }

    /** Leaves the Radio destination, handing the channel list back to the normal active playlist. */
    fun closeRadio() {
        radioNavJob?.cancel()
        radioNavJob = viewModelScope.launch { reloadInternal() }
    }

    fun selectGroup(groupId: Long) {
        _state.value = _state.value.copy(
            list = _state.value.list.copy(selectedGroupId = groupId, selectedRadioShelf = null),
        )
        loadChannels()
    }

    /** Radio only: narrow the list to one shelf of the category menu (ADR-050), or null for every station. */
    fun selectRadioShelf(shelf: RadioShelf?) {
        _state.value = _state.value.copy(
            list = _state.value.list.copy(selectedGroupId = VirtualGroup.ALL, selectedRadioShelf = shelf),
        )
        loadChannels()
    }

    fun setSearch(query: String) {
        _state.value = _state.value.copy(list = _state.value.list.copy(search = query))
        // Debounced, unlike a group change: the filter field calls this on every keystroke.
        loadChannels(debounceMs = FILTER_DEBOUNCE_MS)
    }

    // ---------------------------------------------------------------- search

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState

    private var searchJob: Job? = null

    /**
     * FR-UI-09: one query across channels, the guide and on-demand, over the whole playlist.
     *
     * Debounced and cancellable. `searchProgrammes` is a `LIKE` over a table that holds a
     * programme per channel per half hour for a week — six figures of rows, with no index that can
     * serve a leading wildcard — and it used to run once per keystroke.
     */
    fun search(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < MIN_SEARCH_LENGTH) {
            _searchState.value = SearchState(query = query)
            return
        }
        _searchState.value = _searchState.value.copy(query = query, running = true)
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val playlistId = _state.value.activePlaylist?.id
            val results = withContext(Dispatchers.IO) {
                Triple(
                    playlistId?.let { dao.channels(it, search = trimmed) }.orEmpty(),
                    epg.searchProgrammes(trimmed, System.currentTimeMillis()),
                    playlistId?.let { dao.vod(it, search = trimmed) }.orEmpty(),
                )
            }
            _searchState.value = SearchState(
                query = query,
                running = false,
                channels = results.first,
                programmes = results.second,
                vod = results.third,
            )
        }
    }

    /** Drops the results when the search surface closes, so reopening it starts clean. */
    fun clearSearch() {
        searchJob?.cancel()
        _searchState.value = SearchState()
    }

    /** The channel a guide hit belongs to, so a programme result is something you can act on. */
    suspend fun channelForProgramme(programme: Programme): Channel? =
        withContext(Dispatchers.IO) { dao.channelForEpgId(programme.epgChannelId) }

    /**
     * Reads the list the current group and filter describe.
     *
     * The job is held and cancelled, because this is called on every keystroke and every group
     * change: without it, overlapping queries commit in whatever order they finish, so a slow
     * query for "b" could land after the one for "bbc" and leave the box and the list disagreeing.
     */
    private fun loadChannels(debounceMs: Long = 0) {
        listJob?.cancel()
        val active = _state.value.activePlaylist ?: run {
            _state.value = _state.value.copy(list = _state.value.list.copy(channels = emptyList()))
            return
        }
        val listState = _state.value.list
        listJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val channels = withContext(Dispatchers.IO) {
                val prefs = settings.state.value
                val hideLocked = prefs.hideLockedChannels
                val sort = prefs.channelSort
                val search = listState.search.takeIf { it.isNotBlank() }
                val radioPlaylistId = _state.value.playlists
                    .firstOrNull { it.url == IsraelRadioStations.M3U_URL }
                    ?.id
                val isRadio = active.url == IsraelRadioStations.M3U_URL
                val raw = when (listState.selectedGroupId) {
                    VirtualGroup.ALL -> dao.channels(active.id, search = search, sort = sort)
                    VirtualGroup.FAVOURITES ->
                        dao.channels(active.id, favouritesOnly = true, search = search, sort = sort)
                    // The filter box used to do nothing at all here, because the argument was
                    // simply not passed on.
                    //
                    // The only query here not scoped to `active`: recents deliberately span
                    // playlists (see the DAO). Radio is the exception at both ends — its own
                    // recents are its stations, and the channel list's are anything but them.
                    VirtualGroup.RECENT -> dao.recentChannels(
                        search = search,
                        onlyPlaylistId = if (isRadio) active.id else null,
                        excludePlaylistId = if (isRadio) null else radioPlaylistId,
                    )
                    // Always in the playlist's own order: this is a repair screen, and the point
                    // is to find the channel you hid, not to browse.
                    VirtualGroup.HIDDEN -> dao.channels(active.id, hiddenOnly = true, search = search)
                    VirtualGroup.YOUTUBE -> emptyList()
                    else -> dao.channels(
                        active.id,
                        groupId = listState.selectedGroupId,
                        search = search,
                        sort = sort,
                    )
                }
                // Radio: drop Russian-language stations everywhere (not just a specific category —
                // see RadioCategoryClassifier), then narrow further to the selected shelf of the
                // category menu, if any (ADR-050): a station is on its region's and its genre's.
                val radioFiltered = if (isRadio) {
                    fun groupNameOf(channel: Channel) = listState.groups.firstOrNull { it.id == channel.groupId }?.name
                    raw
                        .filterNot { RadioCategoryClassifier.isRussian(it.displayName, groupNameOf(it)) }
                        .let { stations ->
                            val shelf = listState.selectedRadioShelf
                            if (shelf == null) {
                                stations
                            } else {
                                stations.filter { shelf in RadioCategoryClassifier.shelves(groupNameOf(it), it.displayName) }
                            }
                        }
                } else {
                    raw
                }
                val shown = if (hideLocked) radioFiltered.filter { !it.locked } else radioFiltered
                // YouTube channels in the list (ADR-061): after every channel of the playlist in
                // "All", and alone in a group of their own; never on the radio.
                if (isRadio) {
                    shown
                } else {
                    val youtube = YouTubeChannels.channels(ServiceLocator.youtube.listed.value, active.id)
                        .filter { search == null || it.displayName.contains(search, ignoreCase = true) }
                    when (listState.selectedGroupId) {
                        VirtualGroup.ALL -> shown + youtube
                        VirtualGroup.YOUTUBE -> youtube
                        else -> shown
                    }
                }
            }
            _state.value = _state.value.copy(list = _state.value.list.copy(channels = channels))
        }
    }

    // --------------------------------------------------------------- playback

    /**
     * Whether what's actually audible right now is a bundled radio station (ADR-015), rather
     * than a live TV channel, catch-up, on-demand title or a recording played back. Read from
     * [AppUiState.nowPlaying] rather than [AppUiState.currentChannel] — the latter deliberately
     * survives an on-demand session, so it can still name a radio station while a film is what's
     * actually playing.
     *
     * [MainActivity][com.idanck.shidur.ui.MainActivity] uses this to decide whether a locked or
     * turned-off screen should pause playback (a picture has nothing to show anyway) or keep a
     * radio station going in the background: there is no picture to lose, so nothing is gained by
     * stopping it.
     *
     * "Playing", not merely "loaded": a station the user paused and then locked the screen on has
     * nothing to keep alive, and promoting a media-playback foreground service for silence is
     * both a wasted service and a notification about nothing. [PlaybackStatus.playWhenReady] is
     * the right flag rather than `playing` — a station that is rebuffering at the moment the
     * screen locks is still a station the user is listening to.
     */
    val isRadioPlaying: Boolean
        get() {
            if (!player.status.value.playWhenReady) return false
            // A podcast is sound with no picture, as a station is: it carries on with the screen off
            // and the app left, under the same notification (ADR-067).
            if (_state.value.nowPlaying is NowPlayingInfo.Podcast) return true
            val live = (_state.value.nowPlaying as? NowPlayingInfo.Live)?.channel ?: return false
            return isRadioChannel(live, _state.value)
        }

    fun play(channel: Channel) {
        // FR-PRF-04/05: every way onto a channel — the list, zapping, a typed number, the last
        // channel, a launcher deep link, the start channel — comes through here, so this is the
        // one gate. Until 2026-10-13 a locked channel played on the first press.
        if (channelNeedsPin(channel)) {
            _pinRequest.value = PinRequest.Play(channel)
            return
        }
        // A YouTube channel from the list plays in YouTube's own player, which the shell opens
        // (ADR-061). What was playing stays the "current" channel, so leaving YouTube returns to it.
        channel.youtubeChannelId?.let {
            _youtubeRequest.value = it
            return
        }
        leaveVod()
        val playlist = _state.value.playlists.firstOrNull { it.id == channel.playlistId }
        val profile = playlist?.let { sources.profileFor(it) }
            ?: com.idanck.shidur.data.net.RequestProfile(userAgent = settings.state.value.userAgent)
        // The timeshift buffer is for television (ADR-065): a radio station is listened to live,
        // and its stream is not MPEG-TS anyway.
        val radio = playlist?.url == IsraelRadioStations.M3U_URL
        player.play(channel, profile, timeshift = settings.state.value.timeshiftEnabled && !radio)
        settings.rememberChannel(channel.id)
        _state.value = _state.value.copy(
            currentChannel = channel,
            nowPlaying = NowPlayingInfo.Live(channel),
        )
        viewModelScope.launch(Dispatchers.IO) {
            dao.recordWatch(channel.id, System.currentTimeMillis())
            // The launcher's "Continue watching" row is that write with artwork on it, so it is
            // rebuilt straight after — but handed to the application scope, for the same reason
            // `saveVodProgress` uses it: zapping and then leaving the app cancels
            // `viewModelScope`, and the last channel watched is exactly the one the row most
            // needs. Started from inside this block so it can never read the database before
            // the write above lands. Throttled and best-effort inside.
            ServiceLocator.appScope.launch { WatchNext.sync(ServiceLocator.appContext) }
            // The app's own row (FR-TVI-03) carries recents too, and the programme now on.
            ServiceLocator.appScope.launch { HomeRow.sync(ServiceLocator.appContext) }
        }
        refreshNowNext(channel)
    }

    /**
     * Starts whatever failed again from the top: the error panel's "Retry" (ADR-034).
     *
     * Through the same entry point that started it, not a bare re-prepare of the player, so the
     * request profile, the PIN gate, the catch-up window and the film's resume mark are all asked
     * again — any of them can be why it failed, and a setting changed since is the usual fix.
     */
    fun retryPlayback() {
        when (val playing = _state.value.nowPlaying) {
            is NowPlayingInfo.Live -> play(playing.channel)
            is NowPlayingInfo.Catchup -> playCatchup(playing.channel, playing.programme)
            is NowPlayingInfo.OnDemand -> playVod(playing.item)
            is NowPlayingInfo.RecordingFile -> playRecording(playing.recording)
            is NowPlayingInfo.Podcast -> playPodcast(playing.episode)
            null -> Unit
        }
    }

    /**
     * Plays whatever channel [channelId] names, from outside the browsing state (FR-TVI-04).
     *
     * The entry point for a launcher deep link, so it reads the channel from the database rather
     * than from the list on screen — on a cold start there is no list yet. Marking the start
     * behaviour consumed is what stops [resumeStartChannel] racing this and winning: both call
     * [play], and the one the viewer actually asked for has to be the one that survives.
     */
    fun playChannelId(channelId: Long) {
        startBehaviourConsumed = true
        viewModelScope.launch {
            val channel = withContext(Dispatchers.IO) { dao.channel(channelId) }
            if (channel == null) {
                post(UiMessage(error = AppError(AppError.Kind.NotFound)))
                return@launch
            }
            // The owning playlist may not be in state yet on a cold start, and `play` resolves
            // the request profile from it. Reloading here is cheaper than getting a radio
            // station's headers wrong.
            if (_state.value.playlists.none { it.id == channel.playlistId }) {
                val playlists = withContext(Dispatchers.IO) { dao.playlists() }
                _state.value = _state.value.copy(playlists = playlists)
            }
            play(channel)
        }
    }

    /** FR-PLB-18/19: relative zapping within whatever list is on screen. */
    fun zap(delta: Int) {
        val current = _state.value.currentChannel
        val channels = _state.value.list.channels
        val index = channels.indexOfFirst { it.id == current?.id }
        if (index >= 0) {
            play(channels[(index + delta).mod(channels.size)])
            return
        }
        // The playing channel is not in the browsed list — filtered away, or played from
        // search. Zapping within a list the user cannot see jumped to its first row, which
        // from the phone's player screen read as a leap to an unrelated channel; the playlist
        // in its own order is the neighbourhood a viewer actually expects.
        val playlistId = current?.playlistId ?: _state.value.activePlaylist?.id ?: return
        viewModelScope.launch {
            val prefs = settings.state.value
            val all = withContext(Dispatchers.IO) {
                val raw = dao.channels(playlistId, sort = prefs.channelSort)
                if (prefs.hideLockedChannels) raw.filter { !it.locked } else raw
            }
            if (all.isEmpty()) return@launch
            val at = all.indexOfFirst { it.id == current?.id }
            play(if (at < 0) all[0] else all[(at + delta).mod(all.size)])
        }
    }

    /** FR-PLB-19: jump by the channel number the user typed. */
    fun playByNumber(number: Int) {
        val match = _state.value.list.channels.firstOrNull { it.number == number }
            ?: _state.value.list.channels.getOrNull(number - 1)
        if (match != null) play(match) else post(UiMessage(error = AppError(AppError.Kind.NotFound)))
    }

    /** FR-PLB-20: back to whatever was on before the current channel. */
    fun toggleLastChannel() {
        val previous = settings.state.value.previousChannelId
        if (previous == 0L) return
        viewModelScope.launch {
            val channel = withContext(Dispatchers.IO) { dao.channel(previous) } ?: return@launch
            play(channel)
        }
    }

    /** [resumeStartChannel] is once per process: a recreated activity must not restart it. */
    private var startBehaviourConsumed = false

    /**
     * FR-SET-04 / FR-PLB-21: what to put on screen when the app opens.
     *
     * Answers whether a start channel actually started, so the phone shell knows to open its
     * Now Playing screen over the tuning stream rather than leaving it playing behind a list.
     * Suspends for the lookup: answering early on the id alone would open that screen over
     * nothing whenever a refresh has since deleted the channel. One-shot per process — the
     * activity re-runs its start effect on every recreate (a language change does one
     * deliberately), and replaying the start channel there would tear down whatever the user
     * was actually watching.
     */
    suspend fun resumeStartChannel(): Boolean {
        if (startBehaviourConsumed) return false
        startBehaviourConsumed = true
        val s = settings.state.value
        val id = when (s.startBehaviour) {
            com.idanck.shidur.data.prefs.StartBehaviour.SPECIFIC_CHANNEL -> s.startChannelId
            com.idanck.shidur.data.prefs.StartBehaviour.LAST_CHANNEL ->
                if (s.resumeLastChannel) s.lastChannelId else 0L
            // The home screen is the one browse surface that *wants* a stream underneath it:
            // its hero is the live picture, and opening on a black rectangle captioned "OK on
            // the channel playing above fills the screen" would be a lie about an empty box.
            com.idanck.shidur.data.prefs.StartBehaviour.HOME ->
                if (s.resumeLastChannel) s.lastChannelId else 0L
            // The list and guide behaviours open an overlay instead; the shell handles that,
            // and starting a stream underneath it would waste a provider connection.
            else -> 0L
        }
        if (id == 0L) return false
        val channel = withContext(Dispatchers.IO) { dao.channel(id) } ?: return false
        play(channel)
        // A locked start channel waits on the PIN prompt instead; nothing is playing yet.
        return _state.value.currentChannel?.id == channel.id
    }

    fun refreshNowNext(channel: Channel? = _state.value.currentChannel) {
        val target = channel ?: return
        viewModelScope.launch {
            val (now, next) = withContext(Dispatchers.IO) { epg.nowAndNext(target) }
            _state.value = _state.value.copy(now = now, next = next)
        }
    }

    /** FR-PLB-04: per-channel decoder choice, remembered. */
    fun setChannelDecoders(channel: Channel, video: DecoderMode, audio: DecoderMode) {
        viewModelScope.launch {
            val updated = channel.copy(videoDecoder = video, audioDecoder = audio)
            withContext(Dispatchers.IO) { dao.setChannelDecoders(channel.id, video, audio) }
            val playlist = _state.value.playlists.firstOrNull { it.id == channel.playlistId }
            val profile = playlist?.let { sources.profileFor(it) }
                ?: com.idanck.shidur.data.net.RequestProfile(userAgent = settings.state.value.userAgent)
            _state.value = _state.value.copy(currentChannel = updated)
            player.reloadWithDecoders(updated, profile)
            loadChannels()
        }
    }

    /**
     * FR-CU-03/04: play a past programme from the provider's archive.
     *
     * Answers whether playback actually started: the guards abandon the attempt with a message,
     * and the phone shell must not open its Now Playing screen over nothing.
     */
    fun playCatchup(channel: Channel, programme: Programme): Boolean {
        val now = System.currentTimeMillis()
        if (!channel.hasCatchup) {
            post(UiMessage(error = AppError(AppError.Kind.CatchupUnsupported)))
            return false
        }
        if (!CatchupUrlBuilder.isWithinWindow(channel, programme.startUtc, now)) {
            post(UiMessage(error = AppError(AppError.Kind.OutsideCatchupWindow)))
            return false
        }
        val url = CatchupUrlBuilder.build(channel, programme.startUtc, programme.endUtc, now)
        if (url == null) {
            post(UiMessage(error = AppError(AppError.Kind.CatchupUnsupported)))
            return false
        }
        val playlist = _state.value.playlists.firstOrNull { it.id == channel.playlistId }
        val profile = playlist?.let { sources.profileFor(it) }
            ?: com.idanck.shidur.data.net.RequestProfile(userAgent = settings.state.value.userAgent)
        // After the guards, never before: three of them abandon the attempt and leave the film
        // that is already playing exactly where it was, so releasing it here would forget where
        // that film had reached without anything having replaced it.
        leaveVod()
        player.playUrl(programme.title, url, profile)
        _state.value = _state.value.copy(
            currentChannel = channel,
            now = programme,
            nowPlaying = NowPlayingInfo.Catchup(channel, programme),
        )
        return true
    }

    /**
     * Plays a film or a single episode through the owning playlist's request profile.
     *
     * A series is refused rather than attempted. It has no stream of its own — only its episodes
     * do — and putting its synthetic id through the film endpoint is exactly the failure this
     * replaced: a 404 the player spends its whole retry budget on before showing a message that
     * reads like the provider is broken.
     */
    fun playVod(item: VodItem): Boolean {
        val playlist = _state.value.playlists.firstOrNull { it.id == item.playlistId }
            ?: _state.value.activePlaylist
            ?: return false
        if (item.isSeriesContainer) {
            post(UiMessage(text = string(R.string.vod_open_series_to_play)))
            return false
        }
        val client = sources.xtreamClient(playlist)
        if (client == null) {
            post(UiMessage(error = AppError(AppError.Kind.NotFound, "on-demand needs an Xtream source")))
            return false
        }
        val url = if (item.isEpisode) {
            client.episodeUrl(item.streamId, item.containerExtension)
        } else {
            client.vodUrl(item.streamId, item.containerExtension)
        }
        leaveVod()
        playingVod = item
        // The generation pins the start to the latest tap: two quick taps read their resume
        // marks concurrently, and without it the reads could complete out of order — the
        // earlier film starting last and playing under the later one's title.
        val generation = ++vodPlayGeneration
        viewModelScope.launch {
            // The database, not the caller's snapshot: the screens only re-read on their own
            // triggers, so the tile's resume mark goes stale the moment another episode saves
            // its place — and starting from a stale mark has the next save overwrite the real
            // one, losing the viewer's position to the row they pressed (FR-PLB-29).
            val resume = if (item.id == 0L) {
                item.resumePositionMs
            } else {
                withContext(Dispatchers.IO) { dao.vodResumePosition(item.id) }
            }
            if (generation != vodPlayGeneration) return@launch
            player.playUrl(item.name, url, sources.profileFor(playlist), resume)
        }
        _state.value = _state.value.copy(nowPlaying = NowPlayingInfo.OnDemand(item))
        return true
    }

    /** Bumped per [playVod] so a slow resume read cannot start an older tap's film last. */
    private var vodPlayGeneration = 0

    /** Where the podcasts screen is, so the television's Back can climb it (ADR-067). */
    val podcastNav = com.idanck.shidur.ui.podcasts.PodcastNav()

    /** The podcast episode playing, if one is (ADR-067). */
    val podcast = PodcastSession(
        player = player,
        repo = ServiceLocator.podcasts,
        scope = viewModelScope,
        writeScope = ServiceLocator.appScope,
        playNext = { playPodcast(it) },
    )

    /**
     * Plays a podcast episode from where the viewer stopped in it (or [fromStart]), at the show's
     * own speed and past its intro skip. The mark is read again from the database: the caller's
     * row can be minutes old, and another device may have moved it since (ADR-067).
     */
    fun playPodcast(episode: PodcastEpisode, fromStart: Boolean = false): Boolean {
        leaveVod()
        val generation = ++vodPlayGeneration
        viewModelScope.launch {
            val fresh = ServiceLocator.podcasts.episode(episode.key) ?: episode
            val prefs = ServiceLocator.podcasts.podcast(fresh.podcastId)?.prefs
            if (generation != vodPlayGeneration) return@launch
            podcast.start(fresh, fromStart, prefs?.speed ?: 0f, prefs?.skipIntroSeconds ?: 0)
            _state.value = _state.value.copy(nowPlaying = NowPlayingInfo.Podcast(fresh))
        }
        _state.value = _state.value.copy(nowPlaying = NowPlayingInfo.Podcast(episode))
        return true
    }

    /**
     * What a previous visit already stored for one series. No network, so it answers at once.
     *
     * This is what the episode list draws first, and what it re-reads after a star: the database
     * is the authority on both, and neither is worth an HTTP request.
     */
    suspend fun storedEpisodes(series: VodItem): List<VodItem> = withContext(Dispatchers.IO) {
        val seriesId = series.seriesId ?: return@withContext emptyList()
        val playlistId = playlistIdFor(series) ?: return@withContext emptyList()
        dao.episodes(playlistId, seriesId)
    }

    /**
     * Fetches one series' episodes from the panel, stores them, and answers with what is stored.
     *
     * `get_series_info` is one request per series, so this cannot happen during a refresh — a
     * playlist carrying a thousand series would make a thousand requests for screens nobody
     * opened. It happens when a series is opened instead, and the round trip through the database
     * is the point: it gives every episode a row of its own to hold a resume position and a
     * favourite, and it is what a failed fetch falls back to.
     *
     * Pair it with [storedEpisodes]: that answers at once from what a previous visit left, so a
     * slow or unreachable panel costs a refresh rather than the whole screen.
     */
    suspend fun refreshEpisodes(series: VodItem): List<VodItem> {
        val seriesId = series.seriesId ?: return emptyList()
        val playlistId = playlistIdFor(series) ?: return emptyList()

        val (fetched, stored) = withContext(Dispatchers.IO) {
            val client = _state.value.playlists.firstOrNull { it.id == playlistId }
                ?.let { sources.xtreamClient(it) }
            val outcome = client?.seriesEpisodes(seriesId)
            outcome?.getOrNull()?.let { listed ->
                dao.transaction { d ->
                    listed.forEach { dao.upsertVod(d, it.copy(playlistId = playlistId)) }
                    // What the panel stopped listing leaves the shelf too: a pruned episode
                    // kept in the cache answers 404 through /series/, which reads like a
                    // broken provider. Only on a successful fetch, and only inside its
                    // transaction; surviving rows keep their favourite and resume marks.
                    dao.deleteStaleEpisodes(d, playlistId, seriesId, listed.map { it.streamId }.toSet())
                }
            }
            outcome to dao.episodes(playlistId, seriesId)
        }

        // Posted out here, on whatever thread called in, rather than inside the block above.
        // `post` is a read-modify-write on the one state flow and every other one in this file
        // lands on the main thread; from a background thread it silently drops whichever
        // concurrent update loses the race.
        when {
            fetched == null ->
                post(UiMessage(error = AppError(AppError.Kind.NotFound, "on-demand needs an Xtream source")))
            fetched.getOrNull() == null ->
                post(UiMessage(error = fetched.errorOrNull() ?: AppError(AppError.Kind.Unknown)))
        }
        return stored
    }

    /** The playlist a VOD row belongs to, falling back to the active one for an unsaved item. */
    private fun playlistIdFor(item: VodItem): Long? =
        item.playlistId.takeIf { it != 0L } ?: _state.value.activePlaylist?.id

    /**
     * The on-demand row playback is currently inside, so its position can be written back.
     *
     * Nothing had ever written `resume_position_ms`. It was read when playback started and never
     * saved, which left "continue watching" (FR-PLB-29) permanently empty and every episode
     * restarting from zero.
     */
    private var playingVod: VodItem? = null

    /** Ends the current on-demand session, keeping the place it reached. */
    private fun leaveVod() {
        saveVodProgress()
        podcast.leave()
        playingVod = null
        // Every source switch passes through here, so a resume read still in flight belongs
        // to a session that just ended — without this, its deferred start could replace the
        // live channel that interrupted it.
        vodPlayGeneration++
    }

    // ------------------------------------------------------------------ sport and news

    /** Opens Sport mode (ADR-041): two panels, on the television and the phone alike (decision 10). */
    fun enterSport() = enterPanels(SportSession.Mode.SPORT, SportLayout.PAIR)

    /**
     * Opens News mode (ADR-042): two panels, or four if the viewer chose them (ADR-045), one of
     * which may show the headlines.
     */
    fun enterNews() = enterPanels(SportSession.Mode.NEWS, settings.state.value.newsPanels)

    /** News mode's two or four panels (ADR-045): remembered, and applied at once while it is up. */
    fun setNewsPanels(panels: Int) {
        val count = SportLayout.panels(panels)
        settings.setNewsPanels(count)
        if (sport.active && sport.mode.value == SportSession.Mode.NEWS) sport.setPanels(count)
    }

    /** News mode's running strip of headlines across the top of the screen (ADR-045). */
    fun setNewsTicker(on: Boolean) = settings.setNewsTicker(on)

    /**
     * The full-screen player stands down first — its decoder and its connection are the panels'
     * now — after writing down where an on-demand title had got to, so that leaving with nothing
     * chosen can put it back where it was. From one mode straight into the other, the first
     * closes and the player stays down: what played before either is still what comes back.
     */
    private fun enterPanels(mode: SportSession.Mode, panels: Int) {
        if (sport.active) {
            if (sport.mode.value == mode) return
            sport.close()
        } else {
            playingBeforeSport = playbackStatus.value.playWhenReady
            saveVodProgress()
            player.silence()
            player.setPlayWhenReady(false)
        }
        val opening = sport.begin(mode, panels)
        viewModelScope.launch {
            val news = mode == SportSession.Mode.NEWS
            val offered = panelChoices(mode)
            val choices = offered.choices
            val stored = if (news) settings.newsSlots() else settings.sportSlots()
            // A panel left on the provider's copy of a station comes back on the free one.
            val restored = stored.map { id ->
                id?.let { wanted -> choices.firstOrNull { it.id == wanted } ?: offered.replacedBy[wanted] }
            }
            val flashesAt = if (news) stored.indexOf(SportSession.FLASHES_ID) else -1
            sport.offer(opening, choices, restored, flashesAt)
        }
    }

    /** Whether the full-screen player was playing, rather than paused, as Sport or News mode opened. */
    private var playingBeforeSport = false

    /**
     * Leaves Sport or News mode for the full-screen player, which carries on with the channel being
     * heard — or, with nothing chosen, with whatever was playing before the mode opened.
     *
     * @param restartPaused whether something that was paused then starts again too. The
     *   television does, where it is in front of the viewer; the phone leaves it paused under the
     *   mini player rather than sounding behind a list.
     */
    fun exitSport(restartPaused: Boolean = true) {
        if (!sport.active) return
        val chosen = sport.focusedChannel()
        sport.close()
        when {
            chosen != null -> play(chosen)
            restartPaused || playingBeforeSport -> retryPlayback()
        }
    }

    /** Sport or News mode left some other way — a launcher card, which decides for itself what plays. */
    fun closeSport() = sport.close()

    /** Whether YouTube's screen has the full-screen player standing down (ADR-061). */
    private var inYouTube = false

    /** Whether the full-screen player was playing, rather than paused, as YouTube took over. */
    private var playingBeforeYouTube = false

    /**
     * YouTube's screen opens (ADR-061): its own player plays, and only one player may play on a
     * screen, so the full-screen one stands down, as it does for Sport mode, after writing down
     * where an on-demand title had got to.
     */
    fun enterYouTube() {
        if (inYouTube) return
        inYouTube = true
        playingBeforeYouTube = playbackStatus.value.playWhenReady
        saveVodProgress()
        player.silence()
        player.setPlayWhenReady(false)
    }

    /**
     * YouTube's screen is left: what played before it starts again, unless the way out leads to
     * Sport or News mode, whose panels take the screen instead ([restart] false). The phone passes
     * false too: there what was playing comes back only if it was playing, under the mini player.
     */
    fun leaveYouTube(restart: Boolean) {
        if (!inYouTube) return
        inYouTube = false
        if (restart || playingBeforeYouTube) retryPlayback()
    }

    /** Back from the background: the panels start again, any the parental lock now covers aside. */
    fun resumeSport() = sport.resume { channel -> !channelNeedsPin(channel) }

    /**
     * Every channel [mode] may show: the Israeli sports channels ([IsraeliSportChannels]) or news
     * channels ([IsraeliNewsChannels]) of every playlist but the bundled radio, in playlist order.
     * Hidden channels and hidden groups stay hidden, and a channel behind the parental PIN is left
     * out rather than asked for in each of the panels. A station the bundled free-to-air playlist
     * carries is offered from there only, not again through the provider ([FreeCopies]).
     */
    private suspend fun panelChoices(mode: SportSession.Mode): FreeCopies.Result = withContext(Dispatchers.IO) {
        val s = settings.state.value
        val unlocked = _parentalUnlocked.value
        val playlists = dao.playlists().filter { it.enabled && it.url != IsraelRadioStations.M3U_URL }
        val free = playlists.filter { it.url == IsraelPublicChannels.M3U_URL }.map { it.id }.toSet()
        val channels = playlists
            .flatMap { playlist ->
                val groups = dao.groups(playlist.id, includeHidden = true).associateBy { it.id }
                dao.channels(playlist.id).filter { channel ->
                    val group = channel.groupId?.let { groups[it] }
                    val names = listOfNotNull(channel.name, channel.customName)
                    val offered = names.any { name ->
                        when (mode) {
                            SportSession.Mode.SPORT ->
                                IsraeliSportChannels.isIsraeliSport(name, group?.name, channel.tvgId)
                            SportSession.Mode.NEWS ->
                                IsraeliNewsChannels.isIsraeliNews(name, group?.name, channel.tvgId)
                        }
                    }
                    offered && !ParentalGate.channelNeedsPin(
                        channelLocked = channel.locked,
                        groupLocked = group?.locked == true,
                        pinSet = s.parentalPinHash.isNotBlank(),
                        lockChannels = s.lockChannels,
                        unlocked = unlocked,
                    )
                }
            }
        FreeCopies.prefer(channels, free)
    }

    /** Each playlist's name, by its id: which one a channel in the panels' picker comes from. */
    fun playlistNames(): Map<Long, String> = _state.value.playlists.associate { it.id to it.name }

    /** Whether [channel] is the bundled free-to-air playlist's (ADR-013): a broadcaster's, no account. */
    fun isFreeToAir(channel: Channel): Boolean =
        _state.value.playlists.firstOrNull { it.id == channel.playlistId }?.url == IsraelPublicChannels.M3U_URL

    /** The account limits [connectionLimit] has learned, by playlist. */
    private val connectionLimits = HashMap<Long, Int>()

    /**
     * How many connections at once the account behind [channel]'s playlist allows, for a panel the
     * provider closed to say so (ADR-041). Asked of the provider once and remembered; null when it
     * does not answer, or for the free-to-air playlist, which has no account.
     */
    suspend fun connectionLimit(channel: Channel): Int? {
        connectionLimits[channel.playlistId]?.let { return it }
        val playlist = _state.value.playlists.firstOrNull { it.id == channel.playlistId } ?: return null
        if (playlist.url == IsraelPublicChannels.M3U_URL) return null
        val limit = sources.maxConnections(playlist, channel.urls.firstOrNull()) ?: return null
        connectionLimits[channel.playlistId] = limit
        return limit
    }

    /** The request profile [channel]'s own playlist asks for, or the app's defaults. */
    private fun profileFor(channel: Channel): com.idanck.shidur.data.net.RequestProfile {
        val playlist = _state.value.playlists.firstOrNull { it.id == channel.playlistId }
        return playlist?.let { sources.profileFor(it) }
            ?: com.idanck.shidur.data.net.RequestProfile(userAgent = settings.state.value.userAgent)
    }

    /**
     * Writes where the viewer got to, if that is anywhere worth returning to.
     *
     * A position inside the first minute is not a place anyone wants resumed, and one in the last
     * few per cent means they finished — that is stored as zero, so the title leaves the
     * continue-watching row rather than sitting at 99% for good.
     */
    fun saveVodProgress() {
        // A podcast episode's place too, from the same moments (ADR-067).
        podcast.save()
        val item = playingVod ?: return
        if (item.id == 0L) return
        val (position, duration) = player.positionAndDuration() ?: return
        val keep = resumeMark(position, duration) ?: return

        // Not `viewModelScope`: this is called from `Activity.onStop`, and on the way out of the
        // app `onCleared` cancels that scope immediately afterwards — often before the write has
        // been picked up. The one write that matters most would be the one most likely to be lost.
        // Guarded, because `appScope` has no exception handler: a full disk on the way out of
        // the app would otherwise crash the process over a bookkeeping write.
        ServiceLocator.appScope.launch {
            runCatching { dao.saveResume(item.id, keep, System.currentTimeMillis()) }
        }
    }

    /** Plays a completed recording from wherever it was written. */
    fun playRecording(recording: Recording): Boolean {
        val path = recording.filePath
        if (path == null) {
            post(UiMessage(error = AppError(AppError.Kind.NotFound, "recording has no file")))
            return false
        }
        val uri = if (path.startsWith("/")) "file://$path" else path
        leaveVod()
        player.playUrl(recording.title, uri, com.idanck.shidur.data.net.RequestProfile(), recording.resumePositionMs)
        _state.value = _state.value.copy(nowPlaying = NowPlayingInfo.RecordingFile(recording))
        return true
    }

    // ---------------------------------------------------------------- editing

    /**
     * FR-ORG-07. Takes the state to write rather than toggling, because not every caller is
     * holding a fresh channel: the programme dialog is handed a snapshot when it opens and never
     * re-reads it, so a toggle derived from `channel.favourite` there wrote the same value every
     * time and could add a favourite but never remove one.
     */
    fun setFavourite(channel: Channel, favourite: Boolean) {
        // Every copy of the channel the state holds, not only the list the reload below refreshes:
        // a star drawn from any other copy never moved (see `withFavourite`).
        _state.value = _state.value.withFavourite(channel.id, favourite)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.setChannelFlags(channel.id, favourite = favourite) }
            loadChannels()
            // Favourites lead the home-screen row (FR-TVI-03). Forced past the zapping throttle:
            // a star is a deliberate act and should reach the row now, not on the next sweep.
            ServiceLocator.appScope.launch { HomeRow.sync(ServiceLocator.appContext, force = true) }
        }
    }

    /**
     * FR-ORG-03. This is what "remove this channel" means here, and deliberately so: a refresh
     * re-imports every channel the provider still lists, so a real delete would come back on the
     * next refresh with the favourite, name, number and order the user gave it all reset. Hiding
     * survives a refresh and can be undone. See ADR-010.
     */
    fun setHidden(channel: Channel, hidden: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.setChannelFlags(channel.id, hidden = hidden) }
            loadChannels()
            ServiceLocator.appScope.launch { HomeRow.sync(ServiceLocator.appContext, force = true) }
            post(
                UiMessage(
                    text = string(
                        if (hidden) R.string.channel_hidden_done else R.string.channel_shown_done,
                        channel.displayName,
                    ),
                ),
            )
        }
    }

    fun setLocked(channel: Channel, locked: Boolean) {
        // Putting a lock on is free; taking one off is loosening parental controls.
        val s = settings.state.value
        if (!locked && ParentalGate.parentalChangeNeedsPin(s.parentalPinHash.isNotBlank(), _parentalUnlocked.value)) {
            _pinRequest.value = PinRequest.Unlock(channel)
            return
        }
        applyLocked(channel, locked)
    }

    private fun applyLocked(channel: Channel, locked: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.setChannelFlags(channel.id, locked = locked) }
            loadChannels()
        }
    }

    /** Puts a channel back the way the provider sent it, keeping favourite/hidden/order. */
    fun resetChannelCustomisations(channel: Channel) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.resetChannelCustomisations(channel.id) }
            loadChannels()
            post(UiMessage(text = string(R.string.channel_reset_done, channel.name)))
        }
    }

    /**
     * Applies the channel editor in one pass. Each field is written on its own, and only when it
     * changed, so an edit never rewrites a column the user did not touch.
     */
    fun saveChannelEdits(channel: Channel, name: String?, logo: String?, number: Int, groupId: Long?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (name != channel.customName) dao.setChannelCustomName(channel.id, name)
                if (logo != channel.customLogoUrl) dao.setChannelCustomLogo(channel.id, logo)
                if (number != channel.number) dao.setChannelNumber(channel.id, number.coerceIn(0, 9999))
                if (groupId != channel.groupId) dao.setChannelGroup(channel.id, groupId)
            }
            loadChannels()
        }
    }

    /** FR-ORG-03: hiding has to be reversible, and this is the way back for all of them at once. */
    fun showAllHidden() {
        val active = _state.value.activePlaylist ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.showAllHidden(active.id) }
            loadChannels()
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        ServiceLocator.locale.getString(resId, *args)

    // ----------------------------------------------------------------- groups

    /** FR-ORG-02: a group of the user's own, alongside the ones the playlist supplies. */
    fun createGroup(name: String) {
        val active = _state.value.activePlaylist ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                val next = (_state.value.list.groups.maxOfOrNull { it.sortIndex } ?: 0) + 1
                dao.createCustomGroup(active.id, trimmed, next)
            }
            // The unique index on (playlist_id, name) rejected it; say so rather than appearing
            // to have worked.
            if (id == -1L) post(UiMessage(text = string(R.string.group_exists, trimmed)))
            reload()
        }
    }

    /**
     * FR-ORG-03 for whole groups. Hidden groups stay in the column, drawn dimmed, so this can be
     * undone; their channels drop out of every list.
     */
    fun setGroupHidden(group: ChannelGroup, hidden: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.setGroupHidden(group.id, hidden) }
            reload()
        }
    }

    /**
     * Renaming is offered for custom groups only. A provider group is matched by name on every
     * refresh, so renaming one would leave the old group orphaned and its replacement recreated
     * under the provider's name — that needs a column to remember the original, and a migration.
     */
    fun renameGroup(group: ChannelGroup, name: String) {
        val trimmed = name.trim()
        if (!group.custom || trimmed.isBlank()) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { dao.renameGroup(group.id, trimmed) }
            if (!ok) post(UiMessage(text = string(R.string.group_exists, trimmed)))
            reload()
        }
    }

    /** Deletes a custom group. Its channels survive, ungrouped — `deleteGroup` clears their id. */
    fun deleteGroup(group: ChannelGroup) {
        if (!group.custom) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.deleteGroup(group.id) }
            if (_state.value.list.selectedGroupId == group.id) selectGroup(VirtualGroup.ALL)
            reload()
        }
    }

    /**
     * Whether the list currently on screen is ordered by `sort_index`, which is the only case
     * where swapping two neighbours means what the user thinks it means. Favourites is ordered by
     * `sort_index` too, but its neighbours are not neighbours in the full list, so a swap there
     * flings a channel across the playlist; Recent is ordered by watch time, where a swap changes
     * nothing on screen and silently reorders somewhere else entirely.
     */
    val canReorder: Boolean
        get() {
            // A sort preset orders the list by something other than `sort_index`, so a swap there
            // changes nothing on screen while quietly rearranging the order the user built by
            // hand. The group test alone was not enough once FR-ORG-06 existed.
            if (settings.state.value.channelSort != ChannelSort.PROVIDER) return false
            val group = _state.value.list.selectedGroupId
            return group == VirtualGroup.ALL || group >= 0
        }

    /** FR-ORG-05: move-mode reordering, one step at a time so a D-pad can drive it. */
    fun moveChannel(channel: Channel, delta: Int) {
        if (!canReorder) return
        val channels = _state.value.list.channels
        val index = channels.indexOfFirst { it.id == channel.id }
        val target = index + delta
        if (index < 0 || target !in channels.indices) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val other = channels[target]
                // One transaction: a process death between the two writes would otherwise leave
                // both channels sharing a sort_index, and the tie is broken by `number, name`.
                dao.transaction {
                    dao.setChannelSortIndex(channel.id, other.sortIndex)
                    dao.setChannelSortIndex(other.id, channel.sortIndex)
                }
            }
            loadChannels()
        }
    }

    /** FR-EPG-12: a per-channel offset that never touches any other channel's rows. */
    fun setChannelEpgOffset(channel: Channel, minutes: Int?) {
        viewModelScope.launch {
            val clamped = minutes?.let(TimeModel::clampOffset)
            withContext(Dispatchers.IO) { dao.setChannelEpgOffset(channel.id, clamped) }
            _state.value = _state.value.copy(
                currentChannel = _state.value.currentChannel
                    ?.takeIf { it.id == channel.id }
                    ?.copy(epgOffsetMinutes = clamped)
                    ?: _state.value.currentChannel,
            )
            loadChannels()
            refreshNowNext()
        }
    }

    fun bindGuide(channel: Channel, epgId: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { epg.bindManually(channel.id, epgId) }
            loadChannels()
            refreshNowNext()
            _state.value.activePlaylist?.let { p ->
                val stats = withContext(Dispatchers.IO) { epg.stats(p.id) }
                _state.value = _state.value.copy(guideStats = stats)
            }
        }
    }

    // ---------------------------------------------------------------- parental (FR-PRF-04/05/07)

    private fun channelNeedsPin(channel: Channel): Boolean {
        val s = settings.state.value
        // Groups come from the list on screen. A channel from a playlist that is not browsed
        // (a deep link into another one) is judged on its own lock alone.
        val groupLocked = channel.groupId
            ?.let { id -> _state.value.list.groups.firstOrNull { it.id == id }?.locked }
            ?: false
        return ParentalGate.channelNeedsPin(
            channelLocked = channel.locked,
            groupLocked = groupLocked,
            pinSet = s.parentalPinHash.isNotBlank(),
            lockChannels = s.lockChannels,
            unlocked = _parentalUnlocked.value,
        )
    }

    /** Checks [pin]; on success unlocks and carries out whatever was waiting for it. */
    fun submitPin(pin: String, onResult: (PinResult) -> Unit) {
        val wait = pinLimiter.remainingLockMillis()
        if (wait > 0) {
            onResult(PinResult.LockedOut(((wait + 999) / 1000).toInt()))
            return
        }
        val s = settings.state.value
        viewModelScope.launch {
            // 60,000 PBKDF2 rounds: a noticeable moment on a television box, so not on the
            // main thread.
            val ok = withContext(Dispatchers.Default) {
                ParentalPin.verify(pin, s.parentalPinHash, s.parentalPinSalt)
            }
            if (!ok) {
                pinLimiter.onFailure()
                val lockedFor = pinLimiter.remainingLockMillis()
                onResult(if (lockedFor > 0) PinResult.LockedOut(((lockedFor + 999) / 1000).toInt()) else PinResult.Wrong)
                return@launch
            }
            pinLimiter.onSuccess()
            _parentalUnlocked.value = true
            val pending = _pinRequest.value
            _pinRequest.value = null
            when (pending) {
                is PinRequest.Play -> play(pending.channel)
                is PinRequest.Unlock -> applyLocked(pending.channel, false)
                null -> Unit
            }
            onResult(PinResult.Accepted)
        }
    }

    fun cancelPinRequest() {
        _pinRequest.value = null
    }

    /**
     * Stores a new PIN. Hashing it is the same 60,000 PBKDF2 rounds as checking one, and on the
     * main thread it froze the screen long enough for Android to report the app as not
     * responding (QA run 38420681633, television emulator).
     */
    fun setPin(pin: String) {
        // Before the hash lands: whoever just set the PIN knows it, and the parental section
        // must not flip to asking for it between Save and the next row.
        _parentalUnlocked.value = true
        viewModelScope.launch(Dispatchers.Default) {
            val hashed = ParentalPin.hash(pin)
            settings.setParentalPin(hashed.hash, hashed.salt)
        }
    }

    /** The app left the screen: whoever picks the remote up next is asked again. */
    fun lockParental() {
        _parentalUnlocked.value = false
        _pinRequest.value = null
    }

    // ---------------------------------------------------------------- refresh

    fun refreshPlaylist(playlistId: Long? = null) {
        val id = playlistId ?: _state.value.activePlaylist?.id ?: return
        _state.value = _state.value.copy(refreshing = true)
        viewModelScope.launch {
            val refreshed = when (val result = sources.refresh(id)) {
                is Outcome.Ok -> {
                    _state.value = _state.value.copy(lastRefresh = result.value)
                    post(UiMessage(text = null))
                    true
                }
                is Outcome.Err -> {
                    post(UiMessage(error = result.error))
                    false
                }
            }
            _state.value = _state.value.copy(refreshing = false)
            reload()
            // The guide comes with the channels, as it already does in the background refresh.
            // Without this a playlist just added showed an empty guide until someone found
            // "Update guide now", or for up to its whole refresh interval (12 hours by default).
            // Read back from the database: the refresh may have filled in the guide address
            // itself (an Xtream panel's xmltv.php, an M3U header's url-tvg).
            if (refreshed) {
                withContext(Dispatchers.IO) { dao.playlist(id) }
                    ?.takeIf { !it.epgUrl.isNullOrBlank() }
                    ?.let { loadGuide(it) }
            }
        }
    }

    fun refreshGuide() {
        val playlist = _state.value.activePlaylist ?: return
        viewModelScope.launch { loadGuide(playlist) }
    }

    private suspend fun loadGuide(playlist: Playlist) {
        _state.value = _state.value.copy(guideProgress = EpgProgress(0, 0))
        val result = epg.refreshGuide(playlist) { progress ->
            _state.value = _state.value.copy(guideProgress = progress)
        }
        when (result) {
            is Outcome.Ok -> if (_state.value.activePlaylist?.id == playlist.id) {
                _state.value = _state.value.copy(guideStats = result.value)
                // The match just written each channel's `resolvedEpgId` to the database, and the
                // list in state still holds the rows read before it. The grid reads its programmes
                // by that id, so a playlist just added drew "No guide data" on every row — while
                // the player, which reads its channel afresh, showed what was on — until something
                // else happened to read the list again (QA-25, issue #152).
                loadChannels()
            }
            is Outcome.Err -> post(UiMessage(error = result.error))
        }
        _state.value = _state.value.copy(guideProgress = null)
        refreshNowNext()
    }

    // ---------------------------------------------------------------- home screen

    /**
     * Everything the home screen draws, read in one pass off the main thread (FR-UI-24).
     *
     * Assembling the rails from this is [com.idanck.shidur.ui.home.HomeRails.build], which is
     * pure and unit-tested; this half is only the queries. Splitting them that way is what makes
     * the screen's rules checkable in an environment that cannot build the app.
     *
     * The radio playlist is read directly rather than through [openRadio], which would repoint
     * the whole browsing state at it — the home screen wants a rail of stations, not to *be* the
     * radio screen. It does borrow one thing from `openRadio`: merging the playlist row into
     * state if it is missing, because a cold start can bootstrap radio after the first
     * `reload()`, and `isRadioPlaying` answers by playlist id.
     */
    suspend fun loadHomeData(): HomeData {
        val activeId = _state.value.activePlaylist?.id
        val sort = settings.state.value.channelSort

        val radioPlaylist = withContext(Dispatchers.IO) {
            sources.ensureRadioPlaylist()
            dao.playlists().firstOrNull { it.enabled && it.url == IsraelRadioStations.M3U_URL }
        }
        if (radioPlaylist != null && _state.value.playlists.none { it.id == radioPlaylist.id }) {
            _state.value = _state.value.copy(playlists = _state.value.playlists + radioPlaylist)
        }

        val hideLocked = settings.state.value.hideLockedChannels
        fun visible(channels: List<Channel>) = if (hideLocked) channels.filter { !it.locked } else channels

        val channels = withContext(Dispatchers.IO) {
            activeId?.let { visible(dao.channels(it, sort = sort)) }.orEmpty()
        }
        val radio = withContext(Dispatchers.IO) {
            radioPlaylist?.let { playlist ->
                val groups = dao.groups(playlist.id, includeHidden = true)
                // The same stations the Radio screen shows, filtered the same way: a rail that
                // offered what that screen deliberately hides would be a second, contradictory
                // answer to the same question.
                visible(dao.channels(playlist.id)).filterNot {
                    RadioCategoryClassifier.isRussian(
                        it.displayName,
                        groups.firstOrNull { group -> group.id == it.groupId }?.name,
                    )
                }
            }.orEmpty()
        }
        val recent = withContext(Dispatchers.IO) { visible(dao.recentChannels(limit = RECENT_LIMIT)) }
        val favourites = withContext(Dispatchers.IO) { visible(dao.favouriteChannels()) }

        // One batched query for every rail's captions and for "starting soon" — one statement
        // per distinct EPG offset, not one per channel.
        val now = System.currentTimeMillis()
        val forGuide = (channels + radio + recent + favourites).distinctBy { it.id }
        val programmes = withContext(Dispatchers.IO) {
            epg.guideWindow(forGuide, now, now + HomeRails.STARTING_SOON_WINDOW_MS)
        }

        return HomeData(
            recent = recent,
            channels = channels,
            radio = radio,
            favourites = favourites,
            programmes = programmes,
            featured = ServiceLocator.featured.state.value,
        )
    }

    // ---------------------------------------------------------------- guide

    suspend fun guideWindow(channels: List<Channel>, fromUtc: Long, toUtc: Long): Map<Long, List<Programme>> {
        val (youtube, rest) = channels.partition { it.youtubeChannelId != null }
        val guide = withContext(Dispatchers.IO) { epg.guideWindow(rest, fromUtc, toUtc) }
        if (youtube.isEmpty()) return guide
        // What a YouTube channel streams live is its guide (ADR-061), from the shared cache: asked
        // for again only when the last answer is a few minutes old.
        val repo = ServiceLocator.youtube
        viewModelScope.launch { repo.refresh() }
        val now = System.currentTimeMillis()
        val states = repo.channels.value
        return guide + youtube.associate { channel ->
            channel.id to YouTubeChannels.programmes(channel, states[channel.youtubeChannelId], now)
                .filter { it.endUtc > fromUtc && it.startUtc < toUtc }
        }
    }

    private val _youtubeRequest = MutableStateFlow<String?>(null)

    /** A YouTube channel picked in the channel list or the guide, for the shell to open (ADR-061). */
    val youtubeRequest: StateFlow<String?> = _youtubeRequest

    fun consumeYoutubeRequest() {
        _youtubeRequest.value = null
    }

    suspend fun programmesFor(channel: Channel, fromUtc: Long, toUtc: Long): List<Programme> =
        withContext(Dispatchers.IO) { epg.programmesFor(channel, fromUtc, toUtc) }

    /**
     * What is being recorded, or is going to be, over a stretch of the guide — grouped by channel.
     *
     * FR-UI-10 asks for recording state to be badged in the grid alongside catch-up availability.
     * Only the archive badge was ever drawn, so a programme you had scheduled looked exactly like
     * one you had not, and the only way to check was to leave the guide for the recordings list.
     *
     * Matched by overlap rather than by an equal start time, because a scheduled recording is
     * padded by the pre- and post-roll settings (`RecordingScheduler.scheduleProgramme`): its
     * window is deliberately wider than the programme's, so the two are never equal.
     */
    suspend fun recordingsIn(fromUtc: Long, toUtc: Long): Map<Long, List<Recording>> =
        withContext(Dispatchers.IO) {
            ServiceLocator.dao.recordings()
                .filter { it.endUtc > fromUtc && it.startUtc < toUtc }
                .groupBy { it.channelId }
        }

    suspend fun unmatchedChannels(): List<Channel> = withContext(Dispatchers.IO) {
        _state.value.activePlaylist?.let { epg.unmatchedChannels(it.id) }.orEmpty()
    }

    suspend fun searchGuideEntries(query: String) = withContext(Dispatchers.IO) {
        epg.searchEpgChannels(query)
    }

    // ---------------------------------------------------------------- misc

    fun post(message: UiMessage) {
        _state.value = _state.value.copy(message = message)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun onSettingsChanged() {
        player.applySettingsChange()
    }

    override fun onCleared() {
        BackgroundPlayback.returnSkip(skipStation)
        BackgroundPlayback.publish(null)
        player.onScheduleRetry = null
        player.onNotice = null
        sport.close()
        super.onCleared()
    }

    private companion object {
        /** A single letter matches most of a playlist; two is where a result list means something. */
        const val MIN_SEARCH_LENGTH = 2
        const val SEARCH_DEBOUNCE_MS = 250L

        /** Shorter than the search debounce: this one only re-queries a single indexed table. */
        const val FILTER_DEBOUNCE_MS = 150L
    }
}
