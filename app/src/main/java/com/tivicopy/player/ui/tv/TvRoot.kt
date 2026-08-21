package com.tivicopy.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tivicopy.player.R
import com.tivicopy.player.ServiceLocator
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.Programme
import com.tivicopy.player.data.model.VodItem
import com.tivicopy.player.record.SERIES_LOOKAHEAD_MILLIS
import com.tivicopy.player.ui.AppViewModel
import com.tivicopy.player.ui.common.ChannelLogo
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.tvMetricsFor
import com.tivicopy.player.ui.common.describe
import com.tivicopy.player.ui.common.PlaybackStateOverlay
import com.tivicopy.player.ui.common.PlayerSurface
import com.tivicopy.player.ui.common.ProgrammeProgress
import com.tivicopy.player.ui.screens.AddSourceScreen
import com.tivicopy.player.ui.screens.ChannelListScreen
import com.tivicopy.player.ui.screens.GuideScreen
import com.tivicopy.player.ui.screens.ProgrammeDetailsDialog
import com.tivicopy.player.ui.screens.RecordingsScreen
import com.tivicopy.player.ui.screens.SearchScreen
import com.tivicopy.player.ui.screens.VodScreen
import com.tivicopy.player.ui.screens.VodScreenState
import com.tivicopy.player.ui.settings.SettingsScreen
import com.tivicopy.player.ui.settings.SettingsSection
import com.tivicopy.player.ui.common.UpdatePrompt

/** Which overlay, if any, sits on top of the video. Nothing here ever stops playback. */
enum class TvOverlay { NONE, CHANNELS, MINI_GUIDE, GUIDE, SEARCH, RECORDINGS, VOD, SETTINGS, ADD_SOURCE, INFO }

/**
 * The TV shell: fullscreen video is the root screen (FR-UI-02), and every other surface is an
 * overlay drawn over a stream that keeps playing (FR-UI-06, NFR-PERF-04).
 */
@Composable
fun TvRoot(
    vm: AppViewModel,
    overlay: TvOverlay,
    settingsSection: SettingsSection,
    onOverlayChange: (TvOverlay) -> Unit,
    onSettingsSection: (SettingsSection) -> Unit,
    numericEntry: String,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val status by vm.playbackStatus.collectAsState()
    val prefs by vm.settingsState.collectAsState()
    var details by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }
    // Loaded when the details dialog opens so a series recording can claim the episodes the
    // guide already lists, without querying on every recomposition.
    var upcoming by remember { mutableStateOf<List<Programme>>(emptyList()) }
    // A series picked out of search, handed to the on-demand overlay so it opens on that series
    // rather than on the grid the user would then have to search again.
    var pendingSeries by remember { mutableStateOf<VodItem?>(null) }
    // Held here, above the overlay switch, so closing and reopening the on-demand overlay
    // restores the search box, the filter, the open series and the scroll position.
    val vodState = remember { VodScreenState() }

    // The screen drops the request itself once it has taken it; this only covers the request
    // that is never taken, because on demand was left before it composed.
    LaunchedEffect(overlay) { if (overlay != TvOverlay.VOD) pendingSeries = null }

    LaunchedEffect(details?.first?.id) {
        val channel = details?.first
        upcoming = if (channel == null) emptyList() else {
            val now = System.currentTimeMillis()
            vm.programmesFor(channel, now, now + SERIES_LOOKAHEAD_MILLIS)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        PlayerSurface(
            engine = ServiceLocator.player,
            aspectMode = prefs.aspectMode,
            subtitleScale = prefs.subtitleTextScale,
            modifier = Modifier.fillMaxSize(),
        )

        // Zapping on a remote gives no other feedback: without this a channel that is still
        // tuning is a black screen, identical to one that failed. The settled-failure text is
        // left to PlaybackErrorBanner below, which is where a 10-foot layout wants it.
        PlaybackStateOverlay(
            status = status,
            showErrorText = false,
            opacity = prefs.overlayOpacity,
            modifier = Modifier.fillMaxSize(),
        )

        // The info card auto-dismisses; everything else waits for Back.
        if (overlay == TvOverlay.INFO) {
            ChannelInfoCard(
                channel = state.currentChannel,
                now = state.now,
                next = state.next,
                modifier = Modifier.align(Alignment.BottomStart).padding(TV_CONTENT_INSET),
            )
        }

        if (numericEntry.isNotEmpty()) {
            Text(
                text = stringResource(R.string.channel_number_entry, numericEntry),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(TV_CONTENT_INSET)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = prefs.overlayOpacity))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        status.error?.let { error ->
            PlaybackErrorBanner(
                error = error,
                modifier = Modifier.align(Alignment.TopStart).padding(TV_CONTENT_INSET),
            )
        }

        if (overlay != TvOverlay.NONE && overlay != TvOverlay.INFO) {
            OverlayScaffold(opacity = prefs.overlayOpacity) {
                when (overlay) {
                    TvOverlay.CHANNELS -> ChannelListScreen(
                        vm = vm,
                        onPlay = { channel -> vm.play(channel); onOverlayChange(TvOverlay.NONE) },
                        onSearch = { onOverlayChange(TvOverlay.SEARCH) },
                        // Opening the list lands on the playing channel, focused, so zapping
                        // onward is one press instead of a scroll from the top.
                        scrollToCurrent = true,
                        focusCurrent = true,
                        modifier = Modifier.fillMaxSize(),
                    )

                    TvOverlay.MINI_GUIDE -> MiniGuide(vm = vm, modifier = Modifier.fillMaxSize())

                    TvOverlay.GUIDE -> GuideScreen(
                        vm = vm,
                        onPlay = { channel -> vm.play(channel); onOverlayChange(TvOverlay.NONE) },
                        onOpenProgramme = { channel, programme -> details = channel to programme },
                        modifier = Modifier.fillMaxSize(),
                    )

                    TvOverlay.SEARCH -> SearchScreen(
                        vm = vm,
                        onPlay = { channel -> vm.play(channel); onOverlayChange(TvOverlay.NONE) },
                        onOpenProgramme = { channel, programme -> details = channel to programme },
                        onPlayVod = { item -> vm.playVod(item); onOverlayChange(TvOverlay.NONE) },
                        onOpenSeries = { item ->
                            pendingSeries = item
                            onOverlayChange(TvOverlay.VOD)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    TvOverlay.RECORDINGS -> RecordingsScreen(
                        onPlayRecording = { recording ->
                            vm.playRecording(recording)
                            onOverlayChange(TvOverlay.NONE)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    TvOverlay.VOD -> VodScreen(
                        vm = vm,
                        onPlay = { item ->
                            vm.playVod(item)
                            onOverlayChange(TvOverlay.NONE)
                        },
                        modifier = Modifier.fillMaxSize(),
                        openSeriesRequest = pendingSeries,
                        onSeriesRequestConsumed = { pendingSeries = null },
                        state = vodState,
                    )

                    TvOverlay.SETTINGS -> SettingsScreen(
                        vm = vm,
                        section = settingsSection,
                        onNavigate = onSettingsSection,
                        onAddSource = { onOverlayChange(TvOverlay.ADD_SOURCE) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    TvOverlay.ADD_SOURCE -> AddSourceScreen(
                        vm = vm,
                        onDone = { onOverlayChange(TvOverlay.NONE) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> Unit
                }
            }
        }

        details?.let { (channel, programme) ->
            ProgrammeDetailsDialog(
                channel = channel,
                programme = programme,
                onDismiss = { details = null },
                onWatch = { vm.play(channel); onOverlayChange(TvOverlay.NONE) },
                onCatchup = { vm.playCatchup(channel, programme); onOverlayChange(TvOverlay.NONE) },
                onFavourite = { favourite -> vm.setFavourite(channel, favourite) },
                onMessage = { text -> vm.post(com.tivicopy.player.ui.UiMessage(text = text)) },
                upcoming = upcoming,
            )
        }

        // FR-UPD-03: offered here rather than inside a screen, so it survives tab changes and
        // is absent in picture-in-picture, which returns above this point.
        UpdatePrompt()
    }
}

/**
 * The container every browse overlay is drawn in.
 *
 * The background is painted on the full-size box and the inset is applied *inside* it. It used to
 * be the other way round, which drew the scrim within a 32dp/24dp margin and left every surface —
 * the channel list, the guide, settings — floating in a frame of raw video. That was the second of
 * the three reasons the interface was reported as not filling the screen, and the most visible.
 *
 * The inset itself is kept, as content padding rather than a frame: dropping it altogether would
 * put settings rows and on-demand titles flush against the edge of the panel, and a television
 * that still overscans would eat them. [TV_CONTENT_INSET] is what remains of the old overscan
 * margin — the surface reaches the edge, the words do not.
 */
@Composable
private fun OverlayScaffold(opacity: Float, content: @Composable () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = opacity))
            .padding(TV_CONTENT_INSET),
    ) {
        // Measured here rather than read from the Configuration: this box is what the screens
        // actually get, insets and all, and it is the only honest input to how wide an hour of
        // guide should be.
        val metrics = tvMetricsFor(maxWidth.value, maxHeight.value)
        CompositionLocalProvider(LocalTvMetrics provides metrics) { content() }
    }
}

/**
 * How far the ten-foot shell keeps text from the edge of the panel.
 *
 * Not a safe area — the surfaces themselves are full-bleed by decision (docs/03 section 3.1). This
 * is only the breathing room any readable layout wants, and it happens to absorb the small crop an
 * older overscanning television applies.
 */
internal val TV_CONTENT_INSET = 24.dp

/** The zapping info card (FR-PLB-18). */
@Composable
fun ChannelInfoCard(
    channel: Channel?,
    now: Programme?,
    next: Programme?,
    modifier: Modifier = Modifier,
) {
    if (channel == null) return
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChannelLogo(channel = channel, sizeDp = 56)
            Column {
                Text(
                    text = "${channel.number.takeIf { it > 0 } ?: ""} ${channel.displayName}".trim(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = now?.title ?: stringResource(R.string.no_guide_data),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        now?.let {
            Text(
                text = TimeModel.formatClockRange(it.startUtc, it.endUtc),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProgrammeProgress(it.startUtc, it.endUtc, modifier = Modifier.fillMaxWidth(0.5f))
        }
        next?.let {
            Text(
                text = "${TimeModel.ltr(TimeModel.formatClock(it.startUtc))} · ${it.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The single-channel timeline over playing video (FR-UI-04). */
@Composable
fun MiniGuide(vm: AppViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val channel = state.currentChannel
    var programmes by remember { mutableStateOf<List<Programme>>(emptyList()) }

    LaunchedEffect(channel?.id) {
        programmes = channel?.let {
            vm.programmesFor(
                it,
                System.currentTimeMillis() - 2 * 60 * 60_000L,
                System.currentTimeMillis() + 8 * 60 * 60_000L,
            )
        }.orEmpty()
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = channel?.displayName.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (programmes.isEmpty()) {
            Text(
                text = stringResource(R.string.no_guide_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.4f),
            ) {
                items(programmes, key = { it.id }) { programme ->
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = TimeModel.formatClock(programme.startUtc),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = programme.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackErrorBanner(error: com.tivicopy.player.core.AppError, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp),
    ) {
        Text(
            text = error.describe(context),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
