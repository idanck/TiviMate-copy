package com.tivicopy.player.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tivicopy.player.ui.common.ListRow
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.RowTrail
import com.tivicopy.player.ui.common.StudioIcons
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.animatedColour
import com.tivicopy.player.ui.common.focusLift
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.theme.StudioTheme
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A heading in a settings section, as the canvas draws the two (`TvSettingsSources`).
 *
 * The one that opens a section ([topDivider] false) is its page title: the h1 step in ink. One
 * between groups of options inside it ([topDivider] true) is a quiet label — the meta step,
 * semibold, in the secondary ink, with space above it rather than a rule: each row is its own card
 * when focused, and a line across the panel would cut through it.
 */
@Composable
fun SettingsSectionTitle(text: String, topDivider: Boolean = true) {
    if (topDivider) {
        Text(
            text = text,
            style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = StudioTheme.colors.ink2,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 2.dp),
        )
    } else {
        Text(
            text = text,
            style = StudioTheme.type.h1,
            color = StudioTheme.colors.ink,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        )
    }
}

/**
 * The space between adjacent options. Studio's rows are separated by a gap rather than a rule
 * (`--st-gap-row`): each row is its own card when focused, and a line under it would cut through
 * the card.
 */
@Composable
internal fun RowDivider() {
    Spacer(modifier = Modifier.height(4.dp))
}

/**
 * One setting: Studio's [ListRow] with a settings section's habits — a description under the
 * label, a value or a chevron at the end, and the gap after it.
 *
 * `onClick` is the last parameter on purpose: a trailing lambda at a call site must be the
 * click action, not composable content that would then run during composition.
 *
 * @param value the setting's current value, shown at the end in the secondary ink
 * @param chevron that the row opens something further in rather than acting in place
 * @param danger a destructive action: the label in the error colour
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    /** Studio's `ListRow` glyph at the start; the section rows carry one (`canvas/Settings`). */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    value: String? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
    /** The section shown beside the list in the two-pane layout. */
    selected: Boolean = false,
    onClick: () -> Unit = {},
) {
    ListRow(
        label = title,
        onClick = onClick,
        sub = subtitle?.takeIf { it.isNotBlank() },
        icon = icon,
        trail = when {
            value != null -> RowTrail.VALUE
            chevron -> RowTrail.CHEVRON
            else -> RowTrail.NONE
        },
        value = value,
        danger = danger,
        selected = selected,
    )
    RowDivider()
}

@Composable
fun SettingsSwitch(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(
        label = title,
        onClick = { onChange(!checked) },
        sub = subtitle?.takeIf { it.isNotBlank() },
        trail = RowTrail.TOGGLE,
        toggled = checked,
    )
    RowDivider()
}

/**
 * A radio list; used for every enum-valued setting so the pattern is identical throughout.
 *
 * Studio's choice is a row with a check at its end (`ListRow`'s `check` trail), not a form's
 * radio button: this is a list of what is on.
 */
@Composable
fun <T> SettingsChoice(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = StudioTheme.colors.ink2,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
        )
        options.forEach { (value, label) ->
            ListRow(
                label = label,
                onClick = { onSelect(value) },
                selected = value == selected,
                trail = if (value == selected) RowTrail.CHECK else RowTrail.NONE,
            )
        }
    }
    RowDivider()
}

/**
 * A number set along a range: its label and value over Studio's track.
 *
 * The whole row is one focus target, drawn as every other row is ([focusSurface]), and the
 * remote's left and right move the value one [keyStep] at a time — in the direction the track is
 * drawn, so in Hebrew, where it runs right to left, left raises it. Held, a key repeats, and over
 * a long range it soon moves five steps a press. Up and down are left alone, so focus goes on to
 * the next row as it does from any other.
 *
 * Material's slider is still what is drawn, and what a finger drags on a phone, but it never takes
 * focus itself. On its own it showed no focus at all, and with no steps its arrow keys moved by 1%
 * of the range, which a setting stored as a whole number truncated straight back to where it was:
 * on a television the row looked dead.
 *
 * Focused, the row is the lit surface (`Slider`, ADR-046): the label, the value between two
 * chevrons (each dimmed once the value is at the end it points to), the thumb and the track all
 * turn dark on it.
 *
 * @param keyStep how far one press of the remote moves the value. Defaults to one of [steps] when
 *   there are some and to a twentieth of the range otherwise; a setting stored as a whole number
 *   needs a whole step.
 */
@Composable
fun SettingsSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    keyStep: Float = defaultKeyStep(range, steps),
    onChange: (Float) -> Unit,
) {
    val studio = StudioTheme.colors
    val tv = StudioTheme.television
    val metrics = LocalTvMetrics.current
    val shape = RoundedCornerShape(metrics.rowCorner)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var focused by remember { mutableStateOf(false) }
    // Read when the key arrives rather than when the row was composed: a held key can repeat
    // before the new value has come back round from the settings.
    val latest by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusLift(focused, scale = metrics.rowFocusScale, shape = shape)
            // A control (`Slider`): the whole row becomes the lit surface, and everything on it
            // turns dark, the track included.
            .focusSurface(focused = focused, shape = shape, fill = FocusFill.LIT)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val towardsEnd = when (event.key) {
                    Key.DirectionRight -> !rtl
                    Key.DirectionLeft -> rtl
                    else -> return@onKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    val times = keyStepMultiplier(event.nativeKeyEvent.repeatCount, range, keyStep)
                    val next = steppedValue(latest, towardsEnd, keyStep, range, times)
                    if (next != latest) change(next)
                }
                // The release as well, so no half of the press reaches the focus search.
                true
            }
            .focusable()
            .semantics { contentDescription = "$title, $valueLabel" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = StudioTheme.type.rowLabel.copy(fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold),
                color = animatedColour(if (focused) studio.onCue else studio.ink),
                modifier = Modifier.weight(1f),
            )
            if (tv) {
                // Laid out at rest too, only invisible, so the value does not jump sideways when
                // focus arrives. `ChevronEnd` turned over is the one towards the start: the icon
                // set draws only the canvas's glyphs, and the canvas has the one chevron.
                SliderChevron(
                    focused = focused,
                    atLimit = value <= range.start,
                    modifier = Modifier.graphicsLayer { scaleX = -1f },
                )
            }
            Text(
                valueLabel,
                style = StudioTheme.type.meta,
                color = animatedColour(if (focused) studio.onCue else studio.ink2),
            )
            if (tv) SliderChevron(focused = focused, atLimit = value >= range.endInclusive)
        }
        val thumb = animatedColour(if (focused) studio.onCue else studio.ink)
        val rest = animatedColour(if (focused) studio.onCue.copy(alpha = 0.16f) else studio.track)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            // The row takes focus and the keys; the slider would otherwise be a second, invisible
            // stop on the way down the list, and handle the keys its own way.
            modifier = Modifier.focusProperties { canFocus = false },
            // Ink on the track, as the progress bar is; dark on the lit surface.
            colors = SliderDefaults.colors(
                thumbColor = thumb,
                activeTrackColor = thumb,
                inactiveTrackColor = rest,
                activeTickColor = studio.ground,
                inactiveTickColor = studio.ink3,
            ),
        )
    }
    RowDivider()
}

/** One of the chevrons either side of a focused slider's value; dim at the end it points to. */
@Composable
private fun SliderChevron(focused: Boolean, atLimit: Boolean, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    // On the lit surface: dark, and dimmed at the end it points to.
    val tint = when {
        !focused -> studio.onCue.copy(alpha = 0f)
        atLimit -> studio.onCue.copy(alpha = 0.35f)
        else -> studio.onCue
    }
    Icon(
        imageVector = StudioIcons.ChevronEnd,
        contentDescription = null,
        tint = animatedColour(tint),
        modifier = modifier.size(13.dp),
    )
}

/** One step of a slider with [steps], or a twentieth of a range without any. */
internal fun defaultKeyStep(range: ClosedFloatingPointRange<Float>, steps: Int): Float =
    (range.endInclusive - range.start) / (if (steps > 0) steps + 1 else 20)

/**
 * Where one press of the remote takes [value]: [times] steps of [step] towards the end of [range]
 * or its start, on the grid of whole steps counted from the start, and no further than either end.
 * A value between two grid points — a phone's drag — goes to the next one that way, never back.
 */
internal fun steppedValue(
    value: Float,
    towardsEnd: Boolean,
    step: Float,
    range: ClosedFloatingPointRange<Float>,
    times: Int = 1,
): Float {
    // Within a hair of a grid point counts as on it: 0.3 + 0.05 × 7 is not quite 0.65 in a float.
    val position = (value - range.start) / step
    val index = if (towardsEnd) {
        floor(position + GRID_SLACK).toInt() + times
    } else {
        ceil(position - GRID_SLACK).toInt() - times
    }
    return (range.start + index * step).coerceIn(range)
}

/**
 * Five steps a press once a key has been held for [ACCELERATE_AFTER] repeats — about a second
 * — on a range longer than [LONG_RANGE_STEPS] steps; one otherwise, so a short range is never
 * overshot.
 */
internal fun keyStepMultiplier(repeatCount: Int, range: ClosedFloatingPointRange<Float>, step: Float): Int =
    if (repeatCount >= ACCELERATE_AFTER && (range.endInclusive - range.start) / step > LONG_RANGE_STEPS) 5 else 1

private const val ACCELERATE_AFTER = 10
private const val LONG_RANGE_STEPS = 40

/** A thousandth of a step: float error, not a position between two grid points. */
private const val GRID_SLACK = 0.001f
