package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.tivicopy.player.R
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.radio.RadioCategory
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.StationDisc
import com.tivicopy.player.ui.common.filledContentColour
import com.tivicopy.player.ui.common.focusLift
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.theme.StudioTheme

/** The strip's three buckets, in the canvas's order. */
private enum class StripBucket { NATIONAL, REGIONAL, OTHER }

private fun RadioCategory.bucket(): StripBucket = when (this) {
    RadioCategory.NATIONAL -> StripBucket.NATIONAL
    RadioCategory.REGIONAL -> StripBucket.REGIONAL
    else -> StripBucket.OTHER
}

/**
 * The stations along the bottom of the television's radio screen (`canvas/Radio.dc.html`): a
 * heading and a chip per category (national, regional, everything else), and under them the
 * stations' discs, the one on air ringed in the tally and the one the remote is on in the cue.
 * OK on a disc tunes it. It opens on the category of the station that is playing.
 *
 * The categories are the remote's too (ADR-058): ▲ from any station goes to the chosen category's
 * chip — not to whatever lies nearest above, which from the far end of the row was the play
 * button, so the chips could not be reached at all — ◀▶ walk the chips, and the stations follow
 * the chip under the remote without OK, as a tab row does on a television. ▼ goes back down.
 */
@Composable
internal fun TvStationStrip(
    vm: AppViewModel,
    current: Channel?,
    modifier: Modifier = Modifier,
) {
    var stations by remember { mutableStateOf<List<Pair<Channel, RadioCategory>>>(emptyList()) }
    LaunchedEffect(Unit) { stations = vm.radioStations() }
    if (stations.isEmpty()) return

    val byBucket = remember(stations) { stations.groupBy({ it.second.bucket() }, { it.first }) }
    val currentBucket = byBucket.entries.firstOrNull { (_, list) -> list.any { it.id == current?.id } }?.key
    var selected by remember(currentBucket) {
        mutableStateOf(currentBucket ?: byBucket.keys.minByOrNull { it.ordinal } ?: StripBucket.NATIONAL)
    }
    val shown = byBucket[selected].orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(selected, current?.id) {
        val at = shown.indexOfFirst { it.id == current?.id }
        // Another category starts at its first station, not where the last one was left.
        listState.scrollToItem(if (at >= 0) at else 0)
    }
    // One per category, so ▲ from the stations can land on the chosen one.
    val chipFocus = remember { StripBucket.entries.associateWith { FocusRequester() } }
    val studio = StudioTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = STRIP_SAFE_X),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.radio_stations),
                style = StudioTheme.type.h1,
                color = studio.ink,
            )
            StripBucket.entries.filter { it in byBucket }.forEach { bucket ->
                StripChip(
                    label = stringResource(
                        when (bucket) {
                            StripBucket.NATIONAL -> R.string.radio_category_national
                            StripBucket.REGIONAL -> R.string.radio_category_regional
                            StripBucket.OTHER -> R.string.radio_category_other
                        },
                    ),
                    selected = bucket == selected,
                    focusRequester = chipFocus.getValue(bucket),
                    onFocused = { selected = bucket },
                    onClick = { selected = bucket },
                )
            }
        }
        LazyRow(
            state = listState,
            // Applies to every station in the row: ▲ goes to the chosen category's chip.
            modifier = Modifier.focusProperties { up = chipFocus.getValue(selected) },
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
    /** Called as the remote reaches the chip: the category follows the remote, no OK needed. */
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
