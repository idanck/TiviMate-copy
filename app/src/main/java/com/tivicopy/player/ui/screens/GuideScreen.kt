package com.tivicopy.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.model.Recording
import com.tivicopy.player.data.model.RecordingState
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.ClockText
import com.tivicopy.player.ui.common.EmptyState
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.StudioButton
import com.tivicopy.player.ui.common.StudioButtonSize
import com.tivicopy.player.ui.common.StudioButtonVariant
import com.tivicopy.player.ui.common.filledContentColour
import com.tivicopy.player.ui.common.focusLift
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.delay

// The three dimensions this grid is laid out from now come from `LocalTvMetrics`, which the
// television shell fills in from the real viewport (see `ui/common/TvMetrics.kt`). The touch shell
// leaves the default in place, so the phone's grid is unchanged.

/**
 * The grid guide: channels down, time across (FR-UI-05).
 *
 * Two things keep this responsive on a mid-range TV box with a 500-channel, 7-day guide
 * (FR-UI-07, NFR-PERF-03):
 *
 * - the channel axis is a `LazyColumn`, so only visible rows are composed at all;
 * - programmes are fetched for the **visible time window only**, in one batched query per
 *   distinct EPG offset, and refetched when the window moves — the guide is never held in
 *   memory in full.
 *
 * Scrolling backwards is the same code path as forwards (FR-CU-02): the window simply moves,
 * and past programmes carry an archive badge where the channel offers one.
 */
@Composable
fun GuideScreen(
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    onOpenProgramme: (Channel, Programme) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Told which programme the remote is on, so a panel beside the grid can describe it
     * (`canvas/Guide.dc.html`'s focused programme). Null where nothing is listening.
     */
    onFocusProgramme: ((Channel, Programme) -> Unit)? = null,
    /** Off where a Studio top bar above this screen already carries the clock. */
    showClock: Boolean = true,
) {
    val state by vm.state.collectAsState()
    val channels = state.list.channels

    // Window origin, snapped to the half hour so the time axis lines up with the grid.
    var windowStart by remember {
        mutableStateOf(snapToHalfHour(System.currentTimeMillis() - 30 * 60_000L))
    }
    val windowEnd by remember { derivedStateOf { windowStart + WINDOW_HOURS * 60 * 60_000L } }

    val listState = rememberLazyListState()
    val timeScroll = rememberScrollState()
    var programmes by remember { mutableStateOf<Map<Long, List<Programme>>>(emptyMap()) }

    // The visible slice of the channel axis drives the query, so a 500-channel guide costs the
    // same as a 20-channel one.
    //
    // How many rows are visible is asked of the layout rather than assumed. It used to be a
    // constant twelve, which was a guess at a phone's row count and wrong on a television in both
    // directions: too many rows on a small overlay meant querying guide data nothing showed, and
    // too few on a large panel meant every row past the eighteenth rendered "no guide data" while
    // the data sat in the database. That was a correctness bug, not a tuning one.
    val visibleChannels by remember(channels) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val firstVisible = visible.firstOrNull()?.index ?: listState.firstVisibleItemIndex
            val lastVisible = visible.lastOrNull()?.index ?: (firstVisible + FALLBACK_ROWS)
            val first = (firstVisible - PREFETCH_ROWS).coerceAtLeast(0)
            val last = (lastVisible + 1 + PREFETCH_ROWS).coerceAtMost(channels.size)
            if (first >= last) emptyList() else channels.subList(first, last).toList()
        }
    }

    var recordings by remember { mutableStateOf<Map<Long, List<Recording>>>(emptyMap()) }

    LaunchedEffect(visibleChannels, windowStart) {
        // Replaced rather than accumulated: the map is bounded by what is on screen, so a
        // long scroll through a 500-channel guide cannot grow the heap without limit.
        programmes = if (visibleChannels.isEmpty()) emptyMap()
        else vm.guideWindow(visibleChannels, windowStart, windowEnd)
    }

    // FR-UI-10: recording state belongs in the grid beside catch-up availability. Keyed on the
    // window rather than the visible channels, because the whole set for six hours is a handful
    // of rows — far cheaper than re-querying on every scroll tick.
    LaunchedEffect(windowStart) { recordings = vm.recordingsIn(windowStart, windowEnd) }

    Column(modifier = modifier.fillMaxSize()) {
        GuideToolbar(
            windowStart = windowStart,
            showClock = showClock,
            onJumpNow = { windowStart = snapToHalfHour(System.currentTimeMillis() - 30 * 60_000L) },
            onShift = { hours -> windowStart += hours * 60 * 60_000L },
        )

        // FR-UI-05's jump-to-date, as the strip of days the benchmark puts above its grid rather
        // than the dialog this used to open (ADR-020): reaching next Tuesday is now one press
        // sideways and one press of OK, from a row that also says what today is.
        GuideDayStrip(
            windowStart = windowStart,
            onPick = { day ->
                val now = System.currentTimeMillis()
                // Picking today means "now", not 00:00 — nobody opens the guide to read what was
                // on at midnight.
                windowStart = if (day == TimeModel.startOfLocalDay(now)) {
                    snapToHalfHour(now - 30 * 60_000L)
                } else {
                    day
                }
            },
        )
        HorizontalDivider()

        if (channels.isEmpty()) {
            EmptyState(stringResource(R.string.empty_no_channels), icon = Icons.Filled.LiveTv)
            return@Column
        }

        TimeAxis(windowStart = windowStart, scrollState = timeScroll)
        HorizontalDivider()

        // The grid and the current-time marker share a box so the marker can be drawn across
        // every row at once, rather than each row drawing its own slice of a line.
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(channels, key = { it.id }) { channel ->
                    GuideRow(
                        channel = channel,
                        programmes = programmes[channel.id].orEmpty(),
                        recordings = recordings[channel.id].orEmpty(),
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        scrollState = timeScroll,
                        onScreen = channel.id == state.currentChannel?.id,
                        onChannelClick = { onPlay(channel) },
                        onProgrammeClick = { onOpenProgramme(channel, it) },
                        onProgrammeFocused = { onFocusProgramme?.invoke(channel, it) },
                    )
                }
            }
            NowMarker(
                windowStart = windowStart,
                windowEnd = windowEnd,
                scrollState = timeScroll,
            )
        }
    }
}

/**
 * The line down the grid at the current time (FR-UI-05).
 *
 * A grid guide without one asks the viewer to read the hour ruler and work out where "now" falls,
 * every time they open it. The requirement has always named it; the code never drew it, and
 * `docs/10` recorded the row as Done regardless.
 *
 * It ticks on its own rather than only when the grid recomposes, because it is a clock: left to
 * the guide's own recompositions it would freeze wherever it happened to be when the guide opened.
 */
@Composable
private fun BoxScope.NowMarker(
    windowStart: Long,
    windowEnd: Long,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    val metrics = LocalTvMetrics.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(NOW_MARKER_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    if (now < windowStart || now > windowEnd) return

    // `ScrollState.value` is in pixels; every other measurement here is in dp.
    val scrolled = with(LocalDensity.current) { scrollState.value.toDp() }
    val x = metrics.guideChannelColumnWidth +
        millisToDp(now - windowStart, metrics.guideHourWidth) - scrolled
    // Scrolled far enough forward and "now" sits behind the channel column, where a line would
    // read as a border on the names rather than a time.
    if (x < metrics.guideChannelColumnWidth) return

    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = x)
            // The tally: "now" is what is on air (`canvas/Guide.dc.html`).
            .width(1.5.dp)
            .fillMaxHeight()
            .background(StudioTheme.colors.tally),
    )
}

/** How often the current-time line moves. A guide hour is wide; a minute is plenty. */
private const val NOW_MARKER_TICK_MS = 60_000L

@Composable
private fun GuideToolbar(
    windowStart: Long,
    showClock: Boolean,
    onJumpNow: () -> Unit,
    onShift: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = TimeModel.formatDay(windowStart),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(modifier = Modifier.weight(1f))
        // Ghosts, all three (MIGRATION.md §2): moving the window is not this screen's main action.
        StudioButton(
            label = stringResource(R.string.guide_shift_back_hours),
            onClick = { onShift(-2) },
            variant = StudioButtonVariant.GHOST,
            size = StudioButtonSize.MEDIUM,
        )
        StudioButton(
            label = stringResource(R.string.guide_jump_now),
            onClick = onJumpNow,
            variant = StudioButtonVariant.GHOST,
            size = StudioButtonSize.MEDIUM,
        )
        StudioButton(
            label = stringResource(R.string.guide_shift_forward_hours),
            onClick = { onShift(2) },
            variant = StudioButtonVariant.GHOST,
            size = StudioButtonSize.MEDIUM,
        )
        // The day buttons that used to sit here are the strip below; what is left is the pair
        // that moves inside a day. The clock is here because a full-screen overlay takes the
        // device's own away with it, and a guide is read against the current time.
        if (showClock) {
            ClockText(
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The days the guide can jump to, as a row of chips (FR-UI-05).
 *
 * Days are named rather than dated where a name exists — yesterday, today, tomorrow — and by
 * weekday plus date beyond that, which is how anyone actually asks for a day. The whole strip is
 * a `LazyRow` starting one chip before today, so today is on screen without being flush against
 * the edge, and the days behind it are one press away for catch-up (FR-CU-02).
 */
@Composable
private fun GuideDayStrip(windowStart: Long, onPick: (Long) -> Unit) {
    // One reading of the clock for the whole strip: taken again per chip, a day boundary crossed
    // mid-composition would put two different "today"s on the same row.
    val reference = remember { System.currentTimeMillis() }
    val selectedDay = TimeModel.startOfLocalDay(windowStart)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (GUIDE_DAYS.indexOf(0) - 1).coerceAtLeast(0),
    )
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(GUIDE_DAYS.toList(), key = { it }) { offset ->
            // Normalised through `startOfLocalDay` rather than by adding whole days to a
            // midnight: the hour the clocks change would otherwise leave one chip pointing at
            // 23:00 the day before.
            val day = TimeModel.startOfLocalDay(reference + offset * DAY_MILLIS)
            val label = when (offset) {
                -1 -> stringResource(R.string.guide_day_yesterday)
                0 -> stringResource(R.string.guide_day_today)
                1 -> stringResource(R.string.guide_day_tomorrow)
                else -> TimeModel.formatWeekday(day)
            }
            DayChip(
                label = label,
                date = TimeModel.formatDay(day),
                selected = day == selectedDay,
                onClick = { onPick(day) },
            )
        }
    }
}

@Composable
private fun DayChip(label: String, date: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Studio's chip (`canvas/Chip.dc.html`): a quiet surface at rest, ink when it is the chosen
    // day, the cue when focus is on it.
    val shape = RoundedCornerShape(LocalTvMetrics.current.chipCorner)
    // One line, not two. This strip sits between the guide's preview and its grid on a
    // television, where every dp it takes is taken from the rows — and a row is what anyone
    // opened the guide to read.
    Row(
        modifier = Modifier
            .focusLift(focused, shape = shape)
            .focusSurface(focused = focused, shape = shape, selected = selected, fill = FocusFill.FILLED)
            .onFocusChanged { focused = it.isFocused }
            // As everywhere else in this grid: `clickable` is the focus target on its own.
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$label $date" }
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = filledContentColour(focused, selected),
            maxLines = 1,
        )
        Text(
            // `dd/MM` beside Hebrew reorders unless it is isolated (FR-UI-21).
            text = TimeModel.ltr(date),
            style = MaterialTheme.typography.labelSmall,
            color = filledContentColour(focused, selected, muted = true),
            maxLines = 1,
        )
    }
}

/**
 * The hour ruler. It shares [scrollState] with every row, so horizontal scrolling stays in
 * lock-step without a custom layout or a nested scroll contract.
 */
@Composable
private fun TimeAxis(windowStart: Long, scrollState: androidx.compose.foundation.ScrollState) {
    val metrics = LocalTvMetrics.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(metrics.guideChannelColumnWidth))
        Row(modifier = Modifier.horizontalScroll(scrollState)) {
            repeat(WINDOW_HOURS) { hour ->
                val at = windowStart + hour * 60 * 60_000L
                // Each hour is marked by a tick on its edge, as the canvas draws it.
                Row(
                    modifier = Modifier
                        .width(metrics.guideHourWidth)
                        .padding(top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(modifier = Modifier.width(1.dp).height(14.dp).background(StudioTheme.colors.track))
                    Text(
                        text = TimeModel.formatClock(at),
                        style = StudioTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = StudioTheme.colors.ink2,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    programmes: List<Programme>,
    /** What is scheduled or recorded on this channel in the visible window (FR-UI-10). */
    recordings: List<Recording>,
    windowStart: Long,
    windowEnd: Long,
    scrollState: androidx.compose.foundation.ScrollState,
    /** Whether this is the channel on screen, which the tally marks. */
    onScreen: Boolean,
    onChannelClick: () -> Unit,
    onProgrammeClick: (Programme) -> Unit,
    onProgrammeFocused: (Programme) -> Unit,
) {
    val metrics = LocalTvMetrics.current
    val studio = StudioTheme.colors
    Row(modifier = Modifier.fillMaxWidth().height(metrics.guideRowHeight)) {
        // Studio's channel cell: a `surface` card with the number, the name, and the tally when
        // this is the channel on screen.
        Row(
            modifier = Modifier
                .width(metrics.guideChannelColumnWidth)
                .fillMaxHeight()
                .padding(2.dp)
                .clip(RoundedCornerShape(metrics.rowCorner))
                .background(studio.surface)
                // No `focusable()` beside `clickable()`: clickable is focusable in its own right,
                // so the pair produced two focus targets and cost a remote an invisible extra
                // press to cross each row.
                .clickable(onClick = onChannelClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (channel.number > 0) {
                Text(
                    text = channel.number.toString(),
                    style = StudioTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = studio.ink3,
                    modifier = Modifier.widthIn(min = GUIDE_NUMBER_WIDTH),
                )
            } else {
                ChannelLogo(channel = channel, sizeDp = metrics.rowLogoSize)
            }
            Text(
                text = channel.displayName,
                style = StudioTheme.type.rowLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = studio.ink,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onScreen) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(studio.tally))
            }
        }

        Box(modifier = Modifier.horizontalScroll(scrollState)) {
            if (programmes.isEmpty()) {
                // FR-EPG-16: say so, rather than leaving an unexplained empty lane.
                Box(
                    modifier = Modifier
                        .width(metrics.guideHourWidth * WINDOW_HOURS)
                        .height(metrics.guideRowHeight)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(metrics.rowCorner))
                        .background(studio.past),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(R.string.no_guide_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            } else {
                Row {
                    var cursor = windowStart
                    programmes.filter { it.endUtc > windowStart && it.startUtc < windowEnd }
                        .forEach { programme ->
                            val start = programme.startUtc.coerceAtLeast(windowStart)
                            if (start > cursor) {
                                TimeGap(widthMillis = start - cursor)
                            }
                            val end = programme.endUtc.coerceAtMost(windowEnd)
                            // Keyed so a refetch matches each cell to the programme it was drawing
                            // rather than to whatever now sits in that position. Scrolling the
                            // channel axis replaces the whole programme map, and unkeyed cells are
                            // recreated on every one of those — disposing whichever one the remote
                            // was focused on, which drops focus to the root and leaves the D-pad
                            // apparently dead.
                            key(programme.id) {
                                ProgrammeCell(
                                    programme = programme,
                                    widthMillis = (end - start).coerceAtLeast(60_000L),
                                    archived = channel.hasCatchup &&
                                        programme.endUtc < System.currentTimeMillis(),
                                    recordingState = recordings.stateFor(programme),
                                    onScreen = onScreen,
                                    onClick = { onProgrammeClick(programme) },
                                    onFocused = { onProgrammeFocused(programme) },
                                )
                            }
                            cursor = end
                        }
                    if (cursor < windowEnd) TimeGap(widthMillis = windowEnd - cursor)
                }
            }
        }
    }
}

/** Blank space for a stretch of time the guide has no entry for. */
@Composable
private fun TimeGap(widthMillis: Long) {
    val metrics = LocalTvMetrics.current
    Box(
        modifier = Modifier
            .width(millisToDp(widthMillis, metrics.guideHourWidth))
            .height(metrics.guideRowHeight),
    )
}

@Composable
/**
 * Which recording, if any, covers this programme.
 *
 * By overlap of the midpoint, not by an equal start time: a scheduled recording carries the
 * pre- and post-roll padding, so its window is wider than the programme's by design. Cancelled
 * and failed recordings are ignored — a badge for something that did not happen is a lie the
 * grid does not need to tell.
 */
private fun List<Recording>.stateFor(programme: Programme): RecordingState? {
    if (isEmpty()) return null
    val midpoint = programme.startUtc + (programme.endUtc - programme.startUtc) / 2
    return firstOrNull { recording ->
        midpoint in recording.startUtc until recording.endUtc &&
            recording.state != RecordingState.CANCELLED &&
            recording.state != RecordingState.FAILED
    }?.state
}

@Composable
private fun ProgrammeCell(
    programme: Programme,
    widthMillis: Long,
    archived: Boolean,
    recordingState: RecordingState?,
    /** Whether this cell's channel is the one on screen; its airing cell then carries the tally. */
    onScreen: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val live = now in programme.startUtc until programme.endUtc
    // The cell's own body colour, which is about the programme rather than about focus: a block
    // in this grid has to be visible at rest, and the one airing now is what the grid is read to
    // find. The focus treatment layers over it (`ui/common/Focus.kt`) instead of replacing it,
    // which is what lets a focused cell still say whether it is the live one.
    // Studio's three cell tones (`canvas/GuideCell.dc.html`): aired, airing now, still to come.
    val studio = StudioTheme.colors
    val body = when {
        live -> studio.airing
        programme.endUtc <= now -> studio.past
        else -> studio.future
    }
    val metrics = LocalTvMetrics.current
    // A cell is a card in Studio, and is cut like one.
    val cellShape = RoundedCornerShape(metrics.rowCorner)
    Column(
        modifier = Modifier
            // The focused cell lifts slightly out of the grid, as the benchmark's does: a border
            // alone is hard to find on a wall of cells from across a room. It grew instantly
            // until `focusLift` existed, which on a grid this dense read as the cells flickering
            // rather than as one of them rising (`ui/common/Motion.kt`).
            // Studio does not grow a cell — on a grid this dense it would cover its neighbours —
            // but it does lift it: the shadow and the cue ring are the answer.
            .focusLift(focused, scale = 1f, shape = cellShape)
            .width(millisToDp(widthMillis, metrics.guideHourWidth))
            .height(metrics.guideRowHeight)
            .padding(2.dp)
            .clip(cellShape)
            .background(body)
            // The app's one focus treatment. This cell used to ring itself at a hard-coded 2dp,
            // so on a television every row in the channel list was ringed at 3dp and every cell
            // in the guide beside it at 2dp.
            .focusSurface(focused = focused, shape = cellShape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            // As in the channel cell above: `clickable` carries the focus target, and pairing it
            // with `focusable()` made every cell cost two D-pad presses to pass.
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val past = programme.endUtc <= now
        val cellTitle = StudioTheme.type.body.copy(
            fontSize = StudioTheme.type.body.fontSize * CELL_TITLE_SCALE,
            fontWeight = FontWeight.SemiBold,
        )
        val cellTime = StudioTheme.type.body.copy(fontSize = StudioTheme.type.body.fontSize * CELL_TIME_SCALE)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (live && onScreen) {
                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(studio.tally))
            }
            Text(
                text = programme.title,
                style = cellTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (past) studio.ink2 else studio.ink,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = TimeModel.formatClock(programme.startUtc),
                style = cellTime,
                color = if (past) studio.ink3 else studio.ink2,
            )
            // FR-UI-10: archive availability is badged in the grid itself.
            if (archived) {
                Text(
                    text = stringResource(R.string.catchup_available),
                    style = cellTime,
                    color = studio.next,
                )
            }
            // The other half of FR-UI-10, which was never drawn: a programme you had scheduled
            // looked exactly like one you had not.
            recordingState?.let { state ->
                Text(
                    text = stringResource(state.label()),
                    style = cellTime,
                    color = when (state) {
                        RecordingState.RECORDING -> studio.tally
                        else -> studio.ink2
                    },
                )
            }
        }
    }
}

/*
 * The guide's measurements from `canvas/Guide.dc.html` and `GuideCell.dc.html`, against the body
 * step: a cell's title is 21px and its time 17px where the body is 24.
 */
private const val CELL_TITLE_SCALE = 21f / 24f
private const val CELL_TIME_SCALE = 17f / 24f
private val GUIDE_NUMBER_WIDTH = 15.dp

private fun millisToDp(millis: Long, hourWidth: Dp) = hourWidth * (millis.toFloat() / 3_600_000f)

private fun snapToHalfHour(utcMillis: Long): Long = (utcMillis / 1_800_000L) * 1_800_000L

/**
 * A day, for the strip's arithmetic. The strip normalises every result through
 * `TimeModel.startOfLocalDay`, so this is a step size rather than a day boundary — which is the
 * distinction the guide's old UTC-snapped `snapToDay` blurred, and why picking a day used to land
 * the window on 02:00 or 03:00 local rather than on midnight.
 */
private const val DAY_MILLIS = 24 * 60 * 60_000L

/**
 * Which days the strip offers, relative to today.
 *
 * Backwards for catch-up, forwards for the guide's own horizon. A fortnight either way covers
 * every EPG this app has seen; the row scrolls, so the range costs nothing but the chips a
 * viewer can actually reach.
 */
private val GUIDE_DAYS = -14..14

/** Hours rendered at once; anything wider is reached by scrolling the window, not the layout. */
private const val WINDOW_HOURS = 6

/**
 * How many rows to assume before the list has measured itself.
 *
 * Only ever used for the first composition, when `visibleItemsInfo` is still empty; one frame
 * later the real count replaces it.
 */
private const val FALLBACK_ROWS = 8

/** Prefetch margin either side of what is on screen. */
private const val PREFETCH_ROWS = 6
