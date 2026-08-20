package com.tivicopy.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.ChannelGroup
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.settings.SettingsChoice

/**
 * Everything you can do to one channel, in one place (FR-ORG-03..05, FR-ORG-07, FR-ORG-09/10).
 *
 * A column of [FocusableRow]s rather than a `DropdownMenu`: this has to be driven by a D-pad on a
 * television as readily as by a finger on a phone, and a focusable row is the shape the rest of
 * the app already navigates with.
 *
 * Reordering is offered only where the list on screen is in the user's own order. In Favourites
 * the neighbouring row is not a neighbour in the full list, so a swap would fling the channel
 * across the playlist; in Recently watched the order comes from watch times, so a swap would
 * change nothing visible and quietly reorder somewhere else.
 */
@Composable
fun ChannelMenuDialog(
    channel: Channel,
    canReorder: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleLocked: () -> Unit,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(channel.displayName, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MenuRow(stringResource(R.string.action_watch)) { onPlay(); onDismiss() }
                MenuRow(
                    stringResource(
                        if (channel.favourite) R.string.action_unfavourite else R.string.action_favourite,
                    ),
                ) { onToggleFavourite(); onDismiss() }
                MenuRow(stringResource(R.string.channel_edit)) { onEdit() }
                MenuRow(
                    stringResource(if (channel.hidden) R.string.action_show else R.string.action_hide),
                ) { onToggleHidden(); onDismiss() }
                MenuRow(
                    stringResource(if (channel.locked) R.string.channel_unlock else R.string.channel_lock),
                ) { onToggleLocked(); onDismiss() }

                if (canReorder) {
                    MenuRow(stringResource(R.string.channel_move_up)) { onMove(-1) }
                    MenuRow(stringResource(R.string.channel_move_down)) { onMove(1) }
                } else {
                    // Saying why beats a greyed-out row with no explanation at ten feet.
                    Text(
                        text = stringResource(R.string.channel_move_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    FocusableRow(onClick = onClick, contentDescription = label) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The local overrides a channel carries: a name, a logo, a number and a group (FR-ORG-04,
 * FR-ORG-09, FR-ORG-10, FR-ORG-02).
 *
 * Each field's placeholder is the provider's own value, so an empty field reads as "use what the
 * provider sent" rather than as a mistake. None of this touches the source: a refresh updates the
 * provider fields and leaves every one of these alone.
 */
@Composable
fun ChannelEditDialog(
    channel: Channel,
    groups: List<ChannelGroup>,
    onDismiss: () -> Unit,
    onSave: (name: String?, logo: String?, number: Int, groupId: Long?) -> Unit,
    onReset: () -> Unit,
) {
    var name by remember(channel.id) { mutableStateOf(channel.customName.orEmpty()) }
    var logo by remember(channel.id) { mutableStateOf(channel.customLogoUrl.orEmpty()) }
    var number by remember(channel.id) { mutableStateOf(channel.number.takeIf { it > 0 }?.toString().orEmpty()) }
    var groupId by remember(channel.id) { mutableStateOf(channel.groupId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_edit), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.channel_custom_name)) },
                    placeholder = { Text(channel.name) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = logo,
                    onValueChange = { logo = it },
                    label = { Text(stringResource(R.string.channel_custom_logo)) },
                    placeholder = { Text(channel.logoUrl.orEmpty()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { entered -> number = entered.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.channel_number)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                val ungrouped: List<Pair<Long?, String>> =
                    listOf(null to stringResource(R.string.channel_group_none))
                SettingsChoice(
                    title = stringResource(R.string.channel_group),
                    options = ungrouped + groups.map { it.id to it.name },
                    selected = groupId,
                    onSelect = { groupId = it },
                )
                FocusableRow(onClick = { onReset(); onDismiss() }, contentDescription = stringResource(R.string.channel_reset)) {
                    Text(
                        text = stringResource(R.string.channel_reset),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name.trim().takeIf { it.isNotBlank() },
                        logo.trim().takeIf { it.isNotBlank() },
                        number.toIntOrNull() ?: 0,
                        groupId,
                    )
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}
