package com.tivicopy.player.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.radio.RadioCategory
import com.tivicopy.player.data.radio.RadioCategoryClassifier
import com.tivicopy.player.ui.AppUiState
import com.tivicopy.player.ui.common.PhoneButtonVariant
import com.tivicopy.player.ui.common.PhoneButtonSize
import com.tivicopy.player.ui.common.PhoneButton
import com.tivicopy.player.ui.common.Equalizer
import com.tivicopy.player.ui.common.LiveBadge
import com.tivicopy.player.ui.common.StationDisc
import com.tivicopy.player.ui.common.splitSong
import com.tivicopy.player.ui.common.studioAmbient
import com.tivicopy.player.ui.theme.StudioTheme

/**
 * Studio's radio on a phone (`docs/design/studio/canvas/PRadio.dc.html`): the whole screen, not a
 * box under a video that is not there. The shifting background (ADR-030), the way down and the live
 * badge across the top, the station's disc haloed in light, the song in Karantina with the artist
 * under it, the equaliser (ADR-031), favourite / previous / play-pause / next / guide, and along the
 * bottom the stations, by category, the one on air marked with the tally.
 */
@Composable
internal fun PhoneRadio(
    state: AppUiState,
    channel: Channel,
    song: String?,
    playWhenReady: Boolean,
    live: Boolean,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFavourite: () -> Unit,
    onOpenGuide: () -> Unit,
    onPlay: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val parts = song?.let(::splitSong)
    Box(modifier = modifier.studioAmbient()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.action_collapse_player),
                        tint = studio.ink,
                    )
                }
                // The radio survives a pause, so the badge needs both: a stopped stream is not live.
                if (live && playWhenReady) LiveBadge()
            }
            Box(modifier = Modifier.padding(top = 16.dp)) {
                StationDisc(channel = channel, size = 200.dp, halo = true)
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.radio_now_playing_on, channel.displayName),
                    style = type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = studio.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The song as a broadcast would set it; the station's own name when it names none.
                Text(
                    text = parts?.title ?: channel.displayName,
                    style = type.hero.copy(
                        lineHeight = type.hero.fontSize * 0.95f,
                        textDirection = TextDirection.Content,
                    ),
                    color = studio.ink,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                parts?.artist?.let { artist ->
                    Text(
                        text = artist,
                        style = type.body.copy(
                            fontSize = type.body.fontSize * (18f / 16f),
                            textDirection = TextDirection.Content,
                        ),
                        color = studio.ink2,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Equalizer(
                playing = playWhenReady,
                description = stringResource(R.string.radio_equalizer),
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 18.dp).fillMaxWidth().height(56.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoneButton(
                    label = stringResource(if (channel.favourite) R.string.action_unfavourite else R.string.action_favourite),
                    icon = if (channel.favourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    iconOnly = true,
                    variant = PhoneButtonVariant.GHOST,
                    onClick = onFavourite,
                )
                PhoneButton(
                    label = stringResource(R.string.radio_previous),
                    icon = Icons.Filled.KeyboardArrowUp,
                    iconOnly = true,
                    onClick = onPrevious,
                )
                PhoneButton(
                    label = stringResource(if (playWhenReady) R.string.action_pause else R.string.action_play),
                    icon = if (playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    iconOnly = true,
                    variant = PhoneButtonVariant.PRIMARY,
                    size = PhoneButtonSize.XL,
                    onClick = onTogglePlay,
                )
                PhoneButton(
                    label = stringResource(R.string.radio_next),
                    icon = Icons.Filled.KeyboardArrowDown,
                    iconOnly = true,
                    onClick = onNext,
                )
                PhoneButton(
                    label = stringResource(R.string.nav_guide),
                    icon = Icons.Filled.DateRange,
                    iconOnly = true,
                    variant = PhoneButtonVariant.GHOST,
                    onClick = onOpenGuide,
                )
            }
            StationStrip(
                state = state,
                current = channel,
                onPlay = onPlay,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp),
            )
        }
    }
}

/** The three buckets the strip's chips offer, in the canvas's order. */
private enum class StripCategory { NATIONAL, REGIONAL, OTHER }

private fun RadioCategory.strip(): StripCategory = when (this) {
    RadioCategory.NATIONAL -> StripCategory.NATIONAL
    RadioCategory.REGIONAL -> StripCategory.REGIONAL
    else -> StripCategory.OTHER
}

/**
 * The stations, a chip per category (national, regional, everything else) over a row of discs.
 * It opens on the category of the station that is on, with that station marked by the tally.
 * Drawn only when the radio's station list is loaded (the radio tab put it there).
 */
@Composable
private fun StationStrip(
    state: AppUiState,
    current: Channel,
    onPlay: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stations = state.radioAllChannels
    if (stations.isEmpty()) return
    val studio = StudioTheme.colors
    val byCategory = remember(stations, state.list.groups) {
        stations.groupBy { channel ->
            val group = state.list.groups.firstOrNull { it.id == channel.groupId }?.name
            RadioCategoryClassifier.classify(group, channel.displayName).strip()
        }
    }
    val currentCategory = byCategory.entries.firstOrNull { (_, list) -> list.any { it.id == current.id } }?.key
    var selected by remember(currentCategory) {
        mutableStateOf(currentCategory ?: byCategory.keys.minByOrNull { it.ordinal } ?: StripCategory.NATIONAL)
    }
    val shown = byCategory[selected].orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(selected, current.id) {
        val at = shown.indexOfFirst { it.id == current.id }
        if (at >= 0) listState.scrollToItem(at)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.radio_stations),
                style = StudioTheme.type.h1.copy(fontSize = StudioTheme.type.h1.fontSize * (18f / 20f)),
                color = studio.ink,
            )
            StripCategory.entries.filter { it in byCategory }.forEach { category ->
                PhoneChip(
                    label = stringResource(
                        when (category) {
                            StripCategory.NATIONAL -> R.string.radio_category_national
                            StripCategory.REGIONAL -> R.string.radio_category_regional
                            StripCategory.OTHER -> R.string.radio_category_other
                        },
                    ),
                    selected = category == selected,
                    onClick = { selected = category },
                )
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shown, key = { it.id }) { station ->
                val onAir = station.id == current.id
                Column(
                    modifier = Modifier
                        .width(72.dp)
                        .clickable(role = Role.Button, onClick = { onPlay(station) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = if (onAir) {
                            Modifier.border(2.dp, studio.tally, CircleShape).padding(2.dp)
                        } else {
                            Modifier.padding(2.dp)
                        },
                    ) {
                        StationDisc(channel = station, size = 60.dp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (onAir) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(studio.tally))
                        }
                        Text(
                            text = station.displayName,
                            style = StudioTheme.type.caption.copy(
                                fontSize = StudioTheme.type.caption.fontSize * (12f / 13f),
                                fontWeight = if (onAir) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = if (onAir) studio.ink else studio.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** `PChip`: ink on the ground when selected, ink-2 on the surface otherwise. 36dp, fully round. */
@Composable
internal fun PhoneChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) studio.ink else studio.surface)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) studio.ground else studio.ink2,
            maxLines = 1,
        )
    }
}
