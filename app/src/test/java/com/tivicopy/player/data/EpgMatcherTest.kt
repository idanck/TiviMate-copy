package com.tivicopy.player.data

import com.tivicopy.player.data.epg.EpgMatcher
import com.tivicopy.player.data.epg.MatchKind
import com.tivicopy.player.data.model.Channel
import com.tivicopy.player.data.model.EpgChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FR-EPG-07/08: stage order is the contract — a correct tvg-id must never lose to a guess. */
class EpgMatcherTest {

    private fun channel(id: Long, name: String, tvgId: String? = null, manual: String? = null) =
        Channel(id = id, playlistId = 1, identity = "id$id", name = name, tvgId = tvgId, manualEpgId = manual)

    private val guide = listOf(
        EpgChannel("bbc.one.uk", listOf("BBC One")),
        EpgChannel("bbc.two.uk", listOf("BBC Two")),
        EpgChannel("sky.sports.main", listOf("Sky Sports Main Event")),
        EpgChannel("Discovery.uk", emptyList()),
    )

    @Test
    fun `exact tvg-id wins`() {
        val results = EpgMatcher().match(listOf(channel(1, "Totally Different Name", tvgId = "bbc.one.uk")), guide)
        assertEquals("bbc.one.uk", results[0].epgId)
        assertEquals(MatchKind.EXACT_ID, results[0].kind)
    }

    @Test
    fun `a manual binding outranks everything`() {
        val results = EpgMatcher().match(
            listOf(channel(1, "BBC One", tvgId = "bbc.one.uk", manual = "sky.sports.main")),
            guide,
        )
        assertEquals("sky.sports.main", results[0].epgId)
        assertEquals(MatchKind.MANUAL, results[0].kind)
    }

    @Test
    fun `normalized names match through quality tokens`() {
        val results = EpgMatcher().match(listOf(channel(1, "BBC Two FHD")), guide)
        assertEquals("bbc.two.uk", results[0].epgId)
        assertEquals(MatchKind.NORMALIZED_NAME, results[0].kind)
    }

    @Test
    fun `a guide id that reads as a name still matches`() {
        val results = EpgMatcher().match(listOf(channel(1, "Discovery HD")), guide)
        assertEquals("Discovery.uk", results[0].epgId)
    }

    @Test
    fun `an unrelated channel is reported unmatched rather than guessed`() {
        val results = EpgMatcher().match(listOf(channel(1, "Al Jazeera Arabic")), guide)
        assertNull(results[0].epgId)
        assertEquals(MatchKind.NONE, results[0].kind)
    }

    @Test
    fun `a near miss is taken only above the confidence threshold`() {
        val results = EpgMatcher().match(listOf(channel(1, "Sky Sports Main Events")), guide)
        assertEquals("sky.sports.main", results[0].epgId)
        assertEquals(MatchKind.FUZZY, results[0].kind)
    }
}
