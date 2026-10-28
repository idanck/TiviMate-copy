package com.tivicopy.player.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.SportSession
import com.tivicopy.player.ui.common.EmptyPanel
import com.tivicopy.player.ui.common.FlashesPanel
import com.tivicopy.player.ui.common.ListRow
import com.tivicopy.player.ui.common.Loader
import com.tivicopy.player.ui.common.NewsTicker
import com.tivicopy.player.ui.common.PanelCaption
import com.tivicopy.player.ui.common.PanelChangeButton
import com.tivicopy.player.ui.common.PanelFailure
import com.tivicopy.player.ui.common.PanelVideo
import com.tivicopy.player.ui.common.RowTrail
import com.tivicopy.player.ui.common.SportPickerRow
import com.tivicopy.player.ui.common.StudioIcons
import com.tivicopy.player.ui.common.StudioSheet
import com.tivicopy.player.ui.common.animatedColour
import com.tivicopy.player.ui.common.panelConnecting
import com.tivicopy.player.ui.theme.StudioTheme
import com.tivicopy.player.ui.tv.SportLayout
import kotlinx.coroutines.delay

/**
 * Sport mode on the phone (ADR-041): two Israeli sports channels at once, over everything, the
 * way the player screen is.
 *
 * The same session as the television's, so the same rules: the panel in focus is drawn larger and
 * is the only one heard, a channel already on screen moves rather than opening a second connection,
 * and leaving plays the channel in focus in the player screen. What differs is the hand. A tap
 * gives a panel the focus and the sound, or picks a channel for an empty one; a double tap fills
 * the screen with a panel and puts the grid back; the swap button on a panel, or a long press on
 * it, changes its channel (ADR-041 decision 11). The bar on top leaves and pauses them both.
 *
 * Lying down, the panels stand side by side, as on the television, and the bar rides over them,
 * stepping aside a few seconds after the last touch. Held upright, they stand one above the other
 * under the bar.
 *
 * News mode (ADR-042) is this screen too, with the headlines on offer in the picker beside the
 * news channels. A tap on the headlines gives them the focus and
 * leaves the sound with the picture beside them. News mode's bar also switches between two panels
 * and four, and the running strip of headlines along the top (ADR-045).
 */
@Composable
internal fun PhoneSport(
    vm: AppViewModel,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = vm.sport
    val slots by session.slots.collectAsState()
    val focused by session.focused.collectAsState()
    val expanded by session.expanded.collectAsState()
    val picking by session.picking.collectAsState()
    val choices by session.choices.collectAsState()
    val sources = remember(choices) { vm.playlistNames() }
    val paused by session.paused.collectAsState()
    val count by session.count.collectAsState()
    val mode by session.mode.collectAsState()
    val news = mode == SportSession.Mode.NEWS
    val heard = SportSession.audibleSlot(slots, focused)
    val studio = StudioTheme.colors
    val prefs by vm.settingsState.collectAsState()
    val live by session.live.collectAsState()
    val onPanels = { vm.setNewsPanels(if (count > SportLayout.PAIR) SportLayout.PAIR else SportLayout.SLOTS) }
    val onTicker = { vm.setNewsTicker(!prefs.newsTicker) }

    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp

    // A panel filling the screen goes back into the grid first. The picker is a sheet, which takes
    // Back for itself while it is open.
    BackHandler {
        if (session.expanded.value) session.setExpanded(false) else onLeave()
    }

    // Counted rather than timed: each touch restarts the countdown below, and nothing ticks while
    // nobody touches the screen.
    var touches by remember { mutableIntStateOf(0) }
    var barShown by remember { mutableStateOf(true) }
    LaunchedEffect(touches, landscape, picking) {
        barShown = true
        if (landscape && picking == null) {
            delay(BAR_HOLD_MS)
            barShown = false
        }
    }

    val gap = with(LocalDensity.current) { PANEL_GAP.roundToPx() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(studio.ground)
            // Sees every touch on its way down without taking it, so the panels still get theirs.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDown() }) touches++
                    }
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (!landscape) {
                SportBar(
                    news = news,
                    paused = paused,
                    four = count > SportLayout.PAIR,
                    ticker = prefs.newsTicker,
                    onLeave = onLeave,
                    onTogglePause = session::togglePause,
                    onPanels = onPanels,
                    onTicker = onTicker,
                )
            }
            // In a band of its own above the panels rather than over them, so it hides no picture.
            if (news && prefs.newsTicker) {
                NewsTicker(live = live, modifier = Modifier.height(TICKER_HEIGHT))
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                Layout(
                    modifier = Modifier.fillMaxSize(),
                    content = {
                        // Keyed by place, as on the television: a panel's view, and so its video
                        // surface, stays where it is while the grid around it moves.
                        slots.take(count).forEachIndexed { index, slot ->
                            key(index) {
                                PhoneSportPanel(
                                    vm = vm,
                                    slot = slot,
                                    inFocus = index == focused,
                                    audible = index == heard,
                                    expanded = expanded,
                                    paused = paused,
                                    onTap = {
                                        session.focus(index)
                                        when {
                                            slot.flashes -> Unit
                                            slot.channel == null -> session.openPicker(index)
                                            slot.panel.status.value.failed -> session.retry(index)
                                        }
                                    },
                                    onDoubleTap = {
                                        if (slot.channel != null || slot.flashes) {
                                            session.focus(index)
                                            session.setExpanded(!session.expanded.value)
                                        }
                                    },
                                    onLongPress = {
                                        session.focus(index)
                                        session.openPicker(index)
                                    },
                                    onChange = {
                                        session.focus(index)
                                        session.openPicker(index)
                                    },
                                )
                            }
                        }
                    },
                ) { measurables, constraints ->
                    val cells = SportLayout.cells(
                        constraints.maxWidth,
                        constraints.maxHeight,
                        focused,
                        expanded,
                        gap,
                        count,
                    )
                    val placeables = measurables.mapIndexed { i, measurable ->
                        measurable.measure(Constraints.fixed(cells[i].width, cells[i].height))
                    }
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        // Relative, so the first panel is at the start edge in Hebrew too.
                        placeables.forEachIndexed { i, placeable -> placeable.placeRelative(cells[i].x, cells[i].y) }
                    }
                }
                if (landscape && barShown) {
                    SportBar(
                        news = news,
                        paused = paused,
                        four = count > SportLayout.PAIR,
                        ticker = prefs.newsTicker,
                        onLeave = onLeave,
                        onTogglePause = session::togglePause,
                        onPanels = onPanels,
                        onTicker = onTicker,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .background(studio.ground.copy(alpha = BAR_SCRIM_ALPHA)),
                    )
                }
            }
        }

        picking?.let { slot ->
            PhoneSportPicker(
                slot = slot,
                slots = slots.take(count),
                choices = choices,
                sources = sources,
                news = news,
                onPick = { channel -> session.assign(slot, channel) },
                onFlashes = { session.showFlashes(slot) },
                onClear = { session.clear(slot) },
                onDismiss = session::closePicker,
            )
        }
    }
}

/**
 * The bar: back, the title, and pause or play for every panel. In News mode also two panels or
 * four, and the running strip of headlines, each lit in the cue while it is on (ADR-045).
 */
@Composable
private fun SportBar(
    news: Boolean,
    paused: Boolean,
    four: Boolean,
    ticker: Boolean,
    onLeave: () -> Unit,
    onTogglePause: () -> Unit,
    onPanels: () -> Unit,
    onTicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(onClick = onLeave) {
            Icon(
                imageVector = StudioIcons.Back,
                contentDescription = stringResource(R.string.action_back),
                tint = studio.ink,
            )
        }
        Text(
            text = stringResource(if (news) R.string.nav_news else R.string.nav_sport),
            style = StudioTheme.type.titleSmall,
            color = studio.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        if (news) {
            IconButton(onClick = onPanels) {
                Icon(
                    imageVector = StudioIcons.Grid,
                    contentDescription = stringResource(R.string.news_panels_toggle),
                    tint = if (four) studio.cue else studio.ink,
                )
            }
            IconButton(onClick = onTicker) {
                Icon(
                    imageVector = StudioIcons.News,
                    contentDescription = stringResource(R.string.news_ticker),
                    tint = if (ticker) studio.cue else studio.ink,
                )
            }
        }
        IconButton(onClick = onTogglePause) {
            Icon(
                imageVector = if (paused) StudioIcons.Play else StudioIcons.Pause,
                contentDescription = stringResource(if (paused) R.string.action_play else R.string.action_pause),
                tint = studio.ink,
            )
        }
    }
}

/**
 * One panel, reached by a finger: the picture, what it is, and whether it is the one heard; News
 * mode's headlines; or an empty place.
 */
@Composable
private fun PhoneSportPanel(
    vm: AppViewModel,
    slot: SportSession.Slot,
    inFocus: Boolean,
    audible: Boolean,
    expanded: Boolean,
    paused: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    /** The swap button: the picker for this panel, for a finger that does not know to hold. */
    onChange: () -> Unit,
) {
    val studio = StudioTheme.colors
    val channel = slot.channel
    val player by slot.panel.player.collectAsState()
    val status by slot.panel.status.collectAsState()
    // The gesture detector below is started once, so it calls through these to reach the panel's
    // current handlers rather than the ones it was started with.
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val longPress by rememberUpdatedState(onLongPress)
    val label = when {
        slot.flashes -> stringResource(R.string.news_flashes)
        else -> channel?.displayName ?: stringResource(R.string.sport_add_channel)
    }
    // The panel in focus wears the cue, crossed in at one width rather than widened (ADR-025).
    val ring = animatedColour(if (inFocus) studio.cue else studio.cue.copy(alpha = 0f))
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (channel == null && !slot.flashes) studio.card else Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { doubleTap() },
                    onLongPress = { longPress() },
                )
            }
            .semantics {
                contentDescription = label
                onClick { tap(); true }
                onLongClick { longPress(); true }
            },
    ) {
        if (slot.flashes) {
            val live by vm.sport.live.collectAsState()
            FlashesPanel(live = live, modifier = Modifier.fillMaxSize())
            PanelChangeButton(
                onClick = onChange,
                label = stringResource(R.string.news_menu_change),
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            )
        } else if (channel == null) {
            EmptyPanel(
                hint = stringResource(R.string.sport_add_hint_touch),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            PanelVideo(player = player, modifier = Modifier.fillMaxSize())
            when {
                status.failed -> PanelFailure(
                    vm = vm,
                    channel = channel,
                    status = status,
                    hint = stringResource(R.string.sport_panel_retry_hint_touch),
                    modifier = Modifier.align(Alignment.Center),
                )
                panelConnecting(status) -> Loader(modifier = Modifier.align(Alignment.Center), size = 28.dp)
                else -> Unit
            }
            PanelCaption(
                vm = vm,
                channel = channel,
                audible = audible,
                paused = paused,
                expanded = expanded,
                modifier = Modifier.align(Alignment.BottomStart),
                onChange = onChange,
            )
        }
        // Last, so it is drawn over the picture; square, because a video surface ignores the clip
        // a rounded corner would ask for.
        Box(modifier = Modifier.matchParentSize().border(RING_WIDTH, ring))
    }
}

/**
 * The channel picker, as a sheet from the foot of the screen like every phone menu. Israeli sports
 * channels only, or in News mode the headlines and Israeli news channels; a channel already in
 * another panel says so, and choosing it swaps the two, as choosing the headlines does.
 */
@Composable
private fun PhoneSportPicker(
    slot: Int,
    slots: List<SportSession.Slot>,
    choices: List<Channel>?,
    /** Each playlist's name, by its id. */
    sources: Map<Long, String>,
    news: Boolean,
    onPick: (Channel) -> Unit,
    onFlashes: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val studio = StudioTheme.colors
    val current = slots.getOrNull(slot)?.channel
    val showsFlashes = slots.getOrNull(slot)?.flashes == true
    val elsewhere = slots.mapIndexedNotNull { i, s -> s.channel?.id?.takeIf { i != slot } }.toSet()
    val flashesElsewhere = slots.withIndex().any { (i, s) -> i != slot && s.flashes }
    val list = choices.orEmpty()
    // Which playlist a channel is from is worth a line only when there is more than one.
    val mixed = list.distinctBy { it.playlistId }.size > 1
    val clearRow = current != null || showsFlashes
    StudioSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.sport_pick_title),
        caption = stringResource(if (news) R.string.news_pick_subtitle else R.string.sport_pick_subtitle),
    ) {
        val listState = rememberLazyListState()
        // Opens on the channel the panel already shows, if it has one, counting the rows above
        // the channels: the one that empties the panel, and News mode's headlines.
        val shown = list.indexOfFirst { it.id == current?.id }
        val leading = (if (clearRow) 1 else 0) + (if (news) 1 else 0)
        LaunchedEffect(slot) {
            if (shown >= 0) listState.scrollToItem(shown + leading)
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (clearRow) {
                item(key = "clear") {
                    ListRow(
                        label = stringResource(R.string.sport_pick_remove),
                        icon = StudioIcons.Close,
                        onClick = onClear,
                    )
                }
            }
            if (news) {
                item(key = "flashes") {
                    ListRow(
                        label = stringResource(R.string.news_flashes),
                        icon = StudioIcons.News,
                        onClick = onFlashes,
                        selected = showsFlashes,
                        trail = RowTrail.VALUE,
                        value = if (flashesElsewhere) stringResource(R.string.sport_pick_on_screen) else null,
                    )
                }
            }
            when {
                choices == null -> item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) { Loader(size = 28.dp) }
                }

                list.isEmpty() -> item(key = "none") {
                    Text(
                        text = stringResource(if (news) R.string.news_pick_none else R.string.sport_pick_none),
                        style = StudioTheme.type.body,
                        color = studio.ink2,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }

                else -> items(list, key = { it.id }) { channel ->
                    SportPickerRow(
                        channel = channel,
                        selected = channel.id == current?.id,
                        onScreenElsewhere = channel.id in elsewhere,
                        source = if (mixed) sources[channel.playlistId] else null,
                        onClick = { onPick(channel) },
                    )
                }
            }
        }
    }
}

/** The black line between panels. */
private val PANEL_GAP = 3.dp

/** The cue ring round the panel being heard. */
private val RING_WIDTH = 2.dp

private val BAR_HEIGHT = 56.dp

/** News mode's strip of headlines along the top (ADR-045). */
private val TICKER_HEIGHT = 34.dp

/** How long the bar stays over the pictures, lying down, after the last touch. */
private const val BAR_HOLD_MS = 4_000L

private const val BAR_SCRIM_ALPHA = 0.85f
