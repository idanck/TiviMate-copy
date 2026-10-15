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
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.SectionHeader
import com.tivicopy.player.ui.common.StudioToggle
import com.tivicopy.player.ui.theme.StudioTheme
import com.tivicopy.player.ui.common.StudioIcons

/**
 * A thin wrapper over the shared [SectionHeader] with Settings' own, more prominent look (it
 * doubles as each section's page title, not just a mid-list group label) — a single header
 * implementation, so it draws the same top divider between categories as every other menu.
 * [topDivider] should be `false` only for the header that opens a whole Settings screen, where a
 * rule above it would imply content that is not there.
 */
@Composable
fun SettingsSectionTitle(text: String, topDivider: Boolean = true) {
    SectionHeader(
        text = text,
        modifier = if (topDivider) Modifier else Modifier.padding(top = 12.dp),
        // Studio's section heading (`canvas/Settings.dc.html`): the h1 step in ink. It used to be
        // `primary`, which is now the cue — the focus colour — and a heading is never focus.
        style = StudioTheme.type.h1,
        color = StudioTheme.colors.ink,
        topDivider = topDivider,
    )
}

/**
 * The space between adjacent options. Studio's rows are separated by a gap rather than a rule
 * (`--st-gap-row`): each row is its own card when focused, and a line under it would cut through
 * the card.
 */
@Composable
private fun RowDivider() {
    Spacer(modifier = Modifier.height(4.dp))
}

/**
 * `onClick` is the last parameter on purpose: a trailing lambda at a call site must be the
 * click action, not composable content that would then run during composition.
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    /** Studio's `ListRow` glyph at the start; the section rows carry one (`canvas/Settings`). */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** The section shown beside the list in the two-pane layout. */
    selected: Boolean = false,
    onClick: () -> Unit = {},
) {
    FocusableRow(onClick = onClick, contentDescription = title, selected = selected) {
        if (icon != null) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = StudioTheme.colors.ink2,
                modifier = Modifier.size(SETTINGS_ICON),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = StudioTheme.type.rowLabel, color = StudioTheme.colors.ink)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = StudioTheme.type.caption.copy(fontWeight = FontWeight.Normal),
                    color = StudioTheme.colors.ink2,
                )
            }
        }
        trailing()
    }
    RowDivider()
}

@Composable
fun SettingsSwitch(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        trailing = { StudioToggle(checked = checked) },
        onClick = { onChange(!checked) },
    )
}

/** A radio list; used for every enum-valued setting so the pattern is identical throughout. */
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
            style = StudioTheme.type.rowLabel,
            color = StudioTheme.colors.ink2,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp),
        )
        // Studio's choice is a row with a check at its end (`ListRow`'s `check` trail), not a
        // form's radio button: this is a list of what is on.
        options.forEach { (value, label) ->
            FocusableRow(onClick = { onSelect(value) }, selected = value == selected, contentDescription = label) {
                Text(
                    label,
                    style = StudioTheme.type.body,
                    color = StudioTheme.colors.ink,
                    modifier = Modifier.weight(1f),
                )
                if (value == selected) {
                    Icon(
                        imageVector = StudioIcons.Check,
                        contentDescription = null,
                        tint = StudioTheme.colors.ink,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
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

/** The glyph on a section row. */
private val SETTINGS_ICON = 20.dp
