package com.idanck.shidur.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The baseline profile still names this app's own code (ADR-021).
 *
 * `src/main/baseline-prof.txt` is a file nothing imports, nothing calls and no screen renders —
 * the kind that is deleted during a tidy-up without anyone noticing, and whose absence shows up
 * only as an interface that feels slow on a television nobody here can look at. Measured, the
 * difference between this app's code being compiled ahead of time and not is a 95th-percentile
 * frame of 21ms against 150ms.
 *
 * It checks the rules cover the packages that matter rather than checking the file exists, so a
 * profile emptied down to a comment fails too.
 */
class BaselineProfileTest {

    private fun profile(): File =
        listOf(File("src/main/baseline-prof.txt"), File("app/src/main/baseline-prof.txt"))
            .firstOrNull { it.isFile }
            ?: error("no baseline profile found from ${File(".").absolutePath}")

    @Test
    fun `the profile covers the packages that draw and feed the screens`() {
        val rules = profile().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        assertTrue("the baseline profile has no rules in it", rules.isNotEmpty())
        listOf("ui", "core", "data", "player").forEach { pkg ->
            assertTrue(
                "nothing in the baseline profile covers com.idanck.shidur.$pkg",
                rules.any { it.contains("Lcom/idanck/shidur/$pkg/") },
            )
        }
    }

    /**
     * Every rule is a profile rule, not prose that lost its `#`. A malformed line fails the
     * release build in `profgen`, which is a long way to find out about a typo.
     */
    @Test
    fun `every rule is a method rule for this app`() {
        profile().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { rule ->
                assertTrue(
                    "not a profile rule: $rule",
                    rule.startsWith("HSPL") && rule.contains("->") &&
                        rule.contains("Lcom/idanck/shidur/"),
                )
            }
    }
}
