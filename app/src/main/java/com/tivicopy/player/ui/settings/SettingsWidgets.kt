package com.tivicopy.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tivicopy.player.ui.common.ListRow
import com.tivicopy.player.ui.common.RowTrail
import com.tivicopy.player.ui.theme.StudioTheme
import com.tivicopy.player.ui.common.StudioIcons

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

@Composable
fun SettingsSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = StudioTheme.type.rowLabel, color = StudioTheme.colors.ink)
            Text(valueLabel, style = StudioTheme.type.meta, color = StudioTheme.colors.ink2)
        }
        val studio = StudioTheme.colors
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            // Ink on the track, as Studio's progress bar is: the cue is reserved for focus.
            colors = SliderDefaults.colors(
                thumbColor = studio.ink,
                activeTrackColor = studio.ink,
                inactiveTrackColor = studio.track,
                activeTickColor = studio.ground,
                inactiveTickColor = studio.ink3,
            ),
        )
    }
    RowDivider()
}
