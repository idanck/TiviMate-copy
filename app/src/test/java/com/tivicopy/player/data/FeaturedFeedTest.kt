package com.tivicopy.player.data

import android.app.Application
import com.tivicopy.player.core.Outcome
import com.tivicopy.player.data.featured.FeaturedFeed
import com.tivicopy.player.data.featured.resolveChannel
import com.tivicopy.player.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The featured rail's feed (FR-UI-26).
 *
 * The parser's shape is borrowed from `UpdateManifest`, but its strictness is deliberately not:
 * that document decides which binary is installed over the running app, this one decides which
 * posters appear on a shelf. So the interesting cases here are the ones where those two differ —
 * a bad row is dropped rather than failing the document, and an *empty* feed is a valid answer
 * rather than a broken one, because publishing no items is how the owner turns the rail off.
 *
 * Robolectric because `org.json` is a platform class: `unitTests.isReturnDefaultValues = true`
 * in `app/build.gradle.kts` makes the stubbed android.jar return defaults for every call, which
 * would make every assertion below pass for the wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FeaturedFeedTest {

    private fun parse(raw: String) = FeaturedFeed.parse(raw)

    // `getOrNull` rather than a cast: it is a real method on Outcome, so there is no unchecked
    // cast and no type argument to get wrong.
    private fun items(raw: String) = requireNotNull(parse(raw).getOrNull()).items

    @Test
    fun `a well-formed feed parses`() {
        val list = items(
            """
            {"version": 1, "items": [
              {"id": "a", "title": "Lanterns", "subtitle": "HBO",
               "image": "https://example.com/a.jpg", "channel": "hot3.il"}
            ]}
            """.trimIndent(),
        )
        assertEquals(1, list.size)
        assertEquals("a", list[0].id)
        assertEquals("Lanterns", list[0].title)
        assertEquals("HBO", list[0].subtitle)
        assertEquals("https://example.com/a.jpg", list[0].imageUrl)
        assertEquals("hot3.il", list[0].channelRef)
    }

    @Test
    fun `the version may be omitted`() {
        assertEquals(1, items("""{"items": [{"title": "X"}]}""").size)
    }

    @Test
    fun `a version this build does not know is refused outright`() {
        assertTrue(parse("""{"version": 2, "items": []}""") is Outcome.Err)
    }

    @Test
    fun `text that is not JSON is refused`() {
        assertTrue(parse("<html>nope</html>") is Outcome.Err)
    }

    /**
     * The case that matters most, and the one easiest to get backwards: an empty feed is how the
     * rail is switched off. Reporting it as an error would leave the previous feed cached and the
     * rail still promoting it, which is the exact opposite of what was asked.
     */
    @Test
    fun `an empty feed is a valid answer, not a failure`() {
        assertTrue(items("""{"version": 1, "items": []}""").isEmpty())
        assertTrue(items("""{"version": 1}""").isEmpty())
    }

    @Test
    fun `an item with no title is dropped and its siblings survive`() {
        val list = items(
            """{"items": [{"title": ""}, {"title": "Kept"}, {"subtitle": "no title"}]}""",
        )
        assertEquals(listOf("Kept"), list.map { it.title })
    }

    /** An image served in the clear costs the picture, not the card. */
    @Test
    fun `a non-https image is dropped but the item is kept`() {
        val list = items("""{"items": [{"title": "X", "image": "http://example.com/a.jpg"}]}""")
        assertEquals(1, list.size)
        assertNull(list[0].imageUrl)
    }

    @Test
    fun `duplicate ids collapse`() {
        val list = items("""{"items": [{"id": "same", "title": "A"}, {"id": "same", "title": "B"}]}""")
        assertEquals(listOf("A"), list.map { it.title })
    }

    @Test
    fun `an item without an id still gets a stable one`() {
        val list = items("""{"items": [{"title": "A"}, {"title": "B"}]}""")
        assertEquals(2, list.size)
        assertEquals(2, list.map { it.id }.toSet().size)
    }

    @Test
    fun `a runaway feed is truncated`() {
        val many = (1..FeaturedFeed.MAX_ITEMS + 20).joinToString(",") { """{"id":"$it","title":"t$it"}""" }
        assertEquals(FeaturedFeed.MAX_ITEMS, items("""{"items": [$many]}""").size)
    }

    // ------------------------------------------------------------ resolving a reference

    private fun channel(id: Long, name: String, tvgId: String? = null, number: Int = 0) =
        Channel(id = id, playlistId = 1, identity = "i$id", name = name, tvgId = tvgId, number = number)

    private val channels = listOf(
        channel(1, "כאן 11", tvgId = "kan11.il", number = 11),
        channel(2, "Channel 12", tvgId = "keshet12.il", number = 12),
        channel(3, "Channel 13", number = 13),
    )

    @Test
    fun `a reference matches the guide id first`() {
        assertEquals(1L, resolveChannel("kan11.il", channels)?.id)
        assertEquals(2L, resolveChannel("KESHET12.IL", channels)?.id)
    }

    @Test
    fun `a reference matches the channel name`() {
        assertEquals(1L, resolveChannel("כאן 11", channels)?.id)
        assertEquals(3L, resolveChannel("channel 13", channels)?.id)
    }

    /**
     * The ordering that matters: "12" is the channel numbered 12, not "Channel 12" matched as a
     * name — and the name pass runs first, so this pins that a bare number falls through it.
     */
    @Test
    fun `a bare number matches the channel number`() {
        assertEquals(2L, resolveChannel("12", channels)?.id)
    }

    @Test
    fun `an unknown reference resolves to nothing`() {
        assertNull(resolveChannel("bbc.uk", channels))
        assertNull(resolveChannel("  ", channels))
        assertNull(resolveChannel("99", channels))
    }
}
