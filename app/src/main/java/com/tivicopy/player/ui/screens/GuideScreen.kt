package com.tivicopy.player.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.model.Recording
import com.tivicopy.player.data.model.RecordingState
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.AheadPrefetch
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.ClockText
import com.tivicopy.player.ui.common.EmptyState
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.QuietPrefetchScheduler
import com.tivicopy.player.ui.common.StudioButton
import com.tivicopy.player.ui.common.StudioButtonSize
import com.tivicopy.player.ui.common.StudioButtonVariant
import com.tivicopy.player.ui.common.animatedColourState
import com.tivicopy.player.ui.common.focusLift
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.common.litEdge
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.delay
import com.tivicopy.player.ui.common.StudioIcons

// The three dimensions this grid is laid out from now come from `LocalTvMetrics`, which the
// television shell fills in from the real viewport (see `ui/common/TvMetrics.kt`). The touch shell
// leaves the default in place, so the phone's grid is unchanged.

/**
 * The hours the guide shows: where its window starts, and the three ways it moves — to now, by
 * two hours, to a day.
 *
 * Held apart from the grid so the two shells can lay the guide out differently around the one
 * window. The phone stacks the toolbar, the strip of days and the grid ([GuideScreen]); the
 * television puts the days in a band over the picture and the grid on a backdrop of its own under
 * it (`ui/tv/GuideOverPicture.kt`, ADR-051), and both halves have to move the same window.
 */
@Stable
internal class GuideWindow(initialStart: Long) {
    /** The window's origin, on a half hour so the time axis lines up with the grid. */
    var start by mutableLongStateOf(initialStart)
        private set

    /** Where the window ends: [WINDOW_HOURS] after [start]. */
    val end: Long get() = start + WINDOW_HOURS * HOUR_MILLIS

    fun jumpNow() {
        start = nowWindowStart(System.currentTimeMillis())
    }

    fun shift(hours: Int) {
        start += hours * HOUR_MILLIS
    }

    /**
     * Opens [day], a local midnight from the strip. Picking today means "now", not 00:00 — nobody
     * opens the guide to read what was on at midnight.
     */
    fun pick(day: Long) {
        val now = System.currentTimeMillis()
        start = if (day == TimeModel.startOfLocalDay(now)) nowWindowStart(now) else day
    }
}

/** A [GuideWindow] that opens on the half hour before now, kept for as long as the guide is. */
@Composable
internal fun rememberGuideWindow(): GuideWindow =
    remember { GuideWindow(nowWindowStart(System.currentTimeMillis())) }

/**
 * The grid guide: channels down, time across (FR-UI-05), as the phone lays it out — a toolbar,
 * the strip of days and the grid under them. The television draws the same parts over the picture
 * instead ([GuideDayRow], [GuideGrid]; ADR-051).
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
    /** Told which programme the remote is on. Null where nothing is listening. */
    onFocusProgramme: ((Channel, Programme) -> Unit)? = null,
    /** Off where a Studio top bar above this screen already carries the clock. */
    showClock: Boolean = true,
    /**
     * The channel whose programme on air the guide opens on, focused; null for none. Read once,
     * when the guide opens.
     */
    landOn: Long? = null,
) {
    val window = rememberGuideWindow()
    Column(modifier = modifier.fillMaxSize()) {
        // FR-UI-05's jump-to-date, as the strip of days the benchmark puts above its grid rather
        // than the dialog this used to open (ADR-020): reaching next Tuesday is now one press
        // sideways and one press of OK, from a row that also says what today is.
        GuideToolbar(
            windowStart = window.start,
            showClock = showClock,
            onJumpNow = window::jumpNow,
            onShift = window::shift,
        )
        GuideDayStrip(window = window)
        HorizontalDivider()
        GuideGrid(
            vm = vm,
            window = window,
            onPlay = onPlay,
            onOpenProgramme = onOpenProgramme,
            onFocusProgramme = onFocusProgramme,
            landOn = landOn,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The television's day switcher (ADR-051): the strip of days, its chips at the large control
 * height with the chosen day in amber, and the window's moves inside a day at its end.
 *
 * One row, as it has been since ADR-013's addendum: the date the phone's toolbar prints is what
 * the chosen chip says, and the clock is in the top bar.
 *
 * @param prefetch builds the chips past either edge while the screen is still; see [GuideGrid]
 */
@Composable
internal fun GuideDayRow(
    window: GuideWindow,
    prefetch: QuietPrefetchScheduler?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GuideDayStrip(
            window = window,
            prefetch = prefetch,
            prominent = true,
            modifier = Modifier.weight(1f),
        )
        // Plates rather than ghosts here: this row sits on the picture, dimmed only lightly, and a
        // bare label over a bright frame is not something anyone can read across a room.
        WindowButtons(
            onJumpNow = window::jumpNow,
            onShift = window::shift,
            variant = StudioButtonVariant.SECONDARY,
        )
    }
}

/**
 * The grid itself: the hour ruler, the channel rows and the line at the current time, for
 * [window]. Draws no strip of days and no toolbar; [GuideScreen] and the television's guide put
 * those where each wants them.
 *
 * @param onFocusProgramme told which programme the remote is on, so a strip or a sheet beside the
 *   grid can describe it. Null where nothing is listening.
 * @param landOn the channel whose programme on air the guide opens on, focused: the playing one
 *   when the guide comes from the video (`canvas/GuideFromVideo.dc.html`, ADR-036), or the one the
 *   channel list's preview asked for. Read once, when the guide opens.
 * @param prefetch the television's: builds the rows past either edge of the list while the screen
 *   is still, rather than in the frame the remote scrolls to them (`QuietPrefetch.kt`, ADR-048's
 *   0.20.9–0.20.11). Null keeps the lazy list's own prefetch, which a phone's fling wants.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GuideGrid(
    vm: AppViewModel,
    window: GuideWindow,
    onPlay: (Channel) -> Unit,
    onOpenProgramme: (Channel, Programme) -> Unit,
    modifier: Modifier = Modifier,
    onFocusProgramme: ((Channel, Programme) -> Unit)? = null,
    landOn: Long? = null,
    prefetch: QuietPrefetchScheduler? = null,
) {
    val state by vm.state.collectAsState()
    val channels = state.list.channels
    val metrics = LocalTvMetrics.current
    // What every cell shares, worked out here once rather than in each of a few hundred cells.
    val look = rememberCellLook(metrics.cellCorner)
    // Lets the rows' programmes in a row a frame as the guide opens and as its window moves. On a
    // television only: a phone builds a row well inside a frame, and a fling wants them all.
    val staged = StudioTheme.television
    val gate = remember(staged) { RowGate(staged) }

    // A row of the guide is a channel cell and every programme in six hours, each with its own
    // focus treatment: on a Google TV Streamer it takes longer to build than a frame has, so the
    // list's own prefetch would never fit one and would ask again every frame, waking the app
    // sixty times a second for as long as the guide stayed open (as home's rails did, ADR-048).
    // The rows past each edge are built while the screen is still instead.
    val listState = if (prefetch != null) {
        rememberLazyListState(
            prefetchStrategy = remember(prefetch) { AheadPrefetch(prefetch, ahead = ROWS_AHEAD) },
        )
    } else {
        rememberLazyListState()
    }
    val timeScroll = rememberScrollState()
    var programmes by remember { mutableStateOf<Map<Long, List<Programme>>>(emptyMap()) }
    // Which channels the last fetch was for, and which window: a row outside it is still waiting
    // for its programmes, which is not the same as having none, and draws an empty lane rather
    // than "no guide data".
    var covered by remember { mutableStateOf(Coverage(Long.MIN_VALUE, emptySet())) }

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

    // [landOn], once. Held here rather than in a row, which the lazy list may compose and drop
    // more than once on the way there. The requester stays on that channel's airing cell for as
    // long as the guide is open; only the first fetch that covers that row asks for focus.
    val landingId = remember { landOn }
    var landed by remember { mutableStateOf(landingId == null) }
    val onAirFocus = remember { FocusRequester() }
    // Set by that channel's row once its programmes are drawn: before that its airing cell does
    // not exist to be focused, the rows being let in a frame at a time (`RowGate`).
    var landingReady by remember { mutableStateOf(false) }
    val markLanding: () -> Unit = remember { { landingReady = true } }

    // That channel's row on screen first, with the row above it in view, as the canvas sets it.
    // Keyed on the list: the guide can compose before the channels have arrived.
    LaunchedEffect(channels) {
        if (landed || landingId == null) return@LaunchedEffect
        val index = channels.indexOfFirst { it.id == landingId }
        if (index > 0) listState.scrollToItem(index - 1)
    }

    val windowStart = window.start
    val windowEnd = window.end
    // A new window has its rows' programmes let in a row a frame again, as the opening does.
    gate.stageFor(windowStart)

    LaunchedEffect(visibleChannels, windowStart) {
        // Replaced rather than accumulated: the map is bounded by what is on screen, so a
        // long scroll through a 500-channel guide cannot grow the heap without limit.
        val slice: Map<Long, List<Programme>> = if (visibleChannels.isEmpty()) emptyMap()
        else vm.guideWindow(visibleChannels, windowStart, windowEnd)
        // A channel whose programmes came back the same keeps the list it had, so its row is
        // skipped rather than recomposed: a step down the list refetches the whole visible slice,
        // and every row in it used to be recomposed for a new list of the same programmes.
        val previous = programmes
        programmes = slice.mapValues { (id, list) -> previous[id]?.takeIf { it == list } ?: list }
        covered = Coverage(windowStart, visibleChannels.mapTo(HashSet<Long>(visibleChannels.size)) { it.id })
    }

    // The landing waits for that channel's row to draw its programmes while it is on screen — a
    // row only in the prefetch margin, or still waiting its turn, has no cells to focus — and has
    // one try: a channel with nothing on air now leaves the guide opening as it always has, and a
    // later refetch never pulls the remote back from wherever the viewer has taken it.
    LaunchedEffect(landingReady, visibleChannels) {
        if (landed || !landingReady) return@LaunchedEffect
        val id = landingId ?: return@LaunchedEffect
        // Two frames: one for the row's cells to be laid out, one for the requester to attach.
        withFrameNanos { }
        withFrameNanos { }
        // Not on screen yet: the scroll to it is still to come, and this runs again when it has.
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == id }) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (programmes[id].orEmpty().any { now in it.startUtc until it.endUtc }) {
            runCatching { onAirFocus.requestFocus() }
        }
        landed = true
    }

    // FR-UI-10: recording state belongs in the grid beside catch-up availability. Keyed on the
    // window rather than the visible channels, because the whole set for six hours is a handful
    // of rows — far cheaper than re-querying on every scroll tick.
    LaunchedEffect(windowStart) { recordings = vm.recordingsIn(windowStart, windowEnd) }

    // The current time, for the line down the grid (FR-UI-05). It ticks on its own rather than
    // only when the grid recomposes, because it is a clock: left to the guide's own recompositions
    // it would freeze wherever it happened to be when the guide opened. Read only where the line
    // is drawn, so a tick redraws the grid and recomposes nothing.
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(NOW_MARKER_TICK_MS)
            now.longValue = System.currentTimeMillis()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (channels.isEmpty()) {
            EmptyState(stringResource(R.string.empty_no_channels), icon = StudioIcons.Tv)
            return@Column
        }

        TimeAxis(windowStart = windowStart, scrollState = timeScroll)
        HorizontalDivider()

        // The grid and the current-time line share a box so the line can be drawn across every
        // row at once, rather than each row drawing its own slice of it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nowLine(
                    window = window,
                    now = { now.longValue },
                    scrollState = timeScroll,
                    columnWidth = metrics.guideChannelColumnWidth,
                    hourWidth = metrics.guideHourWidth,
                    // The tally: "now" is what is on air (`canvas/Guide.dc.html`).
                    colour = StudioTheme.colors.tally,
                ),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                    GuideRow(
                        channel = channel,
                        programmes = programmes[channel.id].orEmpty(),
                        fetched = covered.start == windowStart && channel.id in covered.ids,
                        recordings = recordings[channel.id].orEmpty(),
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        scrollState = timeScroll,
                        onScreen = channel.id == state.currentChannel?.id,
                        gate = gate,
                        order = index,
                        look = look,
                        onChannelClick = { onPlay(channel) },
                        onProgrammeClick = { onOpenProgramme(channel, it) },
                        onProgrammeFocused = { onFocusProgramme?.invoke(channel, it) },
                        onAirFocus = onAirFocus.takeIf { channel.id == landingId },
                        onShown = if (channel.id == landingId && !landed) markLanding else null,
                    )
                }
            }
        }
    }
}

/**
 * The line down the grid at the current time (FR-UI-05), over the rows.
 *
 * A grid guide without one asks the viewer to read the hour ruler and work out where "now" falls,
 * every time they open it. The requirement has always named it; the code never drew it, and
 * `docs/10` recorded the row as Done regardless.
 *
 * Drawn rather than composed. It used to be a composable of its own that read the clock and the
 * grid's horizontal scroll where it was composed, so every frame of a sideways scroll recomposed
 * it; here both are read where the line is drawn, and a tick or a scroll only redraws (ADR-051).
 *
 * The position is worked out from the start edge, as the rows are laid out, and mirrored in a
 * right-to-left layout, where the channel column and the first hour are on the right.
 */
private fun Modifier.nowLine(
    window: GuideWindow,
    now: () -> Long,
    scrollState: ScrollState,
    columnWidth: Dp,
    hourWidth: Dp,
    colour: Color,
): Modifier = drawWithContent {
    drawContent()
    val time = now()
    val start = window.start
    if (time < start || time > window.end) return@drawWithContent
    val column = columnWidth.toPx()
    // `ScrollState.value` is in pixels, as everything else here now is.
    val x = column + hourWidth.toPx() * ((time - start) / HOUR_MILLIS.toFloat()) - scrollState.value
    // Scrolled far enough forward and "now" sits behind the channel column, where a line would
    // read as a border on the names rather than a time.
    if (x < column) return@drawWithContent
    val width = NOW_LINE_WIDTH.toPx()
    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - x - width else x
    drawRect(color = colour, topLeft = Offset(left, 0f), size = Size(width, size.height))
}

/** How often the current-time line moves. A guide hour is wide; a minute is plenty. */
private const val NOW_MARKER_TICK_MS = 60_000L

/** The current-time line's width. */
private val NOW_LINE_WIDTH = 1.5.dp

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
        WindowButtons(onJumpNow = onJumpNow, onShift = onShift)
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
 * Two hours back, now, two hours on: the window's moves inside a day.
 *
 * @param variant ghosts on the phone (MIGRATION.md §2): moving the window is not this screen's
 *   main action. The television's day row sits on the picture and asks for plates.
 */
@Composable
private fun WindowButtons(
    onJumpNow: () -> Unit,
    onShift: (Int) -> Unit,
    variant: StudioButtonVariant = StudioButtonVariant.GHOST,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StudioButton(
            label = stringResource(R.string.guide_shift_back_hours),
            onClick = { onShift(-2) },
            variant = variant,
            size = StudioButtonSize.MEDIUM,
        )
        StudioButton(
            label = stringResource(R.string.guide_jump_now),
            onClick = onJumpNow,
            variant = variant,
            size = StudioButtonSize.MEDIUM,
        )
        StudioButton(
            label = stringResource(R.string.guide_shift_forward_hours),
            onClick = { onShift(2) },
            variant = variant,
            size = StudioButtonSize.MEDIUM,
        )
    }
}

/**
 * The days the guide can jump to, as a row of chips (FR-UI-05).
 *
 * Days are named rather than dated where a name exists — yesterday, today, tomorrow — and by
 * weekday plus date beyond that, which is how anyone actually asks for a day. The whole strip is
 * a `LazyRow` starting one chip before today, so today is on screen without being flush against
 * the edge, and the days behind it are one press away for catch-up (FR-CU-02).
 *
 * @param prefetch the television's quiet prefetch (see [GuideGrid]); null keeps the list's own
 * @param prominent the television's day switcher (ADR-051): chips at the large control height
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideDayStrip(
    window: GuideWindow,
    modifier: Modifier = Modifier,
    prefetch: QuietPrefetchScheduler? = null,
    prominent: Boolean = false,
) {
    // One reading of the clock for the whole strip: taken again per chip, a day boundary crossed
    // mid-composition would put two different "today"s on the same row.
    val reference = remember { System.currentTimeMillis() }
    val selectedDay = TimeModel.startOfLocalDay(window.start)
    val firstShown = (GUIDE_DAYS.indexOf(0) - 1).coerceAtLeast(0)
    val listState = if (prefetch != null) {
        rememberLazyListState(
            initialFirstVisibleItemIndex = firstShown,
            prefetchStrategy = remember(prefetch) { AheadPrefetch(prefetch, ahead = DAYS_AHEAD) },
        )
    } else {
        rememberLazyListState(initialFirstVisibleItemIndex = firstShown)
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        // On the television the chips line up with the grid's edge under them, with room only
        // for a focused chip's growth; the strip clips at its own edges.
        contentPadding = if (prominent) {
            PaddingValues(horizontal = 4.dp, vertical = 6.dp)
        } else {
            PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        },
        horizontalArrangement = Arrangement.spacedBy(if (prominent) 10.dp else 8.dp),
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
                prominent = prominent,
                onClick = { window.pick(day) },
            )
        }
    }
}

@Composable
private fun DayChip(
    label: String,
    date: String,
    selected: Boolean,
    /** The television's day switcher: the large control height, and the label at the action step. */
    prominent: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    // Studio's chip (`canvas/Chip.dc.html`): a quiet plate at rest, amber when it is the chosen
    // day (Merhav's "current", as the side menu marks the screen you are on), the lit surface when
    // focus is on it. The plate and the ring are drawn by `focusSurface`; the words' colours are
    // read where they are drawn too, so a press along the strip recomposes each chip once and
    // nothing at all through the crossing (`StudioButton`, ADR-048's 0.20.8).
    val labelColour = animatedColourState(
        when {
            focused -> studio.onCue
            selected -> studio.current
            else -> studio.ink
        },
    )
    val dateColour = animatedColourState(
        when {
            focused -> studio.onCue
            selected -> studio.current
            else -> studio.ink2
        },
    )
    val shape = RoundedCornerShape(LocalTvMetrics.current.chipCorner)
    Row(
        modifier = Modifier
            .then(if (prominent) Modifier.height(DAY_CHIP_HEIGHT) else Modifier)
            .focusLift(focused, shape = shape)
            .focusSurface(focused = focused, shape = shape, selected = selected, fill = FocusFill.FILLED)
            .onFocusChanged { focused = it.isFocused }
            // As everywhere else in this grid: `clickable` is the focus target on its own.
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$label $date" }
            .padding(horizontal = if (prominent) 18.dp else 14.dp, vertical = if (prominent) 0.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (prominent) 8.dp else 6.dp),
    ) {
        BasicText(
            text = label,
            style = type.action.copy(
                fontWeight = if (prominent && (selected || focused)) FontWeight.Bold else type.action.fontWeight,
            ),
            color = { labelColour.value },
            maxLines = 1,
        )
        BasicText(
            // `dd/MM` beside Hebrew reorders unless it is isolated (FR-UI-21).
            text = TimeModel.ltr(date),
            style = if (prominent) type.meta else type.caption,
            color = { dateColour.value },
            maxLines = 1,
        )
    }
}

/**
 * The hour ruler. It shares [scrollState] with every row, so horizontal scrolling stays in
 * lock-step without a custom layout or a nested scroll contract.
 */
@Composable
private fun TimeAxis(windowStart: Long, scrollState: ScrollState) {
    val metrics = LocalTvMetrics.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(metrics.guideChannelColumnWidth))
        Row(modifier = Modifier.horizontalScroll(scrollState)) {
            repeat(WINDOW_HOURS) { hour ->
                val at = windowStart + hour * HOUR_MILLIS
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
    /**
     * Whether [programmes] have been fetched for this channel and this window. Until they have,
     * the lane waits as an empty plate rather than saying there is no guide data.
     */
    fetched: Boolean,
    /** What is scheduled or recorded on this channel in the visible window (FR-UI-10). */
    recordings: List<Recording>,
    windowStart: Long,
    windowEnd: Long,
    scrollState: ScrollState,
    /** Whether this is the channel on screen, which the tally marks. */
    onScreen: Boolean,
    gate: RowGate,
    /** The row's place in the list, which is its place in the gate's queue: top first. */
    order: Int,
    look: GuideCellLook,
    onChannelClick: () -> Unit,
    onProgrammeClick: (Programme) -> Unit,
    onProgrammeFocused: (Programme) -> Unit,
    /** Attached to whichever of this row's cells is on air now; see `GuideGrid`'s `landOn`. */
    onAirFocus: FocusRequester? = null,
    /** Told once this row's programmes are drawn; the grid's landing waits for it. */
    onShown: (() -> Unit)? = null,
) {
    val metrics = LocalTvMetrics.current
    val studio = StudioTheme.colors
    // Which window this row has drawn its programmes for. A row composed while the gate is staging
    // — the guide opening, or its window moving — waits its turn (`RowGate`); one composed after,
    // scrolled to or built ahead while the screen was still, draws them as soon as they are here.
    var shownFor by remember { mutableLongStateOf(if (gate.staging) NOT_SHOWN else windowStart) }
    val shown = fetched && (shownFor == windowStart || !gate.staged)
    if (fetched && !shown) {
        LaunchedEffect(windowStart) { gate.await(order) { shownFor = windowStart } }
    }
    if (shown && onShown != null) {
        LaunchedEffect(Unit) { onShown() }
    }
    Row(modifier = Modifier.fillMaxWidth().height(metrics.guideRowHeight)) {
        // Studio's channel cell: a `surface` card with the number, the name, and the tally when
        // this is the channel on screen.
        Row(
            modifier = Modifier
                .width(metrics.guideChannelColumnWidth)
                .fillMaxHeight()
                .padding(2.dp)
                .clip(look.shape)
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

        // What falls inside the hours on screen. A channel whose programmes are all outside them
        // has as little to show here as one with none at all, and says so the same way.
        val visible = if (shown) programmes.filter { it.endUtc > windowStart && it.startUtc < windowEnd } else emptyList()
        Box(modifier = Modifier.horizontalScroll(scrollState)) {
            if (!shown) {
                // Waiting for its programmes, or for its turn to draw them: an empty plate of
                // glass the width of the window, one shape and no text — nothing to compose.
                Box(
                    modifier = Modifier
                        .width(metrics.guideHourWidth * WINDOW_HOURS)
                        .height(metrics.guideRowHeight)
                        .padding(2.dp)
                        .background(studio.card, look.shape),
                )
            } else if (visible.isEmpty()) {
                // FR-EPG-16: say so, rather than leaving an unexplained empty lane. Studio's
                // diagonal stripes (`TvGuideNoData`, ADR-034), so the row reads as "nothing known"
                // rather than as a programme that has ended, and keeps its place in the order.
                val shape = look.shape
                Box(
                    modifier = Modifier
                        .width(metrics.guideHourWidth * WINDOW_HOURS)
                        .height(metrics.guideRowHeight)
                        .padding(2.dp)
                        .clip(shape)
                        .noDataStripes(
                            stripe = studio.past,
                            ground = studio.surface,
                            band = if (StudioTheme.television) 7.dp else 10.dp,
                        )
                        .border(1.dp, studio.hairlineSoft, shape),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(R.string.no_guide_data),
                        style = StudioTheme.type.meta,
                        color = studio.ink3,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            } else {
                Row {
                    var cursor = windowStart
                    visible
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
                                    look = look,
                                    onClick = { onProgrammeClick(programme) },
                                    onFocused = { onProgrammeFocused(programme) },
                                    focusRequester = onAirFocus?.takeIf {
                                        System.currentTimeMillis() in programme.startUtc until programme.endUtc
                                    },
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

/**
 * The canvas's `repeating-linear-gradient(-45deg, past 0 band, surface band 2·band)`: [ground]
 * under diagonal bands of [stripe], each [band] wide, rising towards the end of the row.
 */
private fun Modifier.noDataStripes(stripe: Color, ground: Color, band: Dp): Modifier = drawBehind {
    drawRect(ground)
    val width = band.toPx()
    // At 45° a band [width] across is `width·√2` along the row, and the pattern repeats at twice that.
    val period = width * SQRT_2 * 2
    var x = -size.height
    while (x < size.width + size.height) {
        drawLine(
            color = stripe,
            start = Offset(x, size.height),
            end = Offset(x + size.height, 0f),
            strokeWidth = width,
        )
        x += period
    }
}

private const val SQRT_2 = 1.4142135f

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
    look: GuideCellLook,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // The focus treatment — the lift with its shadow, the plate and the ring: three animations,
    // each with a coroutine of its own, and two more layers — is built the first time the remote
    // comes to this cell, not with every cell in the grid. At rest it draws nothing at all, so a
    // cell the remote has never been on looks the same without it, and a row of them is that much
    // quicker to build (ADR-051, 0.21.1). Built, it stays.
    var engaged by remember { mutableStateOf(false) }
    // A frame at rest first, so the first focus crosses in like every later one instead of
    // cutting to it: the animations start from where they are created.
    var settled by remember { mutableStateOf(false) }
    if (engaged && !settled) {
        LaunchedEffect(Unit) { withFrameNanos<Unit> { settled = true } }
    }
    val lit = focused && settled
    val now = System.currentTimeMillis()
    val live = now in programme.startUtc until programme.endUtc
    // The cell's own body colour, which is about the programme rather than about focus: a block
    // in this grid has to be visible at rest, and the one airing now is what the grid is read to
    // find. The focus treatment layers over it (`ui/common/Focus.kt`) instead of replacing it,
    // which is what lets a focused cell still say whether it is the live one.
    // Studio's three cell tones (`canvas/GuideCell.dc.html`): aired, airing now, still to come.
    val studio = StudioTheme.colors
    val past = programme.endUtc <= now
    val body = when {
        live -> studio.airing
        past -> studio.past
        else -> studio.future
    }
    val metrics = LocalTvMetrics.current
    // A cell is a card on a television, and is cut like one; on a phone its corner is tighter.
    val cellShape = look.shape
    Column(
        modifier = Modifier
            // The focused cell lifts slightly out of the grid, as the benchmark's does: a border
            // alone is hard to find on a wall of cells from across a room. It grew instantly
            // until `focusLift` existed, which on a grid this dense read as the cells flickering
            // rather than as one of them rising (`ui/common/Motion.kt`).
            // Studio does not grow a cell — on a grid this dense it would cover its neighbours —
            // but it does lift it: the shadow and the cue ring are the answer.
            .then(if (engaged) Modifier.focusLift(lit, scale = 1f, shape = cellShape) else Modifier)
            .width(millisToDp(widthMillis, metrics.guideHourWidth))
            .height(metrics.guideRowHeight)
            .padding(2.dp)
            .clip(cellShape)
            .background(body)
            // `GuideCell`: a cell still to come or on air has the glass's lit edge; one that has
            // aired has none, and recedes.
            .then(if (!past) Modifier.litEdge(cellShape) else Modifier)
            // The app's one focus treatment. This cell used to ring itself at a hard-coded 2dp,
            // so on a television every row in the channel list was ringed at 3dp and every cell
            // in the guide beside it at 2dp. Its colours are drawn, not composed (ADR-048, 0.20.9).
            .then(if (engaged) Modifier.focusSurface(focused = lit, shape = cellShape) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    engaged = true
                    onFocused()
                }
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // As in the channel cell above: `clickable` carries the focus target, and pairing it
            // with `focusable()` made every cell cost two D-pad presses to pass.
            .clickable(onClick = onClick)
            // Less air on a television, where the row is cut to fit eight under the band over the
            // picture (`guideGridRowHeightFor`); the phone's row keeps its height, and its padding.
            .padding(horizontal = 8.dp, vertical = look.verticalPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Bare text with the grid's own styles (`GuideCellLook`), and a row around it only where
        // there is something beside it: two lines of text are what most cells are.
        val title = if (past) look.titlePast else look.title
        if (live && onScreen) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(5.dp).background(studio.tally, CircleShape))
                BasicText(text = programme.title, style = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            BasicText(text = programme.title, style = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val time = TimeModel.formatClock(programme.startUtc)
        val timeStyle = if (past) look.timePast else look.time
        if (!archived && recordingState == null) {
            BasicText(text = time, style = timeStyle, maxLines = 1)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(text = time, style = timeStyle, maxLines = 1)
                // FR-UI-10: archive availability is badged in the grid itself.
                if (archived) {
                    BasicText(text = look.catchup, style = look.time.copy(color = studio.next), maxLines = 1)
                }
                // The other half of FR-UI-10, which was never drawn: a programme you had
                // scheduled looked exactly like one you had not.
                recordingState?.let { state ->
                    BasicText(
                        text = stringResource(state.label()),
                        style = look.time.copy(
                            color = when (state) {
                                RecordingState.RECORDING -> studio.tally
                                else -> studio.ink2
                            },
                        ),
                        maxLines = 1,
                    )
                }
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

/**
 * What every programme cell in the grid shares: its shape, the four styles of its two lines (the
 * title and the time, aired and not, colour included) and the catch-up badge's word.
 *
 * Worked out once for the grid. Each cell used to copy the body step twice, cut its own shape
 * and look its badge up again, a few hundred times on every opening.
 */
@Immutable
private class GuideCellLook(
    val shape: Shape,
    val title: TextStyle,
    val titlePast: TextStyle,
    val time: TextStyle,
    val timePast: TextStyle,
    val catchup: String,
    /** Less air on a television, where the row is cut to fit eight under the band. */
    val verticalPadding: Dp,
)

@Composable
private fun rememberCellLook(corner: Dp): GuideCellLook {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val television = StudioTheme.television
    val catchup = stringResource(R.string.catchup_available)
    return remember(corner, studio, type, television, catchup) {
        // The line height follows the size. Copied from the body step with the size alone
        // changed, both lines kept the body's line height, and the two of them stood taller than
        // a television row.
        val body = type.body
        val title = body.copy(
            fontSize = body.fontSize * CELL_TITLE_SCALE,
            lineHeight = body.lineHeight * CELL_TITLE_SCALE,
            fontWeight = FontWeight.SemiBold,
        )
        val time = body.copy(
            fontSize = body.fontSize * CELL_TIME_SCALE,
            lineHeight = body.lineHeight * CELL_TIME_SCALE,
        )
        GuideCellLook(
            shape = RoundedCornerShape(corner),
            title = title.copy(color = studio.ink),
            titlePast = title.copy(color = studio.ink2),
            time = time.copy(color = studio.ink2),
            timePast = time.copy(color = studio.ink3),
            catchup = catchup,
            verticalPadding = if (television) 3.dp else 6.dp,
        )
    }
}

/**
 * Lets the rows' programmes in one row a frame, top first, as the guide opens and each time its
 * window moves (ADR-051, 0.21.1).
 *
 * A row of programmes took 18–40ms to compose on a Google TV Streamer, and the guide built every
 * row on screen in one frame: once as it opened, before the guide data had arrived, as "no guide
 * data" stripes, and again in the frame it did, with their cells — about 800ms each (device trace,
 * 0.21.0). Now the guide opens with its band, its ruler and its channel column and an empty plate
 * of glass in every lane, and each row's programmes are composed in a frame of their own once
 * they have arrived.
 *
 * Only a row composed while the gate is *staging* waits: from the guide's opening and from each
 * new window, until every row that was waiting has had its turn. A row composed after that —
 * scrolled to, or built ahead while the screen is still (`AheadPrefetch`) — draws its programmes
 * at once, which is what building it ahead is for. A row waits only once its programmes are
 * here, so nothing asks for frames while a fetch is out.
 */
private class RowGate(
    /** Whether rows wait at all; false draws every row's programmes as soon as they are here. */
    val staged: Boolean,
) {
    /** Whether a row composed now waits its turn before it draws its programmes. */
    var staging = staged
        private set

    private var window = Long.MIN_VALUE
    private val waiting = ArrayList<Waiter>()
    private var frame = Long.MIN_VALUE
    private var given = 0

    private class Waiter(val order: Int)

    /** Called as the grid composes: a new window lets every row's programmes in again, in turn. */
    fun stageFor(windowStart: Long) {
        if (windowStart == window) return
        window = windowStart
        staging = staged
    }

    /**
     * Waits for this row's turn: [ROWS_PER_FRAME] a frame, lowest [order] first, which is the top
     * of the list. [onTurn] runs inside the frame, so what it changes is composed in that frame.
     */
    suspend fun await(order: Int, onTurn: () -> Unit) {
        val me = Waiter(order)
        waiting.add(me)
        var through = false
        try {
            while (!through) {
                withFrameNanos<Unit> { frameTime ->
                    if (frameTime != frame) {
                        frame = frameTime
                        given = 0
                    }
                    if (given < ROWS_PER_FRAME && waiting.minByOrNull { it.order } === me) {
                        given++
                        waiting.remove(me)
                        through = true
                        onTurn()
                    }
                }
            }
        } finally {
            // A row let go before its turn — scrolled away, or its window moved on — leaves the
            // queue without ending the staging; the last one through ends it.
            if (!through) waiting.remove(me)
            if (through && waiting.isEmpty()) staging = false
        }
    }
}

/** Rows let through [RowGate] in one frame. */
private const val ROWS_PER_FRAME = 1

/** A row that has drawn its programmes for no window yet. */
private const val NOT_SHOWN = Long.MIN_VALUE

/** The channels a fetch brought programmes back for, and the window it was for. */
private data class Coverage(val start: Long, val ids: Set<Long>)

/** The television's day chip: `--st-h-control-l`, 80px at 1080p, the height of a large button. */
private val DAY_CHIP_HEIGHT = 40.dp

private fun millisToDp(millis: Long, hourWidth: Dp) = hourWidth * (millis.toFloat() / HOUR_MILLIS.toFloat())

private fun snapToHalfHour(utcMillis: Long): Long = (utcMillis / 1_800_000L) * 1_800_000L

/** Where a window opened "now" starts: the half hour before the half hour before [now]. */
private fun nowWindowStart(now: Long): Long = snapToHalfHour(now - 30 * 60_000L)

private const val HOUR_MILLIS = 60 * 60_000L

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

/** Prefetch margin either side of what is on screen, for the guide data (not for composing). */
private const val PREFETCH_ROWS = 6

/**
 * How many rows the television keeps built past each edge of the list ([AheadPrefetch]). Two, as
 * home keeps two rails: the next press either way finds its row built, and a held key outruns any
 * number. Fewer than [PREFETCH_ROWS], so a row built ahead is built with its programmes.
 */
private const val ROWS_AHEAD = 2

/** How many day chips the television keeps built past each edge of the strip. */
private const val DAYS_AHEAD = 2
