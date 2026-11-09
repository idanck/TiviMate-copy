package com.idanck.shidur.ui.common

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.idanck.shidur.R
import com.idanck.shidur.data.model.Channel

/**
 * The activity behind a composition's context.
 *
 * A bare `as? Activity` is not enough here: the language setting wraps the activity in a
 * `ContextWrapper` (see `data/prefs/AppLocale.kt`), so the activity sits one link down the chain.
 */
tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * A channel tile that falls back to a generated monogram when the provider supplies no logo or
 * the logo fails to load (FR-ORG-10). The fallback colour is derived from the name so the same
 * channel always gets the same tile.
 */
@Composable
fun ChannelLogo(
    channel: Channel,
    modifier: Modifier = Modifier,
    sizeDp: Int = 44,
    /**
     * Whether the plate is a disc rather than a rounded square. Radio takes it: a station is a
     * mark you recognise, not a picture you look at, and the round plate is what tells a viewer
     * scrolling one list from the other which one they are in without reading a word.
     */
    circular: Boolean = false,
) {
    val url = channel.displayLogo
    var failed by remember(url) { mutableStateOf(false) }
    val shape = if (circular) CircleShape else RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(shape)
            .background(monogramColour(channel.displayName)),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank() || failed) {
            Text(
                text = monogram(channel.displayName),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = channel.displayName,
                // Held inside the disc rather than filling its bounding square. A logo fitted to
                // the square runs to both of its vertical edges, which on a round plate is
                // exactly where there is no plate left — station wordmarks lost their first and
                // last letters to the clip. The inset is what an inscribed square costs.
                modifier = Modifier
                    .size(sizeDp.dp)
                    .then(if (circular) Modifier.padding(sizeDp.dp * CIRCULAR_LOGO_INSET) else Modifier),
                onError = { failed = true },
            )
        }
    }
}

/** The live badge's sizes (`LiveBadge.dc.html`): on a phone it is always the phone's own. */
enum class LiveBadgeSize { M, S }

/**
 * "Live", with the tally light (`LiveBadge.dc.html`, `st-live`): a pill of dark red glass with a
 * red edge, the dot glowing, and always a word — red never says "live" alone.
 *
 * Shared rather than owned by one screen: the station backdrop, the lower third, the home banner,
 * the transport bar and a row's "on screen" all have to say the same thing about the same stream.
 *
 * @param label what it says: "live" unless told otherwise ("on screen", "recording")
 * @param size the television's 44px or 34px badge at 1080p; a phone draws its 26dp one either way
 * @param lit on the lit surface of a focused row: solid tally with dark text, as the canvas's
 *   `ListRow` draws it, because red glass on a white plate would be pink
 */
@Composable
fun LiveBadge(
    modifier: Modifier = Modifier,
    label: String? = null,
    size: LiveBadgeSize = LiveBadgeSize.M,
    lit: Boolean = false,
) {
    val studio = com.idanck.shidur.ui.theme.StudioTheme.colors
    val type = com.idanck.shidur.ui.theme.StudioTheme.type
    val tv = com.idanck.shidur.ui.theme.StudioTheme.television
    val shape = CircleShape
    // Height, padding at the start and at the end, the gap, the dot and its halo, in dp.
    val m = when {
        !tv -> LiveBadgeMeasure(26f, 10f, 12f, 6f, 8f, 3f)
        size == LiveBadgeSize.M -> LiveBadgeMeasure(22f, 8f, 9f, 5f, 6f, 2.5f)
        else -> LiveBadgeMeasure(17f, 6f, 7f, 5f, 5f, 2f)
    }
    val textStyle = when {
        !tv -> type.caption.copy(fontSize = type.caption.fontSize * (12f / 13f), fontWeight = FontWeight.Bold)
        size == LiveBadgeSize.M -> type.meta.copy(fontWeight = FontWeight.Bold)
        else -> type.caption.copy(fontSize = type.caption.fontSize * 0.9f, fontWeight = FontWeight.Bold)
    }
    val dot = if (lit) studio.onTally else studio.tally
    val halo = studio.tally.copy(alpha = LIVE_HALO_ALPHA)
    val glow = studio.tally.copy(alpha = LIVE_GLOW_ALPHA)
    Row(
        modifier = modifier
            .height(m.height.dp)
            .clip(shape)
            .background(if (lit) studio.tally else studio.liveGlass)
            .border(1.dp, if (lit) studio.tally else studio.liveEdge, shape)
            .padding(start = m.start.dp, end = m.end.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.gap.dp),
    ) {
        Box(
            modifier = Modifier
                .size(m.dot.dp)
                // The dot's halo and glow (`st-live-dot`), drawn round it rather than laid out, so
                // the pill keeps its size. None on the lit surface.
                .drawBehind {
                    if (!lit) {
                        // `this.size`: the draw area, not this function's `size` parameter.
                        val r = this.size.minDimension / 2f
                        drawCircle(
                            brush = Brush.radialGradient(listOf(glow, glow.copy(alpha = 0f)), center = center, radius = r * 3f),
                            radius = r * 3f,
                        )
                        drawCircle(color = halo, radius = r + m.halo.dp.toPx())
                    }
                }
                .clip(CircleShape)
                .background(dot),
        )
        Text(
            text = label ?: stringResource(R.string.playback_live_badge),
            style = textStyle,
            color = if (lit) studio.onTally else studio.liveInk,
            maxLines = 1,
        )
    }
}

/**
 * The tally as a live dot (`st-live-dot`): 8dp, with its halo and glow drawn round it, and a word
 * for TalkBack — red never says "live" alone.
 */
@Composable
fun LiveDot(description: String?, modifier: Modifier = Modifier) {
    val tally = com.idanck.shidur.ui.theme.StudioTheme.colors.tally
    Box(
        modifier = modifier
            .size(8.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .drawBehind {
                val r = this.size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(tally.copy(alpha = 0.45f), tally.copy(alpha = 0f)),
                        center = center,
                        radius = r * 2.6f,
                    ),
                    radius = r * 2.6f,
                )
                drawCircle(color = tally.copy(alpha = 0.22f), radius = r + 3.dp.toPx())
            }
            .clip(CircleShape)
            .background(tally),
    )
}

/** One size of [LiveBadge], in dp. */
private class LiveBadgeMeasure(
    val height: Float,
    val start: Float,
    val end: Float,
    val gap: Float,
    val dot: Float,
    val halo: Float,
)

/** `0 0 0 5px rgba(255, 90, 95, 0.22), 0 0 16px rgba(255, 90, 95, 0.7)`, softened for a radial. */
private const val LIVE_HALO_ALPHA = 0.22f
private const val LIVE_GLOW_ALPHA = 0.45f

/**
 * How far a logo is held back from the edge of a round plate, as a fraction of its width.
 *
 * The largest square that fits inside a circle is `1/√2` of its diameter, so clearing the clip
 * outright would cost about 15% a side. This is a little under that: a wordmark fitted to the
 * remaining width is centred, so its extremes sit where the disc is still widest, and giving back
 * the last couple of percent buys visibly larger artwork on a 36dp list row.
 */
private const val CIRCULAR_LOGO_INSET = 0.12f

/** Shared with the home screen's tiles, which draw art for things that are not channels. */
internal fun monogram(name: String): String {
    val words = name.trim().split(' ').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

internal fun monogramColour(name: String): Color {
    val hue = ((name.hashCode() and 0x7FFFFFFF) % 360).toFloat()
    return Color.hsl(hue, 0.42f, 0.32f)
}

/**
 * A row that is focusable for D-pad use and clickable for touch, with the app's focus treatment.
 * NFR-UX-02 asks for a 3:1 focus contrast, which the lit surface and the white ring meet on any
 * glass here.
 *
 * Two kinds of row, by [fill] (ADR-046): a content row — a channel, a recording, a search hit — is
 * lit glass in a white ring under focus ([FocusFill.TINT]); a row that acts like a menu item
 * ([ListRow]) becomes the lit surface ([FocusFill.LIT]), and its content turns dark, which it learns
 * from [LocalOnLitSurface].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FocusableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    contentDescription: String? = null,
    onFocused: () -> Unit = {},
    /**
     * The row's secondary action: a long press on touch, and the remote's Menu key on a
     * television. Both routes are needed — a D-pad has no long press worth relying on, and a
     * phone has no Menu key.
     */
    onLongClick: (() -> Unit)? = null,
    /**
     * Lets a caller focus this row programmatically — the TV shell's jump-to-the-playing-channel
     * when the list opens. Null (the default) changes nothing for every other row.
     */
    focusRequester: FocusRequester? = null,
    /**
     * The channel on screen (`ListRow`'s `onscreen` state): a wash of the tally, a tally ring and
     * its glow, as the channel card draws it. Focus still wins.
     */
    onScreen: Boolean = false,
    /** A content row ([FocusFill.TINT], the default) or a menu row ([FocusFill.LIT]). */
    fill: FocusFill = FocusFill.TINT,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    // Every list in the app is built from this row, and the two form factors want different
    // numbers out of it: a phone row is read at arm's length and a television row from across a
    // room, where an 8dp corner and a 2dp ring are a rectangle with a faint outline. The metrics
    // carry the answer, so the channel list, the radio list and the guide all pick up the ten-foot
    // card language on a television and change not at all on a phone (`ui/common/TvMetrics.kt`).
    val rowMetrics = LocalTvMetrics.current
    val rowShape = RoundedCornerShape(rowMetrics.rowCorner)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            // Studio's row lift (`--st-focus-scale-row`): less than a card's, because a row is
            // the width of its panel. One on a phone.
            .focusLift(focused, scale = rowMetrics.rowFocusScale, shape = rowShape)
            // The app's focus treatment (`ui/common/Focus.kt`). Every list in the app is built
            // from this row, so this one call is most of what focus feels like here. The channel
            // on screen keeps the card's tally treatment whichever kind of row it is in.
            .focusSurface(
                focused = focused,
                shape = rowShape,
                selected = selected || onScreen,
                fill = if (onScreen && !(fill == FocusFill.LIT && focused)) FocusFill.RAISE else fill,
            )
            .onFocusChanged {
                // `hasFocus`, not `isFocused`: rows now carry their own controls — the favourite
                // toggle — and a D-pad step onto one of those must not look like leaving the row.
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interaction)
            // The activity binds Menu to Settings, but `dispatchKeyEvent` offers the view
            // hierarchy the key first, so a focused row claims it before that ever runs.
            .onKeyEvent { event ->
                val wanted = event.key == Key.Menu || event.key == Key.F1
                if (onLongClick != null && wanted && event.type == KeyEventType.KeyDown) {
                    onLongClick(); true
                } else {
                    false
                }
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { contentDescription?.let { this.contentDescription = it } }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        val scope = this
        CompositionLocalProvider(LocalOnLitSurface provides (fill == FocusFill.LIT && focused)) {
            scope.content()
        }
    }
}

/**
 * The one category-header look shared by every list/menu in the app — Settings' own
 * [com.idanck.shidur.ui.settings.SettingsSectionTitle] is a thin wrapper over this rather than
 * a second definition, so "a category header" reads the same everywhere. [topDivider] draws a
 * full-width rule above the label, which is what actually separates one category's options from
 * the previous one's; turn it off for a header that opens a screen with nothing above it.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    topDivider: Boolean = true,
) {
    Column(modifier = modifier) {
        if (topDivider) {
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Text(
            text = text,
            style = style,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Now/next progress for the info card and the channel rows. */
@Composable
fun ProgrammeProgress(startUtc: Long, endUtc: Long, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis()
    val span = (endUtc - startUtc).coerceAtLeast(1)
    val fraction = ((now - startUtc).toFloat() / span).coerceIn(0f, 1f)
    StudioProgress(fraction = fraction, modifier = modifier.fillMaxWidth())
}

/**
 * The progress bar (`canvas/Progress.dc.html`): ink on a faint track, rounded, 4dp. Ink rather than
 * the cue or amber: a progress bar is never where the remote is, and never the chosen thing.
 *
 * @param indeterminate for work whose length is not known yet — a search, a download before its
 *   size is in: a third of the bar travels from the start to the end and round again (`st-indet`),
 *   following the reading direction. [fraction] is ignored.
 */
@Composable
fun StudioProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 4.dp,
    indeterminate: Boolean = false,
) {
    val studio = com.idanck.shidur.ui.theme.StudioTheme.colors
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(studio.track)
            .semantics {
                progressBarRangeInfo = if (indeterminate) {
                    ProgressBarRangeInfo.Indeterminate
                } else {
                    ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                }
            },
    ) {
        if (indeterminate) {
            val travel = rememberInfiniteTransition(label = "indeterminate")
            val position = travel.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(INDETERMINATE_MS, easing = Motion.Ease)),
                label = "indeterminatePosition",
            )
            val layoutDirection = LocalLayoutDirection.current
            Box(
                modifier = Modifier
                    .fillMaxWidth(INDETERMINATE_SEGMENT)
                    .height(height)
                    // Read in the draw phase, so the travelling costs no recomposition. From just
                    // before the start to just past the end, mirrored for a right-to-left screen.
                    .graphicsLayer {
                        val track = size.width / INDETERMINATE_SEGMENT
                        val x = -size.width + position.value * (track + size.width)
                        translationX = if (layoutDirection == LayoutDirection.Rtl) -x else x
                    }
                    .clip(shape)
                    .background(studio.ink),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(height)
                    .clip(shape)
                    .background(studio.ink),
            )
        }
    }
}

/** The travelling segment's share of the bar, and one crossing (`st-indet`). */
private const val INDETERMINATE_SEGMENT = 0.32f
private const val INDETERMINATE_MS = 1400

@Composable
fun TwoLine(
    primary: String,
    secondary: String?,
    modifier: Modifier = Modifier,
    primaryStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleSmall,
) {
    Column(modifier = modifier) {
        Text(
            text = primary,
            style = primaryStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!secondary.isNullOrBlank()) {
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The wall clock the ten-foot surfaces carry in a corner (ADR-020).
 *
 * Both the home banner and the guide show one, which is what the benchmark does and what a
 * television is half expected to do: the panel is often the only clock in the room, and an
 * overlay that covers the whole screen takes the device's own clock away with it.
 *
 * It ticks on its own rather than on whatever recomposes the screen around it. A clock that
 * updates only when the guide scrolls is a clock that is wrong every time anyone looks at it.
 * Isolated with [com.idanck.shidur.core.TimeModel.ltr] because `21:40` inside a Hebrew layout
 * reorders otherwise (FR-UI-21).
 */
@Composable
fun ClockText(
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(CLOCK_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    Text(
        text = com.idanck.shidur.core.TimeModel.ltr(
            com.idanck.shidur.core.TimeModel.formatClock(now),
        ),
        style = style,
        color = color,
        modifier = modifier,
    )
}

/**
 * How often the clock catches up. Well under a minute, so the displayed minute is never more
 * than a moment stale, and far too rare to cost anything.
 */
private const val CLOCK_TICK_MS = 20_000L

/**
 * A tag on a row or a sheet — "archive", a lock, a programme's category — as the canvas draws one
 * (`ColorRules`, `ListRow`'s muted value): a pill of thin glass, in the second ink.
 *
 * Never amber: amber is where you are and what is chosen, and a tag is a fact about the thing
 * (ADR-046; it was the cue once, MIGRATION.md §3).
 */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    val studio = com.idanck.shidur.ui.theme.StudioTheme
    val tv = studio.television
    Text(
        text = text,
        style = studio.type.caption,
        color = studio.colors.ink2,
        maxLines = 1,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(studio.colors.card)
            .border(1.dp, studio.colors.edgeLine, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = if (tv) 7.dp else 10.dp, vertical = if (tv) 2.dp else 4.dp),
    )
}

/**
 * Reports every key the platform delivers without consuming it (FR-UI-12).
 *
 * Users of non-standard remotes need to know what their device actually sends before they can
 * bind anything to it; this modifier makes that observable rather than guesswork.
 */
@Composable
fun Modifier.keyProbe(onKey: (name: String, code: Int) -> Unit): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return this
        .focusRequester(requester)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == android.view.KeyEvent.ACTION_DOWN) {
                onKey(android.view.KeyEvent.keyCodeToString(native.keyCode), native.keyCode)
            }
            false
        }
        .focusable()
}
