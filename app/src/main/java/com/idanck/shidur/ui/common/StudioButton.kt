package com.idanck.shidur.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.idanck.shidur.ui.theme.StudioTheme

/** The two button heights the canvas draws (`--st-h-control-l`, `--st-h-control-m`). */
enum class StudioButtonSize { LARGE, MEDIUM }

/**
 * The canvas's four buttons (`Button.dc.html`, `PButton.dc.html`).
 *
 * - [PRIMARY] — the one action a screen or a dialog is built around. Brighter glass at rest on a
 *   television, where the lit surface is kept for focus; the cue (amber) on a phone, where it marks
 *   what is active.
 * - [SECONDARY] — a pill of thin glass with its lit edge.
 * - [GHOST] — only its label: "close", "cancel", "later", "skip". Nothing behind it at rest.
 * - [DANGER] — a secondary button whose label is the error colour, for a destructive action.
 */
enum class StudioButtonVariant { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * The button, in whichever shell is showing (`StudioTheme.television`).
 *
 * On a television it is a pill of glass that *becomes* the lit surface when focus is on it — dark
 * text, a lift and a 1.06 growth — whatever its variant, because focus has one look (ADR-046). On a
 * phone it is [PhoneButton], which has no focus state: there the cue (amber) is the primary
 * variant's own fill. Icon-only buttons are round, and say their [label] to TalkBack.
 *
 * Every Material `Button` and `TextButton` in the app came through here in Studio stage 7, which is
 * what lets one screen — the channel menu, an update prompt — be drawn by both shells.
 *
 * @param enabled false draws the button at the canvas's disabled opacity and takes it out of the
 *   focus order, so the remote steps over it rather than landing on something that does nothing
 * @param focusRequester lets a screen land the remote here first
 * @param active a toggle that is on — a favourite that is set (ADR-058). On a television it is the
 *   "current" amber: amber glass, an amber ring and an amber glyph at rest, and under focus an
 *   amber surface with dark content instead of the white one, so on and off never look alike,
 *   focused or not. On a phone ([PhoneButton]) the glyph and label of a non-primary button take the
 *   cue (amber), as `PButton` draws a toggle that is on.
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
    active: Boolean = false,
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
            active = active,
        )
        return
    }
    var focused by remember { mutableStateOf(false) }
    val studio = StudioTheme.colors
    // A pill (`--st-r-pill`); a button with only a glyph is a circle.
    val shape = CircleShape
    val large = size == StudioButtonSize.LARGE
    // `--st-h-control-l` and `--st-h-control-m`: 80px and 64px at 1080p.
    val height = if (large) 40.dp else 32.dp
    val primary = variant == StudioButtonVariant.PRIMARY
    val ghost = variant == StudioButtonVariant.GHOST
    // Every resting value that has nothing to show is its own colour at alpha zero, never
    // `Color.Transparent` (`Motion.kt`): a ghost button's fill fades out of the lit surface, not
    // into black.
    // The three colours are read where they are drawn, not here: a crossing redraws the button and
    // recomposes nothing. Read here, each frame of one recomposed two buttons at once as focus
    // moved between them, 7–8ms a frame on a Google TV Streamer — over home's fade from the video,
    // as the remote was handed back to "Full screen" (device trace, 0.20.7).
    val fill = animatedColourState(
        when {
            active && focused -> studio.current
            active -> studio.currentGlass
            focused -> studio.cue
            primary -> studio.light.copy(alpha = PRIMARY_GLASS_ALPHA)
            ghost -> studio.control.copy(alpha = 0f)
            else -> studio.control
        },
    )
    val edge = animatedColourState(
        when {
            active && focused -> studio.current
            active -> studio.currentEdge
            focused -> studio.cue
            ghost -> studio.edgeLine.copy(alpha = 0f)
            else -> studio.edgeLine
        },
    )
    val content = animatedColourState(
        when {
            active && focused -> studio.onCurrent
            active -> studio.current
            focused -> studio.onCue
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
            .drawBehind {
                drawRect(fill.value)
                // One width at rest and focused (`Focus.kt`); only the colour crosses. Inside the
                // pill, as `border` draws it.
                val stroke = (if (active) ACTIVE_EDGE else BUTTON_EDGE).toPx()
                drawRoundRect(
                    color = edge.value,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    // `this.size`: the drawing's, not this button's [StudioButtonSize].
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    cornerRadius = CornerRadius((this.size.minDimension - stroke) / 2),
                    style = Stroke(stroke),
                )
            }
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // `clickable` carries the focus target on its own; `focusable()` beside it would cost
            // the remote a second press to pass the button. Disabled, it is not a focus target.
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = if (iconOnly) 0.dp else if (large) 18.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            // The glyph is painted in the content colour as it is drawn, like the fill and the label.
            val glyph = rememberVectorPainter(icon)
            Box(
                modifier = Modifier
                    .size(if (large) 15.dp else 13.dp)
                    .drawBehind {
                        with(glyph) { draw(this@drawBehind.size, colorFilter = ColorFilter.tint(content.value)) }
                    },
            )
        }
        if (!iconOnly) {
            val action = StudioTheme.type.action
            BasicText(
                text = label,
                style = action.copy(
                    fontSize = if (large) action.fontSize else action.fontSize * MEDIUM_LABEL_SCALE,
                    fontWeight = if (focused || primary) FontWeight.Bold else FontWeight.SemiBold,
                ),
                color = { content.value },
                maxLines = 1,
            )
        }
    }
}

/** The television button's edge, at rest and focused alike. */
private val BUTTON_EDGE = 1.dp

/** An [active] button's amber ring: twice the edge, so it reads from the sofa. */
private val ACTIVE_EDGE = 2.dp

/** The medium button's label is 24px against the large one's 28 at 1080p. */
private const val MEDIUM_LABEL_SCALE = 24f / 28f

/** A primary button at rest is brighter glass: white at 24% (`Button.dc.html`). */
private const val PRIMARY_GLASS_ALPHA = 0.24f

/** The canvas's disabled state, the same for a button, a field and a toggle. */
internal const val DISABLED_ALPHA = 0.45f

/** `PButton`'s three heights: 64, 52 and 44dp (`--st-h-control-l`, `--st-h-control-m`). */
internal enum class PhoneButtonSize(val height: Dp, val icon: Dp) {
    XL(64.dp, 30.dp),
    L(52.dp, 22.dp),
    M(44.dp, 18.dp),
}

/**
 * `PButton`'s variants: the cue (amber) for the one main action, thin glass, nothing behind, or
 * thin glass whose label is the error colour.
 */
internal enum class PhoneButtonVariant { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * `PButton`: the button at a phone's scale, always a pill. A primary button is the cue (amber) with
 * a lift, a secondary or danger one thin glass with its lit edge, a ghost one only its icon and
 * label. Icon-only buttons are round. [active] — a toggle that is on — tints a non-primary button's
 * glyph and label with the cue (amber); a primary one is amber already.
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
    active: Boolean = false,
) {
    val studio = StudioTheme.colors
    val shape = CircleShape
    val content = when {
        variant == PhoneButtonVariant.PRIMARY -> studio.onCue
        active -> studio.cue
        variant == PhoneButtonVariant.DANGER -> studio.error
        else -> studio.ink
    }
    val surface = when (variant) {
        PhoneButtonVariant.PRIMARY -> Modifier.shadow(8.dp, shape).clip(shape).background(studio.cue)
        PhoneButtonVariant.SECONDARY, PhoneButtonVariant.DANGER -> Modifier.glass(GlassMaterial.THIN, shape)
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
                    fontWeight = if (variant == PhoneButtonVariant.PRIMARY) FontWeight.Bold else FontWeight.SemiBold,
                ),
                color = content,
                maxLines = 1,
            )
        }
    }
}
