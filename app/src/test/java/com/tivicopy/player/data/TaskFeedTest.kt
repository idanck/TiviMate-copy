package com.tivicopy.player.data

import android.app.Application
import com.tivicopy.player.core.Outcome
import com.tivicopy.player.data.tasks.TaskFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the screensaver's task list accepts (FR-UI-32, ADR-024).
 *
 * The formats are the feature here. "How do I get my own tasks onto the television" has to have
 * an answer a person can act on in five minutes with a text file and a web server, so a Markdown
 * checklist — what a task already looks like in a notes app — is a first-class input alongside
 * JSON. These tests are what stop that answer quietly rotting.
 *
 * Robolectric for the same reason [FeaturedFeedTest] needs it: `org.json` is a platform class,
 * and the stubbed `android.jar` unit tests compile against returns a default for every call —
 * which would make the JSON half of this file pass without parsing anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TaskFeedTest {

    /** `getOrNull` rather than a cast: a real method on [Outcome], so nothing here is unchecked. */
    private fun ok(raw: String): TaskFeed = requireNotNull(TaskFeed.parse(raw).getOrNull())

    // ---------------------------------------------------------------- markdown

    @Test
    fun `a markdown checklist keeps what is open and drops what is done`() {
        val feed = ok(
            """
            # Alpha
            - [ ] Book the dentist
            - [x] Order the food
            - [X] Collect the results
            * [ ] Refill the ointment
            """.trimIndent(),
        )
        assertEquals(listOf("Book the dentist", "Refill the ointment"), feed.items.map { it.title })
    }

    @Test
    fun `headings name the list and say where each task came from`() {
        val feed = ok(
            """
            # Alpha
            ## Teeth
            - [ ] Book the dentist
            ## Skin
            - [ ] Refill the ointment
            """.trimIndent(),
        )
        assertEquals("Alpha", feed.title)
        assertEquals(listOf("Teeth", "Skin"), feed.items.map { it.note })
    }

    @Test
    fun `markdown punctuation is not printed at the viewer`() {
        val feed = ok("- [ ] **Book** the [[Vet|vet]] for a `checkup` #task")
        assertEquals("Book the vet for a checkup", feed.items.single().title)
    }

    @Test
    fun `a link keeps its text and loses its address`() {
        val feed = ok("- [ ] Read [the lab results](https://example.com/a?b=c)")
        assertEquals("Read the lab results", feed.items.single().title)
    }

    /**
     * Obsidian's Tasks plugin writes a due date as `📅 2026-09-01` at the end of the line. The
     * date is lifted out, and the marker that introduced it goes with it — the alternative is a
     * stray emoji hanging off the end of every dated task.
     */
    @Test
    fun `a due date is lifted out of the line, marker and all`() {
        val feed = ok("- [ ] Renew the prescription 📅 2026-09-01")
        val task = feed.items.single()
        assertEquals("Renew the prescription", task.title)
        assertEquals("2026-09-01", task.due)
    }

    @Test
    fun `a task with no date has no date`() {
        assertNull(ok("- [ ] Renew the prescription").items.single().due)
    }

    @Test
    fun `hebrew survives the trip intact`() {
        val feed = ok("- [ ] לקבוע תור לוטרינר #משימה")
        assertEquals("לקבוע תור לוטרינר", feed.items.single().title)
    }

    /** A list somebody keeps by hand, with no checkbox syntax anywhere in it. */
    @Test
    fun `plain bullets are read as tasks when the document has no checkboxes`() {
        val feed = ok(
            """
            # Shopping
            - Ointment
            - Food
            """.trimIndent(),
        )
        assertEquals(listOf("Ointment", "Food"), feed.items.map { it.title })
    }

    /** But a document *with* checkboxes has already said which of its bullets are tasks. */
    @Test
    fun `plain bullets are ignored once the document uses checkboxes`() {
        val feed = ok(
            """
            - [ ] Book the dentist
            - a note to self
            """.trimIndent(),
        )
        assertEquals(listOf("Book the dentist"), feed.items.map { it.title })
    }

    /**
     * The trap the bullet fallback sets for itself: a list whose every box is ticked has no open
     * tasks, and is still a checklist. Reading its ticked lines back as plain bullets would put
     * "[x] Order the food" on the television.
     */
    @Test
    fun `a list with everything ticked is finished, not a list of bullets`() {
        val feed = ok(
            """
            # Alpha
            - [x] Order the food
            - [x] Book the dentist
            """.trimIndent(),
        )
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `an empty box with nothing after it is not a task`() {
        assertTrue(ok("- [ ]    ").items.isEmpty())
    }

    @Test
    fun `a document with nothing in it is an empty list rather than a failure`() {
        assertTrue(ok("").items.isEmpty())
        assertTrue(ok("# Alpha\n\nnothing to do today.").items.isEmpty())
    }

    @Test
    fun `a runaway document is capped`() {
        val many = (1..500).joinToString("\n") { "- [ ] task $it" }
        assertEquals(TaskFeed.MAX_ITEMS, ok(many).items.size)
    }

    // -------------------------------------------------------------------- json

    @Test
    fun `a json document is read, and its finished items are dropped`() {
        val feed = ok(
            """
            {
              "version": 1,
              "title": "Alpha",
              "items": [
                {"id": "a", "title": "Book the dentist", "note": "Teeth", "due": "2026-09-01"},
                {"id": "b", "title": "Order the food", "done": true},
                {"title": "  "}
              ]
            }
            """.trimIndent(),
        )
        assertEquals("Alpha", feed.title)
        val task = feed.items.single()
        assertEquals("a", task.id)
        assertEquals("Book the dentist", task.title)
        assertEquals("Teeth", task.note)
        assertEquals("2026-09-01", task.due)
    }

    @Test
    fun `a bare json array is a list too`() {
        val feed = ok("""[{"title": "Book the dentist"}]""")
        assertEquals(listOf("Book the dentist"), feed.items.map { it.title })
    }

    @Test
    fun `a json document with no items is an empty list rather than a failure`() {
        assertTrue(ok("""{"version": 1}""").items.isEmpty())
        assertTrue(ok("""{"items": []}""").items.isEmpty())
    }

    /**
     * The two things that *are* failures: something that opened as JSON and is not, and a schema
     * this build does not know. Both leave the last good list on screen rather than blanking it.
     */
    @Test
    fun `broken json and an unknown schema are refused`() {
        assertTrue(TaskFeed.parse("{ not json at all") is Outcome.Err)
        assertTrue(TaskFeed.parse("""{"version": 99, "items": []}""") is Outcome.Err)
    }
}
