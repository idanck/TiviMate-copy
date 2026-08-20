package com.tivicopy.player.ui.common

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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
fun ChannelLogo(channel: Channel, modifier: Modifier = Modifier, sizeDp: Int = 44) {
    val url = channel.displayLogo
    var failed by remember(url) { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)

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
                modifier = Modifier.size(sizeDp.dp),
                onError = { failed = true },
            )
        }
    }
}

private fun monogram(name: String): String {
    val words = name.trim().split(' ').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

private fun monogramColour(name: String): Color {
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
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val border = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    val background = when {
        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        selected -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(2.dp, border, RoundedCornerShape(8.dp))
            .onFocusChanged {
                // `hasFocus`, not `isFocused`: rows now carry their own controls — the favourite
                // toggle — and a D-pad step onto one of those must not look like leaving the row.
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
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

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
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
