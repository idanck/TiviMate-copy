package com.tivicopy.player.data.tasks

import com.tivicopy.player.core.AppError
import com.tivicopy.player.core.Outcome
import org.json.JSONArray
import org.json.JSONObject

/**
 * One thing still to do, as the radio screensaver prints it (FR-UI-32).
 *
 * Deliberately three lines of text and nothing else. This is read from across a room, off a
 * screen nobody is sitting in front of, in between a clock and a station name — a priority, an
 * assignee, a project colour and a completion percentage would all be information the surface
 * cannot use.
 */
data class TaskItem(
    /** Stable within one document; the cross-fade is keyed on it. */
    val id: String,
    val title: String,
    /**
     * Where the task comes from — the heading above it in a Markdown list, or `note` in JSON.
     * Printed above the title in small type, so a list gathered from several notes still says
     * which one each line belongs to.
     */
    val note: String? = null,
    /** A due date, verbatim as the document wrote it. Never parsed into a calendar. */
    val due: String? = null,
)

/**
 * A list of open tasks, fetched from an address the owner of the device configures.
 *
 * **Why there is no built-in source.** This app is installed on other people's televisions, so
 * "my tasks" cannot mean a hard-coded account, a vault path or a personal URL. It works exactly
 * as the featured rail does (FR-UI-26): a setting holds an HTTPS address, an empty address means
 * the feature does not exist, and what is served there is the user's business. The mechanism
 * ships; the content does not.
 *
 * **Two formats, because the honest answer to "how do I get my tasks in there" is a text file.**
 *
 * - *Markdown* — any document with `- [ ]` checklist lines. That is what a task actually looks
 *   like in Obsidian, in a README, in a note synced to a NAS, or in a file a script assembles on
 *   the Pi beside `radio.m3u` (ADR-015). Ticked lines (`- [x]`) are dropped: the screen is for
 *   what is left. `#` headings become the section each task is printed under, and the first one
 *   names the list. A document with no checklist lines at all falls back to its plain bullets,
 *   so a hand-kept list works without anyone having to learn a syntax.
 * - *JSON* — `{"version":1,"title":"…","items":[{"title":"…","note":"…","due":"…"}]}`, for a
 *   generator that would rather emit structure than text.
 *
 * Which one is decided by the first non-blank character, not by the file extension or the
 * content type: a document served as `text/plain` that starts with `{` is JSON, and everything
 * else is read as Markdown.
 *
 * Failure follows [com.tivicopy.player.data.featured.FeaturedFeed]: one malformed *item* is
 * dropped and the rest of the list still renders, and a document with nothing in it is a valid
 * empty list rather than an error — publishing an empty file is how you clear the screen without
 * unsetting the address, and reporting that as a failure would keep yesterday's list cached and
 * on the television precisely when the intent was to take it down.
 */
data class TaskFeed(val title: String?, val items: List<TaskItem>) {

    companion object {

        /** A ceiling on how much of a document is kept. The screen shows one task at a time. */
        const val MAX_ITEMS = 40

        /** Absent means 1. A JSON document naming a version this build does not know is refused. */
        const val SCHEMA_VERSION = 1

        fun parse(raw: String): Outcome<TaskFeed> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return Outcome.Ok(TaskFeed(null, emptyList()))
            return when (trimmed.first()) {
                '{', '[' -> parseJson(trimmed)
                else -> Outcome.Ok(parseMarkdown(raw))
            }
        }

        // ------------------------------------------------------------------ json

        private fun parseJson(raw: String): Outcome<TaskFeed> {
            // A bare array is accepted as well as a document, because a generator that already
            // has a list of tasks should not have to invent a wrapper for it.
            if (raw.first() == '[') {
                val array = try {
                    JSONArray(raw)
                } catch (t: Throwable) {
                    return Outcome.Err(AppError(AppError.Kind.ParseFailed, "task feed is not JSON", t))
                }
                return Outcome.Ok(TaskFeed(null, itemsOf(array)))
            }

            val json = try {
                JSONObject(raw)
            } catch (t: Throwable) {
                return Outcome.Err(AppError(AppError.Kind.ParseFailed, "task feed is not JSON", t))
            }
            val version = json.optInt("version", SCHEMA_VERSION)
            if (version != SCHEMA_VERSION) {
                return Outcome.Err(AppError(AppError.Kind.ParseFailed, "task feed: version $version"))
            }
            val title = json.optString("title").trim().ifEmpty { null }
            val array = json.optJSONArray("items")
                ?: return Outcome.Ok(TaskFeed(title, emptyList()))
            return Outcome.Ok(TaskFeed(title, itemsOf(array)))
        }

        private fun itemsOf(array: JSONArray): List<TaskItem> {
            val items = buildList {
                for (index in 0 until array.length()) {
                    if (size >= MAX_ITEMS) break
                    val obj = array.optJSONObject(index) ?: continue
                    // Ticked items are dropped here as well as in the Markdown reader: a feed
                    // that publishes its whole list and marks what is finished is a reasonable
                    // thing to write, and this screen is only ever about what is left.
                    if (obj.optBoolean("done", false)) continue
                    val title = clean(obj.optString("title"))
                    if (title.isEmpty()) continue
                    add(
                        TaskItem(
                            id = obj.optString("id").trim().ifEmpty { "$index:$title" },
                            title = title,
                            note = clean(obj.optString("note")).ifEmpty { null },
                            due = obj.optString("due").trim().ifEmpty { null },
                        ),
                    )
                }
            }
            return items.distinctBy { it.id }
        }

        // -------------------------------------------------------------- markdown

        private fun parseMarkdown(raw: String): TaskFeed {
            val lines = raw.lineSequence().toList()
            var documentTitle: String? = null
            var heading: String? = null
            // Whether the document uses checklist syntax at all — which is not the same question
            // as whether anything is still open. A list whose every box is ticked is a finished
            // list, and falling through to the bullet pass below would print its ticked lines
            // back as tasks, brackets and all.
            var sawCheckbox = false

            val open = buildList {
                for (line in lines) {
                    val head = HEADING.matchEntire(line)
                    if (head != null) {
                        heading = clean(head.groupValues[1]).ifEmpty { null }
                        if (documentTitle == null) documentTitle = heading
                        continue
                    }
                    val checkbox = CHECKBOX.matchEntire(line) ?: continue
                    sawCheckbox = true
                    // `- [x]`, `- [X]` and Obsidian's other single-character states all mean
                    // "not open"; only a blank box is a task this screen has anything to say
                    // about.
                    if (checkbox.groupValues[1].isNotBlank()) continue
                    item(checkbox.groupValues[2], heading, size)?.let { add(it) }
                    if (size >= MAX_ITEMS) break
                }
            }
            if (sawCheckbox) return TaskFeed(documentTitle, open.distinctBy { it.id })

            // No checklist anywhere in the document, so its plain bullets are the list. This is
            // what makes a note somebody keeps by hand work without them having to learn the
            // checkbox syntax first — and it is safe only as a fallback: a document that has
            // checkboxes has said which of its bullets are tasks.
            heading = null
            val bullets = buildList {
                for (line in lines) {
                    val head = HEADING.matchEntire(line)
                    if (head != null) {
                        heading = clean(head.groupValues[1]).ifEmpty { null }
                        continue
                    }
                    val bullet = BULLET.matchEntire(line) ?: continue
                    item(bullet.groupValues[1], heading, size)?.let { add(it) }
                    if (size >= MAX_ITEMS) break
                }
            }
            return TaskFeed(documentTitle, bullets.distinctBy { it.id })
        }

        private fun item(body: String, heading: String?, index: Int): TaskItem? {
            // Cleaned first, then dated. The other order looks more natural and is wrong: what
            // is left where a due date was is a marker — Obsidian's Tasks plugin writes
            // "📅 2026-09-01" — and trimming that marker off the end of a line that still has
            // its Markdown on it takes the `]]` of a trailing wiki link with it, leaving the
            // link unclosed and printed verbatim.
            val cleaned = clean(body)
            val due = ISO_DATE.find(cleaned)?.value
            val title = if (due == null) cleaned else {
                ISO_DATE.replace(cleaned, "").trimEnd { !it.isLetterOrDigit() }
            }
            if (title.isEmpty()) return null
            return TaskItem(
                id = "$index:$title",
                title = title,
                note = heading,
                due = due,
            )
        }

        // ----------------------------------------------------------------- text

        /**
         * A line of somebody's notes, reduced to something a television can print.
         *
         * Wiki links keep their alias, ordinary links keep their text, `#tags` and emphasis
         * markers go, and everything left is collapsed onto one line. None of it is an attempt
         * at rendering Markdown: it is an attempt at not printing its punctuation.
         */
        internal fun clean(text: String): String {
            var out: String = WIKILINK.replace(text) { it.groupValues[2].ifEmpty { it.groupValues[1] } }
            out = MDLINK.replace(out) { it.groupValues[1] }
            out = TAG.replace(out) { it.groupValues[1] }
            out = MARKUP.replace(out, "")
            out = WHITESPACE.replace(out, " ")
            return out.trim()
        }

        private val HEADING = Regex("""\s{0,3}#{1,6}\s+(.+)\s*""")
        private val CHECKBOX = Regex("""\s*[-*+]\s+\[(.?)]\s*(.*)""")
        private val BULLET = Regex("""\s*[-*+]\s+(.*)""")
        private val WIKILINK = Regex("""\[\[([^\[\]|]+)(?:\|([^\[\]]+))?]]""")
        private val MDLINK = Regex("""\[([^\[\]]+)]\([^()\s]*\)""")

        /** A `#tag`, with whatever separated it from the word before kept in place. */
        private val TAG = Regex("""(^|\s)#[\p{L}\p{N}_/-]+""")

        /** Bold, italic and code markers. Underscores are left alone; they occur inside names. */
        private val MARKUP = Regex("""[*`]""")

        private val WHITESPACE = Regex("""\s+""")

        /** A date anywhere in a task line is its due date, whatever marker was put in front. */
        private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
