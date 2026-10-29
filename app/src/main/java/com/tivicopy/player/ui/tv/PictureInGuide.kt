package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.catchup.CatchupUrlBuilder
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.LiveBadge
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.dockedGuideRowHeightFor
import com.tivicopy.player.ui.common.StudioButton
import com.tivicopy.player.ui.common.StudioButtonSize
import com.tivicopy.player.ui.screens.GuideScreen
import com.tivicopy.player.ui.theme.StudioTheme
import com.tivicopy.player.ui.common.StudioIcons

/**
 * The guide, laid out around the picture instead of over it.
 *
 * The ten-foot guide used to be a full-screen sheet drawn at 85% opacity across the video: the
 * stream kept playing underneath, which satisfied the requirement on paper, but nothing of it was
 * visible or watchable. Opening the guide therefore cost you the programme you were watching, and
 * the way back to it was a Back press — on a remote, where browsing the schedule while something
 * plays is the whole point of a guide.
 *
 * Here the video is not covered at all. It is moved: [TvRoot] shrinks the one player surface into
 * the slot this layout reserves for it, so the picture keeps running, in view, while the grid owns
 * the rest of the screen. Nothing re-tunes, because nothing is torn down — the same surface simply
 * measures smaller.
 *
 * The panes are painted individually and the preview slot is left unpainted. A single full-screen
 * scrim would have been simpler and would have tinted the live picture with it.
 *
 * Two panes, not the three ADR-013 first described. The channel rail that used to stand beside
 * the grid listed the same channels as the grid's own channel column, a hand's width apart, and
 * tuned the same way the column does on OK; it cost the programmes a fifth of the panel's width.
 * The Studio canvas (`Guide.dc.html`) draws the guide without it, and so does this. The picture is
 * the canvas's size, 288×162dp, where it used to be 400×225 — on a 1080p panel, with the top bar
 * and the strip above the rows, the larger picture left room for fewer than two of them.
 */
@Composable
fun PictureInGuide(
    vm: AppViewModel,
    /** Reports where the live preview should be drawn, in root coordinates. */
    onDockBounds: (Rect) -> Unit,
    onPlay: (Channel) -> Unit,
    onOpenProgramme: (Channel, Programme) -> Unit,
    opacity: Float,
    modifier: Modifier = Modifier,
    /**
     * The channel whose programme on air the guide opens on, focused: the playing one when the
     * guide comes from the video (`canvas/GuideFromVideo.dc.html`, ADR-036), or the one the
     * channel list's preview asked for. Null opens the guide as it always has.
     */
    landOn: Long? = null,
) {
    val state by vm.state.collectAsState()
    val metrics = LocalTvMetrics.current
    val pane = MaterialTheme.colorScheme.background.copy(alpha = opacity)
    // The programme the remote is on in the grid, which the panel beside the picture describes.
    // Until the grid reports one, the panel describes what is on screen.
    var pick by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // Studio's top bar (`canvas/Guide.dc.html`): the app's name and the clock, which the
        // guide's own toolbar therefore no longer draws.
        StudioTopBar(
            startInset = 20.dp,
            showClock = true,
            scrim = false,
            endInset = 20.dp,
            modifier = Modifier.background(pane),
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT + PREVIEW_MARGIN * 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The focused programme first, at the start edge, as the canvas sets it; the
            // picture at the end.
            val panelChannel = pick?.first ?: state.currentChannel
            GuideFocusPanel(
                channel = panelChannel,
                programme = if (pick != null) pick?.second else state.now,
                onWatch = { channel -> onPlay(channel) },
                onCatchup = { channel, programme -> vm.playCatchup(channel, programme) },
                onOpenProgramme = onOpenProgramme,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(pane)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
            // Deliberately unpainted: this box exists to be measured, and the live picture is
            // drawn into the rectangle it reports. What is drawn in it lies over the picture:
            // the tally along its top and whose picture it is.
            Box(
                modifier = Modifier
                    .width(PREVIEW_WIDTH + PREVIEW_MARGIN * 2)
                    .fillMaxHeight()
                    .padding(PREVIEW_MARGIN)
                    .onGloballyPositioned { coordinates ->
                        val origin = coordinates.positionInRoot()
                        onDockBounds(
                            Rect(
                                left = origin.x,
                                top = origin.y,
                                right = origin.x + coordinates.size.width,
                                bottom = origin.y + coordinates.size.height,
                            ),
                        )
                    },
            ) {
                val onAir = state.currentChannel
                if (onAir != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(StudioTheme.colors.tally),
                    )
                    LiveBadge(
                        label = stringResource(R.string.guide_on_screen, onAir.displayName),
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    )
                }
            }
        }

        // The grid's rows are cut from the height left under the picture, not from the panel's:
        // the panel's seventh (`TvMetrics.guideRowHeight`) fits fewer than two rows here on a
        // 1080p television. Measured rather than computed from the constants above, so a panel
        // whose top bar or type scale differs still gets rows that fit what it has.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val docked = metrics.copy(guideRowHeight = dockedGuideRowHeightFor(maxHeight.value))
            CompositionLocalProvider(LocalTvMetrics provides docked) {
                GuideScreen(
                    vm = vm,
                    onPlay = onPlay,
                    onOpenProgramme = onOpenProgramme,
                    modifier = Modifier.fillMaxSize().background(pane),
                    onFocusProgramme = { channel, programme -> pick = channel to programme },
                    showClock = false,
                    landOn = landOn,
                )
            }
        }
    }
}

/**
 * The focused programme, beside the picture (`canvas/Guide.dc.html`): its channel and number, its
 * category, the title at the display weight, the time and where it stands now, the synopsis, and what can
 * be done with it — watch it or start it over while it airs, watch the replay once it has aired,
 * or open its details (record, remind) while it is still to come.
 */
@Composable
private fun GuideFocusPanel(
    channel: Channel?,
    programme: Programme?,
    onWatch: (Channel) -> Unit,
    onCatchup: (Channel, Programme) -> Unit,
    onOpenProgramme: (Channel, Programme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (channel == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = channel.displayName,
                style = type.rowLabel,
                color = studio.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.number > 0) {
                Text(
                    text = stringResource(R.string.channel_number_entry, channel.number.toString()),
                    style = type.meta,
                    color = studio.ink2,
                )
            }
            programme?.category?.takeIf { it.isNotBlank() }?.let { category ->
                Text(
                    text = category,
                    style = type.caption,
                    color = studio.ink2,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(studio.card)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = programme?.title ?: stringResource(R.string.no_guide_data),
            style = type.title,
            color = studio.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (programme == null) return@Column
        val now = System.currentTimeMillis()
        val airing = now in programme.startUtc until programme.endUtc
        val aired = programme.endUtc <= now
        val status = when {
            airing -> stringResource(R.string.guide_status_airing, ((programme.endUtc - now) / 60_000L).toInt())
            aired -> stringResource(R.string.guide_status_aired)
            programme.startUtc - now < 3 * 60 * 60_000L ->
                stringResource(R.string.guide_status_upcoming, ((programme.startUtc - now) / 60_000L).toInt())
            else -> stringResource(R.string.guide_status_upcoming_later)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = TimeModel.formatClockRange(programme.startUtc, programme.endUtc),
                style = type.meta,
                color = studio.ink2,
            )
            Text(text = "·", style = type.meta, color = studio.ink2)
            Text(
                text = status,
                style = type.meta.copy(fontWeight = FontWeight.Medium),
                color = studio.ink,
            )
        }
        programme.description?.takeIf { it.isNotBlank() }?.let { synopsis ->
            // Takes what is left between the time line and the buttons, and no more: the pane is
            // the picture's height, and a synopsis that claimed its two lines regardless pushed
            // the buttons below the pane's edge on a panel where the type runs large.
            Text(
                text = synopsis,
                style = type.caption,
                color = studio.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        val replay = channel.hasCatchup && CatchupUrlBuilder.isWithinWindow(channel, programme.startUtc, now)
        Row(modifier = Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (airing) {
                StudioButton(
                    label = stringResource(R.string.action_watch),
                    icon = StudioIcons.Play,
                    size = StudioButtonSize.MEDIUM,
                    onClick = { onWatch(channel) },
                )
            }
            if (replay && (airing || aired)) {
                StudioButton(
                    label = stringResource(if (airing) R.string.guide_from_start else R.string.guide_watch_catchup),
                    icon = StudioIcons.Restart,
                    size = StudioButtonSize.MEDIUM,
                    onClick = { onCatchup(channel, programme) },
                )
            }
            // Recording and reminders live in the programme's details, which already know the
            // series rules and the padding; the panel opens them rather than repeating them.
            if (!airing && !aired) {
                StudioButton(
                    label = stringResource(R.string.action_record),
                    icon = StudioIcons.Record,
                    size = StudioButtonSize.MEDIUM,
                    onClick = { onOpenProgramme(channel, programme) },
                )
            }
        }
    }
}

/**
 * The preview's size: the Studio canvas's 576×324px at 1080p, where a pixel is half a dp.
 *
 * Fixed rather than derived, unlike everything else the ten-foot layout measures: the slot has to
 * stay a 16:9 rectangle whatever the panel, or the picture inside it letterboxes against its own
 * frame. What scales with the panel is how much guide fits under it. The margin is added outside
 * these, so the picture itself is this size rather than this size less its margin.
 */
private val PREVIEW_WIDTH = 288.dp
private val PREVIEW_HEIGHT = 162.dp

/** Keeps the picture off the panes either side of it. */
private val PREVIEW_MARGIN = 8.dp
