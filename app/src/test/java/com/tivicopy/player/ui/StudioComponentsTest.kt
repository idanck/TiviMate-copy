package com.tivicopy.player.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every control is Studio's (MIGRATION.md round 2, stage 7).
 *
 * Studio stage 7 replaced Material's text field, buttons, switch, progress indicators and snackbar
 * with the canvas's own components in `ui/common`. Each of the Material ones still compiles,
 * still works, and draws the wrong thing — an amber spinner, an outlined field with a floating
 * label, a button that ignores the cue — and is one copied composable away from coming back.
 * Like [FocusVocabularyTest], this holds the *source* to it, because nothing here can render.
 */
class StudioComponentsTest {

    private fun uiSources(): List<File> {
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

    /**
     * A call to one of these, not a longer name that ends in it: `StudioButton(` and `IconButton(`
     * are fine, `Button(` is not.
     */
    private val material = listOf(
        "OutlinedTextField", "TextField", "TextButton", "OutlinedButton", "FilledTonalButton",
        "ElevatedButton", "Button", "Switch", "LinearProgressIndicator", "CircularProgressIndicator",
        "Snackbar",
    )

    @Test
    fun `no screen draws a Material control`() {
        val offenders = uiSources().flatMap { file ->
            val source = code(file)
            material
                .filter { name -> Regex("(?<![A-Za-z0-9_])$name\\(").containsMatchIn(source) }
                .map { "${file.name}: $it" }
        }
        assertEquals(
            "these draw Material's control rather than Studio's (StudioField, StudioButton, " +
                "StudioToggle, StudioProgress, Loader, StudioNotice in ui/common)",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every icon is Studio's`() {
        val offenders = uiSources()
            .filter { code(it).contains("androidx.compose.material.icons") }
            .map { it.name }
        assertEquals(
            "these draw Material's icons rather than StudioIcons (ADR-033); the extended set is no " +
                "longer a dependency at all",
            emptyList<String>(),
            offenders,
        )
    }
}
