package com.tivicopy.player.data

import com.tivicopy.player.data.news.NewsSource
import com.tivicopy.player.data.news.SuggestedNewsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suggested news sources (ADR-038): addresses Settings offers, never switches on.
 *
 * Every suggestion has to be one [com.tivicopy.player.data.prefs.Settings.addNewsSource] would
 * accept — otherwise the row would look like an offer and do nothing — and the list has to fit
 * in the eight sources a television can hold, so that taking every suggestion is possible.
 */
class SuggestedNewsSourcesTest {

    @Test
    fun `every suggestion is an address Settings would accept`() {
        SuggestedNewsSources.ALL.forEach {
            assertTrue("refused: ${it.url}", NewsSource.acceptable(it.url))
        }
    }

    @Test
    fun `suggestions are distinct and fit the source limit`() {
        val all = SuggestedNewsSources.ALL
        assertEquals(all.size, all.map { it.url.lowercase() }.toSet().size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.size <= NewsSource.MAX_SOURCES)
    }

    @Test
    fun `a source already added is no longer suggested`() {
        val first = SuggestedNewsSources.ALL.first()
        val left = SuggestedNewsSources.available(listOf(NewsSource(" ${first.url.uppercase()} ")))
        assertEquals(SuggestedNewsSources.ALL.size - 1, left.size)
        assertTrue(left.none { it.id == first.id })
    }

    @Test
    fun `with nothing added every suggestion is offered, breaking news first`() {
        val offered = SuggestedNewsSources.available(emptyList())
        assertEquals(SuggestedNewsSources.ALL, offered)
        val firstArticles = offered.indexOfFirst { !it.flashes }
        assertTrue(offered.drop(firstArticles).none { it.flashes })
    }
}
