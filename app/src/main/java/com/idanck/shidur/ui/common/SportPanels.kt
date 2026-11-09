package com.idanck.shidur.ui.common

import android.view.ViewGroup
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.idanck.shidur.R
import com.idanck.shidur.ServiceLocator
import com.idanck.shidur.core.AppError
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.news.NewsDigests
import com.idanck.shidur.data.news.NewsRepository
import com.idanck.shidur.data.news.SourcedFlash
import com.idanck.shidur.player.PlaybackStatus
import com.idanck.shidur.ui.AppViewModel
import com.idanck.shidur.ui.theme.StudioTheme
import kotlinx.coroutines.delay

/*
 * The parts of a Sport or News mode panel both shells draw the same way (ADR-041, ADR-042): the
 * picture, an empty place, a channel that will not play, the caption along the foot, News mode's
 * headlines, and a row of the channel picker. The television's grid (`ui/tv/SportScreen.kt`) and the phone's (`ui/mobile/PhoneSport.kt`)
 * differ in how a panel is reached — the remote's focus, or a finger — and in nothing drawn here.
 */

/** Still reaching the stream, or trying again after it dropped. */
internal fun panelConnecting(status: PlaybackStatus): Boolean =
    !status.playing && (status.buffering || status.error != null)

/**
 * The panel's picture. Media3's own view, as the full-screen player uses (`PlayerSurface`), but
 * always fitted and never captioned: subtitles sized for a quarter of the screen are not readable.
 */
@Composable
internal fun PanelVideo(player: ExoPlayer?, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                subtitleView?.visibility = android.view.View.GONE
                // Decoration, never a control: the panel around it is what takes the remote or
                // the finger.
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
        onRelease = { view -> view.player = null },
    )
}

/** An empty place in the grid, saying what [hint] says picks a channel for it. */
@Composable
internal fun EmptyPanel(hint: String, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = StudioIcons.Plus,
            contentDescription = null,
            tint = studio.ink2,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = stringResource(R.string.sport_add_channel),
            style = StudioTheme.type.titleSmall,
            color = studio.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = hint,
            style = StudioTheme.type.caption,
            color = studio.ink2,
            textAlign = TextAlign.Center,
        )
    }
}

/** A panel whose channel will not play, and why, with [hint] saying how to try again. */
@Composable
internal fun PanelFailure(
    vm: AppViewModel,
    channel: Channel,
    status: PlaybackStatus,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    val context = LocalContext.current
    val error = status.error
    val freeToAir = remember(channel.playlistId) { vm.isFreeToAir(channel) }
    val source = remember(channel.playlistId) { vm.playlistNames()[channel.playlistId] }
    var limit by remember(channel.playlistId) { mutableStateOf<Int?>(null) }
    if (status.closed && !freeToAir) {
        LaunchedEffect(channel.playlistId) { limit = vm.connectionLimit(channel) }
    }
    val known = limit
    val message = when {
        // A broadcaster's own server ended the stream: no account, no limit, nothing of the
        // provider's (2026-10-28, where the old wording blamed the provider for it).
        status.closed && freeToAir -> stringResource(R.string.sport_panel_stream_ended_free)
        // The provider closed the stream: on an account with fewer connections than panels, that
        // is what happens to all but the newest (ADR-041). The channel itself is very likely fine.
        // Named, and with the account's limit when its panel says, because the same station can
        // be on screen free and through the provider under one name.
        status.closed && source == null -> stringResource(R.string.sport_panel_stream_closed)
        status.closed && known == 1 -> stringResource(R.string.sport_panel_stream_closed_one, source.orEmpty())
        status.closed && known != null -> stringResource(R.string.sport_panel_stream_closed_many, source.orEmpty(), known)
        status.closed -> stringResource(R.string.sport_panel_stream_closed_by, source.orEmpty())
        // Almost always the device's limit on pictures decoded at once (ADR-041), and that is
        // the thing worth saying: the channel itself is very likely fine.
        error?.kind == AppError.Kind.DecoderFailed -> stringResource(R.string.sport_panel_device_limit)
        else -> (error ?: AppError(AppError.Kind.Unknown)).describe(context)
    }
    Column(
        modifier = modifier
            .background(studio.ground.copy(alpha = FAILURE_PLATE_ALPHA))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = StudioIcons.Alert,
            contentDescription = null,
            tint = studio.error,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = message,
            style = StudioTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = studio.ink,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = hint,
            style = StudioTheme.type.caption,
            color = studio.ink2,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Along the foot of a panel: the channel, the programme on air if the guide knows it, and the
 * sound mark on the panel being heard. Full screen it shows for a moment and then leaves the
 * picture alone, as the info card does. On the phone, [onChange] puts a button at its end that
 * opens the picker for the panel (ADR-041 decision 11); the television's panel has a menu of its
 * own on OK and passes none.
 */
@Composable
internal fun PanelCaption(
    vm: AppViewModel,
    channel: Channel,
    audible: Boolean,
    paused: Boolean,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onChange: (() -> Unit)? = null,
) {
    val studio = StudioTheme.colors
    val onAir = rememberOnAir(vm, channel)
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(expanded, channel.id) {
        visible = true
        if (expanded) {
            delay(CAPTION_HOLD_MS)
            visible = false
        }
    }
    if (!visible) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(studio.ground.copy(alpha = 0f), studio.ground.copy(alpha = CAPTION_SCRIM_ALPHA))),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChannelLogo(channel, sizeDp = 32)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.displayName,
                style = StudioTheme.type.rowLabel.copy(fontWeight = FontWeight.SemiBold),
                color = studio.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onAir != null) {
                Text(
                    text = onAir,
                    style = StudioTheme.type.caption,
                    color = studio.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (paused) {
            Icon(
                imageVector = StudioIcons.Pause,
                contentDescription = null,
                tint = studio.ink,
                modifier = Modifier.size(20.dp),
            )
        }
        if (audible) {
            // In the cue (`TvModePanel`, `PModePanel`): the lit colour on a television, on a disc of
            // thin glass; amber on a phone, where the cue marks what is active.
            val tv = StudioTheme.television
            Box(
                modifier = if (tv) Modifier.size(32.dp).glass(GlassMaterial.THIN, CircleShape) else Modifier,
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = StudioIcons.Audio,
                    contentDescription = stringResource(R.string.sport_sound_here),
                    tint = studio.cue,
                    modifier = Modifier.size(if (tv) 18.dp else 22.dp),
                )
            }
        }
        if (onChange != null) {
            PanelChangeButton(onClick = onChange)
        }
    }
}

/**
 * The phone's way to change what a panel shows (ADR-041 decision 11): a round button with the
 * swap glyph, at the end of a picture's caption and in the corner of the headlines. It is drawn
 * over the panel, so a tap on it reaches the picker and not the panel's own tap.
 */
@Composable
internal fun PanelChangeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.sport_menu_change),
) {
    val studio = StudioTheme.colors
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(CHANGE_BUTTON_SIZE)
            .background(studio.ground.copy(alpha = CHANGE_BUTTON_ALPHA), CircleShape),
    ) {
        Icon(
            imageVector = StudioIcons.Swap,
            contentDescription = label,
            tint = studio.ink,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The title of what is on air on [channel], from the guide, looked at again as each programme
 * ends; null when the guide has nothing for it — which, for several Israeli sports channels, is
 * most of the time.
 */
@Composable
private fun rememberOnAir(vm: AppViewModel, channel: Channel): String? {
    var title by remember(channel.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(channel.id) {
        while (true) {
            val now = System.currentTimeMillis()
            val programme = runCatching { vm.programmesFor(channel, now, now + 1) }
                .getOrNull()
                ?.firstOrNull { it.startUtc <= now && it.endUtc > now }
            title = programme?.title
            val until = programme?.endUtc ?: (now + ON_AIR_RECHECK_MS)
            delay((until - now).coerceIn(ON_AIR_MIN_WAIT_MS, ON_AIR_RECHECK_MS))
        }
    }
    return title
}

/**
 * News mode's panel of headlines (ADR-042): the screensaver's own column (FR-UI-33), from the
 * sources chosen for it in Settings, in its colours and at its speed, fitted to the panel. Read
 * while the panel is up as the screensaver reads them while it is up: on the repository's own
 * ten-minute limit, so a panel and a screensaver never fetch twice between them — and only while
 * [live], so an app left in the background on News mode fetches nothing.
 */
@Composable
internal fun FlashesPanel(live: Boolean, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    val prefs by ServiceLocator.settings.state.collectAsState()
    val digests by ServiceLocator.news.state.collectAsState()
    RefreshHeadlines(live)
    val flashes = remember(prefs.newsSources, digests) {
        NewsDigests.merge(prefs.newsSources, digests, System.currentTimeMillis())
    }
    BoxWithConstraints(modifier = modifier.background(studio.saverGround)) {
        if (flashes.isEmpty()) {
            Text(
                text = stringResource(R.string.news_flashes_none),
                style = StudioTheme.type.body,
                color = studio.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        } else {
            // The screensaver's column is drawn for a 380dp column at its scale of one; a panel
            // is read from nearer, so it takes its scale from a wider one (`FLASH_PANEL_WIDTH_DP`).
            val scale = (maxWidth.value / FLASH_PANEL_WIDTH_DP).coerceIn(FLASH_MIN_SCALE, FLASH_MAX_SCALE)
            // A panel is read, so the smallest parts keep a floor (`TvModeHeadlines`, `PModeHeadlines`).
            val floors = if (StudioTheme.television) FlashFloors.TELEVISION else FlashFloors.PHONE
            val units = SaverUnits(scale, LocalDensity.current, floors)
            FlashColumn(
                flashes = flashes,
                updatedUtc = digests.values.maxOfOrNull { it.fetchedUtc } ?: 0L,
                colours = prefs.screensaverColours,
                speed = prefs.flashSpeed,
                units = units,
                modifier = Modifier.fillMaxSize().padding(horizontal = units.dp(FLASH_SIDE_DP)),
            )
        }
    }
}

/**
 * Keeps the headlines fresh while [live], on the repository's own ten-minute limit. The panel of
 * headlines and the strip both ask; the limit and the repository's lock make the second ask a
 * no-op, so the two never fetch twice between them.
 */
@Composable
private fun RefreshHeadlines(live: Boolean) {
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        while (true) {
            ServiceLocator.news.refresh(force = false)
            delay(NewsRepository.CHECK_INTERVAL_MS)
        }
    }
}

/**
 * News mode's running strip of headlines (ADR-045), across the top of the screen in a band of its
 * own above the panels, so it covers no picture. The "Headlines" plate at the start edge stays put;
 * the headlines run toward it without stopping, rightwards in Hebrew, so each one comes in from its
 * first word. The same headlines, sources and colours as the panel of headlines and the
 * screensaver, at their speed times [TICKER_SPEED_FACTOR]: a line crosses a screen's width, not a
 * column's height.
 */
@Composable
internal fun NewsTicker(live: Boolean, modifier: Modifier = Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val prefs by ServiceLocator.settings.state.collectAsState()
    val digests by ServiceLocator.news.state.collectAsState()
    RefreshHeadlines(live)
    val flashes = remember(prefs.newsSources, digests) {
        NewsDigests.merge(prefs.newsSources, digests, System.currentTimeMillis())
    }
    val label = stringResource(R.string.news_flashes)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(studio.saverGround)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `TvModeTicker`: a plate of lit glass with white words — a label, so never the cue, which
        // is focus on a television and what is active on a phone.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxHeight()
                .background(studio.glassLit)
                .litEdge(RectangleShape)
                .padding(horizontal = 14.dp),
        ) {
            Text(
                text = label,
                style = type.h1.copy(fontWeight = FontWeight.SemiBold),
                color = studio.ink,
                maxLines = 1,
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds(),
        ) {
            if (flashes.isEmpty()) {
                Text(
                    text = stringResource(R.string.news_flashes_none),
                    style = type.body,
                    color = studio.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
                )
            } else {
                TickerLine(flashes = flashes, viewport = constraints.maxWidth.toFloat())
            }
        }
    }
}

/**
 * The strip's moving part: as many rounds of the headlines side by side as the width needs, plus
 * the one coming in, moved toward the start edge as one layer.
 */
@Composable
private fun TickerLine(flashes: List<SourcedFlash>, viewport: Float) {
    val prefs by ServiceLocator.settings.state.collectAsState()
    var roundWidth by remember { mutableIntStateOf(0) }
    val copies = feedCopies(viewport, roundWidth.toFloat())
    val speedPx = with(LocalDensity.current) {
        (prefs.flashSpeed.dpPerSecond * TICKER_SPEED_FACTOR).dp.toPx()
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Frame by frame, unlike the column's dozen steps a second: a line moving sideways at
    // this speed visibly jumps between coarse steps. Read only in the layer below, so a
    // frame redraws one layer and recomposes nothing.
    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val startedAt = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> elapsed = (now - startedAt) / 1_000_000_000f }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxHeight()
            .wrapContentWidth(align = Alignment.Start, unbounded = true)
            .graphicsLayer {
                // How far the copies have moved toward the start edge, from none to one
                // round's width, at which point the second copy stands where the first did.
                val travelled = feedOffset(elapsed, speedPx, roundWidth.toFloat()) + roundWidth
                translationX = if (rtl) travelled else -travelled
            },
    ) {
        repeat(copies) { copy ->
            TickerRound(
                flashes = flashes,
                colours = prefs.screensaverColours,
                modifier = if (copy == 0) {
                    Modifier.onSizeChanged { roundWidth = it.width }
                } else {
                    // The copies are scenery: a screen reader reads the headlines once.
                    Modifier.clearAndSetSemantics { }
                },
            )
        }
    }
}

/** One round of the strip: each headline's source dot, source, time and title, in a line. */
@Composable
private fun TickerRound(flashes: List<SourcedFlash>, colours: Boolean, modifier: Modifier) {
    val studio = StudioTheme.colors
    val type = StudioTheme.type
    val nowUtc = remember(flashes) { System.currentTimeMillis() }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        flashes.forEach { item ->
            val tone = studio.saverTones[item.colourIndex % studio.saverTones.size]
            Box(
                modifier = Modifier
                    .size(TICKER_DOT)
                    .clip(CircleShape)
                    .background(if (colours) tone else studio.ink3),
            )
            Text(
                text = item.source,
                style = type.caption.copy(fontWeight = FontWeight.SemiBold, textDirection = TextDirection.Content),
                color = if (colours) tone else studio.ink2,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp),
            )
            item.flash.publishedUtc?.let { at ->
                Text(
                    text = flashTime(at, nowUtc),
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = studio.ink2,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                text = item.flash.title,
                style = type.h1.copy(textDirection = TextDirection.Content),
                color = studio.ink,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 10.dp, end = TICKER_GAP),
            )
        }
    }
}

/**
 * One channel in the picker: its mark, its name, the playlist it comes from when the picker holds
 * more than one ([source]) — the same station can come free or through the provider, and only the
 * provider's copy spends a connection — and whether it is already in another panel.
 */
@Composable
internal fun SportPickerRow(
    channel: Channel,
    selected: Boolean,
    onScreenElsewhere: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    source: String? = null,
) {
    val studio = StudioTheme.colors
    FocusableRow(
        onClick = onClick,
        selected = selected,
        contentDescription = listOfNotNull(channel.displayName, source).joinToString(", "),
        focusRequester = focusRequester,
    ) {
        ChannelLogo(channel, sizeDp = 28)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.displayName,
                style = StudioTheme.type.rowLabel,
                color = studio.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (source != null) {
                Text(
                    text = source,
                    style = StudioTheme.type.caption,
                    color = studio.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onScreenElsewhere) {
            Text(
                text = stringResource(R.string.sport_pick_on_screen),
                style = StudioTheme.type.caption,
                color = studio.ink2,
                maxLines = 1,
            )
        }
    }
}

/**
 * The panel width at which the headlines are drawn at the screensaver's own size. A panel is read
 * from nearer than a screensaver, so it takes its scale from a wider one than the column's 380dp:
 * on a television the larger panel (58% of 960dp) lands at about one, the smaller at the floor.
 * Was 460dp with a ceiling of 1.25 until 0.17.3, which the owner found a little large.
 */
/**
 * The strip's speed against the column's: the screensaver's slow is 10dp a second, which reads
 * well going down a column and crawls across a screen; five times it is a news channel's ticker.
 */
private const val TICKER_SPEED_FACTOR = 5f
private val TICKER_DOT = 7.dp
private val TICKER_GAP = 36.dp

private const val FLASH_PANEL_WIDTH_DP = 560f
private const val FLASH_MIN_SCALE = 0.7f
private const val FLASH_MAX_SCALE = 1.1f
private const val FLASH_SIDE_DP = 18f

private val CHANGE_BUTTON_SIZE = 36.dp
private const val CHANGE_BUTTON_ALPHA = 0.55f

private const val CAPTION_SCRIM_ALPHA = 0.85f
private const val FAILURE_PLATE_ALPHA = 0.8f

/** How long the caption stays over a panel that fills the screen. */
private const val CAPTION_HOLD_MS = 4_000L

/** How often to look at the guide again when it says nothing about the hour. */
private const val ON_AIR_RECHECK_MS = 5 * 60_000L
private const val ON_AIR_MIN_WAIT_MS = 30_000L
