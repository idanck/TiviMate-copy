package com.tivicopy.player.ui.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.SportSession
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.ListRow
import com.tivicopy.player.ui.common.RowTrail
import com.tivicopy.player.ui.common.EmptyPanel
import com.tivicopy.player.ui.common.FlashesPanel
import com.tivicopy.player.ui.common.Loader
import com.tivicopy.player.ui.common.PanelCaption
import com.tivicopy.player.ui.common.PanelFailure
import com.tivicopy.player.ui.common.PanelVideo
import com.tivicopy.player.ui.common.SportPickerRow
import com.tivicopy.player.ui.common.panelConnecting
import com.tivicopy.player.ui.common.NewsTicker
import com.tivicopy.player.ui.common.StudioIcons
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.theme.StudioTheme

/**
 * Sport mode (ADR-041): two Israeli sports channels at once, side by side (decision 10).
 *
 * The panel with the remote is drawn larger than the others and is the only one heard; the arrows
 * move the remote, and the sound moves with it. OK on a panel opens its menu (decision 11): fill
 * the screen with it, or put the grid back, the other panel playing on behind it all along; change
 * its channel; remove it. On an empty panel OK opens the channel picker straight away, and on a
 * failed one it tries again. A long OK or the Menu key still opens the picker directly. The
 * channel keys step the panel with the remote through the channels on offer; Back from the grid
 * leaves Sport mode, and the channel with the remote carries on full screen. Back, the channel
 * keys and play/pause are the activity's, as on every other television screen; the arrows and OK
 * are this screen's.
 *
 * Drawn over everything, opaque, with no side menu and no top bar: the pictures are the whole
 * screen. The full-screen player's own surface is not composed underneath — two video surfaces
 * covering the same pixels have no defined order, and its last frame could end up on top.
 *
 * News mode (ADR-042) is this screen too, with two panels: one of them may show the headlines,
 * chosen in the picker like a channel. The headlines have the same menu as a picture; with them in
 * focus the sound stays with the channel beside them. News mode's menu also switches between two
 * panels and four, and the running strip of headlines across the top (ADR-045).
 */
@Composable
internal fun SportScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val session = vm.sport
    val slots by session.slots.collectAsState()
    val focused by session.focused.collectAsState()
    val expanded by session.expanded.collectAsState()
    val picking by session.picking.collectAsState()
    val menu by session.menu.collectAsState()
    val choices by session.choices.collectAsState()
    val sources = remember(choices) { vm.playlistNames() }
    val paused by session.paused.collectAsState()
    val count by session.count.collectAsState()
    val mode by session.mode.collectAsState()
    val prefs by vm.settingsState.collectAsState()
    val live by session.live.collectAsState()
    val news = mode == SportSession.Mode.NEWS
    val heard = SportSession.audibleSlot(slots, focused)
    val requesters = remember { List(SportLayout.SLOTS) { FocusRequester() } }
    val drawerOpen = picking != null || menu != null

    // The remote goes to the panel in focus whenever nothing else holds it: on arrival, as the
    // picker or the menu closes, and when the session moves it — a remembered grid whose first
    // channel is not in the first panel, a channel just placed. A move the remote made itself lands
    // here too, on the panel that already has it, and changes nothing. Two frames: one for the grid
    // to compose, one for it to be laid out.
    LaunchedEffect(drawerOpen, focused) {
        if (drawerOpen) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { requesters[session.focused.value].requestFocus() }
    }

    val gap = with(LocalDensity.current) { PANEL_GAP.roundToPx() }
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // In a band of its own above the panels rather than over them, so it hides no picture.
            if (news && prefs.newsTicker) {
                NewsTicker(live = live, modifier = Modifier.height(TICKER_HEIGHT))
            }
            Layout(
                modifier = Modifier.fillMaxWidth().weight(1f),
                content = {
                    // Keyed by place: a panel's view, and so its video surface, stays where it is
                    // while the grid around it moves.
                    slots.take(count).forEachIndexed { index, slot ->
                        key(index) {
                            SportPanel(
                                vm = vm,
                                slot = slot,
                                audible = index == heard,
                                expanded = expanded,
                                paused = paused,
                                canTakeFocus = !drawerOpen && (!expanded || index == focused),
                                requester = requesters[index],
                                onFocused = { session.focus(index) },
                                onOk = {
                                    when {
                                        slot.flashes -> session.openMenu(index)
                                        slot.channel == null -> session.openPicker(index)
                                        slot.panel.status.value.failed -> session.retry(index)
                                        else -> session.openMenu(index)
                                    }
                                },
                                onMenu = { session.openPicker(index) },
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val cells = SportLayout.cells(constraints.maxWidth, constraints.maxHeight, focused, expanded, gap, count)
                val placeables = measurables.mapIndexed { i, measurable ->
                    measurable.measure(Constraints.fixed(cells[i].width, cells[i].height))
                }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // Relative, so the first panel is at the top right in Hebrew.
                    placeables.forEachIndexed { i, placeable -> placeable.placeRelative(cells[i].x, cells[i].y) }
                }
            }
        }

        menu?.let { slot ->
            SportPanelMenu(
                slot = slot,
                slots = slots.take(count),
                expanded = expanded,
                news = news,
                ticker = prefs.newsTicker,
                onExpand = {
                    session.closeMenu()
                    session.focus(slot)
                    session.setExpanded(!session.expanded.value)
                },
                onChange = { session.openPicker(slot) },
                onClear = { session.clear(slot) },
                onPanels = { vm.setNewsPanels(if (count > SportLayout.PAIR) SportLayout.PAIR else SportLayout.SLOTS) },
                onTicker = { vm.setNewsTicker(!prefs.newsTicker) },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        picking?.let { slot ->
            SportPicker(
                slot = slot,
                slots = slots.take(count),
                choices = choices,
                sources = sources,
                news = news,
                onPick = { channel -> session.assign(slot, channel) },
                onFlashes = { session.showFlashes(slot) },
                onClear = { session.clear(slot) },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * One panel: the picture, what it is, and whether it is the one heard; News mode's headlines; or
 * an empty place.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SportPanel(
    vm: AppViewModel,
    slot: SportSession.Slot,
    audible: Boolean,
    expanded: Boolean,
    paused: Boolean,
    /** Off for a panel hidden behind a full-screen one, and for every panel while picking. */
    canTakeFocus: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onOk: () -> Unit,
    onMenu: () -> Unit,
) {
    val studio = StudioTheme.colors
    val channel = slot.channel
    val player by slot.panel.player.collectAsState()
    val status by slot.panel.status.collectAsState()
    var hasFocus by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val label = when {
        slot.flashes -> stringResource(R.string.news_flashes)
        else -> channel?.displayName ?: stringResource(R.string.sport_add_channel)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (channel == null && !slot.flashes) studio.card else Color.Black)
            .focusRequester(requester)
            .focusProperties { canFocus = canTakeFocus }
            .onFocusChanged {
                hasFocus = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            // The Menu key as well as a long OK: a remote's long press is not worth relying on
            // alone (`FocusableRow`).
            .onKeyEvent { event ->
                val menu = event.key == Key.Menu || event.key == Key.F1
                if (menu && event.type == KeyEventType.KeyDown) {
                    onMenu()
                    true
                } else {
                    false
                }
            }
            // The one focus target, carried by the clickable itself; a `focusable()` beside it
            // would cost the remote a second press to cross each panel.
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onMenu,
                onClick = onOk,
            )
            .semantics { contentDescription = label }
            // Square, because a video surface ignores the clip a rounded corner would ask for.
            .focusSurface(focused = hasFocus, shape = RectangleShape, fill = FocusFill.NONE, ringWidth = RING_WIDTH),
    ) {
        if (slot.flashes) {
            val live by vm.sport.live.collectAsState()
            FlashesPanel(live = live, modifier = Modifier.fillMaxSize())
        } else if (channel == null) {
            EmptyPanel(
                hint = stringResource(R.string.sport_add_hint),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            PanelVideo(player = player, modifier = Modifier.fillMaxSize())
            when {
                status.failed -> PanelFailure(
                    vm = vm,
                    channel = channel,
                    status = status,
                    hint = stringResource(R.string.sport_panel_retry_hint),
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
            )
        }
    }
}

/**
 * A panel's menu (ADR-041 decision 11): the same drawer as the picker, with the three things a
 * panel already showing something can do. Fill the screen with it, or put the grid back; change
 * what it shows, which opens the picker in its place; remove it. The remote lands on the first row,
 * so OK twice is still full screen. In News mode, two more rows for the whole screen (ADR-045): two
 * panels or four, and the running strip of headlines.
 */
@Composable
private fun SportPanelMenu(
    slot: Int,
    slots: List<SportSession.Slot>,
    expanded: Boolean,
    news: Boolean,
    ticker: Boolean,
    onExpand: () -> Unit,
    onChange: () -> Unit,
    onClear: () -> Unit,
    onPanels: () -> Unit,
    onTicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val entry = slots.getOrNull(slot)
    val four = slots.size > SportLayout.PAIR
    val title = when {
        entry?.flashes == true -> stringResource(R.string.news_flashes)
        else -> entry?.channel?.displayName ?: stringResource(R.string.sport_add_channel)
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(slot) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(
        modifier = modifier
            .width(PICKER_WIDTH)
            .fillMaxHeight()
            // A drawer against the end edge (ADR-046): solid by the edge, fading towards the
            // panels, never blurred.
            .tvDrawer(atStart = false)
            .padding(start = 28.dp, end = 48.dp, top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            style = StudioTheme.type.titleSmall,
            color = studio.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Text(
            text = stringResource(R.string.sport_menu_subtitle),
            style = StudioTheme.type.caption,
            color = studio.ink2,
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
        )
        ListRow(
            label = stringResource(
                when {
                    !expanded -> R.string.sport_menu_fullscreen
                    four -> R.string.sport_menu_grid_all
                    else -> R.string.sport_menu_grid
                },
            ),
            icon = if (expanded) StudioIcons.Grid else StudioIcons.Fullscreen,
            onClick = onExpand,
            focusRequester = first,
        )
        ListRow(
            label = stringResource(if (news) R.string.news_menu_change else R.string.sport_menu_change),
            icon = StudioIcons.Swap,
            onClick = onChange,
        )
        ListRow(
            label = stringResource(R.string.sport_pick_remove),
            icon = StudioIcons.Close,
            onClick = onClear,
        )
        if (news) {
            ListRow(
                label = stringResource(if (four) R.string.news_panels_two else R.string.news_panels_four),
                icon = StudioIcons.Grid,
                onClick = onPanels,
            )
            ListRow(
                label = stringResource(R.string.news_ticker),
                icon = StudioIcons.News,
                onClick = onTicker,
                trail = RowTrail.TOGGLE,
                toggled = ticker,
            )
        }
    }
}

/**
 * The channel picker: a drawer against the end edge, as the playback menu is, over a grid that
 * keeps playing beside it. Israeli sports channels only (ADR-041), or in News mode Israeli news
 * channels, after the headlines (ADR-042). A channel already in another panel says so, and
 * choosing it swaps the two panels rather than opening a second connection; the headlines move
 * the same way.
 */
@Composable
private fun SportPicker(
    slot: Int,
    slots: List<SportSession.Slot>,
    choices: List<Channel>?,
    /** Each playlist's name, by its id. */
    sources: Map<Long, String>,
    news: Boolean,
    onPick: (Channel) -> Unit,
    onFlashes: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val current = slots.getOrNull(slot)?.channel
    val showsFlashes = slots.getOrNull(slot)?.flashes == true
    val elsewhere = slots.mapIndexedNotNull { i, s -> s.channel?.id?.takeIf { i != slot } }.toSet()
    val flashesElsewhere = slots.withIndex().any { (i, s) -> i != slot && s.flashes }
    val list = choices.orEmpty()
    // Which playlist a channel is from is worth a line only when there is more than one.
    val mixed = list.distinctBy { it.playlistId }.size > 1
    val target = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // The rows in order, by key, and the one the remote lands on: the channel the panel shows,
    // the headlines if it shows them, or else the first channel — the headlines when there is none.
    val clearRow = current != null || showsFlashes
    val keys = buildList {
        if (clearRow) add(CLEAR_KEY)
        if (news) add(FLASHES_KEY)
        list.forEach { add(channelKey(it)) }
    }
    val landOn = when {
        current != null && list.any { it.id == current.id } -> channelKey(current)
        showsFlashes -> FLASHES_KEY
        list.isNotEmpty() -> channelKey(list.first())
        news -> FLASHES_KEY
        else -> null
    }
    val landIndex = landOn?.let { keys.indexOf(it) } ?: -1

    Column(
        modifier = modifier
            .width(PICKER_WIDTH)
            .fillMaxHeight()
            // A drawer against the end edge (ADR-046): solid by the edge, fading towards the
            // panels, never blurred.
            .tvDrawer(atStart = false)
            .padding(start = 28.dp, end = 48.dp, top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = stringResource(R.string.sport_pick_title),
            style = StudioTheme.type.titleSmall,
            color = studio.ink,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Text(
            text = stringResource(if (news) R.string.news_pick_subtitle else R.string.sport_pick_subtitle),
            style = StudioTheme.type.caption,
            color = studio.ink2,
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
        )
        LaunchedEffect(slot, landOn, landIndex) {
            if (landIndex < 0) return@LaunchedEffect
            listState.scrollToItem(landIndex)
            withFrameNanos { }
            withFrameNanos { }
            runCatching { target.requestFocus() }
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (clearRow) {
                item(key = CLEAR_KEY) {
                    ListRow(
                        label = stringResource(R.string.sport_pick_remove),
                        icon = StudioIcons.Close,
                        onClick = onClear,
                    )
                }
            }
            if (news) {
                item(key = FLASHES_KEY) {
                    ListRow(
                        label = stringResource(R.string.news_flashes),
                        icon = StudioIcons.News,
                        onClick = onFlashes,
                        selected = showsFlashes,
                        trail = RowTrail.VALUE,
                        value = if (flashesElsewhere) stringResource(R.string.sport_pick_on_screen) else null,
                        focusRequester = if (landOn == FLASHES_KEY) target else null,
                    )
                }
            }
            when {
                choices == null -> item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
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
                        focusRequester = if (landOn == channelKey(channel)) target else null,
                        onClick = { onPick(channel) },
                    )
                }
            }
        }
    }
}

private const val CLEAR_KEY = "clear"
private const val FLASHES_KEY = "flashes"

private fun channelKey(channel: Channel): String = "channel:${channel.id}"

/** The black line between panels. */
private val PANEL_GAP = 4.dp

/** The cue ring round the panel with the remote: thicker than a row's, read across a room. */
private val RING_WIDTH = 3.dp

private val PICKER_WIDTH = 360.dp

/** News mode's strip of headlines across the top (ADR-045): one line, read across a room. */
private val TICKER_HEIGHT = 48.dp

