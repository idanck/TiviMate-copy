package com.tivicopy.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.SectionHeader

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
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        topDivider = topDivider,
    )
}

/** A low-emphasis rule between adjacent options, so two unrelated settings never sit flush. */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
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
    onClick: () -> Unit = {},
) {
    FocusableRow(onClick = onClick, contentDescription = title) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
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
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp),
        )
        options.forEach { (value, label) ->
            FocusableRow(onClick = { onSelect(value) }, selected = value == selected, contentDescription = label) {
                RadioButton(selected = value == selected, onClick = { onSelect(value) })
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
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
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
    RowDivider()
}
