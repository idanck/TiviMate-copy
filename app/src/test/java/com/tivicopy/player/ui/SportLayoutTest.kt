package com.tivicopy.player.ui

import com.tivicopy.player.ui.tv.SportLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sport mode's grid: the panel in focus is the largest, and nothing overlaps (ADR-041). */
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
}
