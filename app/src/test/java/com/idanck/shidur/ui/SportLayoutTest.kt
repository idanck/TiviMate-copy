package com.idanck.shidur.ui

import com.idanck.shidur.ui.tv.SportLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sport mode's panels: the one in focus is the largest, and nothing overlaps (ADR-041). */
class SportLayoutTest {

    private val width = 1920
    private val height = 1080
    private val gap = 8

    @Test
    fun `the panel in focus is the largest, wherever it is`() {
        for (focused in 0 until SportLayout.SLOTS) {
            val cells = SportLayout.cells(width, height, focused, expanded = false, gap = gap)
            val areas = cells.map { it.width.toLong() * it.height }
            assertEquals("focus on $focused", areas.indices.maxByOrNull { areas[it] }, focused)
        }
    }

    @Test
    fun `the panel in focus keeps the screen's shape`() {
        val cell = SportLayout.cells(width, height, focused = 0, expanded = false, gap = gap)[0]
        val ratio = cell.width.toFloat() / cell.height
        assertEquals(16f / 9f, ratio, 0.02f)
    }

    @Test
    fun `the four panels tile the screen with the gap between them and never overlap`() {
        for (focused in 0 until SportLayout.SLOTS) {
            val cells = SportLayout.cells(width, height, focused, expanded = false, gap = gap)
            val (a, b, c, d) = cells
            assertEquals(width, a.width + gap + b.width)
            assertEquals(height, a.height + gap + c.height)
            assertEquals(a.x, c.x)
            assertEquals(b.x, d.x)
            assertEquals(a.y, b.y)
            assertEquals(c.y, d.y)
            assertEquals(a.width + gap, b.x)
            assertEquals(a.height + gap, c.y)
        }
    }

    @Test
    fun `panels keep their places when focus moves`() {
        val before = SportLayout.cells(width, height, focused = 0, expanded = false, gap = gap)
        val after = SportLayout.cells(width, height, focused = 3, expanded = false, gap = gap)
        // The top start panel stays at the top start, whatever its size.
        assertEquals(0, before[0].x); assertEquals(0, after[0].x)
        assertTrue(before[3].x > before[0].x && after[3].x > after[0].x)
        assertTrue(before[3].y > before[0].y && after[3].y > after[0].y)
    }

    @Test
    fun `expanded, the panel in focus fills the screen and the rest measure nothing`() {
        val cells = SportLayout.cells(width, height, focused = 2, expanded = true, gap = gap)
        assertEquals(SportLayout.Cell(0, 0, width, height), cells[2])
        cells.filterIndexed { i, _ -> i != 2 }.forEach {
            assertEquals(0, it.width)
            assertEquals(0, it.height)
        }
    }

    @Test
    fun `two panels stand side by side on a wide screen, the one in focus the wider`() {
        for (focused in 0 until 2) {
            val cells = SportLayout.cells(2400, 1080, focused, expanded = false, gap = gap, count = 2)
            assertEquals(2, cells.size)
            val (a, b) = cells
            assertEquals(2400, a.width + gap + b.width)
            assertEquals(a.width + gap, b.x)
            cells.forEach { assertEquals(0, it.y); assertEquals(1080, it.height) }
            assertTrue("focus on $focused", cells[focused].width > cells[1 - focused].width)
        }
    }

    @Test
    fun `held upright, panels stand one above the other and the one in focus is the tallest`() {
        for (count in listOf(2, 4)) {
            for (focused in 0 until count) {
                val cells = SportLayout.cells(1080, 2000, focused, expanded = false, gap = gap, count = count)
                assertEquals(count, cells.size)
                cells.forEach {
                    assertEquals(0, it.x)
                    assertEquals(1080, it.width)
                }
                assertEquals(0, cells.first().y)
                assertEquals(2000, cells.sumOf { it.height } + gap * (count - 1))
                cells.zipWithNext().forEach { (upper, lower) -> assertEquals(upper.y + upper.height + gap, lower.y) }
                assertEquals("focus on $focused of $count", focused, cells.indices.maxByOrNull { cells[it].height })
            }
        }
    }

    @Test
    fun `anything but two panels is four`() {
        assertEquals(2, SportLayout.panels(1))
        assertEquals(2, SportLayout.panels(2))
        assertEquals(4, SportLayout.panels(3))
        assertEquals(4, SportLayout.panels(4))
        assertEquals(2, SportLayout.cells(width, height, focused = 0, expanded = false, gap = gap, count = 2).size)
        assertEquals(4, SportLayout.cells(width, height, focused = 0, expanded = false, gap = gap, count = 3).size)
    }

    @Test
    fun `expanded with two, the panel in focus fills the screen and the other measures nothing`() {
        val cells = SportLayout.cells(width, height, focused = 1, expanded = true, gap = gap, count = 2)
        assertEquals(listOf(SportLayout.Cell(0, 0, 0, 0), SportLayout.Cell(0, 0, width, height)), cells)
    }
}
