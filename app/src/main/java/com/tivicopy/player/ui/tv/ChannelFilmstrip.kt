package com.tivicopy.player.ui.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.onFocusChanged
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
import com.tivicopy.player.ui.common.focusLift

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
    val metrics = LocalTvMetrics.current
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

    LazyRow(
        state = listState,
        // A floating plate rather than an edge-to-edge band, so the reel belongs to the same
        // family as the navigation capsule and the transport bar (`TvSurfaces.kt`) instead of
        // reading as a black bar the picture stops at.
        modifier = modifier
            .padding(horizontal = TV_CONTENT_INSET, vertical = 12.dp)
            .tvGlassPanel(opacity),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 14.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(channels, key = { it.id }) { channel ->
            FilmstripTile(
                channel = channel,
                now = nowByChannel[channel.id],
                playing = channel.id == state.currentChannel?.id,
                logoSize = metrics.rowLogoSize,
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

@Composable
private fun FilmstripTile(
    channel: Channel,
    now: Programme?,
    playing: Boolean,
    logoSize: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            // Grows on focus and is drawn over its neighbours while it does, as the home rails
            // do — through a `graphicsLayer`, so the reel does not re-measure and the tiles
            // either side of it stay where they are.
            .focusLift(focused)
            .width(TILE_WIDTH)
            // The same three states, the same ring, the same corner as a home tile: one card
            // look for the whole shell (`TvSurfaces.kt`), rather than this file's own copy of it.
            .tvCard(focused = focused, playing = playing)
            .onFocusChanged { focused = it.isFocused }
            // `clickable` carries the focus target on its own; pairing it with `focusable()`
            // would cost the remote a second press to leave every tile.
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            ChannelLogo(channel = channel, sizeDp = logoSize)
        }
        Text(
            text = channel.displayName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = now?.title ?: stringResource(R.string.no_guide_data),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        now?.let {
            Text(
                text = TimeModel.ltr(TimeModel.formatClockRange(it.startUtc, it.endUtc)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How many channels either side of the playing one to caption with what is on now. */
private const val NEIGHBOURS = 8

/** Wide enough for a channel name and a programme title at ten feet, narrow enough to show several. */
private val TILE_WIDTH = 200.dp
