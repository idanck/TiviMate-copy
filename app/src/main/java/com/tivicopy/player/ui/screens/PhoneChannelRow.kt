package com.tivicopy.player.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.theme.StudioTheme

/**
 * Studio's channel row on a phone (`docs/design/studio/canvas/PChannelRow.dc.html`): 72dp, the
 * channel's number on a tile in Karantina (a station's mark on a disc), its name, what it is
 * showing with the time, and a thin progress line. The channel on screen takes the tally — the
 * on-screen fill, a 1.5dp ring and "on screen" beside its name.
 *
 * A channel with no number shows its logo on the tile instead. The star stays at the end, which the
 * canvas does not draw: marking a favourite in one tap is a requirement (FR-ORG-07), and on a
 * phone the long press that opens the full menu is not something people find.
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
            .clip(shape)
            .then(
                if (onScreen) {
                    Modifier.background(studio.onScreen).border(1.5.dp, studio.tally, shape)
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = channel.displayName }
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (disc) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(studio.card)
                    .border(1.dp, studio.hairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ChannelLogo(channel = channel, sizeDp = 40, circular = true)
            }
        } else {
            val tileShape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 44.dp)
                    .clip(tileShape)
                    .background(if (onScreen) studio.tally.copy(alpha = 0.14f) else studio.card)
                    .border(1.dp, studio.hairlineSoft, tileShape),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.number > 0) {
                    val title = StudioTheme.type.titleSmall
                    Text(
                        text = channel.number.toString(),
                        style = title.copy(fontSize = title.fontSize * (34f / 32f), lineHeight = title.fontSize * (34f / 32f)),
                        color = studio.ink,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    ChannelLogo(channel = channel, sizeDp = 36)
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = channel.displayName,
                    style = StudioTheme.type.rowLabel,
                    color = studio.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (onScreen) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(studio.tally))
                        Text(
                            text = stringResource(R.string.channel_on_screen),
                            style = StudioTheme.type.caption.copy(
                                fontSize = StudioTheme.type.caption.fontSize * (12f / 13f),
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = studio.ink,
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
                        .padding(top = 3.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(studio.track),
                ) {
                    Box(modifier = Modifier.fillMaxWidth(fraction).height(3.dp).background(studio.ink))
                }
            }
        }
        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (channel.favourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (channel.favourite) R.string.action_unfavourite else R.string.action_favourite,
                ),
                tint = if (channel.favourite) studio.cue else studio.ink3,
            )
        }
    }
}
