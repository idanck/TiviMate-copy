package com.tivicopy.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.ChannelGroup
import com.tivicopy.player.data.model.DecoderMode
import com.tivicopy.player.data.model.EpgChannel
import com.tivicopy.player.ui.common.DialogButton
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.StudioButtonVariant
import com.tivicopy.player.ui.common.StudioDialog
import com.tivicopy.player.ui.common.StudioField
import com.tivicopy.player.ui.common.StudioSegmented
import com.tivicopy.player.ui.settings.OffsetRow
import com.tivicopy.player.ui.settings.SettingsChoice
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.delay

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
    onAdvanced: () -> Unit,
    onMove: (Int) -> Unit,
) {
    StudioDialog(
        onDismissRequest = onDismiss,
        title = channel.displayName,
        buttons = { DialogButton(label = stringResource(R.string.action_close), onClick = onDismiss) },
    ) {
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
            MenuRow(stringResource(R.string.channel_advanced)) { onAdvanced() }
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
    }
}

/**
 * The per-channel overrides that are not about identity: a guide offset, a decoder choice, and
 * which guide entry the channel is bound to (FR-EPG-08, FR-EPG-12, FR-PLB-04).
 *
 * All three have been implemented in the view model since the first release with nothing able to
 * call them. They live behind the editor rather than in the main menu because they are the
 * controls a user reaches for when something is wrong — a guide an hour out, a channel that only
 * decodes in software — not part of everyday use.
 */
@Composable
fun ChannelAdvancedDialog(
    channel: Channel,
    onDismiss: () -> Unit,
    onOffset: (Int?) -> Unit,
    onDecoders: (DecoderMode, DecoderMode) -> Unit,
    onBindGuide: () -> Unit,
) {
    var offset by remember(channel.id) { mutableStateOf(channel.epgOffsetMinutes) }

    StudioDialog(
        onDismissRequest = onDismiss,
        title = channel.displayName,
        buttons = { DialogButton(label = stringResource(R.string.action_close), onClick = onDismiss) },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OffsetRow(
                title = stringResource(R.string.settings_epg_offset_channel),
                minutes = offset ?: 0,
            ) { minutes ->
                // Zero means "no override", so the channel falls back to the global offset
                // rather than pinning itself to the same value for ever.
                offset = minutes.takeIf { it != 0 }
                onOffset(offset)
            }

            Text(
                text = stringResource(R.string.decoder_for_channel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            DecoderChoice(
                title = stringResource(R.string.decoder_video),
                selected = channel.videoDecoder,
            ) { onDecoders(it, channel.audioDecoder) }
            DecoderChoice(
                title = stringResource(R.string.decoder_audio),
                selected = channel.audioDecoder,
            ) { onDecoders(channel.videoDecoder, it) }

            MenuRow(
                channel.resolvedEpgId
                    ?.let { stringResource(R.string.guide_mapped, it) }
                    ?: stringResource(R.string.action_map_guide),
            ) { onBindGuide() }
        }
    }
}

/**
 * One decoder, as the canvas draws it (`TvChannelAdvanced`, `PChannelAdvanced`): a label over a
 * `Segmented` of three. Three short, exclusive options are a choice to see at once, not a list.
 */
@Composable
private fun DecoderChoice(title: String, selected: DecoderMode, onSelect: (DecoderMode) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = StudioTheme.colors.ink2,
        )
        StudioSegmented(options = decoderOptions(), selected = selected, onSelect = onSelect)
    }
}

@Composable
private fun decoderOptions(): List<Pair<DecoderMode, String>> = listOf(
    DecoderMode.AUTO to stringResource(R.string.decoder_auto),
    DecoderMode.HARDWARE to stringResource(R.string.decoder_hardware),
    DecoderMode.SOFTWARE to stringResource(R.string.decoder_software),
)

/**
 * FR-EPG-08: binding a channel to a guide entry by hand, for the ones the matcher could not
 * place. The binding is sticky — a refresh does not clear it.
 */
@Composable
fun GuideBindingDialog(
    channel: Channel,
    onDismiss: () -> Unit,
    onSearch: suspend (String) -> List<EpgChannel>,
    onBind: (String?) -> Unit,
) {
    var query by remember { mutableStateOf(channel.displayName) }
    var entries by remember { mutableStateOf<List<EpgChannel>>(emptyList()) }

    LaunchedEffect(query) {
        delay(250)
        entries = if (query.isBlank()) emptyList() else onSearch(query)
    }

    StudioDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.guide_map_channel, channel.displayName),
        buttons = { DialogButton(label = stringResource(R.string.action_close), onClick = onDismiss) },
    ) {

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StudioField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.action_search),
                icon = Icons.Filled.Search,
                modifier = Modifier.fillMaxWidth(),
            )
            if (channel.manualEpgId != null) {
                MenuRow(stringResource(R.string.guide_unmap)) { onBind(null); onDismiss() }
            }
            entries.forEach { entry ->
                MenuRow(entry.displayNames.firstOrNull()?.takeIf { it.isNotBlank() } ?: entry.id) {
                    onBind(entry.id)
                    onDismiss()
                }
            }
            if (query.isNotBlank() && entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.empty_no_results),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    
    }
}

/**
 * What can be done to a group (FR-ORG-02, FR-ORG-03).
 *
 * Renaming and deleting are offered for the user's own groups only. A group that came with the
 * playlist is matched by name on every refresh, so renaming one would orphan it and have the
 * provider's version recreated beside it — that needs a column remembering the original name, and
 * a migration to add it. The dialog says so rather than offering a control that would misbehave.
 */
@Composable
fun GroupMenuDialog(
    group: ChannelGroup,
    onDismiss: () -> Unit,
    onToggleHidden: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    StudioDialog(
        onDismissRequest = onDismiss,
        title = group.name,
        buttons = { DialogButton(label = stringResource(R.string.action_close), onClick = onDismiss) },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MenuRow(
                stringResource(if (group.hidden) R.string.action_show else R.string.action_hide),
            ) { onToggleHidden(); onDismiss() }
            if (group.custom) {
                MenuRow(stringResource(R.string.action_edit)) { onRename() }
                MenuRow(stringResource(R.string.group_delete)) { onDelete(); onDismiss() }
            } else {
                Text(
                    text = stringResource(R.string.group_rename_provider),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Every group in one list, for the compact layout.
 *
 * The wide layout puts groups in a column where each row takes a long press, but a phone gets a
 * chip row instead, and a `FilterChip` has no secondary action to hang this on. Without it,
 * creating, hiding, renaming and deleting a group — all of FR-ORG-02 and half of FR-ORG-03 —
 * were reachable on a television and on nothing else.
 */
@Composable
fun GroupManagerDialog(
    groups: List<ChannelGroup>,
    onDismiss: () -> Unit,
    onNewGroup: () -> Unit,
    onGroupOptions: (ChannelGroup) -> Unit,
) {
    StudioDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.group_manage),
        buttons = { DialogButton(label = stringResource(R.string.action_close), onClick = onDismiss) },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MenuRow(stringResource(R.string.group_new)) { onNewGroup() }
            groups.forEach { group ->
                FocusableRow(
                    onClick = { onGroupOptions(group) },
                    contentDescription = group.name,
                ) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleSmall,
                        // Same dimming as the group column, so a hidden group reads the same
                        // way wherever it is listed.
                        color = if (group.hidden) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

/** A single-field dialog, used for naming a new group and for renaming a custom one. */
@Composable
fun TextEntryDialog(
    title: String,
    label: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    StudioDialog(
        onDismissRequest = onDismiss,
        title = title,
        buttons = {
            DialogButton(
                label = stringResource(R.string.action_save),
                onClick = { onConfirm(value.trim()); onDismiss() },
                variant = StudioButtonVariant.PRIMARY,
                enabled = value.isNotBlank(),
            )
            DialogButton(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    ) {
        StudioField(
            value = value,
            onValueChange = { value = it },
            label = label,
            modifier = Modifier.fillMaxWidth(),
        )
    }
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

    StudioDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.channel_edit),
        buttons = {
            DialogButton(
                label = stringResource(R.string.action_save),
                onClick = {
                    onSave(
                        name.trim().takeIf { it.isNotBlank() },
                        logo.trim().takeIf { it.isNotBlank() },
                        number.toIntOrNull() ?: 0,
                        groupId,
                    )
                    onDismiss()
                },
                variant = StudioButtonVariant.PRIMARY,
            )
            DialogButton(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    ) {

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StudioField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.channel_custom_name),
                placeholder = channel.name,
                modifier = Modifier.fillMaxWidth(),
            )
            StudioField(
                value = logo,
                onValueChange = { logo = it },
                label = stringResource(R.string.channel_custom_logo),
                placeholder = channel.logoUrl.orEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
            StudioField(
                value = number,
                onValueChange = { entered -> number = entered.filter(Char::isDigit).take(4) },
                label = stringResource(R.string.channel_number),
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
    
    }
}
