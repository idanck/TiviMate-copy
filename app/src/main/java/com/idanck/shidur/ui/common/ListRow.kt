package com.idanck.shidur.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.idanck.shidur.R
import com.idanck.shidur.ui.theme.StudioTheme

/** What sits at the end of a [ListRow] (`ListRow`'s `trail`). */
enum class RowTrail {
    /** Nothing: an action that happens in place. */
    NONE,

    /** Opens something further in. */
    CHEVRON,

    /** The chosen one of a list of options. */
    CHECK,

    /** A setting's current value, in the secondary ink. */
    VALUE,

    /** A coloured pill: a recording's or a source's state. Its colour is [RowTone]. */
    STATUS,

    /** A switch; its state is `toggled`. */
    TOGGLE,
}

/** The colour of a [RowTrail.STATUS] pill's dot: the canvas's four. */
enum class RowTone { MUTED, TALLY, OK, ERROR }

/**
 * The list row (`ListRow.dc.html`, `PListRow.dc.html`): every option, menu entry and setting in the
 * app, in both shells.
 *
 * Built on [FocusableRow], so focus, the remote's Menu key and the long press are the same as on
 * every other row. A row like this is a control, not content (ADR-046): under focus it becomes the
 * lit surface ([FocusFill.LIT]) and everything on it turns dark. On top of that, the canvas's parts —
 * an optional number or glyph at the start, the label with an optional second line, and one of the
 * [RowTrail]s at the end — and its states:
 *
 * - [selected] — the chosen value while focus is elsewhere: amber glass, an amber edge, and the
 *   label, glyph and check in amber (`--st-current`).
 * - [onScreen] — the channel on screen: a wash of the tally and "on screen" in a live pill after the
 *   label, so it is not told by colour alone.
 * - [danger] — a destructive action: the label in the error colour.
 * - not [enabled] — the third ink, and a press does nothing. It stays a focus target, so the
 *   remote can still reach it and read why.
 *
 * On a phone a long label takes two lines and its description three, and the check is the cue —
 * there, the cue is amber and marks what is chosen (`studio-phone.css`).
 */
@Composable
fun ListRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    num: String? = null,
    icon: ImageVector? = null,
    trail: RowTrail = RowTrail.NONE,
    value: String? = null,
    tone: RowTone = RowTone.MUTED,
    toggled: Boolean = false,
    selected: Boolean = false,
    onScreen: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val tv = StudioTheme.television
    FocusableRow(
        onClick = { if (enabled) onClick() },
        modifier = modifier.defaultMinSize(
            minHeight = when {
                tv && sub != null -> 46.dp
                // `--st-h-row`: 64px at 1080p.
                tv -> 32.dp
                sub != null -> 64.dp
                else -> 56.dp
            },
        ),
        selected = selected,
        onScreen = onScreen,
        contentDescription = listOfNotNull(label, sub, value).joinToString(", "),
        onFocused = onFocused,
        onLongClick = onLongClick,
        focusRequester = focusRequester,
        fill = FocusFill.LIT,
    ) {
        // Read here, inside the row: whether this row is the lit surface right now.
        val lit = LocalOnLitSurface.current
        val chosen = selected && !lit
        val ink = animatedColour(
            when {
                lit -> studio.onCue
                !enabled -> studio.ink3
                danger -> studio.error
                chosen -> studio.current
                else -> studio.ink
            },
        )
        val secondary = animatedColour(
            when {
                lit -> studio.onCue.copy(alpha = LIT_MUTED_ALPHA)
                enabled -> studio.ink2
                else -> studio.ink3
            },
        )
        val glyph = when {
            lit || danger || chosen || !enabled -> ink
            else -> studio.ink2
        }
        if (num != null) {
            Text(
                text = num,
                style = type.meta.copy(fontWeight = FontWeight.Bold),
                color = secondary,
                modifier = Modifier.width(if (tv) 22.dp else 32.dp),
            )
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = glyph,
                modifier = Modifier.size(if (tv) 14.dp else 22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 8.dp),
            ) {
                Text(
                    text = label,
                    style = type.rowLabel.copy(fontWeight = if (lit || chosen) FontWeight.Bold else FontWeight.SemiBold),
                    color = ink,
                    maxLines = if (tv) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (onScreen) {
                    LiveBadge(
                        label = stringResource(R.string.channel_on_screen),
                        size = LiveBadgeSize.S,
                        lit = lit,
                    )
                }
            }
            if (sub != null) {
                Text(
                    text = sub,
                    style = type.caption.copy(fontWeight = FontWeight.Normal),
                    color = secondary,
                    maxLines = if (tv) 1 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (trail) {
            RowTrail.NONE -> Unit
            RowTrail.CHEVRON -> Icon(
                imageVector = StudioIcons.ChevronEnd,
                contentDescription = null,
                tint = if (tv || lit) secondary else studio.ink3,
                modifier = Modifier.size(if (tv) 13.dp else 20.dp),
            )
            RowTrail.CHECK -> Icon(
                imageVector = StudioIcons.Check,
                contentDescription = null,
                tint = when {
                    lit -> studio.onCue
                    !tv -> studio.cue
                    selected -> studio.current
                    else -> studio.ink
                },
                modifier = Modifier.size(if (tv) 14.dp else 22.dp),
            )
            RowTrail.VALUE -> if (value != null) {
                Text(text = value, style = type.meta, color = secondary, maxLines = 1)
            }
            RowTrail.STATUS -> if (value != null) StatusPill(text = value, tone = tone, lit = lit)
            RowTrail.TOGGLE -> StudioToggle(checked = toggled, enabled = enabled, lit = lit)
        }
    }
}

/**
 * `ListRow`'s status trail: a dot and a word on a pill of thin glass. The dot carries the tone; the
 * word is ink, or the error colour for a failure, or the secondary ink for a muted one. On a lit
 * row everything on it turns dark, the dot's tone excepted.
 *
 * Recording now (the tally) is the live badge's red glass rather than a grey pill with a red dot:
 * it is the one status that means "on air".
 */
@Composable
fun StatusPill(text: String, tone: RowTone, modifier: Modifier = Modifier, lit: Boolean = false) {
    val studio = StudioTheme.colors
    val tv = StudioTheme.television
    if (tone == RowTone.TALLY) {
        LiveBadge(label = text, size = LiveBadgeSize.S, lit = lit, modifier = modifier)
        return
    }
    val shape = MaterialTheme.shapes.extraSmall
    val dot = when (tone) {
        RowTone.TALLY -> studio.tally
        RowTone.ERROR -> studio.error
        RowTone.OK -> studio.ok
        RowTone.MUTED -> if (lit) studio.onCue.copy(alpha = 0.5f) else studio.ink3
    }
    Row(
        modifier = modifier
            .height(if (tv) 19.dp else 26.dp)
            .clip(shape)
            .background(if (lit) studio.onCue.copy(alpha = 0.08f) else studio.card)
            .border(1.dp, if (lit) studio.onCue.copy(alpha = 0.14f) else studio.edgeLine, shape)
            .padding(horizontal = if (tv) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (tv) 4.dp else 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (tv) 5.dp else 8.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Text(
            text = text,
            style = StudioTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
            color = when {
                lit -> studio.onCue
                tone == RowTone.ERROR -> studio.error
                tone == RowTone.MUTED -> studio.ink2
                else -> studio.ink
            },
            maxLines = 1,
        )
    }
}
