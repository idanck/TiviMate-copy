package com.tivicopy.player.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.ui.NowPlayingInfo
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.animatedColour
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.delay
import com.tivicopy.player.ui.common.StudioIcons

/**
 * Studio's phone chrome (`docs/design/studio/canvas/PTopBar`, `PNavBar`, `PMiniPlayer`): the bar
 * over a tab, the five tabs under it, and what is playing docked between the two.
 *
 * On a phone there is no remote, so the cue (amber) marks what is active — the current tab's pill —
 * and the tally (red) keeps its one meaning: what is playing now.
 */

/** One tab in [PhoneNavBar]. */
internal data class PhoneTab(val tab: MobileTab, val label: String, val icon: ImageVector)

/**
 * `PTopBar`: back at the start when there is somewhere to go back to, the title in Karantina, and
 * Sport mode and search at the end. [title] is null where the screen underneath draws its own
 * heading, so the same words never sit twice, one above the other.
 */
@Composable
internal fun PhoneTopBar(
    title: String?,
    onBack: (() -> Unit)?,
    onSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onSport: (() -> Unit)? = null,
) {
    val studio = StudioTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = StudioIcons.Back,
                    contentDescription = stringResource(R.string.action_back),
                    tint = studio.ink,
                )
            }
        }
        Text(
            text = title.orEmpty(),
            style = StudioTheme.type.titleSmall,
            color = studio.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        // Sport mode has no tab of its own: the five are taken (ADR-032, ADR-041).
        if (onSport != null) {
            IconButton(onClick = onSport) {
                Icon(
                    imageVector = StudioIcons.Sport,
                    contentDescription = stringResource(R.string.nav_sport),
                    tint = studio.ink,
                )
            }
        }
        if (onSearch != null) {
            IconButton(onClick = onSearch) {
                Icon(
                    imageVector = StudioIcons.Search,
                    contentDescription = stringResource(R.string.nav_search),
                    tint = studio.ink,
                )
            }
        }
    }
}

/**
 * `PNavBar`: five tabs on the surface, a hairline above them. The current tab's icon sits in a
 * cue pill 56×30 and its label is bold; the others are ink-2. [selected] may be none of them —
 * the on-demand screen, reached only from search, has no tab of its own.
 */
@Composable
internal fun PhoneNavBar(
    tabs: List<PhoneTab>,
    selected: MobileTab?,
    onSelect: (MobileTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(studio.surface)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
    ) {
        // `box-shadow: inset 0 1px 0 var(--st-hairline-soft)`.
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(studio.hairlineSoft))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NAV_BAR_HEIGHT - 1.dp)
                .padding(start = 4.dp, end = 4.dp, top = 5.dp, bottom = 8.dp)
                .selectableGroup(),
        ) {
            tabs.forEach { item ->
                val active = item.tab == selected
                val pill = animatedColour(if (active) studio.cue else studio.cue.copy(alpha = 0f))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(item.tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 56.dp, height = 30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(pill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = if (active) studio.onCue else studio.ink2,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        text = item.label,
                        style = StudioTheme.type.nav.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                        color = if (active) studio.ink else studio.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * `PMiniPlayer`: what is playing, docked above the tabs while the player screen is collapsed — the
 * music-app pattern, so browsing never silences or hides the stream (ADR-011). A card 64dp high
 * with a hairline and a lift; the channel's plate (a disc for a station), the channel under a tally
 * dot, the title, play/pause, and a 2dp progress line along the bottom edge.
 *
 * The canvas shows a frame of the video where the plate is. The video track is not decoded while
 * the player is collapsed (battery and bandwidth, see `MobileRoot`), so the plate is the channel's
 * logo on a 16:9 tile instead.
 */
@Composable
internal fun PhoneMiniPlayer(
    nowPlaying: NowPlayingInfo,
    now: Programme?,
    radio: Boolean,
    /** The intent flag, not `isPlaying` — a rebuffering stream must still offer Pause. */
    wantsPlay: Boolean,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
) {
    val studio = StudioTheme.colors
    val shape = RoundedCornerShape(LocalTvMetrics.current.controlCorner)
    val label = stringResource(R.string.now_playing)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MINI_HEIGHT)
                .shadow(elevation = 8.dp, shape = shape)
                .clip(shape)
                .background(studio.card)
                .border(1.dp, studio.hairlineSoft, shape)
                .clickable(onClick = onExpand)
                .semantics { contentDescription = label },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniPlate(nowPlaying = nowPlaying, radio = radio)
                Spacer(modifier = Modifier.width(12.dp))
                val (channelLine, titleLine) = when (nowPlaying) {
                    is NowPlayingInfo.Live -> nowPlaying.channel.displayName to (now?.title ?: nowPlaying.channel.displayName)
                    is NowPlayingInfo.Catchup -> nowPlaying.channel.displayName to nowPlaying.programme.title
                    is NowPlayingInfo.OnDemand -> (nowPlaying.item.year ?: "") to nowPlaying.item.name
                    is NowPlayingInfo.RecordingFile -> nowPlaying.recording.channelName to nowPlaying.recording.title
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (channelLine.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // The tally only where something is live: a recording or a film is
                            // playing, but it is not on air.
                            if (nowPlaying is NowPlayingInfo.Live) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(studio.tally))
                            }
                            Text(
                                text = channelLine,
                                style = StudioTheme.type.caption,
                                color = studio.ink2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = titleLine,
                        style = StudioTheme.type.rowLabel.copy(fontSize = StudioTheme.type.meta.fontSize * 15f / 14f),
                        color = studio.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (wantsPlay) StudioIcons.Pause else StudioIcons.Play,
                        contentDescription = stringResource(if (wantsPlay) R.string.action_pause else R.string.action_play),
                        tint = studio.ink,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            if (nowPlaying is NowPlayingInfo.Live && now != null) {
                // The fraction is read at composition, and this bar is long-lived, so it is
                // re-read on a slow tick; without it the line sat where it was when the bar opened.
                var tick by remember { mutableIntStateOf(0) }
                LaunchedEffect(now.startUtc) {
                    while (true) {
                        delay(PROGRESS_TICK_MS)
                        tick++
                    }
                }
                key(tick) {
                    val span = (now.endUtc - now.startUtc).coerceAtLeast(1)
                    val fraction = ((System.currentTimeMillis() - now.startUtc).toFloat() / span).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(studio.track),
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(fraction).height(2.dp).background(studio.ink))
                    }
                }
            }
        }
    }
}

/** The plate at the start of the mini player: a disc for a station, a 16:9 tile for the rest. */
@Composable
private fun MiniPlate(nowPlaying: NowPlayingInfo, radio: Boolean) {
    val studio = StudioTheme.colors
    val channel = when (nowPlaying) {
        is NowPlayingInfo.Live -> nowPlaying.channel
        is NowPlayingInfo.Catchup -> nowPlaying.channel
        else -> null
    }
    if (radio && channel != null) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(studio.surface)
                .border(1.dp, studio.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(channel = channel, sizeDp = 36, circular = true)
        }
        return
    }
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(studio.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (channel != null) {
            ChannelLogo(channel = channel, sizeDp = 32)
        } else {
            Icon(imageVector = StudioIcons.Movie, contentDescription = null, tint = studio.ink2)
        }
    }
}

/** `--st-h-topbar`. */
private val TOP_BAR_HEIGHT = 56.dp

/** `--st-h-navbar`. */
private val NAV_BAR_HEIGHT = 72.dp

/** `--st-h-mini`. */
private val MINI_HEIGHT = 64.dp

/** How often the mini player's progress line advances on its own. */
private const val PROGRESS_TICK_MS = 60_000L
