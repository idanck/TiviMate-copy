package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.ui.common.LiveBadge
import com.tivicopy.player.ui.common.LiveBadgeSize
import com.tivicopy.player.ui.common.focusLift
import com.tivicopy.player.ui.theme.StudioTheme

/**
 * The channel card (`canvas/ChannelCard.dc.html`): the one card the home rails and the channel
 * filmstrip are built from (Studio stage 10c; Merhav, ADR-046).
 *
 * The 16:9 tile *is* the card — it takes the focus treatment, the lift and the on-screen state —
 * and the programme and its time sit under it, outside the plate, as captions do under a picture.
 * The tile is thin glass; under focus it is lit glass in a white ring, lifted and grown, its text
 * still white, because it is content. On the tile: the channel's mark (or its name as a word, or a
 * round station disc), its number in the top corner, and while it is on screen a wash of the tally,
 * a red ring and an "on screen" live pill in the other corner, so the state is not carried by the
 * red alone. A programme on air draws its progress along the foot of the tile, in red while the
 * channel is on screen.
 *
 * @param label the channel's name: written on the tile when there is no mark to show, and what
 *   TalkBack reads
 * @param progress how far through the programme on air, or null for none
 */
@Composable
internal fun StudioChannelCard(
    label: String,
    imageUrl: String?,
    number: Int?,
    now: String?,
    time: String?,
    progress: Float?,
    onScreen: Boolean,
    circular: Boolean,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    var focused by remember { mutableStateOf(false) }
    var failed by remember(imageUrl) { mutableStateOf(false) }
    val shape = tvCardShape()
    Column(
        modifier = Modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                // Through a `graphicsLayer`, so the rail does not re-measure and the cards either
                // side stay where they are (`ui/common/Motion.kt`).
                .focusLift(focused, shape = shape)
                .tvCard(focused = focused, playing = onScreen)
                .onFocusChanged { focused = it.isFocused }
                // `clickable` carries the focus target on its own.
                .clickable(onClick = onClick)
                .semantics { contentDescription = listOfNotNull(label, now).joinToString(", ") },
            contentAlignment = Alignment.Center,
        ) {
            when {
                circular -> Box(
                    // A station is round wherever it lands (ADR-022): a disc on the surface colour.
                    modifier = Modifier
                        .fillMaxHeight(0.76f)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(studio.surface)
                        .border(1.dp, studio.hairline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!imageUrl.isNullOrBlank() && !failed) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            onError = { failed = true },
                        )
                    } else {
                        CardWord(label, small = true)
                    }
                }

                !imageUrl.isNullOrBlank() && !failed -> AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                    onError = { failed = true },
                )

                else -> CardWord(label, small = false)
            }
            number?.let {
                Text(
                    // A bare Latin numeral beside Hebrew: isolated, or the layout reorders it.
                    text = TimeModel.ltr(it.toString()),
                    style = type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = studio.ink2,
                    modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            if (onScreen) {
                LiveBadge(
                    label = stringResource(R.string.card_on_screen),
                    size = LiveBadgeSize.S,
                    modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 7.dp, vertical = 8.dp),
                )
            }
            progress?.let { fraction ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .background(studio.track),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(2.5.dp)
                            .background(if (onScreen) studio.tally else studio.ink),
                    )
                }
            }
        }
        Text(
            text = now ?: label,
            style = type.rowLabel.copy(fontSize = type.rowLabel.fontSize * NOW_SCALE),
            color = studio.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        time?.let {
            Text(
                text = TimeModel.ltr(it),
                style = type.caption,
                color = studio.ink3,
                maxLines = 1,
            )
        }
    }
}

/** A channel's name written on its tile, for a channel with no mark to show. */
@Composable
private fun CardWord(label: String, small: Boolean) {
    val h1 = StudioTheme.type.h1
    Text(
        text = label,
        // 40px at 1080p on the tile, 26px inside a station's disc.
        style = h1.copy(fontSize = h1.fontSize * (if (small) 13f / 17f else 20f / 17f)),
        color = StudioTheme.colors.ink,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** The programme under a card is 24px at 1080p against the row label's 26. */
private const val NOW_SCALE = 24f / 26f
