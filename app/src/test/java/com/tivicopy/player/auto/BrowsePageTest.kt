package com.tivicopy.player.auto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `onGetChildren` used to answer every page with the whole list, so a host that paged its browse
 * request read the same stations twice. These pin the windowing it does instead.
 */
class BrowsePageTest {

    private val stations = (1..10).map { "station-$it" }

    @Test
    fun `each page is a distinct window, and together they are the whole list`() {
        val first = stations.browsePage(page = 0, pageSize = 4)
        val second = stations.browsePage(page = 1, pageSize = 4)
        val third = stations.browsePage(page = 2, pageSize = 4)

        assertEquals(listOf("station-1", "station-2", "station-3", "station-4"), first)
        assertEquals(listOf("station-5", "station-6", "station-7", "station-8"), second)
        assertEquals(listOf("station-9", "station-10"), third)
        assertEquals(stations, first + second + third)
    }

    @Test
    fun `a page past the end is empty, not the whole list again`() {
        assertEquals(emptyList<String>(), stations.browsePage(page = 3, pageSize = 4))
        assertEquals(emptyList<String>(), stations.browsePage(page = 99, pageSize = 10))
    }

    @Test
    fun `the browser asking for everything gets everything`() {
        assertEquals(stations, stations.browsePage(page = 0, pageSize = Int.MAX_VALUE))
    }

    @Test
    fun `a page index far enough out to overflow Int arithmetic is still empty`() {
        assertEquals(emptyList<String>(), stations.browsePage(page = Int.MAX_VALUE, pageSize = 1000))
    }

    @Test
    fun `nonsense paging is answered with the list rather than an exception`() {
        assertEquals(stations, stations.browsePage(page = 0, pageSize = 0))
        assertEquals(stations, stations.browsePage(page = -1, pageSize = 4))
        assertEquals(stations, stations.browsePage(page = 0, pageSize = -4))
    }

    @Test
    fun `an empty list pages to nothing`() {
        assertEquals(emptyList<String>(), emptyList<String>().browsePage(page = 1, pageSize = 4))
    }
}
