package com.tivicopy.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tivicopy.player.R
import com.tivicopy.player.data.model.Channel

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

/**
 * "Live", with a dot, on a plate dark enough to hold it over anything.
 *
 * Shared rather than owned by one screen: the station backdrop, the zapping info card and the
 * transport bar all have to say the same word about the same stream, and three of them drawn
 * three ways is how a shell stops looking like one app.
 *
 * Dark plate, white text, in fixed colours rather than scheme ones. It has to read on three
 * different things — live video, a backdrop built from a station's own hue, and a themed panel —
 * and a plate taken from the scheme would fight the first two while a light one would lose to
 * bright video. The alpha is what makes it work on the third: at the 45% it started at, over a
 * light theme's own `surface`, the plate composites to a mid grey and white text on it falls
 * under 3:1. Dark enough to stay a dark plate over white is the whole requirement.
 *
 * The dot is the scheme's error red, the one colour in the ramp that already means "on air".
 */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(LIVE_BADGE_CORNER))
            .background(Color.Black.copy(alpha = LIVE_BADGE_PLATE_ALPHA))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        Text(
            text = stringResource(R.string.playback_live_badge),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

/**
 * Dark enough to hold white text over anything, including a light theme's own surface, and short
 * of the solid black box that would read as a hole punched in the picture.
 */
private const val LIVE_BADGE_PLATE_ALPHA = 0.72f

private val LIVE_BADGE_CORNER = 16.dp

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
 * A row that is focusable for D-pad use and clickable for touch, with a visible focus ring.
 * NFR-UX-02 asks for a 3:1 focus contrast, which the primary colour on any surface here meets.
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
            // The app's one focus treatment (`ui/common/Focus.kt`), tinting rather than raising
            // because a row sits on a settled surface. Every list in the app is built from this
            // row, so this one call is most of what focus feels like here.
            .focusSurface(focused = focused, shape = rowShape, selected = selected)
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
        content = content,
    )
}

/**
 * The one category-header look shared by every list/menu in the app — Settings' own
 * [com.tivicopy.player.ui.settings.SettingsSectionTitle] is a thin wrapper over this rather than
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

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Now/next progress for the info card and the channel rows. */
@Composable
fun ProgrammeProgress(startUtc: Long, endUtc: Long, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis()
    val span = (endUtc - startUtc).coerceAtLeast(1)
    val fraction = ((now - startUtc).toFloat() / span).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.fillMaxWidth(),
    )
}

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
 * Isolated with [com.tivicopy.player.core.TimeModel.ltr] because `21:40` inside a Hebrew layout
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
        text = com.tivicopy.player.core.TimeModel.ltr(
            com.tivicopy.player.core.TimeModel.formatClock(now),
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

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, colour: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colour)
            .padding(horizontal = 6.dp, vertical = 2.dp),
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
