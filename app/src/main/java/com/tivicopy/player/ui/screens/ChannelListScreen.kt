package com.tivicopy.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.ChannelGroup
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.VirtualGroup
import com.tivicopy.player.ui.common.Badge
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.EmptyState
import com.tivicopy.player.ui.common.FocusableRow
import com.tivicopy.player.ui.common.LiveBadge
import com.tivicopy.player.ui.common.ListRow
import com.tivicopy.player.ui.common.ProgrammeProgress
import com.tivicopy.player.ui.common.RowTrail
import com.tivicopy.player.ui.common.SectionHeader
import com.tivicopy.player.ui.common.StudioButton
import com.tivicopy.player.ui.common.StudioButtonSize
import com.tivicopy.player.ui.common.StudioButtonVariant
import com.tivicopy.player.ui.common.StudioField
import com.tivicopy.player.ui.common.TwoLine
import com.tivicopy.player.ui.theme.StudioTheme
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.mobile.PhoneChip
import kotlinx.coroutines.delay
import com.tivicopy.player.ui.common.StudioIcons

/**
 * Groups on one side, channels in the middle, now/next on the other (docs/03 section 3.2).
 *
 * The same composable serves the TV overlay and the phone's Live tab; [compact] drops the
 * group column on narrow screens, where the group filter moves into a chip row instead.
 */
@Composable
fun ChannelListScreen(
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showNowNext: Boolean = true,
    /**
     * Whether to draw a filter field. Touch gets one; the ten-foot shell does not, because a text
     * field inside a D-pad overlay takes the first focus and turns the remote into a keyboard.
     * There, [FilterBanner] reports an active filter instead and the remote's Search key opens the
     * search overlay.
     */
    showFilter: Boolean = compact,
    /**
     * Opens the full search surface from a row at the head of the group column. Null hides the
     * entry point — the television, whose side menu carries search on every screen (ADR-035).
     */
    onSearch: (() -> Unit)? = null,
    /**
     * Opens the add-source screen, from the empty list's own button (`TvEmptyChannels`). Null
     * leaves the empty state with its sentence alone — the radio, whose stations come bundled.
     */
    onAddSource: (() -> Unit)? = null,
    /**
     * Opens the list scrolled to the playing channel instead of the top. One-shot per
     * appearance: a later filter or group change never fights the user's own scrolling.
     */
    scrollToCurrent: Boolean = false,
    /**
     * Additionally puts D-pad focus on the playing channel's row once anchored. TV only —
     * on touch there is no focus to place.
     */
    focusCurrent: Boolean = false,
    /**
     * Draws what each channel is showing right now as the row's second line. Defaults to the
     * compact layout, which has no now/next pane to say it instead.
     */
    showNowLine: Boolean = compact,
    /**
     * Radio only (ADR-015): the "Categories" section becomes National/Regional/genre buckets,
     * classified client-side from the station data by [com.tivicopy.player.data.radio.
     * RadioCategoryClassifier], instead of the playlist's raw provider groups.
     */
    isRadio: Boolean = false,
    /**
     * Opens the guide on a channel, from the "schedule" button at the foot of the television's
     * preview (`canvas/Channels.dc.html`). Null draws no button — the radio and the phone.
     */
    onOpenGuide: ((Channel) -> Unit)? = null,
) {
    val state by vm.state.collectAsState()
    var previewChannel by remember { mutableStateOf<Channel?>(null) }
    var preview by remember { mutableStateOf<Pair<Programme?, Programme?>>(null to null) }
    // Both shells render this screen, so the per-channel dialogs live here rather than being
    // hoisted twice into TvRoot and MobileRoot.
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    var editing by remember { mutableStateOf<Channel?>(null) }
    var advancedFor by remember { mutableStateOf<Channel?>(null) }
    var bindingGuideFor by remember { mutableStateOf<Channel?>(null) }
    var groupMenuFor by remember { mutableStateOf<ChannelGroup?>(null) }
    var renamingGroup by remember { mutableStateOf<ChannelGroup?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var managingGroups by remember { mutableStateOf(false) }
    // The one-shot for [scrollToCurrent], held at screen level rather than inside the list
    // branch: a filter that momentarily empties the list tears that branch down, and a guard
    // living there would reset and re-hijack the scroll when the list refills.
    var anchored by remember { mutableStateOf(false) }

    LaunchedEffect(previewChannel?.id) {
        val channel = previewChannel
        preview = if (channel == null) null to null else {
            val list = vm.programmesFor(
                channel,
                System.currentTimeMillis() - 60 * 60_000L,
                System.currentTimeMillis() + 6 * 60 * 60_000L,
            )
            val now = list.firstOrNull { System.currentTimeMillis() in it.startUtc until it.endUtc }
            val next = list.firstOrNull { it.startUtc >= (now?.endUtc ?: System.currentTimeMillis()) }
            now to next
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        if (!compact) {
            GroupColumn(
                vm = vm,
                onSearch = onSearch,
                onNewGroup = { creatingGroup = true },
                onGroupOptions = { groupMenuFor = it },
                isRadio = isRadio,
                modifier = Modifier.width(LocalTvMetrics.current.groupColumnWidth).fillMaxHeight(),
            )
            VerticalDivider()
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (showFilter) {
                FilterField(query = state.list.search, onQuery = vm::setSearch)
            } else {
                FilterBanner(query = state.list.search, onClear = { vm.setSearch("") })
            }
            if (compact) GroupChips(vm, isRadio = isRadio, onManageGroups = { managingGroups = true })
            if (state.list.selectedGroupId == VirtualGroup.HIDDEN && state.list.channels.isNotEmpty()) {
                StudioButton(
                    label = stringResource(R.string.action_show_all),
                    onClick = { vm.showAllHidden() },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    variant = StudioButtonVariant.GHOST,
                    size = StudioButtonSize.MEDIUM,
                )
            }
            val channels = state.list.channels
            if (channels.isEmpty()) {
                EmptyState(
                    text = when {
                        state.list.selectedGroupId == VirtualGroup.HIDDEN ->
                            stringResource(R.string.hidden_channels_empty)
                        state.list.search.isBlank() -> stringResource(R.string.empty_no_channels)
                        else -> stringResource(R.string.empty_no_results)
                    },
                    icon = when {
                        state.list.selectedGroupId == VirtualGroup.HIDDEN -> StudioIcons.Hide
                        state.list.search.isBlank() -> StudioIcons.Tv
                        else -> StudioIcons.Search
                    },
                    // Only for a list that is empty because there is nothing in it: a filter or the
                    // hidden group being empty is not fixed by another playlist.
                    action = stringResource(R.string.playlist_add).takeIf {
                        onAddSource != null &&
                            state.list.search.isBlank() &&
                            state.list.selectedGroupId != VirtualGroup.HIDDEN
                    },
                    onAction = { onAddSource?.invoke() },
                )
            } else {
                val listState = rememberLazyListState()
                val currentFocus = remember { FocusRequester() }

                if (scrollToCurrent && !anchored) {
                    // Keyed on both the list and the playing channel: at startup the list can
                    // land before `resumeStartChannel` has decided what is playing, so the
                    // anchor waits for the channel rather than firing once against null.
                    LaunchedEffect(channels, state.currentChannel?.id) {
                        if (anchored) return@LaunchedEffect
                        val currentId = state.currentChannel?.id ?: return@LaunchedEffect
                        anchored = true
                        val index = channels.indexOfFirst { it.id == currentId }
                        if (index > 0) listState.scrollToItem(index)
                        if (focusCurrent && index >= 0) {
                            // One frame so the scrolled-to row is composed and carries the
                            // requester; if a re-query removed it in between, no focus jump.
                            withFrameNanos { }
                            runCatching { currentFocus.requestFocus() }
                        }
                    }
                }

                // What every visible channel is showing, fetched the way the guide grid does it:
                // the visible rows drive one windowed query (per distinct EPG offset), replaced
                // rather than accumulated, so a thousand-channel list costs what a screenful
                // costs. Quantised to blocks of rows so a fling crosses a handful of keys, not
                // one per row, and debounced so a restart cancels a query the scroll has
                // already made stale.
                var nowByChannel by remember { mutableStateOf<Map<Long, Programme>>(emptyMap()) }
                if (showNowLine) {
                    val visibleBlock by remember {
                        derivedStateOf { listState.firstVisibleItemIndex / NOW_PREFETCH_ROWS }
                    }
                    LaunchedEffect(channels, visibleBlock) {
                        delay(NOW_QUERY_DEBOUNCE_MS)
                        val first = ((visibleBlock - 1) * NOW_PREFETCH_ROWS).coerceAtLeast(0)
                        val last = (visibleBlock * NOW_PREFETCH_ROWS + NOW_VISIBLE_ROWS + NOW_PREFETCH_ROWS)
                            .coerceAtMost(channels.size)
                        if (first >= last) {
                            nowByChannel = emptyMap()
                            return@LaunchedEffect
                        }
                        val now = System.currentTimeMillis()
                        nowByChannel = vm.guideWindow(channels.subList(first, last).toList(), now, now + 1)
                            .mapNotNull { (id, list) ->
                                list.firstOrNull { now in it.startUtc until it.endUtc }
                                    ?.let { id to it }
                            }
                            .toMap()
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // The phone's rows are separate plates of glass (`PChannels`): 16dp from the
                    // screen's edges and 8dp apart. The television's sit on one panel, close.
                    contentPadding = if (compact) {
                        androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    } else {
                        androidx.compose.foundation.layout.PaddingValues(8.dp)
                    },
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 2.dp),
                ) {
                    items(channels, key = { it.id }) { channel ->
                        val isCurrent = state.currentChannel?.id == channel.id
                        if (compact) {
                            // The phone draws Studio's channel row (ADR-027, stage 5).
                            PhoneChannelRow(
                                channel = channel,
                                onScreen = isCurrent,
                                nowProgramme = nowByChannel[channel.id],
                                disc = isRadio,
                                onClick = { onPlay(channel) },
                                onLongClick = { menuFor = channel },
                                onToggleFavourite = { vm.setFavourite(channel, !channel.favourite) },
                            )
                        } else {
                            ChannelRow(
                                channel = channel,
                                selected = isCurrent,
                                nowProgramme = nowByChannel[channel.id],
                                focusRequester = currentFocus.takeIf { focusCurrent && isCurrent },
                                circularLogo = isRadio,
                                onClick = { onPlay(channel) },
                                onFocused = { previewChannel = channel },
                                onToggleFavourite = { vm.setFavourite(channel, !channel.favourite) },
                                onOptions = { menuFor = channel },
                            )
                        }
                    }
                    item {
                        // The gesture is invisible otherwise, and on a television there is no
                        // convention to fall back on.
                        Text(
                            text = stringResource(R.string.channel_options_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }

        if (showNowNext && !compact) {
            VerticalDivider()
            NowNextPane(
                channel = previewChannel ?: state.currentChannel,
                now = preview.first,
                next = preview.second,
                modifier = Modifier
                    .width(LocalTvMetrics.current.nowNextPaneWidth)
                    .fillMaxHeight()
                    .padding(12.dp),
                circularLogo = isRadio,
                onOpenGuide = onOpenGuide,
            )
        }
    }

    // Re-read from the current list so the dialog reflects a change made in a previous pass —
    // toggling a favourite and reopening the menu should not still offer "Add to favourites".
    menuFor?.let { target ->
        val channel = state.list.channels.firstOrNull { it.id == target.id } ?: target
        ChannelMenuDialog(
            channel = channel,
            canReorder = vm.canReorder,
            onDismiss = { menuFor = null },
            onPlay = { onPlay(channel) },
            onToggleFavourite = { vm.setFavourite(channel, !channel.favourite) },
            onToggleHidden = { vm.setHidden(channel, !channel.hidden) },
            onToggleLocked = { vm.setLocked(channel, !channel.locked) },
            onEdit = { menuFor = null; editing = channel },
            onAdvanced = { menuFor = null; advancedFor = channel },
            onMove = { delta -> vm.moveChannel(channel, delta) },
        )
    }

    advancedFor?.let { target ->
        val channel = state.list.channels.firstOrNull { it.id == target.id } ?: target
        ChannelAdvancedDialog(
            channel = channel,
            onDismiss = { advancedFor = null },
            onOffset = { minutes -> vm.setChannelEpgOffset(channel, minutes) },
            onDecoders = { video, audio -> vm.setChannelDecoders(channel, video, audio) },
            onBindGuide = { advancedFor = null; bindingGuideFor = channel },
        )
    }

    bindingGuideFor?.let { target ->
        val channel = state.list.channels.firstOrNull { it.id == target.id } ?: target
        GuideBindingDialog(
            channel = channel,
            onDismiss = { bindingGuideFor = null },
            onSearch = vm::searchGuideEntries,
            onBind = { epgId -> vm.bindGuide(channel, epgId) },
        )
    }

    editing?.let { target ->
        val channel = state.list.channels.firstOrNull { it.id == target.id } ?: target
        ChannelEditDialog(
            channel = channel,
            groups = state.list.groups,
            onDismiss = { editing = null },
            onSave = { name, logo, number, groupId ->
                vm.saveChannelEdits(channel, name, logo, number, groupId)
            },
            onReset = { vm.resetChannelCustomisations(channel) },
        )
    }

    groupMenuFor?.let { group ->
        GroupMenuDialog(
            group = group,
            onDismiss = { groupMenuFor = null },
            onToggleHidden = { vm.setGroupHidden(group, !group.hidden) },
            onRename = { groupMenuFor = null; renamingGroup = group },
            onDelete = { vm.deleteGroup(group) },
        )
    }

    renamingGroup?.let { group ->
        TextEntryDialog(
            title = stringResource(R.string.group_rename),
            label = stringResource(R.string.group_name),
            initial = group.name,
            onDismiss = { renamingGroup = null },
            onConfirm = { vm.renameGroup(group, it) },
        )
    }

    if (managingGroups) {
        GroupManagerDialog(
            groups = state.list.groups,
            onDismiss = { managingGroups = false },
            onNewGroup = { managingGroups = false; creatingGroup = true },
            onGroupOptions = { managingGroups = false; groupMenuFor = it },
        )
    }

    if (creatingGroup) {
        TextEntryDialog(
            title = stringResource(R.string.group_new),
            label = stringResource(R.string.group_name),
            onDismiss = { creatingGroup = false },
            onConfirm = vm::createGroup,
        )
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    selected: Boolean,
    nowProgramme: Programme?,
    focusRequester: FocusRequester?,
    circularLogo: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    onToggleFavourite: () -> Unit,
    onOptions: () -> Unit,
) {
    // `Channels`' rows are `ListRow`s: the number, the mark, the name with what is on under it,
    // and the archive as a quiet value at the end. The channel on screen takes the tally — the
    // dark red fill, the ring and a dot after its name — rather than the card colour.
    val studio = StudioTheme.colors
    FocusableRow(
        onClick = onClick,
        onScreen = selected,
        contentDescription = channel.displayName,
        onFocused = onFocused,
        onLongClick = onOptions,
        focusRequester = focusRequester,
    ) {
        Text(
            text = channel.number.takeIf { it > 0 }?.toString().orEmpty(),
            style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = studio.ink2,
            // Fixed, not a minimum, so mixed-length numbers keep the logos in one column;
            // sized for the five digits providers actually issue instead of the three the
            // old 36dp held, which wrapped "16035" onto two lines (device report).
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(48.dp),
        )
        ChannelLogo(
            channel = channel,
            sizeDp = LocalTvMetrics.current.rowLogoSize,
            circular = circularLogo,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = channel.displayName,
                    style = StudioTheme.type.rowLabel,
                    color = studio.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(studio.tally))
                }
            }
            nowProgramme?.title?.let {
                Text(
                    text = it,
                    style = StudioTheme.type.caption.copy(fontWeight = FontWeight.Normal),
                    color = studio.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (channel.locked) {
            Icon(
                imageVector = StudioIcons.Lock,
                contentDescription = stringResource(R.string.channel_lock),
                tint = studio.ink2,
                modifier = Modifier.size(14.dp),
            )
        }
        if (channel.hasCatchup) {
            Text(text = stringResource(R.string.catchup_available), style = StudioTheme.type.meta, color = studio.ink2)
        }
        // FR-ORG-07. This was a `★` badge that only reported the state; marking a favourite meant
        // opening the guide, picking a programme and using its dialog. A control on the row is
        // one press on touch, and one step right on a D-pad.
        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (channel.favourite) StudioIcons.StarOn else StudioIcons.Star,
                contentDescription = stringResource(
                    if (channel.favourite) R.string.action_unfavourite else R.string.action_favourite,
                ),
                // A favourite is a full star in white (`star-on`), never the cue: amber marks focus.
                tint = if (channel.favourite) StudioTheme.colors.ink else StudioTheme.colors.ink2,
            )
        }
    }
}

/** The touch filter box. Always visible once set, which is what makes the leak impossible. */
@Composable
private fun FilterField(query: String, onQuery: (String) -> Unit) {
    StudioField(
        value = query,
        onValueChange = onQuery,
        label = stringResource(R.string.filter_channels),
        trailing = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(
                        StudioIcons.Close,
                        contentDescription = stringResource(R.string.action_clear),
                        tint = StudioTheme.colors.ink2,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * The ten-foot equivalent: no keyboard, but never a silently filtered list either. Search on the
 * TV shell is its own overlay, and this is what says so afterwards.
 */
@Composable
private fun FilterBanner(query: String, onClear: () -> Unit) {
    if (query.isBlank()) return
    androidx.compose.material3.AssistChip(
        onClick = onClear,
        label = { Text(stringResource(R.string.filter_active, query), maxLines = 1) },
        trailingIcon = {
            Icon(StudioIcons.Close, contentDescription = stringResource(R.string.action_clear))
        },
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun GroupColumn(
    vm: AppViewModel,
    onSearch: (() -> Unit)?,
    onNewGroup: () -> Unit,
    onGroupOptions: (ChannelGroup) -> Unit,
    isRadio: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    // Computed here, in the composable body, and only referenced (not called) inside the
    // LazyColumn content block below: that block's own type is plain `LazyListScope.() -> Unit`,
    // not `@Composable`, so a @Composable function (this one calls stringResource) can only be
    // invoked from it indirectly, via a value already computed outside.
    val radioCategories = if (isRadio) radioCategoryBuckets(state) else emptyList()
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (onSearch != null) {
            // Search was reachable only through the remote's Search key, which plenty of remotes
            // do not have. It needs somewhere on screen to live.
            item { GroupRow(stringResource(R.string.nav_search), selected = false, onClick = onSearch) }
        }
        // The shell's other destinations used to be listed here too, on the television, in an
        // order and a style of their own. They are the side menu's now, on every screen alike
        // (ADR-035), and this column is the list's own filters and groups.
        // Split so "which of my four smart views" and "which of the provider's own categories"
        // read as two different questions, not one long undifferentiated list (FR-UI-08).
        item {
            SectionHeader(
                text = stringResource(R.string.filter_quick),
                topDivider = onSearch != null,
            )
        }
        item {
            GroupRow(
                stringResource(R.string.all_channels),
                // Radio keeps `selectedGroupId` on ALL while a category narrows the list, so
                // "All" and the chosen category would both draw as selected without this.
                state.list.selectedGroupId == VirtualGroup.ALL && state.list.selectedRadioCategory == null,
                icon = StudioIcons.Grid,
            ) { vm.selectGroup(VirtualGroup.ALL) }
        }
        item {
            GroupRow(
                stringResource(R.string.favourites),
                state.list.selectedGroupId == VirtualGroup.FAVOURITES,
                icon = StudioIcons.StarOn,
            ) { vm.selectGroup(VirtualGroup.FAVOURITES) }
        }
        item {
            GroupRow(
                stringResource(R.string.recently_watched),
                state.list.selectedGroupId == VirtualGroup.RECENT,
                icon = StudioIcons.History,
            ) { vm.selectGroup(VirtualGroup.RECENT) }
        }
        item {
            GroupRow(
                stringResource(R.string.hidden_channels),
                state.list.selectedGroupId == VirtualGroup.HIDDEN,
                icon = StudioIcons.Hide,
            ) { vm.selectGroup(VirtualGroup.HIDDEN) }
        }
        item { SectionHeader(text = stringResource(R.string.filter_categories)) }
        if (isRadio) {
            items(radioCategories, key = { it.second }) { (category, label) ->
                GroupRow(
                    name = label,
                    selected = state.list.selectedRadioCategory == category,
                    onClick = { vm.selectRadioCategory(category) },
                )
            }
        } else {
            item {
                GroupRow(stringResource(R.string.group_new), selected = false, onClick = onNewGroup)
            }
            items(state.list.groups, key = { it.id }) { group ->
                GroupRow(
                    name = group.name,
                    selected = state.list.selectedGroupId == group.id,
                    // A hidden group stays listed, drawn dimmed, because otherwise nothing on screen
                    // could ever show it again.
                    dimmed = group.hidden,
                    onClick = { vm.selectGroup(group.id) },
                    onLongClick = { onGroupOptions(group) },
                )
            }
        }
    }
}

/**
 * The radio category menu (National/Regional/genre buckets/Other), built from every non-Russian
 * radio station regardless of the current selection — see [AppUiState.radioAllChannels] — ordered
 * National, Regional, genres alphabetically, Other last, and skipping any bucket with nothing in it.
 */
@Composable
private fun radioCategoryBuckets(
    state: com.tivicopy.player.ui.AppUiState,
): List<Pair<com.tivicopy.player.data.radio.RadioCategory, String>> {
    val classifier = com.tivicopy.player.data.radio.RadioCategoryClassifier
    val present = state.radioAllChannels.map { channel ->
        val groupName = state.list.groups.firstOrNull { it.id == channel.groupId }?.name
        classifier.classify(groupName, channel.displayName)
    }.toSet()
    val ordered = mutableListOf<Pair<com.tivicopy.player.data.radio.RadioCategory, String>>()
    if (com.tivicopy.player.data.radio.RadioCategory.NATIONAL in present) {
        ordered += com.tivicopy.player.data.radio.RadioCategory.NATIONAL to stringResource(R.string.radio_category_national)
    }
    if (com.tivicopy.player.data.radio.RadioCategory.REGIONAL in present) {
        ordered += com.tivicopy.player.data.radio.RadioCategory.REGIONAL to stringResource(R.string.radio_category_regional)
    }
    present.filterIsInstance<com.tivicopy.player.data.radio.RadioCategory.GENRE>()
        .map { it.label }
        .sorted()
        .forEach { label -> ordered += com.tivicopy.player.data.radio.RadioCategory.GENRE(label) to label }
    if (com.tivicopy.player.data.radio.RadioCategory.OTHER in present) {
        ordered += com.tivicopy.player.data.radio.RadioCategory.OTHER to stringResource(R.string.radio_category_other)
    }
    return ordered
}

@Composable
private fun GroupRow(
    name: String,
    selected: Boolean,
    dimmed: Boolean = false,
    /** A leading icon for the smart-filter rows (star, history, …). */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    // `Channels`' group column: `ListRow`s with their glyphs. A hidden group stays listed, wearing
    // the hide glyph, because otherwise nothing could ever show it again.
    ListRow(
        label = name,
        onClick = onClick,
        icon = if (dimmed) StudioIcons.Hide else icon,
        selected = selected,
        onLongClick = onLongClick,
    )
}

/**
 * Narrow-screen replacement for the group column: the same "which smart view" vs "which of my
 * own categories" split as [GroupColumn], as two labeled chip rows instead of one long one.
 */
@Composable
private fun GroupChips(vm: AppViewModel, isRadio: Boolean = false, onManageGroups: () -> Unit) {
    val state by vm.state.collectAsState()
    // See the matching comment in GroupColumn: computed here, in the composable body, not inside
    // the plain (non-@Composable) LazyRow content block below.
    val radioCategories = if (isRadio) radioCategoryBuckets(state) else emptyList()
    // Studio's chip row (`canvas/PChannels.dc.html`): one row of `PChip`s, the quick filters first
    // and then the categories, the selected one in ink.
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PhoneChip(
                label = stringResource(R.string.all_channels),
                // See the group list's own "All channels" row: a selected radio category leaves
                // the group id on ALL, and two selected chips read as a bug.
                selected = state.list.selectedGroupId == VirtualGroup.ALL &&
                    state.list.selectedRadioCategory == null,
                onClick = { vm.selectGroup(VirtualGroup.ALL) },
            )
        }
        item {
            PhoneChip(
                label = stringResource(R.string.favourites),
                selected = state.list.selectedGroupId == VirtualGroup.FAVOURITES,
                onClick = { vm.selectGroup(VirtualGroup.FAVOURITES) },
            )
        }
        item {
            PhoneChip(
                label = stringResource(R.string.recently_watched),
                selected = state.list.selectedGroupId == VirtualGroup.RECENT,
                onClick = { vm.selectGroup(VirtualGroup.RECENT) },
            )
        }
        item {
            PhoneChip(
                label = stringResource(R.string.hidden_channels),
                selected = state.list.selectedGroupId == VirtualGroup.HIDDEN,
                onClick = { vm.selectGroup(VirtualGroup.HIDDEN) },
            )
        }
        if (isRadio) {
            items(radioCategories, key = { it.second }) { (category, label) ->
                PhoneChip(
                    label = label,
                    selected = state.list.selectedRadioCategory == category,
                    onClick = { vm.selectRadioCategory(category) },
                )
            }
        } else {
            items(state.list.groups, key = { it.id }) { group ->
                PhoneChip(
                    label = group.name,
                    selected = state.list.selectedGroupId == group.id,
                    onClick = { vm.selectGroup(group.id) },
                )
            }
            item {
                // A chip has no long press to hang a context menu on, so the phone reaches the
                // group actions through a manager instead of not at all.
                PhoneChip(
                    label = stringResource(R.string.group_manage),
                    selected = false,
                    onClick = onManageGroups,
                )
            }
        }
    }
}

@Composable
fun NowNextPane(
    channel: Channel?,
    now: Programme?,
    next: Programme?,
    modifier: Modifier = Modifier,
    /**
     * Whether the channel's mark is a station's disc (ADR-022). Defaulted off, so the phone's
     * player screen is unchanged; the radio list passes it, because this pane sits in the column
     * beside rows that are already drawing the same station round.
     */
    circularLogo: Boolean = false,
    /** The television preview's "schedule" button; null draws none. */
    onOpenGuide: ((Channel) -> Unit)? = null,
) {
    if (StudioTheme.television) {
        ChannelPreview(
            channel = channel,
            now = now,
            next = next,
            circularLogo = circularLogo,
            onOpenGuide = onOpenGuide,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (channel == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChannelLogo(channel = channel, sizeDp = 48, circular = circularLogo)
            Text(
                text = channel.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (now == null) {
            // FR-EPG-16: an explicit state, never a blank row.
            Text(
                text = stringResource(R.string.no_guide_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = TimeModel.formatClockRange(now.startUtc, now.endUtc),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = now.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ProgrammeProgress(now.startUtc, now.endUtc)
            now.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        next?.let {
            Box(modifier = Modifier.fillMaxWidth()) {
                TwoLine(
                    primary = it.title,
                    secondary = TimeModel.formatClock(it.startUtc),
                    primaryStyle = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * The preview beside a television's channel list (`Channels`' aside): the number at the display weight
 * beside the name, the live badge, the programme's title at the display weight, its times with the
 * progress and the minutes left, its synopsis, and under a hairline what comes next — in the
 * `next` colour, which is what it is for.
 *
 * Of the canvas's three buttons at the foot of the pane, only "schedule" is drawn ([onOpenGuide]):
 * watching is OK on the row and the favourite is the star on the row, but the channel's schedule
 * was a long press and a menu away. The owner's call (2026-10-16, `MIGRATION.md` round 3).
 */
@Composable
private fun ChannelPreview(
    channel: Channel?,
    now: Programme?,
    next: Programme?,
    circularLogo: Boolean,
    onOpenGuide: ((Channel) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (channel == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (channel.number > 0) {
                Text(text = TimeModel.ltr(channel.number.toString()), style = type.titleSmall, color = studio.ink)
            } else {
                ChannelLogo(channel = channel, sizeDp = 32, circular = circularLogo)
            }
            Text(text = channel.displayName, style = type.rowLabel, color = studio.ink, maxLines = 1)
        }
        if (now == null) {
            // FR-EPG-16: an explicit state, never a blank row.
            Text(text = stringResource(R.string.no_guide_data), style = type.meta, color = studio.ink2)
        } else {
            val clock = System.currentTimeMillis()
            if (clock in now.startUtc until now.endUtc) LiveBadge()
            Text(
                text = now.title,
                // 88px at 1080p against the title step's 108.
                style = type.title.copy(fontSize = type.title.fontSize * (88f / 108f)),
                color = studio.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = TimeModel.ltr(TimeModel.formatClockRange(now.startUtc, now.endUtc)),
                    style = type.meta,
                    color = studio.ink2,
                )
                ProgrammeProgress(now.startUtc, now.endUtc, modifier = Modifier.width(100.dp))
                val left = ((now.endUtc - clock) / 60_000L).toInt()
                if (left > 0) {
                    Text(
                        text = stringResource(R.string.home_minutes_left, left),
                        style = type.meta,
                        color = studio.ink2,
                    )
                }
            }
            now.description?.let {
                Text(text = it, style = type.caption, color = studio.ink2, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
        next?.let {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(studio.hairline))
            Text(
                text = stringResource(R.string.info_next) + " · " + TimeModel.ltr(TimeModel.formatClock(it.startUtc)),
                style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                color = studio.next,
            )
            Text(
                text = it.title,
                style = type.h1.copy(fontWeight = FontWeight.SemiBold),
                color = studio.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = TimeModel.ltr(TimeModel.formatClockRange(it.startUtc, it.endUtc)),
                style = type.meta,
                color = studio.ink2,
            )
        }
        // The foot of the pane, as the canvas sets it: the button, then the hint under it.
        Spacer(modifier = Modifier.weight(1f))
        if (onOpenGuide != null) {
            StudioButton(
                label = stringResource(R.string.preview_open_guide),
                icon = StudioIcons.Guide,
                size = StudioButtonSize.MEDIUM,
                onClick = { onOpenGuide(channel) },
            )
        }
        Text(
            text = stringResource(R.string.channel_options_hint),
            style = type.caption,
            color = studio.ink3,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** How many rows the now-line query covers around the viewport; roughly one screenful. */
private const val NOW_VISIBLE_ROWS = 15

/** Extra rows fetched on either side so a slow scroll never shows a blank second line. */
private const val NOW_PREFETCH_ROWS = 5

/** How long a scroll must rest on a block before its now-line query runs. */
private const val NOW_QUERY_DEBOUNCE_MS = 120L
