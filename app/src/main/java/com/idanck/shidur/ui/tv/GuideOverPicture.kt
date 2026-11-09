package com.idanck.shidur.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
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

/**
 * The television's guide, drawn over the picture (ADR-051).
 *
 * The channel being watched plays on at full size behind it, as it does behind home: nothing is
 * docked, moved or resized. The guide paints its own two backdrops over it, full-bleed:
 *
 * - **A band at the top**, where the picture shows through, dimmed by [BAND_DIM]: the top bar,
 *   the day switcher and a one-line strip describing the programme the remote is on. Under the
 *   top bar the dim deepens towards the top edge, as the top bar's own scrim does over the picture
 *   on home, so the clock reads over any frame.
 * - **The grid under it, on the dark ground** at the viewer's overlay opacity (FR-UI-18), as the
 *   guide's panes were painted before: legible, and a plain fill for the rows to scroll over.
 *
 * How the two numbers meet: the band's 30% is the owner's and fixed — it is there so the picture
 * can be seen — while the setting goes on governing the grid, which is where it always applied
 * here. The setting cannot go below 30%, so the grid is never lighter than the band above it. The
 * band's chips and strip stand on thin glass of their own, so their words read over a bright frame
 * whatever either number is.
 *
 * Before this the picture was shrunk into a 288×162dp slot beside a panel describing the focused
 * programme (ADR-013), which left the grid fewer than five rows. The slot and the panel are gone; the
 * panel's actions are a press of OK away, in the programme's details. What the room bought is
 * eight rows at 1080p and a day switcher at the large control height.
 *
 * The content keeps the room the scaffold's inset used to give it — clear of the side menu's
 * strip at the start, [TV_CONTENT_INSET] at the end and the foot — and is measured inside it, so
 * an hour of guide is as wide as it was: only the backdrops reach the edges of the panel.
 */
@Composable
fun GuideOverPicture(
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    onOpenProgramme: (Channel, Programme) -> Unit,
    /** The viewer's overlay-opacity setting (FR-UI-18): how dark the grid's ground is. */
    opacity: Float,
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
    // One window for the day switcher in the band and the grid under it.
    val window = rememberGuideWindow()
    // Builds the grid's next rows and the strip's next days while the screen is still. One for
    // the screen, so one quiet moment never does two lists' work (`QuietPrefetch.kt`).
    val prefetch = rememberQuietPrefetchScheduler()
    // The programme the remote is on, which the strip describes. Read by the strip alone, so a
    // press in the grid recomposes the cells it moved between and one line of text, not this.
    val focused = remember { mutableStateOf<Pair<Channel, Programme>?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithCache {
                    val dim = Brush.verticalGradient(
                        0f to ground.copy(alpha = TOP_BAR_SCRIM_ALPHA),
                        1f to ground.copy(alpha = BAND_DIM),
                        startY = 0f,
                        endY = TOP_BAR_SCRIM_HEIGHT.toPx(),
                    )
                    // Past the top bar's scrim the gradient holds its last colour: the band's dim.
                    onDrawBehind { drawRect(dim) }
                },
        ) {
            StudioTopBar(startInset = startRoom, showClock = true, scrim = false)
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
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(ground.copy(alpha = opacity))
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
 * How much the band dims the picture it shows: 30%, the owner's figure. The top bar's own scrim
 * deepens it towards the top edge ([TOP_BAR_SCRIM_ALPHA] over [TOP_BAR_SCRIM_HEIGHT]), as it does
 * over the picture on home.
 */
private const val BAND_DIM = 0.3f

/** `.st-scrim-top` (`StudioTopBar`): 82% at the top edge, gone 110dp down. */
private const val TOP_BAR_SCRIM_ALPHA = 0.82f
private val TOP_BAR_SCRIM_HEIGHT = 110.dp

/*
 * The band under the top bar (64dp, `StudioTopBar`), at 1080p: the day switcher's row — its 40dp
 * chips with room for a focused one's growth — then the strip and the gap under it. 156dp in all,
 * fixed, so the grid under it is measured in the same pass and the band never moves.
 */
private val DAY_ROW_HEIGHT = 52.dp
private val STRIP_HEIGHT = 32.dp
private val STRIP_GAP = 8.dp
