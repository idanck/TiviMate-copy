package com.idanck.shidur.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.idanck.shidur.R
import com.idanck.shidur.core.TimeModel
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.Programme
import com.idanck.shidur.ui.common.ChannelLogo
import com.idanck.shidur.ui.common.GlassMaterial
import com.idanck.shidur.ui.common.LiveDot
import com.idanck.shidur.ui.common.LocalTvMetrics
import com.idanck.shidur.ui.common.glass
import com.idanck.shidur.ui.common.tallyGlow
import com.idanck.shidur.ui.theme.StudioTheme
import com.idanck.shidur.ui.common.StudioIcons

/**
 * The channel row on a phone (`PChannelRow.dc.html`; Merhav, ADR-046): 72dp of thin glass at the
 * card corner. A 16:9 tile with the channel's mark and its number in the corner (a station's mark on
 * a disc), its name, what it is showing with the time, and a thin progress line. The channel on
 * screen takes a wash of the tally, a red edge with a soft glow, a live dot and "on screen" beside
 * its name, and its progress in red.
 *
 * The canvas shows a frame of the channel where the tile is; the app keeps no thumbnails, so the
 * tile carries the channel's logo (or its monogram). The star stays at the end: marking a favourite
 * in one tap is a requirement (FR-ORG-07), and on a phone the long press that opens the full menu
 * is not something people find. A favourite is a filled star in ink — never amber, which is what
 * is active, not what is liked.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhoneChannelRow(
    channel: Channel,
    onScreen: Boolean,
    nowProgramme: Programme?,
    disc: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavourite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val shape = RoundedCornerShape(LocalTvMetrics.current.rowCorner)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .then(if (onScreen) Modifier.tallyGlow(shape) else Modifier)
            .then(
                if (onScreen) {
                    Modifier.clip(shape).background(studio.onScreen).border(1.dp, studio.tallyGlow, shape)
                } else {
                    Modifier.glass(GlassMaterial.THIN, shape)
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = channel.displayName }
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (disc) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .glass(GlassMaterial.THIN, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ChannelLogo(channel = channel, sizeDp = 40, circular = true)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(width = 80.dp, height = 45.dp)
                    .glass(GlassMaterial.THIN, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ChannelLogo(channel = channel, sizeDp = 34)
                if (channel.number > 0) {
                    Text(
                        text = TimeModel.ltr(channel.number.toString()),
                        style = StudioTheme.type.caption.copy(
                            fontSize = StudioTheme.type.caption.fontSize * (12f / 13f),
                            fontWeight = FontWeight.Bold,
                        ),
                        color = studio.ink,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(studio.scrim)
                            .padding(horizontal = 6.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = channel.displayName,
                    style = StudioTheme.type.rowLabel.copy(fontWeight = FontWeight.Bold),
                    color = studio.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (onScreen) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LiveDot(description = null)
                        Text(
                            text = stringResource(R.string.channel_on_screen),
                            style = StudioTheme.type.caption.copy(
                                fontSize = StudioTheme.type.caption.fontSize * (12f / 13f),
                                fontWeight = FontWeight.Bold,
                            ),
                            color = studio.liveInk,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (nowProgramme != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = nowProgramme.title,
                        style = StudioTheme.type.meta,
                        color = studio.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = TimeModel.formatClockRange(nowProgramme.startUtc, nowProgramme.endUtc),
                        style = StudioTheme.type.meta,
                        color = studio.ink3,
                        maxLines = 1,
                    )
                }
                val span = (nowProgramme.endUtc - nowProgramme.startUtc).coerceAtLeast(1)
                val fraction = ((System.currentTimeMillis() - nowProgramme.startUtc).toFloat() / span).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(studio.track),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .background(if (onScreen) studio.tally else studio.ink),
                    )
                }
            }
        }
        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (channel.favourite) StudioIcons.StarOn else StudioIcons.Star,
                contentDescription = stringResource(
                    if (channel.favourite) R.string.action_unfavourite else R.string.action_favourite,
                ),
                tint = if (channel.favourite) studio.ink else studio.ink3,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
