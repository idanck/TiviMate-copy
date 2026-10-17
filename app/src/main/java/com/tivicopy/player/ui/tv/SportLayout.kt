package com.tivicopy.player.ui.tv

import kotlin.math.roundToInt

/**
 * Where Sport mode's four panels go (ADR-041), in pixels, free of Compose so it can be tested.
 *
 * A fixed two-by-two grid whose lines move toward the panels that are not in focus. The panel in
 * focus gets [FOCUS_SHARE] of the width and of the height, so it is the largest and keeps the
 * screen's own shape; the one across the diagonal is the smallest, the same shape again; the two
 * beside it are letterboxed or pillarboxed by the player. Nothing ever overlaps — overlapping
 * video surfaces have no defined order on Android — and no panel changes place, so the arrows on
 * the remote always mean the same neighbour.
 *
 * Expanded, the panel in focus fills the screen and the other three measure nothing: they stay
 * composed, and their players keep running, so going back to the grid shows them at once.
 */
internal object SportLayout {

    /** How many panels Sport mode has: two by two (`docs/02` section 2.11). */
    const val SLOTS = 4

    /** The part of the width and of the height the panel in focus takes. */
    const val FOCUS_SHARE = 0.58f

    /** One panel's rectangle, in pixels from the top start corner. */
    data class Cell(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * @param focused the panel in focus, 0 to 3: the top row first, start edge first
     * @param gap the black line between panels
     */
    fun cells(width: Int, height: Int, focused: Int, expanded: Boolean, gap: Int): List<Cell> {
        val target = focused.coerceIn(0, SLOTS - 1)
        if (expanded) {
            return List(SLOTS) { if (it == target) Cell(0, 0, width, height) else Cell(0, 0, 0, 0) }
        }
        val columns = split(width, gap, bigFirst = target % 2 == 0)
        val rows = split(height, gap, bigFirst = target / 2 == 0)
        return List(SLOTS) { slot ->
            val (x, w) = columns[slot % 2]
            val (y, h) = rows[slot / 2]
            Cell(x, y, w, h)
        }
    }

    /** Two spans across [length] with [gap] between them, the larger one first or second. */
    private fun split(length: Int, gap: Int, bigFirst: Boolean): List<Pair<Int, Int>> {
        val usable = (length - gap).coerceAtLeast(0)
        val big = (usable * FOCUS_SHARE).roundToInt()
        val small = usable - big
        val first = if (bigFirst) big else small
        val second = usable - first
        return listOf(0 to first, (first + gap) to second)
    }
}
