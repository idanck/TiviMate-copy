package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.theme.StudioTheme

/**
 * A reel of neighbouring channels along the bottom of playing video (FR-UI-04).
 *
 * This replaces the old mini guide, which listed the *current* channel's schedule in a `LazyRow`
 * with neither `focusable()` nor `clickable()` on anything in it. On a remote that made it a
 * picture of a timeline: nothing in it could be reached, let alone chosen, and the only way out
 * was Back. It satisfied "a single-channel horizontal timeline over playing video" as text and
 * nothing as behaviour.
 *
 * What a remote actually wants at that moment is the next channel, so this shows channels rather
 * than programmes, each captioned with what is on it now. Left and Right walk the reel, OK tunes,
 * and the video behind never stops or shrinks — which is the whole difference between changing
 * channel and going somewhere to change channel.
 */
@Composable
fun ChannelFilmstrip(
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val channels = state.list.channels
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    var nowByChannel by remember { mutableStateOf<Map<Long, Programme>>(emptyMap()) }

    if (channels.isEmpty()) return

    val currentIndex = channels.indexOfFirst { it.id == state.currentChannel?.id }

    // Open on the playing channel with focus already on it, so the first Right press is the next
    // channel rather than the first channel in the playlist.
    LaunchedEffect(channels, state.currentChannel?.id) {
        if (currentIndex < 0) return@LaunchedEffect
        listState.scrollToItem(currentIndex)
        withFrameNanos { }
        runCatching { currentFocus.requestFocus() }
    }

    // What is on the channels either side, fetched the way the channel list does it: one windowed
    // query over what is on screen, replaced rather than accumulated. A reel over live video is
    // exactly the wrong place to ask the database for five hundred rows.
    LaunchedEffect(channels, currentIndex) {
        if (currentIndex < 0) {
            nowByChannel = emptyMap()
            return@LaunchedEffect
        }
        val first = (currentIndex - NEIGHBOURS).coerceAtLeast(0)
        val last = (currentIndex + NEIGHBOURS + 1).coerceAtMost(channels.size)
        if (first >= last) return@LaunchedEffect
        val now = System.currentTimeMillis()
        nowByChannel = channels.subList(first, last).mapNotNull { channel ->
            vm.programmesFor(channel, now, now + 1)
                .firstOrNull { now in it.startUtc until it.endUtc }
                ?.let { channel.id to it }
        }.toMap()
    }

    // `TvFilmstrip`: no plate. The reel runs edge to edge over the foot of the picture, on the
    // canvas's bottom scrim, under a heading that says what the arrows do.
    val studio = StudioTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to studio.ground.copy(alpha = 0f),
                    0.25f to studio.ground.copy(alpha = 0.55f * opacity.coerceAtLeast(0.5f)),
                    0.5f to studio.ground.copy(alpha = 0.95f * opacity.coerceAtLeast(0.5f)),
                    1f to studio.ground.copy(alpha = opacity.coerceAtLeast(0.5f)),
                ),
            )
            .padding(top = 40.dp, bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = TV_CONTENT_INSET),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = stringResource(R.string.nav_live), style = StudioTheme.type.h1, color = studio.ink)
            Text(
                text = stringResource(R.string.filmstrip_hint),
                style = StudioTheme.type.caption,
                color = studio.ink2,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        LazyRow(
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = TV_CONTENT_INSET,
                vertical = 14.dp,
            ),
            // `--st-gap-rail`: 32px at 1080p.
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(channels, key = { it.id }) { channel ->
                val programme = nowByChannel[channel.id]
                val now = System.currentTimeMillis()
                StudioChannelCard(
                    label = channel.displayName,
                    imageUrl = channel.displayLogo,
                    number = channel.number.takeIf { it > 0 },
                    now = programme?.title ?: stringResource(R.string.no_guide_data),
                    time = programme?.let { TimeModel.formatClockRange(it.startUtc, it.endUtc) },
                    progress = programme?.let {
                        ((now - it.startUtc).toFloat() / (it.endUtc - it.startUtc).coerceAtLeast(1)).coerceIn(0f, 1f)
                    },
                    onScreen = channel.id == state.currentChannel?.id,
                    circular = false,
                    width = CARD_WIDTH,
                    onClick = { onPlay(channel) },
                    modifier = if (channel.id == state.currentChannel?.id) {
                        Modifier.focusRequester(currentFocus)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/** How many channels either side of the playing one to caption with what is on now. */
private const val NEIGHBOURS = 8

/** `ChannelCard`'s width: 320px at 1080p. */
private val CARD_WIDTH = 160.dp
