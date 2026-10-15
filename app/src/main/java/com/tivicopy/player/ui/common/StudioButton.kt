package com.tivicopy.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tivicopy.player.ui.theme.StudioTheme

/** The two button heights the canvas draws (`--st-h-control-l`, `--st-h-control-m`). */
enum class StudioButtonSize { LARGE, MEDIUM }

/**
 * The canvas's four buttons (`Button.dc.html`, `PButton.dc.html`).
 *
 * - [PRIMARY] — the one action a screen or a dialog is built around. Ink at rest on a television,
 *   where the cue is kept for focus; the cue itself on a phone, where it marks what is active.
 * - [SECONDARY] — a translucent control inside a hairline.
 * - [GHOST] — only its label: "close", "cancel", "later", "skip". Nothing behind it at rest.
 * - [DANGER] — a secondary button whose label is the error colour, for a destructive action.
 */
enum class StudioButtonVariant { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * Studio's button, in whichever shell is showing (`StudioTheme.television`).
 *
 * On a television it is a translucent control that *becomes* the cue when focus is on it, lifting
 * with a shadow — whatever its variant, because focus has one colour. On a phone it is
 * [PhoneButton], which has no focus state: there the cue is the primary variant's own fill.
 * Icon-only buttons are square on a television, round on a phone, and say their [label] to
 * TalkBack.
 *
 * Every Material `Button` and `TextButton` in the app came through here in Studio stage 7, which is
 * what lets one screen — the channel menu, an update prompt — be drawn by both shells.
 *
 * @param enabled false draws the button at the canvas's disabled opacity and takes it out of the
 *   focus order, so the remote steps over it rather than landing on something that does nothing
 * @param focusRequester lets a screen land the remote here first
 */
@Composable
fun StudioButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconOnly: Boolean = false,
    variant: StudioButtonVariant = StudioButtonVariant.SECONDARY,
    size: StudioButtonSize = StudioButtonSize.LARGE,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    if (!StudioTheme.television) {
        PhoneButton(
            label = label,
            onClick = onClick,
            modifier = modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            icon = icon,
            iconOnly = iconOnly,
            variant = when (variant) {
                StudioButtonVariant.PRIMARY -> PhoneButtonVariant.PRIMARY
                StudioButtonVariant.SECONDARY -> PhoneButtonVariant.SECONDARY
                StudioButtonVariant.GHOST -> PhoneButtonVariant.GHOST
                StudioButtonVariant.DANGER -> PhoneButtonVariant.DANGER
            },
            size = if (size == StudioButtonSize.LARGE) PhoneButtonSize.L else PhoneButtonSize.M,
            enabled = enabled,
        )
        return
    }
    var focused by remember { mutableStateOf(false) }
    val studio = StudioTheme.colors
    // The control corner by the theme, which knows the shell even above `TvRoot`'s metrics —
    // where the update and PIN prompts are drawn.
    val shape = MaterialTheme.shapes.medium
    val large = size == StudioButtonSize.LARGE
    val height = if (large) 42.dp else 32.dp
    val primary = variant == StudioButtonVariant.PRIMARY
    val ghost = variant == StudioButtonVariant.GHOST
    // Every resting value that has nothing to show is its own colour at alpha zero, never
    // `Color.Transparent` (`Motion.kt`): a ghost button's fill fades out of the cue, not into black.
    val fill = animatedColour(
        when {
            focused -> studio.cue
            primary -> studio.ink
            ghost -> studio.control.copy(alpha = 0f)
            else -> studio.control
        },
    )
    val edge = animatedColour(
        when {
            focused -> studio.cue
            primary -> studio.ink
            ghost -> studio.hairline.copy(alpha = 0f)
            else -> studio.hairline
        },
    )
    val content = animatedColour(
        when {
            focused -> studio.onCue
            primary -> studio.ground
            ghost -> studio.ink2
            variant == StudioButtonVariant.DANGER -> studio.error
            else -> studio.ink
        },
    )
    Row(
        modifier = modifier
            .height(height)
            .defaultMinSize(minWidth = if (iconOnly) height else 0.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .focusLift(focused, shape = shape)
            .clip(shape)
            .background(fill)
            // One width at rest and focused (`Focus.kt`); only the colour crosses.
            .border(1.dp, edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // `clickable` carries the focus target on its own; `focusable()` beside it would cost
            // the remote a second press to pass the button. Disabled, it is not a focus target.
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = if (iconOnly) 0.dp else if (large) 19.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(if (large) 15.dp else 13.dp),
                tint = content,
            )
        }
        if (!iconOnly) {
            val action = StudioTheme.type.action
            Text(
                text = label,
                style = action.copy(
                    fontSize = if (large) action.fontSize else action.fontSize * MEDIUM_LABEL_SCALE,
                    fontWeight = if (focused || primary) FontWeight.Bold else FontWeight.Medium,
                ),
                color = content,
                maxLines = 1,
            )
        }
    }
}

/** The medium button's label is 24px against the large one's 30 at 1080p. */
private const val MEDIUM_LABEL_SCALE = 24f / 30f

/** The canvas's disabled state, the same for a button, a field and a toggle. */
internal const val DISABLED_ALPHA = 0.45f

/** `PButton`'s three heights: 64, 48 and 40dp. */
internal enum class PhoneButtonSize(val height: Dp, val icon: Dp) {
    XL(64.dp, 30.dp),
    L(48.dp, 22.dp),
    M(40.dp, 18.dp),
}

/**
 * `PButton`'s variants: the cue fill for the one main action, a card, nothing behind, or a card
 * whose label is the error colour.
 */
internal enum class PhoneButtonVariant { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * `PButton`: Studio's button at a phone's scale. A primary button is the cue with a lift, a
 * secondary or danger one a card inside a hairline, a ghost one only its icon and label. Icon-only
 * buttons are round; the others take the control radius.
 *
 * Phone-only screens call this directly for the sizes the television has no counterpart for
 * ([PhoneButtonSize.XL]); shared screens call [StudioButton], which comes here on a phone.
 */
@Composable
internal fun PhoneButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconOnly: Boolean = false,
    variant: PhoneButtonVariant = PhoneButtonVariant.SECONDARY,
    size: PhoneButtonSize = PhoneButtonSize.L,
    enabled: Boolean = true,
) {
    val studio = StudioTheme.colors
    val shape = if (iconOnly) CircleShape else MaterialTheme.shapes.medium
    val content = when (variant) {
        PhoneButtonVariant.PRIMARY -> studio.onCue
        PhoneButtonVariant.DANGER -> studio.error
        else -> studio.ink
    }
    val surface = when (variant) {
        PhoneButtonVariant.PRIMARY -> Modifier.shadow(8.dp, shape).clip(shape).background(studio.cue)
        PhoneButtonVariant.SECONDARY, PhoneButtonVariant.DANGER ->
            Modifier.clip(shape).background(studio.card).border(1.dp, studio.hairline, shape)
        PhoneButtonVariant.GHOST -> Modifier.clip(shape)
    }
    Row(
        modifier = modifier
            .height(size.height)
            .then(if (iconOnly) Modifier.width(size.height) else Modifier)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = if (iconOnly) 0.dp else if (size == PhoneButtonSize.M) 16.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(size.icon))
        }
        if (!iconOnly) {
            val action = StudioTheme.type.action
            Text(
                text = label,
                style = action.copy(
                    fontSize = if (size == PhoneButtonSize.M) action.fontSize * (14f / 16f) else action.fontSize,
                    fontWeight = when (variant) {
                        PhoneButtonVariant.PRIMARY -> FontWeight.Bold
                        PhoneButtonVariant.DANGER -> FontWeight.SemiBold
                        else -> FontWeight.Medium
                    },
                ),
                color = content,
                maxLines = 1,
            )
        }
    }
}
