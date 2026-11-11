package com.idanck.shidur.ui

import com.idanck.shidur.data.net.RequestProfile
import com.idanck.shidur.data.podcast.PodcastChapters
import com.idanck.shidur.data.podcast.PodcastEpisode
import com.idanck.shidur.data.podcast.PodcastRepository
import com.idanck.shidur.player.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A podcast episode playing (ADR-067): the full-screen player engine plays its audio, and this
 * keeps what belongs to the episode around it — where it got to, what comes next, the sleep timer,
 * the chapters and the speed.
 *
 * The position is written every [SAVE_EVERY_MS] while it plays, on pause, on every switch away and
 * from `MainActivity.onStop`, so a phone that dies keeps its place to the second. At the end the
 * episode is marked played and the next one starts: the head of the queue, else the next of the
 * same show ([PodcastRepository.nextAfter]).
 *
 * Held by [AppViewModel], which owns what is on screen; this is told when an episode starts and
 * when something else takes over.
 */
class PodcastSession(
    private val player: PlayerEngine,
    private val repo: PodcastRepository,
    private val scope: CoroutineScope,
    /**
     * Where positions are written: one that outlives the screen, since the last write happens in
     * `Activity.onStop`, right before the view model's scope is cancelled (see `saveVodProgress`).
     */
    private val writeScope: CoroutineScope,
    /** Starts the next episode through the view model, so `nowPlaying` follows. */
    private val playNext: (PodcastEpisode) -> Unit,
) {

    /** The sleep timer: off, a time it ends at, or the end of the episode. */
    sealed interface Sleep {
        data object Off : Sleep
        data class At(val utc: Long) : Sleep
        data object EndOfEpisode : Sleep
    }

    data class State(
        val episode: PodcastEpisode? = null,
        val chapters: List<PodcastChapters.Chapter> = emptyList(),
        val next: PodcastEpisode? = null,
        val sleep: Sleep = Sleep.Off,
        /** Set when the sleep timer stopped the sound, for "הניגון נעצר" (TvPodcastStates). */
        val sleptAtUtc: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** The speeds the player's button steps through. */
    val speeds = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    private var ticker: Job? = null
    private var sleepJob: Job? = null

    init {
        // The end of an episode, once per item: `ended` is cleared when anything starts.
        scope.launch {
            player.status.distinctUntilChangedBy { it.ended }.collect { status ->
                if (status.ended) onEnded()
            }
        }
        // A pause is a place worth keeping at once, not in ten seconds.
        scope.launch {
            player.status.distinctUntilChangedBy { it.playWhenReady }.collect { status ->
                if (!status.playWhenReady) save()
            }
        }
    }

    /**
     * Starts [episode] from its mark (or [fromStart]), past the show's own intro skip when it
     * would begin at the very start. [speed] is the show's, or 1.
     */
    fun start(episode: PodcastEpisode, fromStart: Boolean, speed: Float, skipIntroSeconds: Int) {
        save()
        val mark = episode.mark.positionMs.takeIf { !fromStart && !episode.mark.played } ?: 0L
        val startAt = if (mark == 0L && skipIntroSeconds > 0) skipIntroSeconds * 1000L else mark
        player.playUrl(episode.title, episode.audioUrl, RequestProfile(), startAt, if (speed > 0f) speed else 1f)
        val keepSleep = _state.value.sleep.takeIf { it is Sleep.At } ?: Sleep.Off
        _state.value = State(episode = episode, sleep = keepSleep)
        startTicker()
        scope.launch {
            val chapters = episode.chaptersUrl?.let { PodcastChapters.fetch(it) }.orEmpty()
            val next = repo.nextAfter(episode.key)
            if (_state.value.episode?.key == episode.key) _state.value = _state.value.copy(chapters = chapters, next = next)
        }
    }

    /** Something else took the player: the place is written down and the session ends. */
    fun leave() {
        save()
        ticker?.cancel()
        ticker = null
        _state.value = State(sleep = _state.value.sleep.takeIf { it is Sleep.At } ?: Sleep.Off)
    }

    val active: Boolean get() = _state.value.episode != null

    /** Where the player is, written to the episode's mark. Safe to call when nothing plays. */
    fun save() {
        val episode = _state.value.episode ?: return
        val (position, duration) = player.positionAndDuration() ?: return
        if (position < 0) return
        val length = duration.takeIf { it > 0 } ?: episode.lengthMs
        writeScope.launch { runCatching { repo.savePosition(episode.key, position, length) } }
    }

    fun skip(deltaMs: Long) = player.skipBy(deltaMs)

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    /** The next speed in [speeds] after the current one, kept as the show's own. */
    fun cycleSpeed() {
        val episode = _state.value.episode ?: return
        val now = player.status.value.speed
        val next = speeds.firstOrNull { it > now + 0.01f } ?: speeds.first()
        player.setSpeed(next)
        scope.launch {
            val podcast = repo.podcast(episode.podcastId) ?: return@launch
            repo.setPrefs(podcast.id, podcast.prefs.copy(speed = next))
        }
    }

    /** Off, or [minutes] from now, or the end of the episode with [minutes] null and [endOfEpisode]. */
    fun setSleep(minutes: Int?, endOfEpisode: Boolean = false) {
        sleepJob?.cancel()
        val sleep = when {
            endOfEpisode -> Sleep.EndOfEpisode
            minutes != null && minutes > 0 -> Sleep.At(System.currentTimeMillis() + minutes * 60_000L)
            else -> Sleep.Off
        }
        _state.value = _state.value.copy(sleep = sleep, sleptAtUtc = 0)
        if (sleep is Sleep.At) {
            sleepJob = scope.launch {
                delay((sleep.utc - System.currentTimeMillis()).coerceAtLeast(0))
                fallAsleep()
            }
        }
    }

    fun dismissSlept() {
        _state.value = _state.value.copy(sleptAtUtc = 0)
    }

    private fun fallAsleep() {
        save()
        player.setPlayWhenReady(false)
        _state.value = _state.value.copy(sleep = Sleep.Off, sleptAtUtc = System.currentTimeMillis())
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(SAVE_EVERY_MS)
                if (player.status.value.playing) save()
            }
        }
    }

    private suspend fun onEnded() {
        val episode = _state.value.episode ?: return
        val length = player.positionAndDuration()?.second?.takeIf { it > 0 } ?: episode.lengthMs
        runCatching { repo.savePosition(episode.key, length, length) }
        if (_state.value.sleep == Sleep.EndOfEpisode) {
            _state.value = _state.value.copy(sleep = Sleep.Off, sleptAtUtc = System.currentTimeMillis())
            return
        }
        val next = repo.nextAfter(episode.key) ?: return
        playNext(next)
    }

    companion object {
        const val SAVE_EVERY_MS = 10_000L
        const val BACK_MS = 15_000L
        const val FORWARD_MS = 30_000L
    }
}
