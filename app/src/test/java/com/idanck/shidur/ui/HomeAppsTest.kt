package com.idanck.shidur.ui

import com.idanck.shidur.ui.home.AppShortcut
import com.idanck.shidur.ui.home.HomeApps
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home screen's app shortcuts (FR-UI-28, ADR-020).
 *
 * `HomeApps` is the half of the strip that can be checked without a device — the same split
 * `HomeRails` uses, and for the same reason: this repository cannot build the app at all, so
 * anything that can be a pure function over plain data is one.
 *
 * The last test here is the one that earns its keep. Android 11 hides a package we have not
 * declared in the manifest's `<queries>` block, whether or not it is installed, so a shortcut
 * added to the catalogue and forgotten in the manifest is invisible on every modern device and
 * perfectly fine on the old one someone happens to test with.
 */
class HomeAppsTest {

    private val catalogue = listOf(
        AppShortcut("one", "One", listOf("com.one.tv", "com.one.phone")),
        AppShortcut("two", "Two", listOf("com.two")),
    )

    @Test
    fun `an entry resolves to its first launchable package, television build first`() {
        val resolved = HomeApps.resolve(catalogue, launchable = { it != "com.one.tv" })
        assertEquals(listOf("com.one.phone", "com.two"), resolved.map { it.packageName })
    }

    @Test
    fun `the leanback build wins when both are installed`() {
        val resolved = HomeApps.resolve(catalogue, launchable = { true })
        assertEquals("com.one.tv", resolved.first().packageName)
    }

    @Test
    fun `an app that is not installed is not a tile`() {
        val resolved = HomeApps.resolve(catalogue, launchable = { it == "com.two" })
        assertEquals(listOf("two"), resolved.map { it.id })
    }

    @Test
    fun `nothing installed is an empty strip, not an empty heading`() {
        assertTrue(HomeApps.resolve(catalogue, launchable = { false }).isEmpty())
    }

    @Test
    fun `the installed app's own name wins, and a blank one falls back`() {
        val named = HomeApps.resolve(
            catalogue,
            launchable = { true },
            label = { name -> if (name == "com.one.tv") "One TV" else "   " },
        )
        assertEquals(listOf("One TV", "Two"), named.map { it.label })
    }

    @Test
    fun `the catalogue has no duplicate ids or packages`() {
        val ids = HomeApps.CATALOGUE.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val packages = HomeApps.declaredPackages()
        assertEquals(packages.size, packages.toSet().size)
    }

    @Test
    fun `every package the catalogue can ask about is declared in the manifest`() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull { it.isFile }
            ?: error("cannot find AndroidManifest.xml from ${File(".").absolutePath}")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val queries = document.getElementsByTagName("queries")
        assertTrue("the manifest declares no <queries> block at all", queries.length > 0)
        val declared = buildSet {
            val packages = document.getElementsByTagName("package")
            for (index in 0 until packages.length) {
                packages.item(index).attributes?.getNamedItem("android:name")?.nodeValue?.let(::add)
            }
        }
        assertEquals(
            "packages in the catalogue that the manifest hides from us",
            emptySet<String>(),
            HomeApps.declaredPackages().toSet() - declared,
        )
    }
}
