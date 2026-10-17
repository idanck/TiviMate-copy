package com.tivicopy.player.ui.tv

import kotlin.math.roundToInt

/**
 * Where Sport mode's panels go (ADR-041), in pixels, free of Compose so it can be tested.
 *
 * Four panels on a screen wider than it is tall — every television, a phone lying down — are a
 * fixed two-by-two grid whose lines move toward the panels that are not in focus. The panel in
 * focus gets [FOCUS_SHARE] of the width and of the height, so it is the largest and keeps the
 * screen's own shape; the one across the diagonal is the smallest, the same shape again; the two
 * beside it are letterboxed or pillarboxed by the player.
 *
 * Two panels stand side by side on a wide screen and one above the other on a tall one, the
 * panel in focus taking [FOCUS_SHARE] of the length they share. Four on a tall screen — a phone
 * held upright — are one column, the panel in focus taking [COLUMN_FOCUS_SHARE] of the height:
 * a two-by-two grid there would make every picture a quarter of a phone's width.
 *
 * Nothing ever overlaps — overlapping video surfaces have no defined order on Android — and no
 * panel changes place, so the arrows on the remote always mean the same neighbour.
 *
 * Expanded, the panel in focus fills the screen and the others measure nothing: they stay
 * composed, and their players keep running, so going back to the grid shows them at once.
 */
internal object SportLayout {

    /** The most panels Sport mode has: two by two (`docs/02` section 2.11). */
    const val SLOTS = 4

    /** The fewer panels the phone also offers (ADR-041). */
    const val PAIR = 2

    /** The part of the width and of the height the panel in focus takes. */
    const val FOCUS_SHARE = 0.58f

    /** The part of the height the panel in focus takes in a column of four. */
    const val COLUMN_FOCUS_SHARE = 0.4f

    /** One panel's rectangle, in pixels from the top start corner. */
    data class Cell(val x: Int, val y: Int, val width: Int, val height: Int)

    /** [count] as Sport mode understands it: two, or else four. */
    fun panels(count: Int): Int = if (count <= PAIR) PAIR else SLOTS

    /**
     * @param focused the panel in focus: in a grid the top row first, start edge first
     * @param gap the black line between panels
     * @param count how many panels there are, two or four; one cell comes back for each
     */
    fun cells(
        width: Int,
        height: Int,
        focused: Int,
        expanded: Boolean,
        gap: Int,
        count: Int = SLOTS,
    ): List<Cell> {
        val n = panels(count)
        val target = focused.coerceIn(0, n - 1)
        if (expanded) {
            return List(n) { if (it == target) Cell(0, 0, width, height) else Cell(0, 0, 0, 0) }
        }
        val wide = width >= height
        return when {
            n == SLOTS && wide -> {
                val columns = spans(width, gap, 2, target % 2, FOCUS_SHARE)
                val rows = spans(height, gap, 2, target / 2, FOCUS_SHARE)
                List(n) { slot ->
                    val (x, w) = columns[slot % 2]
                    val (y, h) = rows[slot / 2]
                    Cell(x, y, w, h)
                }
            }
            wide -> spans(width, gap, n, target, FOCUS_SHARE).map { (x, w) -> Cell(x, 0, w, height) }
            else -> {
                val share = if (n == SLOTS) COLUMN_FOCUS_SHARE else FOCUS_SHARE
                spans(height, gap, n, target, share).map { (y, h) -> Cell(0, y, width, h) }
            }
        }
    }

    /**
     * [n] spans across [length] with [gap] between them, as (start, size): the one at [big] takes
     * [share] of the length and the rest split what is left evenly, the last taking any remainder.
     */
    private fun spans(length: Int, gap: Int, n: Int, big: Int, share: Float): List<Pair<Int, Int>> {
        val usable = (length - gap * (n - 1)).coerceAtLeast(0)
        val large = (usable * share).roundToInt()
        val small = (usable - large) / (n - 1)
        val sizes = MutableList(n) { if (it == big) large else small }
        // Whatever rounding left over goes to the last span, so the spans always fill the length.
        sizes[n - 1] += usable - sizes.sum()
        var start = 0
        return sizes.map { size -> (start to size).also { start += size + gap } }
    }
}
