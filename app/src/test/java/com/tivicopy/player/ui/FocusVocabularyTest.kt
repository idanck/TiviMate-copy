package com.tivicopy.player.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shell has one focus treatment and one menu, and nothing draws its own (ADR-025).
 *
 * These are invariants about *source*, not about rendering, because this repository has no
 * instrumentation tier and the environment it is developed in cannot build the app at all (see
 * `CLAUDE.md`). They are the three defects the ADR-025 audit actually found, each of which
 * compiles cleanly, renders plausibly on the one screen it was written for, and is only visible
 * when a remote crosses the app:
 *
 * 1. a menu drawn with Material's own dialog instead of the app's plate;
 * 2. a ring that widens on focus, which shifts the element's own contents inside its clip at the
 *    moment it is pressed;
 * 3. a resting ring of `Color.Transparent`, which is transparent *black* — a bright ring
 *    interpolating towards it darkens on the way out instead of fading.
 *
 * Each is cheap to reintroduce by copying a nearby composable, which is exactly how the app came
 * to have seven focus treatments in the first place.
 */
class FocusVocabularyTest {

    private fun uiSources(): List<File> {
        // Gradle runs unit tests with the module directory as the working directory; a run from
        // the repository root is common enough to be worth surviving, as `StringsParityTest` does.
        val candidates = listOf(
            File("src/main/java/com/tivicopy/player/ui"),
            File("app/src/main/java/com/tivicopy/player/ui"),
        )
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("cannot find the ui sources from ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Line comments stripped, so a rule can be *described* in a comment without tripping it. */
    private fun code(file: File): String = file.readText().replace(Regex("//[^\n]*"), "")

    /** The balanced argument list of every `.border(...)` in [source], in order. */
    private fun borderArguments(source: String): List<String> = buildList {
        Regex("\\.border\\(").findAll(source).forEach { match ->
            val start = match.range.last
            var depth = 0
            for (i in start until source.length) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            add(source.substring(start, i + 1))
                            return@forEach
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `every menu is the app's menu`() {
        val offenders = uiSources()
            .filter { it.name != "Panel.kt" }
            .filter { code(it).contains("AlertDialog(") }
            .map { it.name }
        assertEquals(
            "these draw Material's dialog rather than AppDialog, so they ignore the panel shape, " +
                "the hairline and the viewer's overlay opacity (ADR-025)",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `no ring widens on focus`() {
        val offenders = uiSources().flatMap { file ->
            borderArguments(code(file))
                // The width is everything before the colour argument.
                .map { it.substringBefore("color") }
                .filter { it.contains("focused") }
                .map { file.name }
        }
        assertEquals(
            "a ring whose width depends on focus moves the element's own contents inside its " +
                "clip at the moment it is pressed; cross the colour instead (ADR-025)",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `no ring rests on transparent`() {
        val offenders = uiSources().flatMap { file ->
            borderArguments(code(file))
                .filter { it.contains("Color.Transparent") }
                .map { file.name }
        }
        assertEquals(
            "transparent is transparent *black*: a ring interpolating towards it darkens on its " +
                "way out. Use the ring's own colour at alpha zero (ADR-025)",
            emptyList<String>(),
            offenders,
        )
    }
}
