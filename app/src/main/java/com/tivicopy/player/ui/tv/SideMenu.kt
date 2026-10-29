package com.tivicopy.player.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.ui.common.FocusFill
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.Motion
import com.tivicopy.player.ui.common.StudioIcons
import com.tivicopy.player.ui.common.TvMetrics
import com.tivicopy.player.ui.common.drawerSurface
import com.tivicopy.player.ui.common.filledContentColour
import com.tivicopy.player.ui.common.focusLift
import com.tivicopy.player.ui.common.focusSurface
import com.tivicopy.player.ui.theme.StudioTheme
import java.util.Locale
import kotlinx.coroutines.delay

/** One entry in the side menu: what it says, its glyph, and the screen it opens. */
internal data class MenuDestination(
    val label: String,
    val icon: ImageVector,
    val overlay: TvOverlay,
)

/**
 * The side menu (`SideMenu.dc.html`; ADR-027, restyled by ADR-046).
 *
 * Full height, on the start edge — the right in Hebrew. Closed, it is a rail of icons in smoky
 * glass that is always on screen, at the same height each icon sits at when the menu is open, so
 * nothing moves under the remote as it opens. It opens the moment focus enters it and closes when
 * focus leaves, and it opens *over* the screen rather than beside it, as a drawer: solid next to
 * the screen's edge and fading over its last third towards the content (`Glass.kt`). The content is
 * inset by the rail's width only ([sideMenuInset]), so focus crossing in never reflows a card.
 * While it is open the rest of the screen is dimmed, which is what says the menu is now the screen.
 *
 * The screen you are on is amber — its glyph and label, a wash of amber glass with an amber edge,
 * and a short amber bar on the screen's edge beside it — and the entry the remote is on is the lit
 * surface. The two are never the same colour, so "where I am" and "where the remote is" can both be
 * read at once.
 *
 * It is the television's one navigation (ADR-035): `TvRoot` draws this single instance over every
 * browse screen, home included, so the same rail with the same entries in the same order is on
 * screen wherever the viewer is, and only which entry is current changes. Choosing an entry hands
 * the remote to the screen it opened, as a press towards the content would, so the menu closes
 * rather than staying open over the screen just chosen.
 *
 * Every entry carries its label as a content description whether or not it is drawn, so the
 * closed rail is still a list of named destinations to TalkBack and to the D-pad (NFR-UX-05).
 *
 * @param current the overlay on screen; the entry that opens it is drawn as the current one, and a
 *   screen with no entry of its own (on demand, adding a source) lights none
 * @param onTowardContent hands the remote back to a screen the D-pad cannot find from here on its
 *   own, on a press towards the content and on the current entry; false when it could not, which
 *   leaves the press to the D-pad. Null leaves it to the D-pad always. Home needs it: its rails
 *   run full-bleed under the rail, so to the D-pad they are not beside the menu at all, and the
 *   press went nowhere (device report, 2026-10-16); see `HomeFocusReturn`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SideMenu(
    current: TvOverlay,
    onOpen: (TvOverlay) -> Unit,
    modifier: Modifier = Modifier,
    onTowardContent: (() -> Boolean)? = null,
) {
    val metrics = LocalTvMetrics.current
    val studio = StudioTheme.colors
    var open by remember { mutableStateOf(false) }
    // The canvas's order (`SideMenu.dc.html`): the screens, then the tools under a hairline. On
    // demand is not among them, as on the phone (ADR-032): search reaches it where a source has any.
    // Sport mode follows the radio (ADR-041). It is the one entry whose screen draws no menu of
    // its own: its pictures fill it, and Back is the way out.
    val destinations = listOf(
        MenuDestination(stringResource(R.string.nav_home), StudioIcons.Home, TvOverlay.HOME),
        MenuDestination(stringResource(R.string.nav_live), StudioIcons.Tv, TvOverlay.CHANNELS),
        MenuDestination(stringResource(R.string.nav_guide), StudioIcons.Guide, TvOverlay.GUIDE),
        MenuDestination(stringResource(R.string.nav_radio), StudioIcons.Radio, TvOverlay.RADIO),
        MenuDestination(stringResource(R.string.nav_sport), StudioIcons.Sport, TvOverlay.SPORT),
        // News mode (ADR-042) follows Sport mode, and is the same kind of screen.
        MenuDestination(stringResource(R.string.nav_news), StudioIcons.News, TvOverlay.NEWS),
        MenuDestination(stringResource(R.string.nav_recordings), StudioIcons.Record, TvOverlay.RECORDINGS),
    )
    val tools = listOf(
        MenuDestination(stringResource(R.string.nav_search), StudioIcons.Search, TvOverlay.SEARCH),
        MenuDestination(stringResource(R.string.nav_settings), StudioIcons.Settings, TvOverlay.SETTINGS),
    )

    // The content lies towards the end edge: the left in Hebrew, the right in English.
    val focusManager = LocalFocusManager.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val towardContent = if (rtl) FocusDirection.Left else FocusDirection.Right
    // The remote's own key for it: the D-pad's arrows are physical, whatever the layout direction.
    val towardContentKey = if (rtl) Key.DirectionLeft else Key.DirectionRight
    // Into the content: handed back by the screen when it takes it, otherwise left to the D-pad.
    val enterContent: () -> Unit = {
        if (onTowardContent?.invoke() != true) focusManager.moveFocus(towardContent)
    }
    // Once the screen an entry opened is composed, the remote moves into it — unless that screen
    // has already claimed it for its own first focus (home's banner, the playing channel's row),
    // which is the better answer and is left alone. Two frames: one for the new screen to compose,
    // one for its own claim to land.
    LaunchedEffect(current) {
        if (!open) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (open) enterContent()
    }
    // The current entry is reported too, though it changes nothing on screen: it is still a choice
    // made from the menu, and a screen opened from the video stops being one once it is (ADR-036).
    val choose: (TvOverlay) -> Unit = { overlay ->
        onOpen(overlay)
        if (overlay == current) enterContent()
    }
    val width by animateDpAsState(
        targetValue = if (open) metrics.homeNavRailWidth else metrics.homeNavRailCollapsedWidth,
        animationSpec = tween(Motion.ENTRANCE, easing = Motion.Ease),
        label = "sideMenuWidth",
    )
    // How far the rail has become the drawer, read where it is drawn.
    val openness = animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(Motion.ENTRANCE, easing = Motion.Ease),
        label = "sideMenuOpenness",
    )
    // Open, the entries sit in from the screen's edge and from the content by the drawer's own
    // padding; closed, they are centred in the rail. They glide there with the width.
    val startPadding by animateDpAsState(
        targetValue = if (open) DRAWER_PAD_START else MENU_PADDING,
        animationSpec = tween(Motion.ENTRANCE, easing = Motion.Ease),
        label = "sideMenuPaddingStart",
    )
    val endPadding by animateDpAsState(
        targetValue = if (open) DRAWER_PAD_END else MENU_PADDING,
        animationSpec = tween(Motion.ENTRANCE, easing = Motion.Ease),
        label = "sideMenuPaddingEnd",
    )
    Box(modifier = modifier.fillMaxSize()) {
        // The screen behind an open menu (`--st-dim-open`).
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(Motion.ENTRANCE, easing = Motion.Ease)),
            exit = fadeOut(tween(Motion.EXIT, easing = Motion.Ease)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(studio.dimOpen))
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxHeight()
                // Before `focusGroup`, so it observes the group's own focus: `hasFocus` is true
                // while any entry inside is focused, which is exactly when the menu is open.
                .onFocusChanged { open = it.hasFocus }
                // Ahead of the D-pad's own search, which finds nothing beside the menu on a screen
                // drawn under the rail. Handed back, the press is spent; otherwise the D-pad
                // carries on as on every other screen.
                .onPreviewKeyEvent { event ->
                    onTowardContent != null && event.type == KeyEventType.KeyDown &&
                        event.key == towardContentKey && onTowardContent()
                }
                .focusGroup()
                .width(width)
                // Closed: the rail's smoky glass, its inner edge and a short shadow. Open: the
                // drawer. The one crosses into the other as the menu opens.
                .railSurface(rtl = rtl, presence = { 1f - openness.value })
                .drawerSurface(atStart = true, presence = { openness.value })
                .padding(top = SAFE_Y, bottom = SAFE_Y),
            verticalArrangement = Arrangement.spacedBy(ENTRY_GAP),
        ) {
            MenuHeader(open = open, startPadding = startPadding)
            destinations.forEach {
                MenuEntry(
                    destination = it,
                    current = it.overlay == current,
                    open = open,
                    onChoose = choose,
                    startPadding = startPadding,
                    endPadding = endPadding,
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = startPadding + DIVIDER_INSET, end = endPadding + DIVIDER_INSET)
                    .padding(vertical = 5.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(studio.hairline),
            )
            tools.forEach {
                MenuEntry(
                    destination = it,
                    current = it.overlay == current,
                    open = open,
                    onChoose = choose,
                    startPadding = startPadding,
                    endPadding = endPadding,
                )
            }
        }
    }
}

/**
 * The closed rail's surface (`--st-rail`): smoky glass, a line of the lit edge along the side that
 * faces the content, and a short shadow falling onto it. Drawn rather than laid out, at
 * [presence], so it can cross into the drawer as the menu opens.
 */
@Composable
private fun Modifier.railSurface(rtl: Boolean, presence: () -> Float): Modifier {
    val studio = StudioTheme.colors
    val tint = studio.rail
    val line = studio.edgeLine
    val shadowPx = with(LocalDensity.current) { RAIL_SHADOW.toPx() }
    return this.drawBehind {
        val amount = presence().coerceIn(0f, 1f)
        if (amount <= 0f) return@drawBehind
        drawRect(color = tint, alpha = amount)
        // The rail sits on the start edge: its inner side is the left in Hebrew.
        val inner = if (rtl) 0f else size.width
        drawLine(
            color = line,
            start = Offset(if (rtl) inner + 0.5f else inner - 0.5f, 0f),
            end = Offset(if (rtl) inner + 0.5f else inner - 0.5f, size.height),
            strokeWidth = 1.dp.toPx(),
            alpha = amount,
        )
        val dark = Color.Black.copy(alpha = RAIL_SHADOW_ALPHA)
        val clear = Color.Black.copy(alpha = 0f)
        drawRect(
            brush = if (rtl) {
                Brush.horizontalGradient(listOf(clear, dark), startX = inner - shadowPx, endX = inner)
            } else {
                Brush.horizontalGradient(listOf(dark, clear), startX = inner, endX = inner + shadowPx)
            },
            topLeft = Offset(if (rtl) inner - shadowPx else inner, 0f),
            size = Size(shadowPx, size.height),
            alpha = amount,
        )
    }
}

/**
 * The tally light, and the app's name beside it once the menu is open. The height is reserved
 * whether or not the name is drawn, so the entries below never jump as the menu opens.
 *
 * Its own composable rather than a `Box` inside the menu's `Column`: inside that `Box` the
 * `ColumnScope` would still be an implicit receiver, and `AnimatedVisibility` would resolve to
 * the `ColumnScope` extension, which does not compile there.
 */
@Composable
private fun MenuHeader(open: Boolean, startPadding: Dp) {
    val studio = StudioTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .padding(bottom = 13.dp)
            .padding(start = startPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TallyLight(
            // Centred on the icons below it.
            modifier = Modifier.padding(start = ENTRY_ICON_INSET + (ENTRY_ICON - TALLY_DOT) / 2),
        )
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(Motion.FAST, easing = Motion.Ease)),
            exit = fadeOut(tween(Motion.FAST, easing = Motion.Ease)),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = appNameStyle(),
                color = studio.ink,
                maxLines = 1,
            )
        }
    }
}

/**
 * One entry: an icon, and its label while the menu is open.
 *
 * The canvas's states (`SideMenu.dc.html`): the entry the remote is on is the lit surface, with
 * dark content, lifted; the screen you are on is amber — glyph, label, a wash of amber glass with
 * an amber edge, and an amber bar on the screen's edge; anything else is bare, in the second ink.
 * The bar sits outside the entry's own padding, on the menu's edge, so it is laid out here rather
 * than inside the entry's plate.
 */
@Composable
private fun MenuEntry(
    destination: MenuDestination,
    current: Boolean,
    open: Boolean,
    onChoose: (TvOverlay) -> Unit,
    startPadding: Dp,
    endPadding: Dp,
) {
    val metrics = LocalTvMetrics.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(metrics.controlCorner)
    val content = filledContentColour(focused = focused, selected = current, muted = true)
    Box(modifier = Modifier.fillMaxWidth().height(ENTRY_HEIGHT)) {
        if (current) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(width = CURRENT_BAR_WIDTH, height = CURRENT_BAR_HEIGHT)
                    // Rounded on the side towards the content, square against the screen's edge.
                    .clip(RoundedCornerShape(topEnd = CURRENT_BAR_WIDTH, bottomEnd = CURRENT_BAR_WIDTH))
                    .background(StudioTheme.colors.current),
            )
        }
        Row(
            modifier = Modifier
                .padding(start = startPadding, end = endPadding)
                .fillMaxWidth()
                .height(ENTRY_HEIGHT)
                .focusLift(focused, scale = metrics.rowFocusScale, shape = shape)
                // A menu row's treatment (`Focus.kt`): the lit surface under focus, amber glass
                // with an amber edge for the screen you are on.
                .focusSurface(focused = focused, shape = shape, selected = current, fill = FocusFill.LIT)
                .onFocusChanged { focused = it.isFocused }
                // `clickable` carries the focus target on its own; `focusable()` beside it would cost
                // the remote a second press to pass every entry.
                .clickable { onChoose(destination.overlay) }
                // Selected the way Material's own navigation rail marks its item: TalkBack says which
                // screen this is, and the emulator QA reads it to tell the screens apart (QA-24).
                .semantics {
                    contentDescription = destination.label
                    role = Role.Tab
                    selected = current
                }
                .padding(horizontal = ENTRY_ICON_INSET),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                modifier = Modifier.size(ENTRY_ICON),
                tint = content,
            )
            AnimatedVisibility(
                visible = open,
                enter = fadeIn(tween(Motion.FAST, easing = Motion.Ease)),
                exit = fadeOut(tween(Motion.FAST, easing = Motion.Ease)),
            ) {
                Text(
                    text = destination.label,
                    style = StudioTheme.type.nav.copy(
                        fontWeight = if (focused || current) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The app's tally light (`TopBar.dc.html`): the red dot before the app's name, with its halo and
 * glow drawn round it so the row keeps its size.
 */
@Composable
private fun TallyLight(modifier: Modifier = Modifier) {
    val tally = StudioTheme.colors.tally
    Box(
        modifier = modifier
            .size(TALLY_DOT)
            .drawBehind {
                val r = this.size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(tally.copy(alpha = TALLY_GLOW_ALPHA), tally.copy(alpha = 0f)),
                        center = center,
                        radius = r * 3.2f,
                    ),
                    radius = r * 3.2f,
                )
                drawCircle(color = tally.copy(alpha = TALLY_HALO_ALPHA), radius = r + 3.dp.toPx())
            }
            .clip(CircleShape)
            .background(tally),
    )
}

/** The app's name as the canvas sets it in the top bar and the open menu: 44px at 800, at 1080p. */
@Composable
private fun appNameStyle() = StudioTheme.type.titleSmall.let {
    it.copy(fontSize = it.fontSize * (44f / 40f), fontWeight = FontWeight.ExtraBold)
}

/**
 * The top bar (`TopBar.dc.html`): the tally light and the app's name at the start, the clock and
 * the date at the end — the top-left corner in Hebrew, where the clock has always been.
 *
 * @param startInset room kept clear for the side menu's closed rail
 * @param showClock false while the radio backdrop behind is already showing a clock of its own
 * @param scrim the gradient that lets it read over video; off as the head of an overlay screen,
 *   which already sits on its own panel and only needs the bar's own height
 * @param endInset the room kept at the end edge; an overlay that pads itself passes zero
 */
@Composable
internal fun StudioTopBar(
    startInset: Dp,
    showClock: Boolean,
    modifier: Modifier = Modifier,
    scrim: Boolean = true,
    endInset: Dp = SAFE_X,
) {
    val studio = StudioTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (scrim) TOP_SCRIM_HEIGHT else TOP_BAR_HEIGHT)
            // `.st-scrim-top`: the name and the clock have to read over any frame of video.
            .then(
                if (scrim) {
                    Modifier.background(
                        Brush.verticalGradient(listOf(studio.ground.copy(alpha = 0.82f), studio.ground.copy(alpha = 0f))),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_BAR_HEIGHT)
                .padding(start = startInset, end = endInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                TallyLight()
                Text(
                    text = stringResource(R.string.app_name),
                    style = appNameStyle(),
                    color = studio.ink,
                    maxLines = 1,
                )
            }
            if (showClock) TopBarClock()
        }
    }
}

/** The clock and, under it, the weekday and date (`חמישי 15.10`). */
@Composable
private fun TopBarClock() {
    val studio = StudioTheme.colors
    // The configuration's locale rather than the JVM default: the in-app language setting
    // (FR-UI-23) wraps this composition's context, so a Hebrew interface would otherwise print an
    // English day name (as `StationBackdrop` found).
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        ConfigurationCompat.getLocales(configuration).get(0) ?: Locale.getDefault()
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Woken on the minute: the clock shows minutes, and nothing here should redraw more often.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(MINUTE_MS - now % MINUTE_MS)
        }
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = TimeModel.ltr(TimeModel.formatClock(now)),
            style = StudioTheme.type.h1.copy(fontSize = StudioTheme.type.h1.fontSize * CLOCK_SCALE, fontWeight = FontWeight.SemiBold),
            color = studio.ink,
        )
        Text(
            text = TimeModel.formatShortDate(now, locale),
            style = StudioTheme.type.caption,
            color = studio.ink2,
        )
    }
}

/**
 * The room content keeps clear at its start edge for the closed rail (`--st-inset-browse`, the
 * rail plus 48px at 1080p).
 */
@Composable
internal fun sideMenuInset(): Dp = sideMenuInset(LocalTvMetrics.current)

/** [sideMenuInset] for metrics the caller already holds, outside the composition that provides them. */
internal fun sideMenuInset(metrics: TvMetrics): Dp = metrics.homeNavRailCollapsedWidth + SIDE_MENU_GAP

/*
 * The canvas's ten-foot spacing, in dp (its 1080p pixels halved).
 */
private val SAFE_X = 48.dp
private val SAFE_Y = 27.dp
private val SIDE_MENU_GAP = 24.dp
/** Centres the 32dp entries in the 56dp rail. */
private val MENU_PADDING = 12.dp
/** The open drawer's padding: 28px from the screen's edge, 32px from the content. */
private val DRAWER_PAD_START = 14.dp
private val DRAWER_PAD_END = 16.dp
/** The header: 74px high and 24 to 30px above the first entry. */
private val HEADER_HEIGHT = 50.dp
/** `--st-h-row`: 64px. */
private val ENTRY_HEIGHT = 32.dp
private val ENTRY_GAP = 2.dp
private val ENTRY_ICON = 16.dp
/** Centres the icon in the rail's 32dp-wide entry (`width: 64px` at 1080p). */
private val ENTRY_ICON_INSET = 8.dp
/** The current screen's bar on the screen's edge: 4 by 32px. */
private val CURRENT_BAR_WIDTH = 2.dp
private val CURRENT_BAR_HEIGHT = 16.dp
/** How much narrower than its entry the hairline between the screens and the tools is. */
private val DIVIDER_INSET = 6.dp
/** The rail's shadow on the content: `-24px 0 60px rgba(0, 0, 0, 0.30)`. */
private val RAIL_SHADOW = 30.dp
private const val RAIL_SHADOW_ALPHA = 0.3f
private val TALLY_DOT = 8.dp
/** The tally light's halo and glow (`0 0 0 6px …0.18, 0 0 18px …0.8`), softened for a radial. */
private const val TALLY_HALO_ALPHA = 0.18f
private const val TALLY_GLOW_ALPHA = 0.5f
private val TOP_BAR_HEIGHT = 64.dp
private val TOP_SCRIM_HEIGHT = 110.dp

/** The clock is 36px at 1080p — h1's 34 nudged up, so it reads as the one number on the bar. */
private const val CLOCK_SCALE = 36f / 34f

private const val MINUTE_MS = 60_000L
