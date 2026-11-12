package com.idanck.shidur.ui.podcasts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.podcast.PodcastChapters
import com.idanck.shidur.data.podcast.PodcastEpisode
import com.idanck.shidur.ui.AppViewModel
import com.idanck.shidur.ui.NowPlayingInfo
import com.idanck.shidur.ui.PodcastSession
import com.idanck.shidur.ui.common.EmptyState
import com.idanck.shidur.ui.common.ListRow
import com.idanck.shidur.ui.common.RowTrail
import com.idanck.shidur.ui.common.StudioButton
import com.idanck.shidur.ui.common.StudioButtonSize
import com.idanck.shidur.ui.common.StudioButtonVariant
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.common.StudioProgress
import com.idanck.shidur.ui.common.StudioSheet
import com.idanck.shidur.ui.theme.StudioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * `TvPodcastPlayer` / `PPodcastPlayer` (ADR-067): the cover, the episode and where it is, the
 * transport — 15 back, play/pause, 30 forward — then the speed, the sleep timer and the star, and
 * under them what plays next and the chapters, when the feed has them.
 */
@Composable
internal fun PodcastPlayerPage(
    vm: AppViewModel,
    onOpenShow: (Long) -> Unit,
    onOptions: (PodcastEpisode) -> Unit,
    onPlay: (PodcastEpisode, Boolean) -> Unit,
) {
    val state by vm.state.collectAsState()
    val session by vm.podcast.state.collectAsState()
    val status by vm.playbackStatus.collectAsState()
    val episode = (state.nowPlaying as? NowPlayingInfo.Podcast)?.episode ?: session.episode
    val tv = StudioTheme.television
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    if (episode == null) {
        EmptyState(text = stringResource(R.string.pod_empty_list), icon = StudioIcons.Podcast)
        return
    }
    // The position, read from the player twice a second while this page is up.
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(episode.lengthMs) }
    LaunchedEffect(episode.key) {
        while (true) {
            ServiceLocator.player.positionAndDuration()?.let { (p, d) ->
                position = p.coerceAtLeast(0)
                if (d > 0) duration = d
            }
            delay(500)
        }
    }
    var sleepOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val repo = ServiceLocator.podcasts
    val changes by repo.changes.collectAsState()
    val loaded by produceState(initialValue = episode, changes, episode.key) { value = repo.episode(episode.key) ?: episode }
    // produceState keeps the last episode's value until the new one is read: never let the star, or
    // anything else here, act on the episode that just ended.
    val fresh = if (loaded.key == episode.key) loaded else episode
    // What the star shows and writes next. Set on a tap, before the repository answers, so a second
    // quick tap undoes the first rather than writing the same value again.
    var favourite by remember(fresh.key, fresh.mark.favourite) { mutableStateOf(fresh.mark.favourite) }
    val queue by produceState(initialValue = emptyList<PodcastEpisode>(), changes) { value = repo.queue() }
    val chapterIndex = PodcastChapters.at(session.chapters, position)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (tv) 0.dp else 16.dp, vertical = if (tv) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 4.dp else 4.dp),
    ) {
        item(key = "now") {
            Row(horizontalArrangement = Arrangement.spacedBy(if (tv) 28.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                PodcastCover(title = episode.podcastTitle, imageUrl = episode.coverUrl, size = if (tv) 200.dp else 120.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 4.dp)) {
                    Text(
                        text = stringResource(R.string.pod_now_playing, episode.podcastTitle),
                        style = type.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = studio.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(text = episode.title, style = if (tv) type.title else type.h1, color = studio.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    episode.publishedUtc?.let { Text(text = TimeModel.formatDay(it), style = type.meta, color = studio.ink2) }
                    if (chapterIndex >= 0) {
                        Text(
                            text = stringResource(R.string.pod_chapter_of, chapterIndex + 1, session.chapters.size, session.chapters[chapterIndex].title),
                            style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = studio.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        item(key = "progress") {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StudioProgress(fraction = if (duration > 0) position.toFloat() / duration else 0f, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(text = TimeModel.ltr(clock(position)), style = type.caption, color = studio.ink, modifier = Modifier.weight(1f))
                    if (duration > 0) {
                        Text(text = stringResource(R.string.pod_left, TimeModel.ltr(clock(duration - position))), style = type.caption, color = studio.ink2)
                    }
                }
            }
        }
        item(key = "transport") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(if (tv) 10.dp else 8.dp, if (tv) Alignment.Start else Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StudioButton(label = stringResource(R.string.pod_back_15), onClick = { vm.podcast.skip(-PodcastSession.BACK_MS) }, icon = StudioIcons.SkipBack, iconOnly = true, size = StudioButtonSize.MEDIUM)
                StudioButton(
                    label = stringResource(if (status.playWhenReady) R.string.action_pause else R.string.action_play),
                    onClick = { ServiceLocator.player.togglePlayPause() },
                    icon = if (status.playWhenReady) StudioIcons.Pause else StudioIcons.Play,
                    iconOnly = true,
                    variant = StudioButtonVariant.PRIMARY,
                )
                StudioButton(label = stringResource(R.string.pod_forward_30), onClick = { vm.podcast.skip(PodcastSession.FORWARD_MS) }, icon = StudioIcons.SkipForward, iconOnly = true, size = StudioButtonSize.MEDIUM)
            }
        }
        item(key = "tools") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, if (tv) Alignment.Start else Alignment.CenterHorizontally),
            ) {
                StudioButton(label = speedText(status.speed), onClick = { vm.podcast.cycleSpeed() }, icon = StudioIcons.Speed, size = StudioButtonSize.MEDIUM)
                val sleep = session.sleep
                StudioButton(
                    label = when (sleep) {
                        is PodcastSession.Sleep.At -> stringResource(R.string.pod_sleep_in, lengthText((sleep.utc - System.currentTimeMillis()).coerceAtLeast(0)))
                        PodcastSession.Sleep.EndOfEpisode -> stringResource(R.string.pod_sleep_end)
                        PodcastSession.Sleep.Off -> stringResource(R.string.pod_sleep)
                    },
                    onClick = { sleepOpen = true },
                    icon = StudioIcons.Moon,
                    active = sleep != PodcastSession.Sleep.Off,
                    iconOnly = !tv && sleep == PodcastSession.Sleep.Off,
                    size = StudioButtonSize.MEDIUM,
                )
                StudioButton(
                    label = stringResource(if (favourite) R.string.pod_favourite_remove else R.string.pod_favourite_add),
                    onClick = {
                        val key = fresh.key
                        val next = !favourite
                        favourite = next
                        scope.launch { repo.setFavourite(key, next) }
                    },
                    // Filled when set, as every other star in the app, and amber (`active`) in both shells.
                    icon = if (favourite) StudioIcons.StarOn else StudioIcons.Star,
                    iconOnly = true,
                    active = favourite,
                    size = StudioButtonSize.MEDIUM,
                )
                StudioButton(label = stringResource(R.string.pod_open_show), onClick = { onOpenShow(episode.podcastId) }, icon = StudioIcons.Podcast, iconOnly = true, size = StudioButtonSize.MEDIUM)
            }
        }
        if (session.sleptAtUtc > 0) {
            item(key = "slept") {
                Text(text = stringResource(R.string.pod_slept), style = type.meta, color = studio.ink2, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
        val upNext = queue.filter { it.key != episode.key }.ifEmpty { listOfNotNull(session.next) }
        if (upNext.isNotEmpty()) {
            item(key = "next-h") { Text(text = stringResource(R.string.pod_up_next), style = type.h1, color = studio.ink, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            itemsIndexed(upNext, key = { _, e -> "q" + e.id }) { _, e ->
                EpisodeRow(e, onPlay = { onPlay(e, false) }, onOptions = { onOptions(e) })
            }
        }
        if (session.chapters.isNotEmpty()) {
            item(key = "ch-h") { Text(text = stringResource(R.string.pod_chapters), style = type.h1, color = studio.ink, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            itemsIndexed(session.chapters, key = { i, _ -> "ch$i" }) { i, chapter ->
                ListRow(
                    label = chapter.title,
                    num = TimeModel.ltr(clock(chapter.startMs)),
                    selected = i == chapterIndex,
                    onClick = { vm.podcast.seekTo(chapter.startMs) },
                )
            }
        } else {
            fresh.description?.let { text ->
                item(key = "desc") { Text(text = text, style = type.meta, color = studio.ink2, modifier = Modifier.padding(top = 12.dp)) }
            }
        }
    }

    if (sleepOpen) {
        StudioSheet(onDismissRequest = { sleepOpen = false }, title = stringResource(R.string.pod_sleep), drawer = true) {
            ListRow(label = stringResource(R.string.pod_off), onClick = { vm.podcast.setSleep(null); sleepOpen = false }, trail = if (session.sleep == PodcastSession.Sleep.Off) RowTrail.CHECK else RowTrail.NONE)
            listOf(15, 30, 45, 60, 90).forEach { minutes ->
                ListRow(label = stringResource(R.string.pod_sleep_minutes, minutes), onClick = { vm.podcast.setSleep(minutes); sleepOpen = false })
            }
            ListRow(
                label = stringResource(R.string.pod_sleep_end),
                onClick = { vm.podcast.setSleep(null, endOfEpisode = true); sleepOpen = false },
                trail = if (session.sleep == PodcastSession.Sleep.EndOfEpisode) RowTrail.CHECK else RowTrail.NONE,
            )
        }
    }
}
