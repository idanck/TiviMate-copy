package com.tivicopy.player.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.AppError
import com.tivicopy.player.core.Outcome
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.catchup.CatchupUrlBuilder
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.ChannelGroup
import com.tivicopy.player.data.model.DecoderMode
import com.tivicopy.player.data.model.EpgMatchStats
import com.tivicopy.player.data.model.Playlist
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.model.RefreshReport
import com.tivicopy.player.data.repo.EpgProgress
import com.tivicopy.player.player.PlaybackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A transient message for the user: a toast-style line with an optional error behind it. */
data class UiMessage(val text: String? = null, val error: AppError? = null, val id: Long = System.nanoTime())

/** Pseudo-group ids for the two synthetic views in the channel list. */
object VirtualGroup {
    const val ALL = -1L
    const val FAVOURITES = -2L
    const val RECENT = -3L
}

data class ChannelListState(
    val groups: List<ChannelGroup> = emptyList(),
    val selectedGroupId: Long = VirtualGroup.ALL,
    val channels: List<Channel> = emptyList(),
    val search: String = "",
)

data class AppUiState(
    val loading: Boolean = true,
    val playlists: List<Playlist> = emptyList(),
    val activePlaylist: Playlist? = null,
    val list: ChannelListState = ChannelListState(),
    val currentChannel: Channel? = null,
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

    init {
        player.onScheduleRetry = { delay, action -> main.postDelayed(action, delay) }
        player.onNotice = { error, what ->
            post(UiMessage(text = noticeText(error, what), error = error))
        }
        reload()
    }

    private fun noticeText(error: AppError, what: String): String = when {
        what == "software" -> getApplication<Application>().getString(com.tivicopy.player.R.string.decoder_fell_back)
        else -> getApplication<Application>().getString(com.tivicopy.player.R.string.failover_switched, what)
    }

    // ------------------------------------------------------------------ load

    fun reload() {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                val playlists = dao.playlists()
                val activeId = settings.state.value.activePlaylistId
                val active = playlists.firstOrNull { it.id == activeId } ?: playlists.firstOrNull()
                val groups = active?.let { dao.groups(it.id) }.orEmpty()
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
        loadChannels()
    }

    private fun loadChannels() {
        val active = _state.value.activePlaylist ?: run {
            _state.value = _state.value.copy(list = _state.value.list.copy(channels = emptyList()))
            return
        }
        val listState = _state.value.list
        viewModelScope.launch {
            val channels = withContext(Dispatchers.IO) {
                val hideLocked = settings.state.value.hideLockedChannels
                val raw = when (listState.selectedGroupId) {
                    VirtualGroup.ALL -> dao.channels(active.id, search = listState.search.takeIf { it.isNotBlank() })
                    VirtualGroup.FAVOURITES -> dao.channels(active.id, favouritesOnly = true, search = listState.search.takeIf { it.isNotBlank() })
                    VirtualGroup.RECENT -> dao.recentChannels()
                    else -> dao.channels(active.id, groupId = listState.selectedGroupId, search = listState.search.takeIf { it.isNotBlank() })
                }
                if (hideLocked) raw.filter { !it.locked } else raw
            }
            _state.value = _state.value.copy(list = _state.value.list.copy(channels = channels))
        }
    }

    // --------------------------------------------------------------- playback

    fun play(channel: Channel) {
        val playlist = _state.value.playlists.firstOrNull { it.id == channel.playlistId }
        val profile = playlist?.let { sources.profileFor(it) }
            ?: com.tivicopy.player.data.net.RequestProfile(userAgent = settings.state.value.userAgent)
        player.play(channel, profile)
        settings.rememberChannel(channel.id)
        _state.value = _state.value.copy(currentChannel = channel)
        viewModelScope.launch(Dispatchers.IO) {
            dao.recordWatch(channel.id, System.currentTimeMillis())
        }
        refreshNowNext(channel)
    }

    /** FR-PLB-18/19: relative zapping within whatever list is on screen. */
    fun zap(delta: Int) {
        val channels = _state.value.list.channels.ifEmpty { return }
        val currentId = _state.value.currentChannel?.id
        val index = channels.indexOfFirst { it.id == currentId }
        val nextIndex = if (index < 0) 0 else ((index + delta) % channels.size + channels.size) % channels.size
        play(channels[nextIndex])
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

    fun resumeStartChannel() {
        val s = settings.state.value
        if (!s.resumeLastChannel) return
        val id = if (s.startChannelId != 0L) s.startChannelId else s.lastChannelId
        if (id == 0L) return
        viewModelScope.launch {
            val channel = withContext(Dispatchers.IO) { dao.channel(id) } ?: return@launch
            play(channel)
        }
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
            withContext(Dispatchers.IO) { dao.upsertChannel(updated) }
            val playlist = _state.value.playlists.firstOrNull { it.id == channel.playlistId }
            val profile = playlist?.let { sources.profileFor(it) }
                ?: com.tivicopy.player.data.net.RequestProfile(userAgent = settings.state.value.userAgent)
            _state.value = _state.value.copy(currentChannel = updated)
            player.reloadWithDecoders(updated, profile)
            loadChannels()
        }
    }

    /** FR-CU-03/04: play a past programme from the provider's archive. */
    fun playCatchup(channel: Channel, programme: Programme) {
        val now = System.currentTimeMillis()
        if (!channel.hasCatchup) {
            post(UiMessage(error = AppError(AppError.Kind.CatchupUnsupported)))
            return
        }
        if (!CatchupUrlBuilder.isWithinWindow(channel, programme.startUtc, now)) {
            post(UiMessage(error = AppError(AppError.Kind.OutsideCatchupWindow)))
            return
        }
        val url = CatchupUrlBuilder.build(channel, programme.startUtc, programme.endUtc, now)
        if (url == null) {
            post(UiMessage(error = AppError(AppError.Kind.CatchupUnsupported)))
            return
        }
        val playlist = _state.value.playlists.firstOrNull { it.id == channel.playlistId }
        val profile = playlist?.let { sources.profileFor(it) }
            ?: com.tivicopy.player.data.net.RequestProfile(userAgent = settings.state.value.userAgent)
        player.playUrl(programme.title, url, profile)
        _state.value = _state.value.copy(currentChannel = channel, now = programme)
    }

    /** Plays a VOD item or series episode through the owning playlist's request profile. */
    fun playVod(item: com.tivicopy.player.data.model.VodItem) {
        val playlist = _state.value.playlists.firstOrNull { it.id == item.playlistId }
            ?: _state.value.activePlaylist
            ?: return
        val url = sources.xtreamClient(playlist)?.vodUrl(item.streamId, item.containerExtension)
            ?: return post(UiMessage(error = AppError(AppError.Kind.NotFound, "on-demand needs an Xtream source")))
        player.playUrl(item.name, url, sources.profileFor(playlist), item.resumePositionMs)
    }

    /** Plays a completed recording from wherever it was written. */
    fun playRecording(recording: com.tivicopy.player.data.model.Recording) {
        val path = recording.filePath
            ?: return post(UiMessage(error = AppError(AppError.Kind.NotFound, "recording has no file")))
        val uri = if (path.startsWith("/")) "file://$path" else path
        player.playUrl(recording.title, uri, com.tivicopy.player.data.net.RequestProfile(), recording.resumePositionMs)
    }

    // ---------------------------------------------------------------- editing

    fun toggleFavourite(channel: Channel) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.setChannelFlags(channel.id, favourite = !channel.favourite) }
            loadChannels()
        }
    }

    fun setHidden(channel: Channel, hidden: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.setChannelFlags(channel.id, hidden = hidden) }
            loadChannels()
        }
    }

    fun setLocked(channel: Channel, locked: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.setChannelFlags(channel.id, locked = locked) }
            loadChannels()
        }
    }

    fun renameChannel(channel: Channel, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.upsertChannel(channel.copy(customName = name.takeIf { it.isNotBlank() }))
            }
            loadChannels()
        }
    }

    /** FR-ORG-05: move-mode reordering, one step at a time so a D-pad can drive it. */
    fun moveChannel(channel: Channel, delta: Int) {
        val channels = _state.value.list.channels
        val index = channels.indexOfFirst { it.id == channel.id }
        val target = index + delta
        if (index < 0 || target !in channels.indices) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val other = channels[target]
                dao.upsertChannel(channel.copy(sortIndex = other.sortIndex))
                dao.upsertChannel(other.copy(sortIndex = channel.sortIndex))
            }
            loadChannels()
        }
    }

    /** FR-EPG-12: a per-channel offset that never touches any other channel's rows. */
    fun setChannelEpgOffset(channel: Channel, minutes: Int?) {
        viewModelScope.launch {
            val clamped = minutes?.let(TimeModel::clampOffset)
            withContext(Dispatchers.IO) { dao.upsertChannel(channel.copy(epgOffsetMinutes = clamped)) }
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

    suspend fun searchProgrammes(query: String): List<Programme> =
        withContext(Dispatchers.IO) { epg.searchProgrammes(query, System.currentTimeMillis()) }

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
}
