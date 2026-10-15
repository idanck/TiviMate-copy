package com.tivicopy.player.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tivicopy.player.ui.theme.StudioTheme

/** The two button heights the canvas draws (`--st-h-control-l`, `--st-h-control-m`). */
enum class StudioButtonSize { LARGE, MEDIUM }

/**
 * Studio's button (`docs/design/studio/canvas/Button.dc.html`), for the ten-foot shell.
 *
 * A translucent control with a hairline edge at rest — it sits over live video, where a plate with
 * no edge bleeds into a bright frame — that *becomes* the cue when focus is on it, lifting with a
 * shadow. [primary] is the canvas's primary variant: ink at rest, for the one action a screen is
 * built around. Icon-only buttons are square and say their [label] to TalkBack.
 *
 * @param focusRequester lets a screen land the remote here first
 */
@Composable
fun StudioButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconOnly: Boolean = false,
    primary: Boolean = false,
    size: StudioButtonSize = StudioButtonSize.LARGE,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LocalTvMetrics.current.controlCorner)
    val large = size == StudioButtonSize.LARGE
    val height = if (large) 42.dp else 32.dp
    val content = filledContentColour(focused, selected = primary)
    Row(
        modifier = modifier
            .height(height)
            .defaultMinSize(minWidth = if (iconOnly) height else 0.dp)
            .focusLift(focused, shape = shape)
            .focusSurface(focused = focused, shape = shape, selected = primary, fill = FocusFill.FILLED)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // `clickable` carries the focus target on its own; `focusable()` beside it would cost
            // the remote a second press to pass the button.
            .clickable(onClick = onClick)
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
