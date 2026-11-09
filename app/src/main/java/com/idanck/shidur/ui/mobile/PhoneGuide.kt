package com.idanck.shidur.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.idanck.shidur.R
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.catchup.CatchupUrlBuilder
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.Programme
import com.idanck.shidur.ui.AppViewModel
import com.idanck.shidur.ui.common.PhoneButton
import com.idanck.shidur.ui.common.PhoneButtonSize
import com.idanck.shidur.ui.common.PhoneButtonVariant
import com.idanck.shidur.ui.screens.GuideScreen
import com.idanck.shidur.ui.theme.StudioTheme
import com.idanck.shidur.ui.common.StudioIcons

/**
 * The phone's guide (`docs/design/studio/canvas/PGuide.dc.html`): the shared grid, and under it a
 * sheet for the programme that was tapped — channel, time and category, the title at the display weight,
 * where it stands, and what can be done with it: watch it or start it over while it airs, watch
 * the replay once it has aired, record it while it is still to come, and its full details. Back
 * closes the sheet before it leaves the guide.
 */
@Composable
internal fun PhoneGuide(
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    onCatchup: (Channel, Programme) -> Unit,
    onOpenProgramme: (Channel, Programme) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pick by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }
    BackHandler(enabled = pick != null) { pick = null }
    Column(modifier = modifier) {
        GuideScreen(
            vm = vm,
            onPlay = onPlay,
            onOpenProgramme = { channel, programme -> pick = channel to programme },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        pick?.let { (channel, programme) ->
            ProgrammeSheet(
                channel = channel,
                programme = programme,
                onWatch = { onPlay(channel) },
                onCatchup = { onCatchup(channel, programme) },
                onDetails = { onOpenProgramme(channel, programme) },
            )
        }
    }
}

@Composable
private fun ProgrammeSheet(
    channel: Channel,
    programme: Programme,
    onWatch: () -> Unit,
    onCatchup: () -> Unit,
    onDetails: () -> Unit,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER)
    val now = System.currentTimeMillis()
    val airing = now in programme.startUtc until programme.endUtc
    val aired = programme.endUtc <= now
    val replay = channel.hasCatchup && CatchupUrlBuilder.isWithinWindow(channel, programme.startUtc, now)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(studio.surface)
            .border(1.dp, studio.hairline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = listOfNotNull(
                channel.displayName,
                TimeModel.formatClockRange(programme.startUtc, programme.endUtc),
                programme.category?.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            style = type.caption,
            color = studio.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = programme.title,
            style = type.titleSmall,
            color = studio.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = when {
                airing -> stringResource(R.string.guide_status_airing, ((programme.endUtc - now) / 60_000L).toInt())
                aired -> stringResource(R.string.guide_status_aired)
                programme.startUtc - now < 3 * 60 * 60_000L ->
                    stringResource(R.string.guide_status_upcoming, ((programme.startUtc - now) / 60_000L).toInt())
                else -> stringResource(R.string.guide_status_upcoming_later)
            },
            style = type.meta.copy(fontWeight = FontWeight.Medium),
            color = studio.ink,
        )
        Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (airing) {
                PhoneButton(
                    label = stringResource(R.string.action_watch),
                    icon = StudioIcons.Play,
                    variant = PhoneButtonVariant.PRIMARY,
                    size = PhoneButtonSize.M,
                    onClick = onWatch,
                )
            }
            if (replay && (airing || aired)) {
                PhoneButton(
                    label = stringResource(if (airing) R.string.guide_from_start else R.string.guide_watch_catchup),
                    icon = StudioIcons.Restart,
                    size = PhoneButtonSize.M,
                    onClick = onCatchup,
                )
            }
            // Recording and reminders live in the programme's details, which know the series
            // rules and the padding; the sheet opens them rather than repeating them.
            if (!airing && !aired) {
                PhoneButton(
                    label = stringResource(R.string.action_record),
                    icon = StudioIcons.Record,
                    size = PhoneButtonSize.M,
                    onClick = onDetails,
                )
            }
            PhoneButton(
                label = stringResource(R.string.guide_details),
                icon = StudioIcons.Info,
                iconOnly = true,
                variant = PhoneButtonVariant.GHOST,
                size = PhoneButtonSize.M,
                onClick = onDetails,
            )
        }
    }
}

/** `--st-r-sheet`. */
private val SHEET_CORNER = 24.dp
