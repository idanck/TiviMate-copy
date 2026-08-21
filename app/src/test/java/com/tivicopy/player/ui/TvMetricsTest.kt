package com.tivicopy.player.ui

import com.tivicopy.player.ui.common.TvMetrics
import com.tivicopy.player.ui.common.tvMetricsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ten-foot layout's dimensions were compile-time constants until this type existed, which is
 * why the guide looked identical on a 1080p panel and a 4K one. `tvMetricsFor` is the pure half of
 * the fix, and the only half that can be checked without a television: this repository has no
 * instrumentation tier (docs/10), so anything provable on the JVM is proved here.
 */
class TvMetricsTest {

    /** What a 1080p television reports, and what the original constants were chosen against. */
    private val hd = 960f to 540f

    @Test
    fun `a 1080p panel keeps the widths the layout was written for`() {
        val metrics = tvMetricsFor(hd.first, hd.second)
        val base = TvMetrics.Phone
        assertEquals(base.guideHourWidth.value, metrics.guideHourWidth.value, 0.5f)
        assertEquals(base.guideChannelColumnWidth.value, metrics.guideChannelColumnWidth.value, 0.5f)
        assertEquals(base.groupColumnWidth.value, metrics.groupColumnWidth.value, 0.5f)
        assertEquals(base.nowNextPaneWidth.value, metrics.nowNextPaneWidth.value, 0.5f)
    }

    @Test
    fun `a wider panel buys wider hours, not the same hours repeated`() {
        val small = tvMetricsFor(hd.first, hd.second)
        val large = tvMetricsFor(1440f, 810f)
        assertTrue(
            "an hour of guide should be wider on a larger panel",
            large.guideHourWidth.value > small.guideHourWidth.value,
        )
        assertTrue(
            "the channel column should be wider on a larger panel",
            large.guideChannelColumnWidth.value > small.guideChannelColumnWidth.value,
        )
    }

    @Test
    fun `scaling is bounded, so an enormous panel does not simply double everything`() {
        val huge = tvMetricsFor(4000f, 2250f)
        val base = TvMetrics.Phone
        // The viewer does not sit twice as far from a 4K panel, so past a point the extra room
        // should buy more content rather than larger content.
        assertTrue(
            "scaling must be clamped",
            huge.guideHourWidth.value <= base.guideHourWidth.value * 1.6f + 0.5f,
        )
    }

    @Test
    fun `a narrow viewport does not shrink the layout without limit`() {
        val narrow = tvMetricsFor(320f, 480f)
        val base = TvMetrics.Phone
        assertTrue(
            "scaling must be floored",
            narrow.guideHourWidth.value >= base.guideHourWidth.value * 0.85f - 0.5f,
        )
    }

    @Test
    fun `row height follows the height available, so a set number of rows fits`() {
        val short = tvMetricsFor(960f, 420f)
        val tall = tvMetricsFor(960f, 900f)
        assertTrue(
            "a taller viewport should give taller rows",
            tall.guideRowHeight.value > short.guideRowHeight.value,
        )
    }

    @Test
    fun `row height stays inside a legible band whatever the viewport`() {
        // A 10-foot row that collapses is unreadable; one that grows without limit shows two
        // channels. Both ends are clamped, and both ends are worth asserting.
        for (height in listOf(1f, 200f, 540f, 1200f, 4000f)) {
            val rowHeight = tvMetricsFor(960f, height).guideRowHeight.value
            assertTrue("row height $rowHeight too small for $height", rowHeight >= 56f)
            assertTrue("row height $rowHeight too large for $height", rowHeight <= 112f)
        }
    }

    @Test
    fun `a logo never scales away to nothing`() {
        for (width in listOf(1f, 320f, 960f, 4000f)) {
            assertTrue(
                "logo size must stay usable at width $width",
                tvMetricsFor(width, 540f).rowLogoSize >= 24,
            )
        }
    }
}
