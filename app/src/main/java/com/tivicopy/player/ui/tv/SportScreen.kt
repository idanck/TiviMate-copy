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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.SportSession
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.ListRow
import com.tivicopy.player.ui.common.EmptyPanel
import com.tivicopy.player.ui.common.Loader
import com.tivicopy.player.ui.common.PanelCaption
import com.tivicopy.player.ui.common.PanelFailure
import com.tivicopy.player.ui.common.PanelVideo
import com.tivicopy.player.ui.common.SportPickerRow
import com.tivicopy.player.ui.common.panelConnecting
import com.tivicopy.player.ui.common.MENU_MIN_OPACITY
import com.tivicopy.player.ui.common.StudioIcons
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.theme.StudioTheme

/**
 * Sport mode (ADR-041): up to four Israeli sports channels at once, two by two.
 *
 * The panel with the remote is drawn larger than the others and is the only one heard; the arrows
 * move the remote, and the sound moves with it. OK on a panel fills the screen with it and OK or
 * Back puts the grid back, the other three playing on behind it all along. A long OK or the Menu
 * key opens the channel picker for that panel, as OK does on an empty one. The channel keys step
 * the panel with the remote through the channels on offer; Back from the grid leaves Sport mode,
 * and the channel with the remote carries on full screen. Back, the channel keys and play/pause
 * are the activity's, as on every other television screen; the arrows and OK are this screen's.
 *
 * Drawn over everything, opaque, with no side menu and no top bar: four pictures are the whole
 * screen. The full-screen player's own surface is not composed underneath — two video surfaces
 * covering the same pixels have no defined order, and its last frame could end up on top.
 */
@Composable
internal fun SportScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val session = vm.sport
    val slots by session.slots.collectAsState()
    val focused by session.focused.collectAsState()
    val expanded by session.expanded.collectAsState()
    val picking by session.picking.collectAsState()
    val choices by session.choices.collectAsState()
    val paused by session.paused.collectAsState()
    val count by session.count.collectAsState()
    val prefs by vm.settingsState.collectAsState()
    val requesters = remember { List(SportLayout.SLOTS) { FocusRequester() } }

    // The remote goes to the panel in focus whenever nothing else holds it: on arrival, as the
    // picker closes, and when the session moves it — a remembered grid whose first channel is not
    // in the first panel, a channel just placed. A move the remote made itself lands here too, on
    // the panel that already has it, and changes nothing. Two frames: one for the grid to compose,
    // one for it to be laid out.
    LaunchedEffect(picking, focused) {
        if (picking != null) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { requesters[session.focused.value].requestFocus() }
    }

    val gap = with(LocalDensity.current) { PANEL_GAP.roundToPx() }
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                // Keyed by place: a panel's view, and so its video surface, stays where it is
                // while the grid around it moves.
                slots.take(count).forEachIndexed { index, slot ->
                    key(index) {
                        SportPanel(
                            vm = vm,
                            slot = slot,
                            inFocus = index == focused,
                            expanded = expanded,
                            paused = paused,
                            canTakeFocus = picking == null && (!expanded || index == focused),
                            requester = requesters[index],
                            onFocused = { session.focus(index) },
                            onOk = {
                                when {
                                    slot.channel == null -> session.openPicker(index)
                                    slot.panel.status.value.failed -> session.retry(index)
                                    else -> session.setExpanded(!session.expanded.value)
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

        picking?.let { slot ->
            SportPicker(
                slot = slot,
                slots = slots,
                choices = choices,
                opacity = prefs.overlayOpacity,
                onPick = { channel -> session.assign(slot, channel) },
                onClear = { session.clear(slot) },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** One panel: the picture, what it is, and whether it is the one heard; or an empty place. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SportPanel(
    vm: AppViewModel,
    slot: SportSession.Slot,
    inFocus: Boolean,
    expanded: Boolean,
    paused: Boolean,
    /** Off for the panels hidden behind a full-screen one, and for all four while picking. */
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
    val label = channel?.displayName ?: stringResource(R.string.sport_add_channel)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (channel == null) studio.card else Color.Black)
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
        if (channel == null) {
            EmptyPanel(
                hint = stringResource(R.string.sport_add_hint),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            PanelVideo(player = player, modifier = Modifier.fillMaxSize())
            when {
                status.failed -> PanelFailure(
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
                audible = inFocus,
                paused = paused,
                expanded = expanded,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/**
 * The channel picker: a drawer against the end edge, as the playback menu is, over a grid that
 * keeps playing beside it. Israeli sports channels only (ADR-041). A channel already in another
 * panel says so, and choosing it swaps the two panels rather than opening a second connection.
 */
@Composable
private fun SportPicker(
    slot: Int,
    slots: List<SportSession.Slot>,
    choices: List<Channel>?,
    opacity: Float,
    onPick: (Channel) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val current = slots.getOrNull(slot)?.channel
    val elsewhere = slots.mapIndexedNotNull { i, s -> s.channel?.id?.takeIf { i != slot } }.toSet()
    val target = remember { FocusRequester() }
    val listState = rememberLazyListState()
    Column(
        modifier = modifier
            .width(PICKER_WIDTH)
            .fillMaxHeight()
            .background(studio.drawer.copy(alpha = opacity.coerceAtLeast(MENU_MIN_OPACITY)))
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
            text = stringResource(R.string.sport_pick_subtitle),
            style = StudioTheme.type.caption,
            color = studio.ink2,
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
        )
        when {
            choices == null -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) { Loader(size = 28.dp) }

            choices.isEmpty() -> Text(
                text = stringResource(R.string.sport_pick_none),
                style = StudioTheme.type.body,
                color = studio.ink2,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            else -> {
                // The remote lands on the channel the panel already shows, or on the first one.
                val landOn = current?.id?.takeIf { id -> choices.any { it.id == id } } ?: choices.first().id
                val clearRows = if (current != null) 1 else 0
                val landIndex = choices.indexOfFirst { it.id == landOn } + clearRows
                LaunchedEffect(slot, landOn) {
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
                    if (current != null) {
                        item(key = "clear") {
                            ListRow(
                                label = stringResource(R.string.sport_pick_remove),
                                icon = StudioIcons.Close,
                                onClick = onClear,
                            )
                        }
                    }
                    items(choices, key = { it.id }) { channel ->
                        SportPickerRow(
                            channel = channel,
                            selected = channel.id == current?.id,
                            onScreenElsewhere = channel.id in elsewhere,
                            focusRequester = if (channel.id == landOn) target else null,
                            onClick = { onPick(channel) },
                        )
                    }
                }
            }
        }
    }
}

/** The black line between panels. */
private val PANEL_GAP = 4.dp

/** The cue ring round the panel with the remote: thicker than a row's, read across a room. */
private val RING_WIDTH = 3.dp

private val PICKER_WIDTH = 360.dp

