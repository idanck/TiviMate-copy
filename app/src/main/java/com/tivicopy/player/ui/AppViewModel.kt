package com.tivicopy.player.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.AppError
import com.tivicopy.player.core.Outcome
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.catchup.CatchupUrlBuilder
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.ChannelGroup
import com.tivicopy.player.data.model.ChannelSort
import com.tivicopy.player.data.model.DecoderMode
import com.tivicopy.player.data.model.EpgMatchStats
import com.tivicopy.player.data.model.Playlist
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.model.Recording
import com.tivicopy.player.data.model.RefreshReport
import com.tivicopy.player.data.model.VodItem
import com.tivicopy.player.data.repo.EpgProgress
import com.tivicopy.player.data.xtream.isEpisode
import com.tivicopy.player.data.xtream.isSeriesContainer
import com.tivicopy.player.player.PlaybackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Below this, resuming would rewind to somewhere the viewer had barely arrived. */
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
}

data class ChannelListState(
    val groups: List<ChannelGroup> = emptyList(),
    val selectedGroupId: Long = VirtualGroup.ALL,
    val channels: List<Channel> = emptyList(),
    /** The channel-list filter box. Scoped to the selected group, and always visible when set. */
    val search: String = "",
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
    val vod: List<com.tivicopy.player.data.model.VodItem> = emptyList(),
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

    init {
        player.onScheduleRetry = { delay, action -> main.postDelayed(action, delay) }
        player.onNotice = { error, what ->
            post(UiMessage(text = noticeText(error, what), error = error))
        }
        reload()
    }

    // `ServiceLocator.locale`, not the application context: `Application` is attached once and
    // never rebuilt, so after a language change it still resolves the previous one until the
    // process dies (FR-UI-23).
    private fun noticeText(error: AppError, what: String): String {
        val strings = ServiceLocator.locale
        return when {
            what == "software" -> strings.getString(com.tivicopy.player.R.string.decoder_fell_back)
            else -> strings.getString(com.tivicopy.player.R.string.failover_switched, what)
        }
    }

    // ------------------------------------------------------------------ load

    fun reload() {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                val playlists = dao.playlists()
                val activeId = settings.state.value.activePlaylistId
                val active = playlists.firstOrNull { it.id == activeId } ?: playlists.firstOrNull()
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
    }

    fun selectPlaylist(id: Long) {
        settings.setActivePlaylist(id)
        reload()
    }

    fun selectGroup(groupId: Long) {
        _state.value = _state.value.copy(list = _state.value.list.copy(selectedGroupId = groupId))
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
                val raw = when (listState.selectedGroupId) {
                    VirtualGroup.ALL -> dao.channels(active.id, search = search, sort = sort)
                    VirtualGroup.FAVOURITES ->
                        dao.channels(active.id, favouritesOnly = true, search = search, sort = sort)
                    // The filter box used to do nothing at all here, because the argument was
                    // simply not passed on.
                    VirtualGroup.RECENT -> dao.recentChannels(search = search)
                    // Always in the playlist's own order: this is a repair screen, and the point
                    // is to find the channel you hid, not to browse.
                    VirtualGroup.HIDDEN -> dao.channels(active.id, hiddenOnly = true, search = search)
                    else -> dao.channels(
                        active.id,
                        groupId = listState.selectedGroupId,
                        search = search,
                        sort = sort,
                    )
                }
                if (hideLocked) raw.filter { !it.locked } else raw
            }
            _state.value = _state.value.copy(list = _state.value.list.copy(channels = channels))
        }
    }

    // --------------------------------------------------------------- playback

    fun play(channel: Channel) {
        leaveVod()
        val playlist = _state.value.playlists.firstOrNull { it.id == channel.playlistId }
        val profile = playlist?.let { sources.profileFor(it) }
            ?: com.tivicopy.player.data.net.RequestProfile(userAgent = settings.state.value.userAgent)
        player.play(channel, profile)
        settings.rememberChannel(channel.id)
        _state.value = _state.value.copy(
            currentChannel = channel,
            nowPlaying = NowPlayingInfo.Live(channel),
        )
        viewModelScope.launch(Dispatchers.IO) {
            dao.recordWatch(channel.id, System.currentTimeMillis())
        }
        refreshNowNext(channel)
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
            com.tivicopy.player.data.prefs.StartBehaviour.SPECIFIC_CHANNEL -> s.startChannelId
            com.tivicopy.player.data.prefs.StartBehaviour.LAST_CHANNEL ->
                if (s.resumeLastChannel) s.lastChannelId else 0L
            // The list and guide behaviours open an overlay instead; the shell handles that,
            // and starting a stream underneath it would waste a provider connection.
            else -> 0L
        }
        if (id == 0L) return false
        val channel = withContext(Dispatchers.IO) { dao.channel(id) } ?: return false
        play(channel)
        return true
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
                ?: com.tivicopy.player.data.net.RequestProfile(userAgent = settings.state.value.userAgent)
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
            ?: com.tivicopy.player.data.net.RequestProfile(userAgent = settings.state.value.userAgent)
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
        playingVod = null
        // Every source switch passes through here, so a resume read still in flight belongs
        // to a session that just ended — without this, its deferred start could replace the
        // live channel that interrupted it.
        vodPlayGeneration++
    }

    /**
     * Writes where the viewer got to, if that is anywhere worth returning to.
     *
     * A position inside the first minute is not a place anyone wants resumed, and one in the last
     * few per cent means they finished — that is stored as zero, so the title leaves the
     * continue-watching row rather than sitting at 99% for good.
     */
    fun saveVodProgress() {
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
        player.playUrl(recording.title, uri, com.tivicopy.player.data.net.RequestProfile(), recording.resumePositionMs)
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.setChannelFlags(channel.id, favourite = favourite) }
            loadChannels()
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

    // ---------------------------------------------------------------- refresh

    fun refreshPlaylist(playlistId: Long? = null) {
        val id = playlistId ?: _state.value.activePlaylist?.id ?: return
        _state.value = _state.value.copy(refreshing = true)
        viewModelScope.launch {
            when (val result = sources.refresh(id)) {
                is Outcome.Ok -> {
                    _state.value = _state.value.copy(lastRefresh = result.value)
                    post(UiMessage(text = null))
                }
                is Outcome.Err -> post(UiMessage(error = result.error))
            }
            _state.value = _state.value.copy(refreshing = false)
            reload()
        }
    }

    fun refreshGuide() {
        val playlist = _state.value.activePlaylist ?: return
        _state.value = _state.value.copy(guideProgress = EpgProgress(0, 0))
        viewModelScope.launch {
            val result = epg.refreshGuide(playlist) { progress ->
                _state.value = _state.value.copy(guideProgress = progress)
            }
            when (result) {
                is Outcome.Ok -> _state.value = _state.value.copy(guideStats = result.value)
                is Outcome.Err -> post(UiMessage(error = result.error))
            }
            _state.value = _state.value.copy(guideProgress = null)
            refreshNowNext()
        }
    }

    // ---------------------------------------------------------------- guide

    suspend fun guideWindow(channels: List<Channel>, fromUtc: Long, toUtc: Long): Map<Long, List<Programme>> =
        withContext(Dispatchers.IO) { epg.guideWindow(channels, fromUtc, toUtc) }

    suspend fun programmesFor(channel: Channel, fromUtc: Long, toUtc: Long): List<Programme> =
        withContext(Dispatchers.IO) { epg.programmesFor(channel, fromUtc, toUtc) }

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
        player.onScheduleRetry = null
        player.onNotice = null
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
