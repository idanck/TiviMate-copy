package com.tivicopy.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
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
import com.tivicopy.player.ui.common.ProgrammeProgress
import com.tivicopy.player.ui.common.SectionHeader
import com.tivicopy.player.ui.common.TwoLine
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.mobile.PhoneChip
import kotlinx.coroutines.delay

/**
 * Somewhere the shell can go, listed above the groups in the channel list's first column.
 *
 * @param label what the row says
 * @param onSelect what opening it does
 */
data class NavDestination(val label: String, val onSelect: () -> Unit)

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
    /** Opens the full search surface. Null hides the entry point. */
    onSearch: (() -> Unit)? = null,
    /**
     * Where else the shell can go from here, drawn above the groups in the same column.
     *
     * This is the ten-foot answer to the phone's bottom navigation bar. The television had no
     * on-screen navigation at all: every destination was a remote key, and two of them were keys
     * plenty of remotes do not have. On demand was worse than hard to reach — it could only be
     * opened by searching for a series and picking it, so a viewer who did not already know a
     * title in the catalogue could not open the catalogue. That failed NFR-UX-01, which asks that
     * every screen be reachable with D-pad, OK and Back alone.
     *
     * Empty on touch, which has a navigation bar of its own.
     */
    destinations: List<NavDestination> = emptyList(),
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
                destinations = destinations,
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
                androidx.compose.material3.TextButton(
                    onClick = { vm.showAllHidden() },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(stringResource(R.string.action_show_all)) }
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
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
            title = stringResource(R.string.group_options),
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
    FocusableRow(
        onClick = onClick,
        selected = selected,
        contentDescription = channel.displayName,
        onFocused = onFocused,
        onLongClick = onOptions,
        focusRequester = focusRequester,
    ) {
        Text(
            text = channel.number.takeIf { it > 0 }?.toString().orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        TwoLine(
            primary = channel.displayName,
            secondary = nowProgramme?.title,
            modifier = Modifier.weight(1f),
        )
        if (channel.hasCatchup) Badge(text = stringResource(R.string.catchup_available))
        if (channel.locked) Badge(text = "🔒")
        // FR-ORG-07. This was a `★` badge that only reported the state; marking a favourite meant
        // opening the guide, picking a programme and using its dialog. A control on the row is
        // one press on touch, and one step right on a D-pad.
        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (channel.favourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (channel.favourite) R.string.action_unfavourite else R.string.action_favourite,
                ),
                tint = if (channel.favourite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** The touch filter box. Always visible once set, which is what makes the leak impossible. */
@Composable
private fun FilterField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        label = { Text(stringResource(R.string.filter_channels)) },
        singleLine = true,
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
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
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
        },
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun GroupColumn(
    vm: AppViewModel,
    onSearch: (() -> Unit)?,
    destinations: List<NavDestination>,
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
        // The same argument as Search above, applied to the rest of the shell: the guide, the
        // catalogue, the recordings and settings were each behind a remote key, and on demand
        // behind no key at all.
        items(destinations, key = { it.label }) { destination ->
            GroupRow(destination.label, selected = false, onClick = destination.onSelect)
        }
        // Split so "which of my four smart views" and "which of the provider's own categories"
        // read as two different questions, not one long undifferentiated list (FR-UI-08).
        item {
            SectionHeader(
                text = stringResource(R.string.filter_quick),
                topDivider = onSearch != null || destinations.isNotEmpty(),
            )
        }
        item {
            GroupRow(
                stringResource(R.string.all_channels),
                // Radio keeps `selectedGroupId` on ALL while a category narrows the list, so
                // "All" and the chosen category would both draw as selected without this.
                state.list.selectedGroupId == VirtualGroup.ALL && state.list.selectedRadioCategory == null,
                icon = Icons.Filled.SelectAll,
            ) { vm.selectGroup(VirtualGroup.ALL) }
        }
        item {
            GroupRow(
                stringResource(R.string.favourites),
                state.list.selectedGroupId == VirtualGroup.FAVOURITES,
                icon = Icons.Filled.Star,
            ) { vm.selectGroup(VirtualGroup.FAVOURITES) }
        }
        item {
            GroupRow(
                stringResource(R.string.recently_watched),
                state.list.selectedGroupId == VirtualGroup.RECENT,
                icon = Icons.Filled.History,
            ) { vm.selectGroup(VirtualGroup.RECENT) }
        }
        item {
            GroupRow(
                stringResource(R.string.hidden_channels),
                state.list.selectedGroupId == VirtualGroup.HIDDEN,
                icon = Icons.Filled.VisibilityOff,
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
    /** A leading icon for the smart-filter rows (star, history, …); provider categories have none. */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    FocusableRow(
        onClick = onClick,
        selected = selected,
        contentDescription = name,
        onLongClick = onLongClick,
    ) {
        val tint = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = tint,
        )
    }
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
) {
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

/** How many rows the now-line query covers around the viewport; roughly one screenful. */
private const val NOW_VISIBLE_ROWS = 15

/** Extra rows fetched on either side so a slow scroll never shows a blank second line. */
private const val NOW_PREFETCH_ROWS = 5

/** How long a scroll must rest on a block before its now-line query runs. */
private const val NOW_QUERY_DEBOUNCE_MS = 120L
