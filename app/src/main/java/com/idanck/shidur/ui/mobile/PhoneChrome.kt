package com.idanck.shidur.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.unit.Dp
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.idanck.shidur.R
import com.idanck.shidur.data.model.Programme
import com.idanck.shidur.ui.NowPlayingInfo
import com.idanck.shidur.ui.common.ChannelLogo
import com.idanck.shidur.ui.common.Equalizer
import com.idanck.shidur.ui.common.GlassMaterial
import com.idanck.shidur.ui.common.LiveDot
import com.idanck.shidur.ui.common.StudioIcons
import com.idanck.shidur.ui.common.animatedColour
import com.idanck.shidur.ui.common.glass
import com.idanck.shidur.ui.theme.StudioTheme
import kotlinx.coroutines.delay

/**
 * The phone's chrome (`PTopBar`, `PNavBar`, `PMiniPlayer`; Merhav, ADR-046): the bar over a tab, the
 * five tabs under it in a floating capsule of glass, and what is playing docked between the two in
 * a capsule of its own.
 *
 * On a phone there is no remote, so the cue (amber) marks what is active — the current tab, the
 * play button — and the tally (red) keeps its one meaning: what is playing live.
 */

/** One tab in [PhoneNavBar]. */
internal data class PhoneTab(val tab: MobileTab, val label: String, val icon: ImageVector)

/**
 * `PTopBar`: back at the start when there is somewhere to go back to, the title at the display
 * weight (the hero step on a tab, the title step under a back button), and Sport mode, News mode
 * and search at the end, each a round button of thin glass. [title] is null where the screen
 * underneath draws its own heading, so the same words never sit twice, one above the other.
 */
@Composable
internal fun PhoneTopBar(
    title: String?,
    onBack: (() -> Unit)?,
    onSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onSport: (() -> Unit)? = null,
    onNews: (() -> Unit)? = null,
    /** After search: YouTube's account button (ADR-069, `PTopBar` account). */
    youtubeAccount: Boolean = false,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            GlassIconButton(
                icon = StudioIcons.Back,
                description = stringResource(R.string.action_back),
                onClick = onBack,
            )
        }
        Text(
            text = title.orEmpty(),
            style = if (onBack != null) type.title else type.hero,
            color = studio.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Sport and News modes have no tab of their own: the five are taken (ADR-032, ADR-041).
        if (onNews != null) {
            GlassIconButton(icon = StudioIcons.News, description = stringResource(R.string.nav_news), onClick = onNews)
        }
        if (onSport != null) {
            GlassIconButton(icon = StudioIcons.Sport, description = stringResource(R.string.nav_sport), onClick = onSport)
        }
        if (onSearch != null) {
            GlassIconButton(icon = StudioIcons.Search, description = stringResource(R.string.nav_search), onClick = onSearch)
        }
        if (youtubeAccount) PhoneYouTubeAccountButton()
    }
}

/** A round button of thin glass, 44dp — the least a finger should be offered — with a 22dp glyph. */
@Composable
private fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(TOUCH)
            .glass(GlassMaterial.THIN, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = StudioTheme.colors.ink, modifier = Modifier.size(22.dp))
    }
}

/**
 * `PNavBar`: the tabs in a capsule of thick glass that floats just above the foot of the screen.
 * The current tab is amber — its glyph and label, on a wash of amber glass inside an amber edge;
 * the others are the second ink. [selected] may be none of them — the on-demand screen, reached
 * only from search, has no tab of its own.
 *
 * [more] are the destinations without a tab of their own (ADR-066: the guide, YouTube and the
 * recordings). They sit behind a last tab, "עוד", which opens [PhoneMorePanel] right above this
 * bar ([moreOpen]); while one of them is the screen on show, or the panel is open, "עוד" is the tab
 * that is lit. Tapping "עוד" again closes the panel, and any other tab closes it on its way.
 */
@Composable
internal fun PhoneNavBar(
    tabs: List<PhoneTab>,
    selected: MobileTab?,
    onSelect: (MobileTab) -> Unit,
    modifier: Modifier = Modifier,
    more: List<PhoneTab> = emptyList(),
    moreOpen: Boolean = false,
    onMoreOpenChange: (Boolean) -> Unit = {},
) {
    val moreLabel = stringResource(R.string.nav_more)
    val shown = if (more.isEmpty()) tabs else tabs + PhoneTab(MobileTab.MORE, moreLabel, StudioIcons.Grid)
    val lit = if (moreOpen || (selected != null && more.any { it.tab == selected })) MobileTab.MORE else selected
    PhoneNavCapsule(
        tabs = shown,
        selected = lit,
        onSelect = {
            if (it == MobileTab.MORE) {
                onMoreOpenChange(!moreOpen)
            } else {
                onMoreOpenChange(false)
                onSelect(it)
            }
        },
        modifier = modifier,
    )
}

/**
 * `PNavOptMoreSheet`: what "עוד" holds, as large tiles in a row — a glyph over its label — on a
 * small sheet of thick glass floating [PNAV_MORE_GAP] above the tab bar, over a scrim that covers
 * the screen down to the bar and no further. The bar stays in view and in reach, with "עוד" lit.
 * It is drawn in the screen itself rather than in a dialog's window, which would cover the bar
 * (0.33.3). The one on show is amber, as its tab would be.
 *
 * @param barHeight how far up from the foot of the screen the tab bar reaches, its insets and
 *   margins included, so the scrim stops at it and the sheet sits above it
 */
@Composable
internal fun PhoneMorePanel(
    open: Boolean,
    items: List<PhoneTab>,
    selected: MobileTab?,
    barHeight: Dp,
    onSelect: (MobileTab) -> Unit,
    onDismiss: () -> Unit,
) {
    val studio = StudioTheme.colors
    BackHandler(enabled = open, onBack = onDismiss)
    Box(modifier = Modifier.fillMaxSize().padding(bottom = barHeight)) {
        AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(studio.scrim)
                    // A tap anywhere above the bar but off the sheet closes it.
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + slideInVertically { it / 4 },
            exit = fadeOut() + slideOutVertically { it / 4 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val title = stringResource(R.string.nav_more)
            val sheet = MaterialTheme.shapes.extraLarge
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = PNAV_MORE_GAP)
                    .glass(GlassMaterial.THICK, sheet)
                    // The sheet keeps its own taps: only the scrim around it closes it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { paneTitle = title }
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(studio.hairline),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items.forEach { item ->
                        val active = item.tab == selected
                        val shape = RoundedCornerShape(18.dp)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(84.dp)
                                .clip(shape)
                                .then(
                                    if (active) {
                                        Modifier.background(studio.currentGlass).border(1.dp, studio.currentEdge, shape)
                                    } else {
                                        Modifier.glass(GlassMaterial.THIN, shape)
                                    },
                                )
                                .selectable(selected = active, role = Role.Tab, onClick = { onSelect(item.tab) }),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                        ) {
                            val tint = if (active) studio.cue else studio.ink
                            Icon(imageVector = item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                            Text(
                                text = item.label,
                                style = StudioTheme.type.nav.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                                color = tint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The gap between the tab bar's capsule and the "עוד" sheet: `PNavOptMoreSheet`'s 12px, less the
 * 4dp the bar keeps above its capsule.
 */
private val PNAV_MORE_GAP = 8.dp

@Composable
private fun PhoneNavCapsule(
    tabs: List<PhoneTab>,
    selected: MobileTab?,
    onSelect: (MobileTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NAV_BAR_HEIGHT)
                .glass(GlassMaterial.THICK, CircleShape)
                .padding(6.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tabs.forEach { item ->
                val active = item.tab == selected
                val fill = animatedColour(if (active) studio.currentGlass else studio.currentGlass.copy(alpha = 0f))
                val edge = animatedColour(if (active) studio.currentEdge else studio.currentEdge.copy(alpha = 0f))
                val content = animatedColour(if (active) studio.cue else studio.ink2)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(fill)
                        .border(1.dp, edge, CircleShape)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(item.tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = item.label,
                        style = StudioTheme.type.nav.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                        color = content,
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
 * music-app pattern, so browsing never silences or hides the stream (ADR-011). A capsule 64dp
 * high: the channel's plate (a disc for a station), an amber equaliser before "now playing" and
 * the channel, the title, play/pause on the cue, and while a live programme is known a 2dp line of
 * its progress in the tally along the foot.
 *
 * Opaque, with an amber edge and a shadow (`canvas/PMiniPlayer.dc.html`, ADR-063): in thick glass
 * like the tabs under it and the rows behind it, it read as one more row and was easy to miss on
 * any screen but the player's. The equaliser is the one thing on the bar that moves, and it moves
 * only with the sound; while the app is not in view it is not composed, so nothing is measured.
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
    val shape = CircleShape
    val label = stringResource(R.string.now_playing)
    val toggleLabel = stringResource(if (wantsPlay) R.string.action_pause else R.string.action_play)
    // The equaliser measures the player's output (`AudioSpectrum`) for as long as it is composed:
    // only while the app is in view, not under a locked screen with the radio on.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val inView = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    // Amber glass over the drawer's dark, made opaque: the plate is the same over any screen.
    val plate = studio.currentGlass.compositeOver(studio.drawer)
    Box(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MINI_HEIGHT)
                .shadow(elevation = MINI_ELEVATION, shape = shape)
                .clip(shape)
                .background(plate)
                .border(MINI_EDGE_WIDTH, studio.current.copy(alpha = MINI_EDGE_ALPHA), shape)
                .clickable(onClick = onExpand)
                .semantics { contentDescription = label },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(start = 12.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniPlate(nowPlaying = nowPlaying, radio = radio)
                Spacer(modifier = Modifier.width(12.dp))
                val (channelLine, titleLine) = when (nowPlaying) {
                    is NowPlayingInfo.Live -> nowPlaying.channel.displayName to (now?.title ?: nowPlaying.channel.displayName)
                    is NowPlayingInfo.Catchup -> nowPlaying.channel.displayName to nowPlaying.programme.title
                    is NowPlayingInfo.OnDemand -> (nowPlaying.item.year ?: "") to nowPlaying.item.name
                    is NowPlayingInfo.RecordingFile -> nowPlaying.recording.channelName to nowPlaying.recording.title
                    is NowPlayingInfo.Podcast -> nowPlaying.episode.podcastTitle to nowPlaying.episode.title
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (channelLine.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // TalkBack hears "live" where something is on air: a recording or a
                            // film is playing, but it is not on air.
                            val live = nowPlaying is NowPlayingInfo.Live
                            if (inView) {
                                Equalizer(
                                    playing = wantsPlay,
                                    description = stringResource(if (live) R.string.playback_live_badge else R.string.now_playing),
                                    colour = studio.current,
                                    modifier = Modifier.size(width = MINI_EQ_WIDTH, height = MINI_EQ_HEIGHT),
                                )
                            } else if (live) {
                                LiveDot(description = stringResource(R.string.playback_live_badge))
                            }
                            Text(
                                text = stringResource(R.string.radio_now_playing_on, channelLine),
                                style = StudioTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = studio.current,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = titleLine,
                        style = StudioTheme.type.rowLabel.copy(fontWeight = FontWeight.Bold),
                        color = studio.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                // The one action here, on the cue: amber is what is active on a phone.
                Box(
                    modifier = Modifier
                        .size(TOUCH)
                        .shadow(elevation = 6.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .background(studio.cue)
                        .clickable(role = Role.Button, onClick = onTogglePlay)
                        .semantics { contentDescription = toggleLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (wantsPlay) StudioIcons.Pause else StudioIcons.Play,
                        contentDescription = null,
                        tint = studio.onCue,
                        modifier = Modifier.size(20.dp),
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
                            .align(Alignment.BottomCenter)
                            .padding(start = 26.dp, end = 26.dp, bottom = 4.dp)
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(CircleShape)
                            .background(studio.track),
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(fraction).height(2.dp).background(studio.tally))
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
                .glass(GlassMaterial.THIN, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(channel = channel, sizeDp = 36, circular = true)
        }
        return
    }
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 40.dp)
            .glass(GlassMaterial.THIN, RoundedCornerShape(12.dp)),
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

/** `--st-h-navbar`: the capsule itself. */
private val NAV_BAR_HEIGHT = 68.dp

/** `--st-h-mini`. */
private val MINI_HEIGHT = 64.dp

/** The mini player's amber edge, its shadow and its equaliser (ADR-063). */
private val MINI_EDGE_WIDTH = 1.5.dp
private const val MINI_EDGE_ALPHA = 0.7f
private val MINI_ELEVATION = 10.dp
private val MINI_EQ_WIDTH = 16.dp
private val MINI_EQ_HEIGHT = 14.dp

/** `--st-touch`: the least a finger should be offered. */
private val TOUCH = 44.dp

/** How often the mini player's progress line advances on its own. */
private const val PROGRESS_TICK_MS = 60_000L
