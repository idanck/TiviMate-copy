package com.tivicopy.player.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.tivicopy.player.R
import com.tivicopy.player.core.TimeModel
import com.tivicopy.player.ui.common.LocalTvMetrics
import com.tivicopy.player.ui.common.Motion
import com.tivicopy.player.ui.common.animatedColour
import com.tivicopy.player.ui.common.focusLift
import com.tivicopy.player.ui.theme.StudioTheme
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * One entry in the side menu. A null [overlay] is the entry for the screen the menu sits on.
 */
internal data class MenuDestination(
    val label: String,
    val icon: ImageVector,
    val overlay: TvOverlay?,
)

/**
 * Studio's side menu (`docs/design/studio/canvas/SideMenu.dc.html`, ADR-027).
 *
 * Full height, on the start edge — the right in Hebrew. Closed, it is a strip of icons that is
 * always on screen, at the same height each icon sits at when the menu is open, so nothing moves
 * under the remote as it opens. It opens the moment focus enters it and closes when focus leaves,
 * and it opens *over* the screen rather than beside it: the content is inset by the strip's width
 * only ([sideMenuInset]), so focus crossing in never reflows a card. While it is open the rest of
 * the screen is dimmed, which is what says the menu is now the screen.
 *
 * This replaces ADR-020's collapsing rail and ADR-022's floating capsule.
 *
 * Every entry carries its label as a content description whether or not it is drawn, so the
 * closed strip is still a list of named destinations to TalkBack and to the D-pad (NFR-UX-05).
 *
 * @param destinations the main destinations, drawn first
 * @param tools the secondary ones (search, settings), drawn under a hairline
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SideMenu(
    destinations: List<MenuDestination>,
    tools: List<MenuDestination>,
    onOpen: (TvOverlay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalTvMetrics.current
    val studio = StudioTheme.colors
    var open by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        targetValue = if (open) metrics.homeNavRailWidth else metrics.homeNavRailCollapsedWidth,
        animationSpec = tween(Motion.ENTRANCE, easing = Motion.Ease),
        label = "sideMenuWidth",
    )
    // Open, the entries sit further in from the screen's edge than they do in the strip
    // (`--st-safe-x` against the strip's own centring); they glide there with the width.
    val startPadding by animateDpAsState(
        targetValue = if (open) SAFE_X else MENU_PADDING,
        animationSpec = tween(Motion.ENTRANCE, easing = Motion.Ease),
        label = "sideMenuPadding",
    )
    Box(modifier = modifier.fillMaxSize()) {
        // The screen behind an open menu (`--st-dim-open`).
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(Motion.ENTRANCE, easing = Motion.Ease)),
            exit = fadeOut(tween(Motion.ENTRANCE, easing = Motion.Ease)),
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
                .focusGroup()
                .width(width)
                .background(animatedColour(if (open) studio.drawer else studio.rail))
                .padding(top = SAFE_Y, bottom = SAFE_Y, start = startPadding, end = MENU_PADDING),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MenuHeader(open = open)
            destinations.forEach { MenuEntry(it, open, onOpen) }
            Box(
                modifier = Modifier
                    .padding(vertical = 7.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(studio.hairline),
            )
            tools.forEach { MenuEntry(it, open, onOpen) }
        }
        // The inner edge: a hairline along the full height of the menu.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = width)
                .width(1.dp)
                .fillMaxHeight()
                .background(studio.hairline),
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
private fun MenuHeader(open: Boolean) {
    val studio = StudioTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .padding(bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                // Centred on the icons below it.
                .padding(start = ENTRY_ICON_INSET + (ENTRY_ICON - TALLY_DOT) / 2)
                .size(TALLY_DOT)
                .clip(CircleShape)
                .background(studio.tally),
        )
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(Motion.FAST, easing = Motion.Ease)),
            exit = fadeOut(tween(Motion.FAST, easing = Motion.Ease)),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = StudioTheme.type.titleSmall,
                color = studio.ink,
                maxLines = 1,
            )
        }
    }
}

/**
 * One entry: an icon, and its label while the menu is open.
 *
 * Studio's states (`SideMenu.dc.html`): focused is the cue with dark text, lifted; the screen
 * you are on is a `card`; anything else is bare, in the secondary ink.
 */
@Composable
private fun MenuEntry(
    destination: MenuDestination,
    open: Boolean,
    onOpen: (TvOverlay) -> Unit,
) {
    val metrics = LocalTvMetrics.current
    val studio = StudioTheme.colors
    var focused by remember { mutableStateOf(false) }
    val current = destination.overlay == null
    val shape = RoundedCornerShape(metrics.controlCorner)
    val fill = animatedColour(
        when {
            focused -> studio.cue
            current -> studio.card
            else -> studio.card.copy(alpha = 0f)
        },
    )
    val content = animatedColour(
        when {
            focused -> studio.onCue
            current -> studio.ink
            else -> studio.ink2
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ENTRY_HEIGHT)
            .focusLift(focused, scale = metrics.rowFocusScale, shape = shape)
            .clip(shape)
            .background(fill)
            .onFocusChanged { focused = it.isFocused }
            // `clickable` carries the focus target on its own; `focusable()` beside it would cost
            // the remote a second press to pass every entry.
            .clickable { destination.overlay?.let(onOpen) }
            .semantics { contentDescription = destination.label }
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
                    fontWeight = when {
                        focused -> FontWeight.Bold
                        current -> FontWeight.SemiBold
                        else -> FontWeight.Medium
                    },
                ),
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Studio's top bar (`TopBar.dc.html`): the app's name at the start, the clock and the date at the
 * end — the top-left corner in Hebrew, where the clock has always been.
 *
 * @param startInset room kept clear for the side menu's closed strip
 * @param showClock false while the radio backdrop behind is already showing a clock of its own
 */
@Composable
internal fun StudioTopBar(
    startInset: Dp,
    showClock: Boolean,
    modifier: Modifier = Modifier,
) {
    val studio = StudioTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TOP_SCRIM_HEIGHT)
            // `.st-scrim-top`: the name and the clock have to read over any frame of video.
            .background(Brush.verticalGradient(listOf(studio.ground.copy(alpha = 0.88f), studio.ground.copy(alpha = 0f)))),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_BAR_HEIGHT)
                .padding(start = startInset, end = SAFE_X),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(modifier = Modifier.size(TALLY_DOT).clip(CircleShape).background(studio.tally))
                Text(
                    text = stringResource(R.string.app_name),
                    style = StudioTheme.type.titleSmall,
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
 * The room content keeps clear at its start edge for the closed strip (`--st-inset-start`, the
 * strip plus 48px at 1080p).
 */
@Composable
internal fun sideMenuInset(): Dp = LocalTvMetrics.current.homeNavRailCollapsedWidth + SIDE_MENU_GAP

/*
 * Studio's ten-foot spacing, in dp (the canvas's 1080p pixels halved).
 */
private val SAFE_X = 48.dp
private val SAFE_Y = 27.dp
private val SIDE_MENU_GAP = 24.dp
private val MENU_PADDING = 12.dp
private val HEADER_HEIGHT = 52.dp
private val ENTRY_HEIGHT = 36.dp
private val ENTRY_ICON = 15.dp
/** Centres the icon in the strip's 32dp-wide entry (`width: 64px` at 1080p). */
private val ENTRY_ICON_INSET = 8.dp
private val TALLY_DOT = 8.dp
private val TOP_BAR_HEIGHT = 64.dp
private val TOP_SCRIM_HEIGHT = 110.dp

/** The clock is 36px at 1080p — h1's 34 nudged up, so it reads as the one number on the bar. */
private const val CLOCK_SCALE = 36f / 34f

private const val MINUTE_MS = 60_000L
