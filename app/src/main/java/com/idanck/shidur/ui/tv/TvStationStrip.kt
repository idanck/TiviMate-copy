package com.idanck.shidur.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.idanck.shidur.R
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.radio.RadioRegion
import com.idanck.shidur.data.radio.RadioShelf
import com.idanck.shidur.ui.AppViewModel
import com.idanck.shidur.ui.common.FocusFill
import com.idanck.shidur.ui.common.LocalTvMetrics
import com.idanck.shidur.ui.common.StationDisc
import com.idanck.shidur.ui.common.filledContentColour
import com.idanck.shidur.ui.common.focusLift
import com.idanck.shidur.ui.common.focusSurface
import com.idanck.shidur.ui.screens.radioShelfLabel
import com.idanck.shidur.ui.theme.StudioTheme

/**
 * The stations along the bottom of the television's radio screen (`canvas/Radio.dc.html`): a
 * heading and a chip per shelf of the radio menu (ADR-050) — national, the regions from the north,
 * other regions, then the genres — and under them the stations' discs, the one on air ringed in
 * the tally and the one the remote is on in the cue. OK on a disc tunes it. It opens on the shelf
 * of the station that is playing: where it is heard, not its genre.
 *
 * The shelves are the remote's too (ADR-058): ▲ from any station goes to the chosen shelf's chip,
 * ◀▶ walk the chips, and the stations follow the chip under the remote without OK, as a tab row
 * does on a television. ▼ goes back down.
 */
@Composable
internal fun TvStationStrip(
    vm: AppViewModel,
    current: Channel?,
    modifier: Modifier = Modifier,
) {
    var stations by remember { mutableStateOf<List<Pair<Channel, List<RadioShelf>>>>(emptyList()) }
    LaunchedEffect(Unit) { stations = vm.radioStations() }
    if (stations.isEmpty()) return

    // The menu's own order (`RadioCategoryClassifier.menu`), each shelf with its stations. A
    // station is on its region's shelf and, when its name says one, its genre's too.
    val shelves = remember(stations) {
        val present = stations.flatMapTo(LinkedHashSet()) { it.second }
        val places = listOf<RadioShelf>(RadioShelf.National) + RadioRegion.entries.map { RadioShelf.Regional(it) }
        val genres = present.filterIsInstance<RadioShelf.Genre>().sortedBy { it.label }
        (places.filter { it in present } + genres).map { shelf ->
            shelf to stations.filter { shelf in it.second }.map { it.first }
        }
    }
    val currentShelf = stations.firstOrNull { it.first.id == current?.id }?.second?.firstOrNull()
    var selected by remember(currentShelf, shelves) {
        mutableStateOf(currentShelf?.takeIf { s -> shelves.any { it.first == s } } ?: shelves.first().first)
    }
    val shown = shelves.firstOrNull { it.first == selected }?.second.orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(selected, current?.id) {
        val at = shown.indexOfFirst { it.id == current?.id }
        // Another shelf starts at its first station, not where the last one was left.
        listState.scrollToItem(if (at >= 0) at else 0)
    }
    // One per shelf, so ▲ from the stations can land on the chosen one.
    val chipFocus = remember(shelves) { shelves.associate { it.first to FocusRequester() } }
    val studio = StudioTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Eight shelves can be wider than the room beside the heading, so the chips scroll with
        // the remote.
        Row(
            modifier = Modifier.padding(start = STRIP_SAFE_X),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.radio_stations),
                style = StudioTheme.type.h1,
                color = studio.ink,
            )
            // Not lazy: every chip stays composed, so ▲ from the stations always has its chip to
            // land on, and the row scrolls to whichever chip the remote is on.
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(end = STRIP_SAFE_X, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                shelves.forEach { (shelf, _) ->
                    StripChip(
                        label = radioShelfLabel(shelf),
                        selected = shelf == selected,
                        focusRequester = chipFocus.getValue(shelf),
                        onFocused = { selected = shelf },
                        onClick = { selected = shelf },
                    )
                }
            }
        }
        LazyRow(
            state = listState,
            // Applies to every station in the row: ▲ goes to the chosen shelf's chip.
            modifier = Modifier.focusProperties { chipFocus[selected]?.let { up = it } },
            contentPadding = PaddingValues(horizontal = STRIP_SAFE_X, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shown, key = { it.id }) { station ->
                StripStation(
                    station = station,
                    onAir = station.id == current?.id,
                    onClick = { vm.play(station) },
                )
            }
        }
    }
}

/** Studio's chip (`canvas/Chip.dc.html`): a quiet surface, ink when chosen, the cue under focus. */
@Composable
private fun StripChip(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    /** Called as the remote reaches the chip: the shelf follows the remote, no OK needed. */
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LocalTvMetrics.current.chipCorner)
    Box(
        modifier = Modifier
            .focusLift(focused, shape = shape)
            .focusSurface(focused = focused, shape = shape, selected = selected, fill = FocusFill.FILLED)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusRequester(focusRequester)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = filledContentColour(focused, selected),
            maxLines = 1,
        )
    }
}

/**
 * A station on the strip: its disc and name. On air, a tally ring and a tally dot before the name;
 * under the remote, the cue ring and a slight lift (`StationDisc`'s `focus` state).
 */
@Composable
private fun StripStation(station: Channel, onAir: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val studio = StudioTheme.colors
    Column(
        modifier = Modifier
            .width(STATION_WIDTH)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .semantics { contentDescription = station.displayName },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ring = when {
            focused -> Modifier.border(2.dp, studio.cue, CircleShape)
            onAir -> Modifier.border(2.dp, studio.tally, CircleShape)
            else -> Modifier
        }
        Box(
            modifier = Modifier
                .focusLift(focused, scale = DISC_FOCUS_SCALE, shape = CircleShape)
                .then(ring)
                .padding(3.dp),
        ) {
            StationDisc(channel = station, size = DISC_SIZE, focused = focused, onAir = onAir)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onAir) {
                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(studio.tally))
            }
            Text(
                text = station.displayName,
                style = StudioTheme.type.caption.copy(
                    fontWeight = if (onAir || focused) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (onAir || focused) studio.ink else studio.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `--st-safe-x` at 1080p, halved. */
private val STRIP_SAFE_X = 48.dp

/** The strip's discs: 150px at 1080p. */
private val DISC_SIZE = 75.dp
private val STATION_WIDTH = 90.dp

/** `StationDisc`'s focus state: `--st-focus-scale`. */
private const val DISC_FOCUS_SCALE = 1.06f
