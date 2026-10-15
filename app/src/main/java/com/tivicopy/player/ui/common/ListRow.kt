package com.tivicopy.player.ui.common

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.ui.theme.StudioTheme

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
 * Studio's list row (`ListRow.dc.html`, `PListRow.dc.html`): every option, menu entry and setting
 * in the app, in both shells.
 *
 * Built on [FocusableRow], so focus, the remote's Menu key and the long press are the same as on
 * every other row. On top of it, the canvas's parts: an optional number or glyph at the start, the
 * label with an optional second line, and one of the [RowTrail]s at the end. And its states:
 *
 * - [selected] — the chosen one while focus is elsewhere: the card colour.
 * - [onScreen] — the channel on screen: the dark tally fill, a tally ring and a red dot after the
 *   label, so it is not told by colour alone.
 * - [danger] — a destructive action: the label in the error colour.
 * - not [enabled] — the third ink, and a press does nothing. It stays a focus target, so the
 *   remote can still reach it and read why.
 *
 * On a television the glyph and the label are ink; on a phone the glyph is the secondary ink, a
 * long label takes two lines and its description three, and the check is the cue — there, the cue
 * marks what is chosen (`studio-phone.css`).
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
    val ink = when {
        !enabled -> studio.ink3
        danger -> studio.error
        else -> studio.ink
    }
    val secondary = if (enabled) studio.ink2 else studio.ink3
    FocusableRow(
        onClick = { if (enabled) onClick() },
        modifier = modifier.defaultMinSize(
            minHeight = when {
                tv && sub != null -> 46.dp
                tv -> 36.dp
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
    ) {
        if (num != null) {
            Text(
                text = num,
                style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                color = secondary,
                modifier = Modifier.width(if (tv) 22.dp else 32.dp),
            )
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (tv) ink else secondary,
                modifier = Modifier.size(if (tv) 14.dp else 22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = label,
                    style = type.rowLabel,
                    color = ink,
                    maxLines = if (tv) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (onScreen) {
                    Box(
                        modifier = Modifier
                            .size(if (tv) 6.dp else 8.dp)
                            .clip(CircleShape)
                            .background(studio.tally),
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
                tint = if (tv) secondary else studio.ink3,
                modifier = Modifier.size(if (tv) 13.dp else 20.dp),
            )
            RowTrail.CHECK -> Icon(
                imageVector = StudioIcons.Check,
                contentDescription = null,
                tint = if (tv) ink else studio.cue,
                modifier = Modifier.size(if (tv) 14.dp else 22.dp),
            )
            RowTrail.VALUE -> if (value != null) {
                Text(text = value, style = type.meta, color = secondary, maxLines = 1)
            }
            RowTrail.STATUS -> if (value != null) StatusPill(text = value, tone = tone)
            RowTrail.TOGGLE -> StudioToggle(checked = toggled, enabled = enabled)
        }
    }
}

/**
 * `ListRow`'s status trail: a dot and a word on the surface colour, inside a faint hairline. The
 * dot carries the tone; the word is ink, or the error colour for a failure, or the secondary ink
 * for a muted one.
 */
@Composable
fun StatusPill(text: String, tone: RowTone, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    val tv = StudioTheme.television
    val shape = MaterialTheme.shapes.extraSmall
    val dot = when (tone) {
        RowTone.TALLY -> studio.tally
        RowTone.ERROR -> studio.error
        RowTone.OK -> studio.ok
        RowTone.MUTED -> studio.ink3
    }
    Row(
        modifier = modifier
            .height(if (tv) 19.dp else 26.dp)
            .clip(shape)
            .background(studio.surface)
            .border(1.dp, studio.hairlineSoft, shape)
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
            color = when (tone) {
                RowTone.ERROR -> studio.error
                RowTone.MUTED -> studio.ink2
                else -> studio.ink
            },
            maxLines = 1,
        )
    }
}
