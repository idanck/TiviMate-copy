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
) {
    companion object {
        /**
         * The values every screen used before this type existed, and what the touch shell still
         * uses.
         */
        val Phone = TvMetrics(
            guideHourWidth = 240.dp,
            guideRowHeight = 64.dp,
            guideChannelColumnWidth = 190.dp,
            groupColumnWidth = 220.dp,
            nowNextPaneWidth = 280.dp,
            rowLogoSize = 36,
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
    )
}

private const val MIN_SCALE = 0.85f
private const val MAX_SCALE = 1.6f

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
