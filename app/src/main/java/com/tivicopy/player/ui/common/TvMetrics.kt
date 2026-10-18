package com.tivicopy.player.ui.common

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The dimensions the browse screens lay themselves out with.
 *
 * Every one of these used to be a compile-time constant shared by both form factors, which is why
 * the ten-foot shell looked the same on a 24" monitor and a 65" panel: a 1080p television reports
 * about 960dp of width, a 4K one can report far more, and nothing in the layout ever asked. The
 * values here are derived from the viewport instead, so the guide gets wider hours and taller rows
 * on a bigger screen rather than more of the same small ones.
 *
 * [Phone] holds the original constants. The touch shell keeps them: a phone is held at arm's
 * length and its layout was already tuned for that, so nothing about this change is aimed at it.
 */
@Immutable
data class TvMetrics(
    /** Width of one hour of the guide's time axis. Programme width derives from this alone. */
    val guideHourWidth: Dp,
    /** Height of one guide row, and so of one programme cell. */
    val guideRowHeight: Dp,
    /** Width of the guide's channel column, to the left of the time grid. */
    val guideChannelColumnWidth: Dp,
    /** Width of the channel list's group column. */
    val groupColumnWidth: Dp,
    /** Width of the channel list's now/next pane. */
    val nowNextPaneWidth: Dp,
    /** Edge length of a channel logo in a list row. */
    val rowLogoSize: Int,
    /** Width of the home screen's side menu while it is open (Studio `--st-w-drawer`). */
    val homeNavRailWidth: Dp,
    /**
     * Width of the side menu's closed strip of icons, which is what it shows until focus enters
     * it (Studio `--st-w-rail`, ADR-027). Every row on the home screen keeps this much clear at its
     * leading edge, and never the open width: the menu opens *over* the screen rather than
     * pushing it, so nothing reflows as focus crosses into it. The room is kept by each row's own
     * content padding rather than by insetting the list, so the rails stay full-bleed under it.
     */
    val homeNavRailCollapsedWidth: Dp,
    /**
     * How much of the panel the home screen's hero banner claims before the first rail of cards.
     * From the height, not the width: the banner is full-bleed, and what matters is that a slice
     * of the rail below it is still visible so the screen reads as scrollable.
     */
    val homeHeroHeight: Dp,
    /** Width of one card on a home-screen rail. */
    val homeCardWidth: Dp,
    /**
     * The corner every card and focusable list row is cut to (Studio `--st-r-card`).
     *
     * A phone row is read at arm's length and a television row from three metres, where the
     * original 8dp read as a plain rectangle. Giving the ten-foot shell a card corner is what
     * makes the channel list, the radio list and the guide belong to the same family as the home
     * rails and the filmstrip instead of looking like a settings screen behind them.
     */
    val rowCorner: Dp,
    /**
     * How thick a focused row's ring is. Thicker on a television for the same reason every other
     * ring in that shell is (NFR-UX-02): it is read at distance, and often against moving video.
     */
    val focusRingWidth: Dp,
    /** The corner of a button (Studio `--st-r-control`). */
    val controlCorner: Dp,
    /** The corner of a chip, such as the guide's day strip (Studio `--st-r-chip`). */
    val chipCorner: Dp,
    /** The corner of a floating panel and of every menu (Studio `--st-r-panel`). */
    val panelCorner: Dp,
    /**
     * How much a focused card or button grows (Studio `--st-focus-scale`). One on a phone, where
     * nothing has focus in the ten-foot sense and a tile that grows under a finger reads as a bug.
     */
    val focusScale: Float,
    /** How much a focused list row grows: less than a card, because a row is the width of a panel. */
    val rowFocusScale: Float,
) {
    companion object {
        /**
         * The values every screen used before this type existed, and what the touch shell still
         * uses. Its shapes and focus are Studio's phone values (`studio-phone.css`).
         */
        val Phone = TvMetrics(
            guideHourWidth = 240.dp,
            guideRowHeight = 64.dp,
            guideChannelColumnWidth = 190.dp,
            groupColumnWidth = 220.dp,
            nowNextPaneWidth = 280.dp,
            rowLogoSize = 36,
            homeNavRailWidth = 240.dp,
            homeNavRailCollapsedWidth = 56.dp,
            homeHeroHeight = 320.dp,
            homeCardWidth = 180.dp,
            rowCorner = 10.dp,
            focusRingWidth = 2.dp,
            controlCorner = 12.dp,
            chipCorner = 18.dp,
            panelCorner = 20.dp,
            focusScale = 1f,
            rowFocusScale = 1f,
        )
    }
}

/**
 * The width a 1080p television reports, which is what the original constants were chosen against.
 * Scaling is expressed relative to it so a 1080p panel comes out unchanged.
 */
private const val REFERENCE_WIDTH_DP = 960f

/**
 * How many guide rows the ten-foot layout aims to show at once.
 *
 * Chosen for reading distance rather than density: the phone fits far more, and a television that
 * fits twelve rows of 64dp is showing text nobody across the room can read. The row height falls
 * out of this and the available height, which is also what makes the guide's query window honest —
 * it now asks for the rows that are actually on screen.
 */
private const val TARGET_GUIDE_ROWS = 7

/**
 * Derives the ten-foot dimensions for a viewport.
 *
 * Pure, and deliberately so: it is the one part of this layout that can be tested without a
 * device, and the repository has no instrumentation tier to test the rest in.
 *
 * @param widthDp width available to the shell, in dp
 * @param heightDp height available to the shell, in dp
 */
fun tvMetricsFor(widthDp: Float, heightDp: Float): TvMetrics {
    // Clamped rather than proportional without limit. A 4K panel that reports 1920dp would
    // otherwise double every dimension, which is not what a bigger television wants — the viewer
    // does not sit twice as far away, so past a point the extra pixels should buy more content,
    // not larger content.
    val scale = (widthDp / REFERENCE_WIDTH_DP).coerceIn(MIN_SCALE, MAX_SCALE)
    val base = TvMetrics.Phone
    return TvMetrics(
        guideHourWidth = base.guideHourWidth * scale,
        // From the height, not the scale: the point of the row height is how many rows fit.
        guideRowHeight = (heightDp / TARGET_GUIDE_ROWS).coerceIn(MIN_ROW_HEIGHT, MAX_ROW_HEIGHT).dp,
        guideChannelColumnWidth = base.guideChannelColumnWidth * scale,
        groupColumnWidth = base.groupColumnWidth * scale,
        nowNextPaneWidth = base.nowNextPaneWidth * scale,
        rowLogoSize = (base.rowLogoSize * scale).toInt(),
        homeNavRailWidth = base.homeNavRailWidth * scale,
        // Never narrower than Studio's 56dp strip: below that a focused icon's ring is cropped.
        homeNavRailCollapsedWidth = (base.homeNavRailCollapsedWidth * scale).coerceAtLeast(base.homeNavRailCollapsedWidth),
        homeHeroHeight = heroHeightFor(heightDp).dp,
        homeCardWidth = base.homeCardWidth * scale,
        // Flat, not scaled. These are about how a shape reads at a viewing distance, and a
        // bigger panel does not move the sofa: a 4K television wants the same corner and the same
        // ring as a 1080p one, which is exactly what scaling them would take away.
        rowCorner = TV_CARD_CORNER,
        focusRingWidth = TV_FOCUS_RING_WIDTH,
        controlCorner = TV_CONTROL_CORNER,
        chipCorner = TV_CHIP_CORNER,
        panelCorner = TV_PANEL_CORNER,
        focusScale = TV_FOCUS_SCALE,
        rowFocusScale = TV_ROW_FOCUS_SCALE,
    )
}

/**
 * How tall a guide row is where the grid sits under the docked picture (`PictureInGuide`).
 *
 * [TvMetrics.guideRowHeight] is cut from the whole panel: a seventh of its height. The television
 * guide never has the whole panel for its rows — the top bar, the preview beside the focused
 * programme, the day strip and the hour ruler come first — so on a 1080p panel that seventh left
 * room for fewer than two rows under them, and the guide read as a picture with a grid's edge
 * showing beneath it. This takes the height the grid actually has, less the strip and the ruler
 * the grid draws above its rows, and cuts it into [DOCKED_GUIDE_ROWS] instead: six, as the Studio
 * canvas (`Guide.dc.html`) draws under its preview.
 *
 * Floored where a cell's two lines of Studio type still fit (`GuideCell.dc.html` draws a 29dp
 * cell; the floor keeps a little more air than that), and capped at the same ceiling as the
 * panel's own row, so a 4K panel buys rows rather than taller ones.
 *
 * @param gridHeightDp the height handed to the guide under the picture, strip and ruler included
 */
fun dockedGuideRowHeightFor(gridHeightDp: Float): Dp {
    val rows = (gridHeightDp - DOCKED_GUIDE_CHROME_HEIGHT).coerceAtLeast(0f)
    return (rows / DOCKED_GUIDE_ROWS).coerceIn(MIN_DOCKED_ROW_HEIGHT, MAX_ROW_HEIGHT).dp
}

/** How many rows the guide under the docked picture aims to show at once. */
private const val DOCKED_GUIDE_ROWS = 6

/**
 * What the guide draws above its rows on a television: the day strip with the window buttons
 * beside it, the hour ruler, and the hairlines between them. Estimated once rather than measured,
 * because a row height that depended on measuring the chrome would arrive one frame late and the
 * grid would be laid out twice on every opening.
 */
private const val DOCKED_GUIDE_CHROME_HEIGHT = 66f

/**
 * The least a docked row may be: the ten-foot cell's title and time (21px and 17px on the 1080p
 * canvas, at Studio's line heights) with their padding, and a few dp of air.
 */
private const val MIN_DOCKED_ROW_HEIGHT = 40f

/**
 * How tall the home banner is on a given panel.
 *
 * A fraction of the height, then bounded twice: never so short that the banner stops being the
 * screen, never so tall that the first rail of cards is entirely below the fold — a home screen
 * whose rails are invisible until you press Down is one nobody scrolls. The second bound is
 * applied with `coerceAtMost` against a floor rather than a single `coerceIn`, because on an
 * absurd viewport (the tests pass 1dp) the two limits cross and `coerceIn` throws.
 */
private fun heroHeightFor(heightDp: Float): Float {
    val wanted = (heightDp * HERO_HEIGHT_FRACTION).coerceIn(MIN_HERO_HEIGHT, MAX_HERO_HEIGHT)
    val roomForATeaser = (heightDp - RAIL_TEASER_HEIGHT).coerceAtLeast(MIN_HERO_HEIGHT)
    return wanted.coerceAtMost(roomForATeaser)
}

/** The banner's share of the panel, before the bounds below are applied. */
private const val HERO_HEIGHT_FRACTION = 0.58f
private const val MIN_HERO_HEIGHT = 160f
private const val MAX_HERO_HEIGHT = 560f

/** How much of the first rail has to stay on screen under the banner. */
private const val RAIL_TEASER_HEIGHT = 150f

private const val MIN_SCALE = 0.85f
private const val MAX_SCALE = 1.6f

/*
 * The ten-foot shapes and focus, from the Studio canvas (`docs/design/studio/studio.css`), whose
 * 1080p pixels are half a dp. A card and a row share a corner, so a channel row and a home tile
 * are cut the same way.
 */
private val TV_CARD_CORNER = 6.dp
private val TV_CONTROL_CORNER = 7.dp
private val TV_CHIP_CORNER = 6.dp
private val TV_PANEL_CORNER = 12.dp

/** The cue ring: 4px on the canvas. One width for every ring the television shell draws. */
private val TV_FOCUS_RING_WIDTH = 2.dp

private const val TV_FOCUS_SCALE = 1.05f
private const val TV_ROW_FOCUS_SCALE = 1.02f

/** Floors and ceilings for the derived row height, so an odd viewport cannot produce a silly one. */
private const val MIN_ROW_HEIGHT = 56f
private const val MAX_ROW_HEIGHT = 112f

/**
 * The metrics in force for the surrounding layout.
 *
 * Defaults to [TvMetrics.Phone], so every screen behaves exactly as it did before unless the
 * television shell has supplied something else. That is what lets the guide and the channel list
 * stay shared between the two shells instead of forking into TV and touch copies.
 */
val LocalTvMetrics = compositionLocalOf { TvMetrics.Phone }
