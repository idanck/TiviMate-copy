package com.tivicopy.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import com.tivicopy.player.ui.common.ListRow
import com.tivicopy.player.ui.common.RowTrail
import com.tivicopy.player.ui.common.StudioButton
import com.tivicopy.player.ui.common.StudioButtonSize
import com.tivicopy.player.ui.common.StudioButtonVariant
import com.tivicopy.player.ui.common.StudioDialog
import com.tivicopy.player.ui.common.StudioField
import com.tivicopy.player.ui.common.StudioIcons
import com.tivicopy.player.ui.common.StudioSegmented
import com.tivicopy.player.ui.common.StudioSheet
import com.tivicopy.player.ui.settings.OffsetRow
import com.tivicopy.player.ui.theme.StudioTheme
import kotlinx.coroutines.delay

/*
 * The channel menu and its six sheets, as the canvas draws them (`TvChannelMenu`,
 * `TvChannelAdvanced`, `TvGuideMap`, `TvChannelEdit`, `TvGroups`, `TvGroupMenu`, `TvGroupNew` and
 * their `P` versions; Studio stage 10a). A list of things to do is a [StudioSheet] — a drawer at the
 * end of the screen for the channel menu, a centred panel for the rest on a television, a bottom
 * sheet on a phone — and every entry in one is a [ListRow] with its glyph. A question with one
 * field (naming a group) stays a [StudioDialog].
 */

/**
 * Everything you can do to one channel, in one place (FR-ORG-03..05, FR-ORG-07, FR-ORG-09/10).
 *
 * A column of rows rather than a `DropdownMenu`: this has to be driven by a D-pad on a television
 * as readily as by a finger on a phone, and a focusable row is the shape the rest of the app
 * already navigates with.
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
    StudioSheet(
        onDismissRequest = onDismiss,
        title = channel.displayName,
        number = channel.number.takeIf { it > 0 }?.toString(),
        drawer = true,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ListRow(stringResource(R.string.action_watch), { onPlay(); onDismiss() }, icon = StudioIcons.Play)
            ListRow(
                label = stringResource(
                    if (channel.favourite) R.string.action_unfavourite else R.string.action_favourite,
                ),
                onClick = { onToggleFavourite(); onDismiss() },
                icon = if (channel.favourite) StudioIcons.StarOn else StudioIcons.Star,
            )
            ListRow(
                stringResource(R.string.channel_edit),
                onEdit,
                icon = StudioIcons.Settings,
                trail = RowTrail.CHEVRON,
            )
            ListRow(
                stringResource(R.string.channel_advanced),
                onAdvanced,
                icon = StudioIcons.Guide,
                trail = RowTrail.CHEVRON,
            )
            ListRow(
                label = stringResource(if (channel.hidden) R.string.action_show else R.string.action_hide),
                onClick = { onToggleHidden(); onDismiss() },
                icon = StudioIcons.Hide,
            )
            ListRow(
                label = stringResource(if (channel.locked) R.string.channel_unlock else R.string.channel_lock),
                onClick = { onToggleLocked(); onDismiss() },
                icon = StudioIcons.Lock,
            )
            if (canReorder) {
                ListRow(stringResource(R.string.channel_move_up), { onMove(-1) }, icon = StudioIcons.ChevronUp)
                ListRow(stringResource(R.string.channel_move_down), { onMove(1) }, icon = StudioIcons.ChevronDown)
            } else {
                // Saying why beats a greyed-out row with no explanation at ten feet.
                MenuNote(stringResource(R.string.channel_move_unavailable))
            }
            // A remote has no "tap outside": the drawer's last row closes it. A phone's sheet
            // closes from above it, as sheets do.
            if (StudioTheme.television) {
                ListRow(stringResource(R.string.action_close), onDismiss, icon = StudioIcons.Close)
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

    StudioSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.channel_advanced),
        caption = channel.displayName,
        buttons = { CloseButton(onDismiss) },
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

            MenuNote(stringResource(R.string.decoder_for_channel))
            DecoderChoice(
                title = stringResource(R.string.decoder_video),
                selected = channel.videoDecoder,
            ) { onDecoders(it, channel.audioDecoder) }
            DecoderChoice(
                title = stringResource(R.string.decoder_audio),
                selected = channel.audioDecoder,
            ) { onDecoders(channel.videoDecoder, it) }

            ListRow(
                label = stringResource(R.string.action_map_guide),
                onClick = onBindGuide,
                icon = StudioIcons.Guide,
                sub = channel.resolvedEpgId?.let { stringResource(R.string.guide_mapped, it) },
                trail = RowTrail.CHEVRON,
            )
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

    StudioSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.guide_map_channel, channel.displayName),
        page = true,
        buttons = { CloseButton(onDismiss) },
    ) {
        StudioField(
            value = query,
            onValueChange = { query = it },
            icon = StudioIcons.Search,
            placeholder = stringResource(R.string.action_search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (channel.manualEpgId != null) {
                ListRow(
                    label = stringResource(R.string.guide_unmap),
                    onClick = { onBind(null); onDismiss() },
                    icon = StudioIcons.Close,
                    danger = true,
                )
            }
            entries.forEach { entry ->
                ListRow(
                    label = entry.displayNames.firstOrNull()?.takeIf { it.isNotBlank() } ?: entry.id,
                    sub = entry.id,
                    onClick = {
                        onBind(entry.id)
                        onDismiss()
                    },
                    trail = if (entry.id == channel.resolvedEpgId) RowTrail.CHECK else RowTrail.NONE,
                    selected = entry.id == channel.resolvedEpgId,
                )
            }
            if (query.isNotBlank() && entries.isEmpty()) {
                MenuNote(stringResource(R.string.empty_no_results))
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
    StudioSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.group_options),
        caption = group.name,
        buttons = { CloseButton(onDismiss) },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ListRow(
                label = stringResource(if (group.hidden) R.string.action_show else R.string.action_hide),
                onClick = { onToggleHidden(); onDismiss() },
                icon = StudioIcons.Hide,
            )
            if (group.custom) {
                ListRow(stringResource(R.string.action_edit), onRename, icon = StudioIcons.Settings)
                ListRow(
                    label = stringResource(R.string.group_delete),
                    onClick = { onDelete(); onDismiss() },
                    icon = StudioIcons.Trash,
                    danger = true,
                )
            } else {
                MenuNote(stringResource(R.string.group_rename_provider), info = true)
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
 *
 * Split as the canvas splits it (`TvGroups`): the playlist's own groups, then the ones the user
 * made, so it is clear which can be renamed.
 */
@Composable
fun GroupManagerDialog(
    groups: List<ChannelGroup>,
    onDismiss: () -> Unit,
    onNewGroup: () -> Unit,
    onGroupOptions: (ChannelGroup) -> Unit,
) {
    StudioSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.group_manage),
        buttons = { CloseButton(onDismiss) },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ListRow(stringResource(R.string.group_new), onNewGroup, icon = StudioIcons.Plus)
            val (custom, provided) = groups.partition { it.custom }
            if (provided.isNotEmpty()) {
                MenuHeading(stringResource(R.string.group_section_playlist))
                provided.forEach { GroupRow(it, StudioIcons.Tv, onGroupOptions) }
            }
            if (custom.isNotEmpty()) {
                MenuHeading(stringResource(R.string.group_section_custom))
                custom.forEach { GroupRow(it, StudioIcons.Grid, onGroupOptions) }
            }
        }
    }
}

/** One group in the manager. A hidden one says so, with the hide glyph in place of its own. */
@Composable
private fun GroupRow(
    group: ChannelGroup,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onGroupOptions: (ChannelGroup) -> Unit,
) {
    ListRow(
        label = group.name,
        onClick = { onGroupOptions(group) },
        icon = if (group.hidden) StudioIcons.Hide else icon,
        sub = if (group.hidden) stringResource(R.string.group_hidden) else null,
        trail = RowTrail.CHEVRON,
    )
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

    StudioSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.channel_edit),
        caption = channel.displayName,
        page = true,
        buttons = {
            StudioButton(
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
                size = sheetButtonSize(),
            )
            StudioButton(
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = StudioButtonVariant.GHOST,
                size = sheetButtonSize(),
            )
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
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
            MenuHeading(stringResource(R.string.channel_group), inset = false)
            val ungrouped: List<Pair<Long?, String>> =
                listOf(null to stringResource(R.string.channel_group_none))
            (ungrouped + groups.map { it.id to it.name }).forEach { (id, label) ->
                ListRow(
                    label = label,
                    onClick = { groupId = id },
                    selected = id == groupId,
                    trail = if (id == groupId) RowTrail.CHECK else RowTrail.NONE,
                )
            }
            ListRow(
                label = stringResource(R.string.channel_reset),
                onClick = { onReset(); onDismiss() },
                icon = StudioIcons.Restart,
                danger = true,
            )
        }
    }
}

/** A sheet's ghost "close" button: a remote has no tap outside to close it with. */
@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    StudioButton(
        label = stringResource(R.string.action_close),
        onClick = onDismiss,
        variant = StudioButtonVariant.GHOST,
        size = sheetButtonSize(),
    )
}

/** A sheet's buttons: the canvas's medium on a television, its large on a phone (`PChannelEdit`). */
@Composable
private fun sheetButtonSize(): StudioButtonSize =
    if (StudioTheme.television) StudioButtonSize.MEDIUM else StudioButtonSize.LARGE

/** A group of rows' heading inside a sheet (`TvGroups`' "from the playlist"). */
@Composable
private fun MenuHeading(text: String, inset: Boolean = true) {
    Text(
        text = text,
        style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
        color = StudioTheme.colors.ink2,
        modifier = Modifier.padding(horizontal = if (inset) 12.dp else 0.dp).padding(top = 8.dp, bottom = 2.dp),
    )
}

/**
 * A line of explanation in a sheet, in the caption step: why a control is missing, what a section
 * does. [info] puts the info glyph before it, as the canvas does for the longer ones.
 */
@Composable
private fun MenuNote(text: String, info: Boolean = false) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (info) {
            Icon(
                imageVector = StudioIcons.Info,
                contentDescription = null,
                tint = StudioTheme.colors.ink3,
                modifier = Modifier.padding(top = 1.dp).size(if (StudioTheme.television) 11.dp else 16.dp),
            )
        }
        Text(text = text, style = StudioTheme.type.caption, color = StudioTheme.colors.ink2)
    }
}
