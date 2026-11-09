package com.idanck.shidur.data

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every string the app ships exists in both languages (FR-UI-23).
 *
 * Android's resource lookup falls back to the default language silently, so a string added to
 * `values/` and forgotten in `values-iw/` produces an English word in the middle of a Hebrew
 * screen — on a device, with nothing failing anywhere to say so. `HebrewStringsTest` proves the
 * *lookup* resolves Hebrew; nothing proved the two files were the same size until this.
 *
 * Reads the XML off disk rather than through `R`, because that fallback is exactly what has to
 * be defeated: through `R` a missing translation looks like a present one.
 */
class StringsParityTest {

    private fun resDir(): File {
        // Gradle runs unit tests with the module directory as the working directory, but a run
        // from the repository root is common enough to be worth surviving.
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("cannot find res/ from ${File(".").absolutePath}")
    }

    private fun names(file: File): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it).attributes?.getNamedItem("name")?.nodeValue }
            .toSet()
    }

    @Test
    fun `the default and Hebrew string tables carry the same keys`() {
        val res = resDir()
        val default = names(File(res, "values/strings.xml"))
        val hebrew = names(File(res, "values-iw/strings.xml"))

        assertTrue("the default string table is empty; wrong working directory?", default.size > 100)
        assertEquals("strings missing from values-iw", emptySet<String>(), default - hebrew)
        assertEquals("strings only in values-iw", emptySet<String>(), hebrew - default)
    }

    /**
     * Hebrew lives in `values-iw`, not `values-he`: `ResourcesImpl.adjustLanguageTag` maps `he`
     * to `iw` on every release since Lollipop, so a `values-he` folder is compiled and never
     * looked up. `HebrewStringsTest` explains it at length; this stops one being added by
     * accident and silently doing nothing.
     */
    @Test
    fun `there is no values-he folder to be silently ignored`() {
        assertTrue(!File(resDir(), "values-he").exists())
    }
}
