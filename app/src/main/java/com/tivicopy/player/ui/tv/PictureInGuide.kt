package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.ProgrammeProgress
import com.tivicopy.player.ui.screens.GuideScreen

/**
 * The guide, laid out around the picture instead of over it.
 *
 * The ten-foot guide used to be a full-screen sheet drawn at 85% opacity across the video: the
 * stream kept playing underneath, which satisfied the requirement on paper, but nothing of it was
 * visible or watchable. Opening the guide therefore cost you the programme you were watching, and
 * the way back to it was a Back press — on a remote, where browsing the schedule while something
 * plays is the whole point of a guide.
 *
 * Here the video is not covered at all. It is moved: [TvRoot] shrinks the one player surface into
 * the slot this layout reserves for it, so the picture keeps running, in view, while the rail and
 * the grid own the rest of the screen. Nothing re-tunes, because nothing is torn down — the same
 * surface simply measures smaller.
 *
 * The panes are painted individually and the preview slot is left unpainted. A single full-screen
 * scrim would have been simpler and would have tinted the live picture with it.
 */
@Composable
fun PictureInGuide(
    vm: AppViewModel,
    /** Reports where the live preview should be drawn, in root coordinates. */
    onDockBounds: (Rect) -> Unit,
    onPlay: (Channel) -> Unit,
    onOpenProgramme: (Channel, Programme) -> Unit,
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val metrics = LocalTvMetrics.current
    val pane = MaterialTheme.colorScheme.background.copy(alpha = opacity)

    Row(modifier = modifier.fillMaxSize()) {
        ChannelRail(
            vm = vm,
            onPlay = onPlay,
            modifier = Modifier
                .width(metrics.groupColumnWidth)
                .fillMaxHeight()
                .background(pane),
        )

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Deliberately empty, and deliberately unpainted: this box exists only to be
                // measured. The live picture is drawn into the rectangle it reports.
                Box(
                    modifier = Modifier
                        .width(PREVIEW_WIDTH)
                        .fillMaxHeight()
                        .padding(PREVIEW_MARGIN)
                        .onGloballyPositioned { coordinates ->
                            val origin = coordinates.positionInRoot()
                            onDockBounds(
                                Rect(
                                    left = origin.x,
                                    top = origin.y,
                                    right = origin.x + coordinates.size.width,
                                    bottom = origin.y + coordinates.size.height,
                                ),
                            )
                        },
                )
                NowPlayingCaption(
                    channel = state.currentChannel,
                    now = state.now,
                    next = state.next,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(pane)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            GuideScreen(
                vm = vm,
                onPlay = onPlay,
                onOpenProgramme = onOpenProgramme,
                modifier = Modifier.fillMaxSize().background(pane),
            )
        }
    }
}

/**
 * The always-present channel column.
 *
 * Not the full channel-list screen: that one owns groups, filters and a now/next pane, and it is
 * reached on its own. This is the rail the guide keeps beside it so the channel being browsed and
 * the channel being watched are both on screen at once — pressing OK here tunes without closing
 * the guide, which is what makes the preview worth having.
 */
@Composable
private fun ChannelRail(
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val channels = state.list.channels
    val listState = rememberLazyListState()
    val metrics = LocalTvMetrics.current

    // Open on the channel that is playing, as the channel-list overlay does. Landing at the top of
    // a five-hundred-channel list when the answer is channel 214 is a scroll the remote should
    // never be asked for.
    var anchored by remember { mutableStateOf(false) }
    LaunchedEffect(channels, state.currentChannel?.id) {
        if (anchored) return@LaunchedEffect
        val currentId = state.currentChannel?.id ?: return@LaunchedEffect
        val index = channels.indexOfFirst { it.id == currentId }
        if (index >= 0) {
            anchored = true
            listState.scrollToItem(index)
            withFrameNanos { }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.nav_live),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(channels, key = { it.id }) { channel ->
                val playing = channel.id == state.currentChannel?.id
                FocusableRow(
                    onClick = { onPlay(channel) },
                    selected = playing,
                    contentDescription = channel.displayName,
                ) {
                    ChannelLogo(channel = channel, sizeDp = metrics.rowLogoSize)
                    Text(
                        text = channel.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** What is playing, beside the preview: the caption the picture cannot carry itself. */
@Composable
private fun NowPlayingCaption(
    channel: Channel?,
    now: Programme?,
    next: Programme?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (channel == null) return@Column
        Text(
            text = channel.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = now?.title ?: stringResource(R.string.no_guide_data),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        now?.let {
            Text(
                text = TimeModel.formatClockRange(it.startUtc, it.endUtc),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProgrammeProgress(it.startUtc, it.endUtc, modifier = Modifier.fillMaxWidth())
        }
        next?.let {
            Text(
                text = "${TimeModel.ltr(TimeModel.formatClock(it.startUtc))} · ${it.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The preview's size.
 *
 * Fixed rather than derived, unlike everything else the ten-foot layout measures: the slot has to
 * stay a 16:9 rectangle whatever the panel, or the picture inside it letterboxes against its own
 * frame. What scales with the panel is how much guide fits beside it.
 */
private val PREVIEW_WIDTH = 400.dp
private val PREVIEW_HEIGHT = 225.dp

/** Keeps the picture off the panes either side of it. */
private val PREVIEW_MARGIN = 8.dp
