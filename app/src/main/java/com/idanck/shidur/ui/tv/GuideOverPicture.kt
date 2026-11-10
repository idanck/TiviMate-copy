package com.idanck.shidur.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.idanck.shidur.R
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.Programme
import com.idanck.shidur.ui.AppViewModel
import com.idanck.shidur.ui.common.Equalizer
import com.idanck.shidur.ui.common.GlassMaterial
import com.idanck.shidur.ui.common.LocalTvMetrics
import com.idanck.shidur.ui.common.glass
import com.idanck.shidur.ui.common.guideGridRowHeightFor
import com.idanck.shidur.ui.common.rememberQuietPrefetchScheduler
import com.idanck.shidur.ui.common.tvMetricsFor
import com.idanck.shidur.ui.screens.GuideDayRow
import com.idanck.shidur.ui.screens.GuideGrid
import com.idanck.shidur.ui.screens.rememberGuideWindow
import com.idanck.shidur.ui.theme.StudioTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The television's guide (ADR-051, made opaque by ADR-063).
 *
 * The channel being watched plays on behind it, as it does behind home — nothing is docked, moved
 * or resized, so its sound goes on — but the guide no longer shows its picture: the whole panel is
 * the dark ground. A grid over a moving picture was hard to read, and the band at the top, where
 * the picture showed through dimmed by 30%, was what the owner asked to close (10/11/2026).
 *
 * With the picture gone, two things say which channel is still heard:
 *
 * - **A pill in the top bar** ([HeardPill]): an equaliser in amber, "still playing", the channel
 *   and its programme. It stays in view however far the grid is scrolled.
 * - **The channel's row in the grid**, its cell lit amber with the equaliser in place of the
 *   tally (`GuideRow`).
 *
 * Under the top bar come the day switcher and a one-line strip describing the programme the remote
 * is on, then the grid. Before ADR-051 the picture was shrunk into a 288×162dp slot beside a panel
 * describing the focused programme (ADR-013), which left the grid fewer than five rows; that room
 * is still the grid's: eight rows at 1080p and a day switcher at the large control height.
 *
 * The viewer's overlay-opacity setting (FR-UI-18) no longer reaches the guide: there is nothing
 * behind it to let through.
 *
 * The content keeps the room the scaffold's inset used to give it — clear of the side menu's
 * strip at the start, [TV_CONTENT_INSET] at the end and the foot — and is measured inside it, so
 * an hour of guide is as wide as it was.
 */
@Composable
fun GuideOverPicture(
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    onOpenProgramme: (Channel, Programme) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The channel whose programme on air the guide opens on, focused: the playing one when the
     * guide comes from the video (`canvas/GuideFromVideo.dc.html`, ADR-036), or the one the
     * channel list's preview asked for. Null opens the guide as it always has.
     */
    landOn: Long? = null,
) {
    val ground = StudioTheme.colors.ground
    val startRoom = sideMenuInset()
    // One window for the day switcher and the grid under it.
    val window = rememberGuideWindow()
    // Builds the grid's next rows and the strip's next days while the screen is still. One for
    // the screen, so one quiet moment never does two lists' work (`QuietPrefetch.kt`).
    val prefetch = rememberQuietPrefetchScheduler()
    // The programme the remote is on, which the strip describes. Read by the strip alone, so a
    // press in the grid recomposes the cells it moved between and one line of text, not this.
    val focused = remember { mutableStateOf<Pair<Channel, Programme>?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Opaque: the picture behind is not shown, only heard (ADR-063).
            .background(ground.copy(alpha = 1f)),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            StudioTopBar(startInset = startRoom, showClock = true, scrim = false)
            HeardPill(vm = vm, modifier = Modifier.align(Alignment.Center))
        }
        GuideDayRow(
            window = window,
            prefetch = prefetch,
            modifier = Modifier
                .fillMaxWidth()
                .height(DAY_ROW_HEIGHT)
                .padding(start = startRoom, end = TV_CONTENT_INSET),
        )
        GuideFocusStrip(
            vm = vm,
            focused = focused,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startRoom, end = TV_CONTENT_INSET, bottom = STRIP_GAP)
                .height(STRIP_HEIGHT),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = startRoom, end = TV_CONTENT_INSET, bottom = TV_CONTENT_INSET),
        ) {
            // Measured here, inside the room the grid keeps, as the scaffold measured its inset box:
            // the hours are as wide as they were. The rows are cut from the height the grid has.
            val grid = tvMetricsFor(maxWidth.value, maxHeight.value)
                .copy(guideRowHeight = guideGridRowHeightFor(maxHeight.value))
            CompositionLocalProvider(LocalTvMetrics provides grid) {
                Column(modifier = Modifier.fillMaxSize()) {
                    HorizontalDivider()
                    GuideGrid(
                        vm = vm,
                        window = window,
                        onPlay = onPlay,
                        onOpenProgramme = onOpenProgramme,
                        onFocusProgramme = { channel, programme -> focused.value = channel to programme },
                        landOn = landOn,
                        prefetch = prefetch,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * What is still heard under the opaque guide (ADR-063): an amber equaliser that moves with the
 * sound, "still playing", the channel and the programme on air, on a plate with an amber edge.
 * Nothing when nothing is playing. Not focusable: it tells, it does not do.
 */
@Composable
private fun HeardPill(vm: AppViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val channel = state.currentChannel ?: return
    val sounding by remember(vm) {
        vm.playbackStatus.map { it.playWhenReady }.distinctUntilChanged()
    }.collectAsState(initial = true)
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val shape = RoundedCornerShape(50)
    val title = state.now?.title?.takeIf { it.isNotBlank() }
    Row(
        modifier = modifier
            .height(HEARD_PILL_HEIGHT)
            .clip(shape)
            .background(studio.drawer)
            .border(1.dp, studio.currentEdge, shape)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Equalizer(
            playing = sounding,
            description = stringResource(R.string.now_playing),
            colour = studio.current,
            modifier = Modifier.size(width = 22.dp, height = 18.dp),
        )
        Text(
            text = stringResource(R.string.guide_still_playing),
            style = type.meta,
            color = studio.ink2,
            maxLines = 1,
        )
        Text(
            text = if (title != null) "${isolate(channel.displayName)}  ·  ${isolate(title)}" else channel.displayName,
            style = type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = studio.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = HEARD_PILL_TEXT_MAX),
        )
    }
}

/**
 * The focused programme, on one line over the picture (ADR-051): its title, its time and where it
 * stands now, its channel, and as much of its synopsis as is left, ellipsised. Until the grid
 * reports a programme it describes what is on screen.
 *
 * One text rather than a row of them, so the line gives way at its end — the synopsis first — and
 * a recomposition is one layout. Its direction is the interface's whatever the title's language,
 * and every part is isolated, so a Latin title in a Hebrew guide does not turn the line around.
 */
@Composable
private fun GuideFocusStrip(
    vm: AppViewModel,
    focused: State<Pair<Channel, Programme>?>,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val pick = focused.value
    val channel = pick?.first ?: state.currentChannel
    val programme = if (pick != null) pick.second else state.now
    if (channel == null) {
        Spacer(modifier = modifier)
        return
    }
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val now = System.currentTimeMillis()
    val status = programme?.let {
        when {
            now in it.startUtc until it.endUtc ->
                stringResource(R.string.guide_status_airing, ((it.endUtc - now) / 60_000L).toInt())
            it.endUtc <= now -> stringResource(R.string.guide_status_aired)
            it.startUtc - now < 3 * 60 * 60_000L ->
                stringResource(R.string.guide_status_upcoming, ((it.startUtc - now) / 60_000L).toInt())
            else -> stringResource(R.string.guide_status_upcoming_later)
        }
    }
    val title = programme?.title ?: stringResource(R.string.no_guide_data)
    val channelLabel = if (channel.id == state.currentChannel?.id) {
        stringResource(R.string.guide_on_screen, channel.displayName)
    } else {
        channel.displayName
    }
    val synopsis = programme?.description
        ?.takeIf { it.isNotBlank() }
        // One line: a synopsis's own breaks would end it early, and only its start can show.
        ?.take(SYNOPSIS_MAX_CHARS)
        ?.replace('\n', ' ')
    val quiet = SpanStyle(fontSize = type.meta.fontSize, fontWeight = FontWeight.Normal, color = studio.ink2)
    val line = buildAnnotatedString {
        append(isolate(title))
        if (programme != null && status != null) {
            withStyle(quiet) {
                append(GAP)
                append(TimeModel.formatClockRange(programme.startUtc, programme.endUtc))
                append(SEPARATOR)
            }
            withStyle(quiet.copy(color = studio.ink, fontWeight = FontWeight.Medium)) { append(isolate(status)) }
        }
        withStyle(quiet) { append(SEPARATOR) }
        withStyle(quiet.copy(fontWeight = FontWeight.SemiBold)) { append(isolate(channelLabel)) }
        if (synopsis != null) {
            withStyle(quiet) { append(SEPARATOR) }
            withStyle(quiet.copy(color = studio.ink3)) { append(isolate(synopsis)) }
        }
    }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr
    Box(
        modifier = modifier
            .glass(GlassMaterial.THIN, RoundedCornerShape(LocalTvMetrics.current.controlCorner))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = line,
            // The title carries the line, at the action step; the rest is the meta step (`quiet`).
            // The line height is the larger step's, so the smaller spans sit inside it.
            style = type.action.copy(fontWeight = FontWeight.Bold, textDirection = direction),
            color = studio.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** First-strong isolation: [text] keeps its own direction inside the strip's (FR-UI-21). */
private fun isolate(text: String): String = "\u2068$text\u2069"

/** Between the title and its time. */
private const val GAP = "   "

/** Between the strip's other parts. */
private const val SEPARATOR = "  ·  "

/** More synopsis than a line of the strip can show on any panel; the rest is never laid out. */
private const val SYNOPSIS_MAX_CHARS = 240

/**
 * The pill in the top bar: its height, and the widest its channel and programme may set, which
 * keeps it clear of the app's name at the start and the clock at the end at 1080p.
 */
private val HEARD_PILL_HEIGHT = 40.dp
private val HEARD_PILL_TEXT_MAX = 280.dp

/*
 * The rows under the top bar (64dp, `StudioTopBar`), at 1080p: the day switcher's row — its 40dp
 * chips with room for a focused one's growth — then the strip and the gap under it. 156dp in all,
 * fixed, so the grid under it is measured in the same pass and the rows never move.
 */
private val DAY_ROW_HEIGHT = 52.dp
private val STRIP_HEIGHT = 32.dp
private val STRIP_GAP = 8.dp
