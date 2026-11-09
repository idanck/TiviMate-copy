package com.idanck.shidur.ui

import com.idanck.shidur.data.featured.FeaturedItem
import com.idanck.shidur.data.model.Channel
import com.idanck.shidur.data.model.Programme
import com.idanck.shidur.ui.home.HomeData
import com.idanck.shidur.ui.home.HomeRailId
import com.idanck.shidur.ui.home.HomeRails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home screen's rails (FR-UI-24).
 *
 * Every rule the screen depends on lives in one pure function precisely so it can be pinned
 * here: this environment cannot build the app, let alone run it on a television, so a rule that
 * only exists inside a composable is a rule nobody can check until it is already on a device.
 *
 * Plain JUnit — no `org.json`, no Android — so it runs on the stubbed android.jar without
 * Robolectric. `nowUtc` is passed in everywhere for the same reason.
 */
class HomeRailsTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun channel(
        id: Long,
        name: String = "Channel $id",
        number: Int = id.toInt(),
        playlistId: Long = 1,
        favourite: Boolean = false,
        lastWatched: Long = 0,
    ) = Channel(
        id = id,
        playlistId = playlistId,
        identity = "i$id",
        name = name,
        number = number,
        favourite = favourite,
        lastWatchedUtc = lastWatched,
        logoUrl = "https://example.com/$id.png",
    )

    private fun programme(startOffsetMin: Long, endOffsetMin: Long, title: String) = Programme(
        epgChannelId = "e",
        startUtc = now + startOffsetMin * minute,
        endUtc = now + endOffsetMin * minute,
        title = title,
    )

    private fun rail(rails: List<com.idanck.shidur.ui.home.HomeRail>, id: HomeRailId) =
        rails.firstOrNull { it.id == id }

    // ------------------------------------------------------------ empty rails

    @Test
    fun `nothing to show produces no rails at all`() {
        assertTrue(HomeRails.build(HomeData(), now).isEmpty())
    }

    /**
     * The whole "degrade to invisible" contract, and the reason the featured rail can ship with
     * no configured source: a rail with no cards never reaches the screen, so there is no
     * heading, no empty state and no placeholder to explain.
     */
    @Test
    fun `each rail disappears independently when its source is empty`() {
        val rails = HomeRails.build(HomeData(channels = listOf(channel(1))), now)
        assertEquals(listOf(HomeRailId.CHANNELS), rails.map { it.id })
    }

    @Test
    fun `rails come back in a fixed order`() {
        val one = channel(1, favourite = true, lastWatched = now - minute)
        val rails = HomeRails.build(
            HomeData(
                recent = listOf(one),
                channels = listOf(one),
                radio = listOf(channel(2, playlistId = 2)),
                favourites = listOf(one),
                programmes = mapOf(1L to listOf(programme(10, 40, "Soon"))),
                featured = listOf(FeaturedItem(id = "f", title = "Promo", channelRef = "1")),
            ),
            now,
        )
        assertEquals(
            listOf(
                HomeRailId.FEATURED,
                HomeRailId.CONTINUE,
                HomeRailId.CHANNELS,
                HomeRailId.RADIO,
                HomeRailId.FAVOURITES,
                HomeRailId.STARTING_SOON,
            ),
            rails.map { it.id },
        )
    }

    // ------------------------------------------------------------ channel cards

    @Test
    fun `a rail is capped and keeps the order it was given`() {
        val channels = (1L..30L).map { channel(it) }
        val cards = rail(HomeRails.build(HomeData(channels = channels), now, maxPerRail = 5), HomeRailId.CHANNELS)!!.cards
        assertEquals(5, cards.size)
        assertEquals(listOf(1, 2, 3, 4, 5), cards.map { it.number })
    }

    @Test
    fun `a card carries the programme that is on right now`() {
        val data = HomeData(
            channels = listOf(channel(1)),
            programmes = mapOf(
                1L to listOf(
                    programme(-90, -30, "Earlier"),
                    programme(-30, 30, "On now"),
                    programme(30, 90, "Later"),
                ),
            ),
        )
        val card = rail(HomeRails.build(data, now), HomeRailId.CHANNELS)!!.cards.single()
        assertEquals("On now", card.subtitle)
        assertTrue(card.live)
        assertTrue(card.hasProgress)
    }

    /** No guide for this channel is an ordinary state, not a missing card. */
    @Test
    fun `a channel with no guide still gets a card`() {
        val data = HomeData(channels = listOf(channel(1)), programmes = mapOf(1L to emptyList()))
        val card = rail(HomeRails.build(data, now), HomeRailId.CHANNELS)!!.cards.single()
        assertNull(card.subtitle)
        assertFalse(card.hasProgress)
        assertEquals("Channel 1", card.title)
    }

    @Test
    fun `a channel with no number gets no badge`() {
        val data = HomeData(channels = listOf(channel(1, number = 0)))
        assertNull(rail(HomeRails.build(data, now), HomeRailId.CHANNELS)!!.cards.single().number)
    }

    // ------------------------------------------------------------ the hidden-group gap

    /**
     * `Dao.recentChannels` and `Dao.favouriteChannels` filter hidden *channels* but, unlike
     * `Dao.channels`, not channels inside a hidden *group*. Screening both through what the
     * properly-scoped queries returned is what stops a channel the viewer hid from walking back
     * onto the home screen through the side door.
     */
    @Test
    fun `a recent or favourite channel that is no longer visible is dropped`() {
        val visible = channel(1)
        val hiddenByItsGroup = channel(2)
        val data = HomeData(
            recent = listOf(hiddenByItsGroup, visible),
            channels = listOf(visible),
            favourites = listOf(hiddenByItsGroup),
        )
        val rails = HomeRails.build(data, now)
        assertEquals(listOf(1L), rail(rails, HomeRailId.CONTINUE)!!.cards.map { it.channelId })
        assertNull(rail(rails, HomeRailId.FAVOURITES))
    }

    /**
     * The other half of that screen, and the one that is easy to get wrong in the opposite
     * direction: `Dao.recentChannels` spans every playlist on the device, so a channel belonging
     * to a playlist this load did not read cannot be judged — dropping it would empty the rail
     * rather than filter it.
     */
    @Test
    fun `a recent channel from a playlist that was not loaded survives`() {
        val loaded = channel(1, playlistId = 1)
        val elsewhere = channel(5, playlistId = 7)
        val data = HomeData(recent = listOf(elsewhere, loaded), channels = listOf(loaded))
        assertEquals(
            listOf(5L, 1L),
            rail(HomeRails.build(data, now), HomeRailId.CONTINUE)!!.cards.map { it.channelId },
        )
    }

    @Test
    fun `a radio station counts as visible`() {
        val station = channel(9, playlistId = 2)
        val data = HomeData(recent = listOf(station), radio = listOf(station))
        assertEquals(listOf(9L), rail(HomeRails.build(data, now), HomeRailId.CONTINUE)!!.cards.map { it.channelId })
    }

    // ------------------------------------------------------------ starting soon

    @Test
    fun `starting soon takes the next programme inside the window, soonest first`() {
        val data = HomeData(
            channels = listOf(channel(1), channel(2), channel(3)),
            programmes = mapOf(
                1L to listOf(programme(45, 105, "Later tonight")),
                2L to listOf(programme(10, 70, "Very soon")),
                3L to listOf(programme(500, 560, "Beyond the window")),
            ),
        )
        val cards = rail(HomeRails.build(data, now), HomeRailId.STARTING_SOON)!!.cards
        assertEquals(listOf("Very soon", "Later tonight"), cards.map { it.title })
        assertEquals(listOf(2L, 1L), cards.map { it.channelId })
    }

    /** A window still ahead must not draw a progress bar sitting at zero. */
    @Test
    fun `a starting soon card is not marked live`() {
        val data = HomeData(
            channels = listOf(channel(1)),
            programmes = mapOf(1L to listOf(programme(10, 70, "Soon"))),
        )
        val card = rail(HomeRails.build(data, now), HomeRailId.STARTING_SOON)!!.cards.single()
        assertFalse(card.live)
        assertFalse(card.hasProgress)
    }

    @Test
    fun `a programme already under way is not starting soon`() {
        val data = HomeData(
            channels = listOf(channel(1)),
            programmes = mapOf(1L to listOf(programme(-10, 50, "On now"))),
        )
        assertNull(rail(HomeRails.build(data, now), HomeRailId.STARTING_SOON))
    }

    @Test
    fun `a channel appears at most once in starting soon`() {
        val data = HomeData(
            channels = listOf(channel(1)),
            programmes = mapOf(
                1L to listOf(programme(10, 40, "First"), programme(40, 70, "Second")),
            ),
        )
        val cards = rail(HomeRails.build(data, now), HomeRailId.STARTING_SOON)!!.cards
        assertEquals(listOf("First"), cards.map { it.title })
    }

    // ------------------------------------------------------------ featured

    @Test
    fun `a featured item resolves to a channel and becomes playable`() {
        val data = HomeData(
            channels = listOf(channel(1, name = "כאן 11", number = 11)),
            featured = listOf(FeaturedItem(id = "f", title = "Promo", channelRef = "11")),
        )
        val card = rail(HomeRails.build(data, now), HomeRailId.FEATURED)!!.cards.single()
        assertEquals("Promo", card.title)
        assertEquals(1L, card.channelId)
    }

    /** A tile that swallows OK is indistinguishable from a frozen screen on a remote. */
    @Test
    fun `a featured item that leads nowhere is dropped`() {
        val data = HomeData(
            channels = listOf(channel(1)),
            featured = listOf(FeaturedItem(id = "f", title = "Promo", channelRef = "nothing-here")),
        )
        assertNull(rail(HomeRails.build(data, now), HomeRailId.FEATURED))
    }

    @Test
    fun `a featured item naming no channel at all is dropped`() {
        val data = HomeData(
            channels = listOf(channel(1)),
            featured = listOf(FeaturedItem(id = "f", title = "Promo")),
        )
        assertNull(rail(HomeRails.build(data, now), HomeRailId.FEATURED))
    }

    @Test
    fun `an unconfigured feed produces no featured rail`() {
        val data = HomeData(channels = listOf(channel(1)), featured = emptyList())
        assertNull(rail(HomeRails.build(data, now), HomeRailId.FEATURED))
    }

    // ------------------------------------------------------------ keys

    @Test
    fun `card keys are unique across the whole screen`() {
        val one = channel(1, favourite = true, lastWatched = now - minute)
        val rails = HomeRails.build(
            HomeData(
                recent = listOf(one),
                channels = listOf(one),
                favourites = listOf(one),
                programmes = mapOf(1L to listOf(programme(10, 40, "Soon"))),
            ),
            now,
        )
        val keys = rails.flatMap { it.cards }.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
