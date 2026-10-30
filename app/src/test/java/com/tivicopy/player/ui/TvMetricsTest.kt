package com.tivicopy.player.ui

import com.tivicopy.player.ui.common.TvMetrics
import com.tivicopy.player.ui.common.dockedGuideRowHeightFor
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

    /** A spread of real panels, smallest first, to check a derived dimension against. */
    private val panels = listOf(800f to 480f, hd, 1280f to 720f, 1440f to 810f, 1920f to 1080f)

    /** `TV_CONTENT_INSET` applied on both sides by the shell's overlay scaffold. */
    private val CONTENT_INSET = 48f

    /** Half of `SIDE_MENU_GAP`: the room the home screen keeps beside the side menu's strip. */
    private val NAV_RAIL_MARGIN = 12f

    @Test
    fun `a 1080p panel keeps the widths the layout was written for`() {
        val metrics = tvMetricsFor(hd.first, hd.second)
        val base = TvMetrics.Phone
        assertEquals(base.guideHourWidth.value, metrics.guideHourWidth.value, 0.5f)
        assertEquals(base.guideChannelColumnWidth.value, metrics.guideChannelColumnWidth.value, 0.5f)
        assertEquals(base.groupColumnWidth.value, metrics.groupColumnWidth.value, 0.5f)
        assertEquals(base.nowNextPaneWidth.value, metrics.nowNextPaneWidth.value, 0.5f)
    }

    /**
     * The home screen's arithmetic invariants (ADR-016, rewritten for ADR-020).
     *
     * The layout they describe changed with the banner: the picture is no longer a boxed hero
     * beside its caption, so "rail + hero + caption fits across the panel" stopped meaning
     * anything. What replaces it is what the banner layout can actually get wrong — a banner so
     * tall the first rail is entirely below the fold, and a content column so narrow beside the
     * collapsed rail that a row holds one card. Neither shows up as an error: both simply produce
     * a home screen nobody here can look at.
     */
    /**
     * The guide under the docked picture (`PictureInGuide`). On a 1080p panel the top bar and the
     * picture's row take 242dp of 540, and what is left has to hold the strip, the ruler and a
     * grid worth opening the guide for: the panel's own row height fitted fewer than two rows
     * there, which is what the owner reported as barely seeing the guide at all.
     */
    @Test
    fun `the docked guide fits at least five rows on a 1080p panel`() {
        val top = 64f
        val preview = 162f + 16f
        val height = hd.second - top - preview
        val rows = (height - 66f) / dockedGuideRowHeightFor(height).value
        assertTrue("$rows rows", rows >= 5f)
        // And the rows are not the panel's: the seventh of the panel the shared metric cuts.
        assertTrue(dockedGuideRowHeightFor(height).value < tvMetricsFor(hd.first, hd.second).guideRowHeight.value)
    }

    @Test
    fun `a docked row never falls below the height its two lines of type need`() {
        listOf(0f, 1f, 100f, 200f).forEach { height ->
            assertTrue(dockedGuideRowHeightFor(height).value >= 40f)
        }
    }

    @Test
    fun `a docked row never grows past the panel row's own ceiling`() {
        panels.forEach { (_, h) ->
            val docked = dockedGuideRowHeightFor(h - 64f - 178f).value
            assertTrue("$docked", docked <= 112f)
        }
        assertEquals(112f, dockedGuideRowHeightFor(10_000f).value, 0.01f)
    }

    @Test
    fun `the banner always leaves a slice of the first rail on screen`() {
        panels.forEach { (w, h) ->
            val metrics = tvMetricsFor(w, h)
            val left = h - metrics.homeHeroHeight.value
            assertTrue(
                "a ${metrics.homeHeroHeight.value}dp banner leaves only ${left}dp of ${h}dp",
                left >= 120f,
            )
        }
    }

    @Test
    fun `a rail always has room for more than one card`() {
        panels.forEach { (w, h) ->
            val metrics = tvMetricsFor(w, h)
            // What the rails actually get: the panel, less the gutter every row keeps clear at
            // its leading edge for the side menu's closed strip and the gap beside it, less the
            // inset the rails apply at the trailing edge. See `sideMenuInset` in
            // `ui/tv/SideMenu.kt`.
            val gutter = metrics.homeNavRailCollapsedWidth.value + NAV_RAIL_MARGIN * 2
            val forCards = w - CONTENT_INSET - gutter
            assertTrue(
                "only ${forCards}dp of ${w}dp left for cards of ${metrics.homeCardWidth.value}dp",
                forCards >= metrics.homeCardWidth.value * 2,
            )
        }
    }

    /**
     * The rail expands over the screen rather than beside it, so the only thing the two widths
     * have to agree on is which is which — but a collapsed rail narrower than a focus ring around
     * an icon is a rail whose focus state is cropped.
     */
    @Test
    fun `the collapsed rail is narrower than the expanded one and still fits an icon`() {
        panels.forEach { (w, h) ->
            val metrics = tvMetricsFor(w, h)
            assertTrue(
                "collapsed ${metrics.homeNavRailCollapsedWidth.value} vs expanded ${metrics.homeNavRailWidth.value}",
                metrics.homeNavRailCollapsedWidth.value < metrics.homeNavRailWidth.value,
            )
            assertTrue(
                "a collapsed rail of ${metrics.homeNavRailCollapsedWidth.value}dp cannot hold an icon and its ring",
                metrics.homeNavRailCollapsedWidth.value >= 48f,
            )
        }
    }

    @Test
    fun `home dimensions scale with the panel and stay clamped`() {
        val small = tvMetricsFor(hd.first, hd.second)
        val large = tvMetricsFor(1440f, 810f)
        assertTrue(large.homeNavRailWidth.value > small.homeNavRailWidth.value)
        assertTrue(large.homeNavRailCollapsedWidth.value > small.homeNavRailCollapsedWidth.value)
        assertTrue(large.homeCardWidth.value > small.homeCardWidth.value)
        // The banner is the one home dimension taken from the height rather than the width scale,
        // because what it has to leave room for is the rail under it.
        assertTrue(large.homeHeroHeight.value > small.homeHeroHeight.value)
        // The same ceiling every other dimension obeys: a 4K panel reporting a huge width must
        // not simply double the furniture.
        val huge = tvMetricsFor(4000f, 2000f)
        assertEquals(
            (TvMetrics.Phone.homeNavRailWidth.value * 1.6f),
            huge.homeNavRailWidth.value,
            0.5f,
        )
        assertEquals("the banner is bounded too", 560f, huge.homeHeroHeight.value, 0.5f)
    }

    /**
     * `tvMetricsFor` is called with whatever a viewport reports, and the banner's height is bounded
     * from both ends — a floor for legibility and a ceiling so the rails survive. On a viewport
     * small enough for those two bounds to cross, the arithmetic must still produce a number
     * rather than throwing, which is exactly what a naive `coerceIn` would do.
     */
    @Test
    fun `the banner height survives an absurd viewport`() {
        for (height in listOf(1f, 120f, 200f, 540f, 4000f)) {
            val hero = tvMetricsFor(960f, height).homeHeroHeight.value
            assertTrue("banner height $hero for a ${height}dp panel", hero in 160f..560f)
        }
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

    /**
     * Merhav's shapes and focus (ADR-046), as the canvas draws them at 1080p. The pill is checked
     * first and on its own: it is a top-level value the television's chip corner is made from, and
     * top-level values are initialised in the order they are written — declared after its user, it
     * would read as 0dp and every chip would be a rectangle, with nothing failing to compile.
     */
    @Test
    fun `chips are pills and the television takes the canvas corners and focus`() {
        val tv = tvMetricsFor(hd.first, hd.second)
        assertTrue("a chip corner of ${tv.chipCorner.value}dp is not a pill", tv.chipCorner.value >= 100f)
        assertTrue("a phone chip corner of ${TvMetrics.Phone.chipCorner.value}dp is not a pill", TvMetrics.Phone.chipCorner.value >= 100f)
        assertEquals(12f, tv.rowCorner.value, 0.01f)
        assertEquals(11f, tv.controlCorner.value, 0.01f)
        assertEquals(22f, tv.panelCorner.value, 0.01f)
        assertEquals(12f, tv.cellCorner.value, 0.01f)
        assertEquals(8f, TvMetrics.Phone.cellCorner.value, 0.01f)
        assertEquals(1.06f, tv.focusScale, 0.0001f)
        assertEquals(1.03f, tv.rowFocusScale, 0.0001f)
        assertEquals("the drawer is 380px at 1080p", 190f, tv.homeNavRailWidth.value, 0.5f)
        assertEquals("the rail is 112px at 1080p", 56f, tv.homeNavRailCollapsedWidth.value, 0.5f)
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
