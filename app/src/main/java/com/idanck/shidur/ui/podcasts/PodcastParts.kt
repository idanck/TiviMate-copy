package com.idanck.shidur.ui.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.idanck.shidur.R
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.podcast.EpisodeStatus
import com.idanck.shidur.data.podcast.PodcastEpisode
import com.idanck.shidur.ui.common.FocusFill
import com.idanck.shidur.ui.common.ListRow
import com.idanck.shidur.ui.common.RowTrail
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.common.focusLift
import com.idanck.shidur.ui.common.focusSurface
import com.idanck.shidur.ui.theme.StudioTheme

/**
 * `PodArt`: a podcast's square cover — the feed's image when it loads, else one of six dark tones
 * picked from the name with the podcast glyph on it, never a meaning colour. [badge] is how many
 * new episodes wait, on a dark pill in the corner.
 */
@Composable
internal fun PodcastCover(
    title: String,
    imageUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    badge: Int = 0,
) {
    val shape = RoundedCornerShape(if (size >= 120.dp) 12.dp else if (size >= 60.dp) 10.dp else 8.dp)
    var failed by remember(imageUrl) { mutableStateOf(imageUrl == null) }
    Box(modifier = modifier.size(size).clip(shape).background(coverTone(title)), contentAlignment = Alignment.Center) {
        if (failed) {
            Icon(
                imageVector = StudioIcons.Podcast,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.22f),
                modifier = Modifier.size(size * 0.36f),
            )
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.size(size),
            )
        }
        if (badge > 0) {
            val tv = StudioTheme.television
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(if (tv) 6.dp else 6.dp)
                    .clip(CircleShape)
                    .background(Color(0xD105070C))
                    .padding(horizontal = if (tv) 6.dp else 7.dp, vertical = 1.dp),
            ) {
                Text(text = badge.toString(), style = StudioTheme.type.caption.copy(fontWeight = FontWeight.Bold), color = Color.White)
            }
        }
    }
}

/**
 * A cover with its name under it, which the remote can stop on: the library's grid, the popular
 * rail, the library rail on "בשבילך". The ring and lift are the tile kind of focus ([FocusFill.NONE]).
 */
@Composable
internal fun PodcastTile(
    title: String,
    caption: String?,
    imageUrl: String?,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
) {
    var focused by remember { mutableStateOf(false) }
    val tv = StudioTheme.television
    val shape = RoundedCornerShape(12.dp)
    Column(modifier = modifier.width(size), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .focusLift(focused && tv, shape = shape)
                .focusSurface(focused = focused && tv, shape = shape, fill = FocusFill.NONE)
                .onFocusChanged { focused = it.isFocused }
                .clickable(role = Role.Button, onClick = onClick),
        ) {
            PodcastCover(title = title, imageUrl = imageUrl, size = size, badge = badge)
        }
        Text(
            text = title,
            style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = StudioTheme.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!caption.isNullOrBlank()) {
            Text(text = caption, style = StudioTheme.type.caption, color = StudioTheme.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * `PodEpisodeRow`: one episode as a row — a control under the remote, the lit surface in focus.
 * The glyph says where the viewer is with it (played: the ringed check), the second line the show
 * and the date or how much is left, the value its length. The one playing is amber. A long press,
 * or Menu on a television, opens its options.
 */
@Composable
internal fun EpisodeRow(
    episode: PodcastEpisode,
    onPlay: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
    showPodcast: Boolean = true,
    playing: Boolean = false,
) {
    val status = episode.status
    val sub = buildList {
        if (showPodcast) add(episode.podcastTitle)
        episode.publishedUtc?.let { add(TimeModel.formatDay(it)) }
        when (status) {
            EpisodeStatus.STARTED -> episode.remainingMs?.let { add(stringResource(R.string.pod_left, lengthText(it))) }
            EpisodeStatus.PLAYED -> add(stringResource(R.string.pod_played))
            EpisodeStatus.NEW -> Unit
        }
        if (episode.mark.favourite) add("★")
    }.joinToString(" · ").let { if (status == EpisodeStatus.NEW) "• $it" else it }
    ListRow(
        label = episode.title,
        onClick = onPlay,
        onLongClick = onOptions,
        modifier = modifier,
        sub = sub.ifBlank { null },
        icon = when {
            playing -> StudioIcons.Pause
            status == EpisodeStatus.PLAYED -> StudioIcons.Played
            else -> StudioIcons.Play
        },
        trail = RowTrail.VALUE,
        value = episode.lengthMs.takeIf { it > 0 }?.let { TimeModel.ltr(clock(it)) },
        selected = playing,
    )
}

/** `54:20` or `1:02:40`. */
internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
}

/** "26 דק׳" or "1:05 ש׳", for how much is left. */
@Composable
internal fun lengthText(ms: Long): String {
    val minutes = ((ms + 59_999) / 60_000).toInt()
    return if (minutes >= 60) stringResource(R.string.pod_hours_short, minutes / 60, minutes % 60)
    else stringResource(R.string.pod_minutes_short, minutes)
}

/** One of six dark tones, by the name, as `PodArt` draws them. */
private fun coverTone(title: String): Brush {
    val tones = listOf(
        Color(0xFF34467A) to Color(0xFF141B33),
        Color(0xFF4A2E63) to Color(0xFF1A1026),
        Color(0xFF23585A) to Color(0xFF0D2122),
        Color(0xFF5C3E26) to Color(0xFF21160D),
        Color(0xFF3A3A66) to Color(0xFF14142A),
        Color(0xFF5A2C3F) to Color(0xFF220F17),
    )
    val (a, b) = tones[(title.hashCode() and 0x7fffffff) % tones.size]
    return Brush.linearGradient(listOf(a, b))
}
