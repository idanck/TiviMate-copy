package com.tivicopy.player.ui

import java.io.DataInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The launcher icon and the TV banner are the files `docs/design/studio/ICON.md` asks for.
 *
 * Nothing on the build side notices a banner at the wrong size or an adaptive icon whose layer
 * points at a drawable that was renamed: aapt only resolves the reference, and the Google TV
 * launcher stretches whatever it is given. The symptom is a blurry or blank tile on a television,
 * which is the one place this repository's environment cannot look (see `CLAUDE.md`). So this
 * reads the files themselves: the PNG header for sizes, the XML for the layers.
 */
class AppIconResourcesTest {

    private fun resDir(): File {
        // Same two working directories `TypefaceAssetsTest` survives: the module, or the root.
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("cannot find res/ from ${File(".").absolutePath}")
    }

    /** Width and height from a PNG's `IHDR` chunk, which always comes first, right after the signature. */
    private fun pngSize(file: File): Pair<Int, Int> = DataInputStream(file.inputStream()).use { input ->
        val signature = ByteArray(8).also { input.readFully(it) }
        assertTrue("$file is not a PNG", signature.contentEquals(PNG_SIGNATURE))
        input.readInt() // chunk length
        val type = ByteArray(4).also { input.readFully(it) }
        assertEquals("$file does not start with IHDR", "IHDR", String(type, Charsets.US_ASCII))
        input.readInt() to input.readInt()
    }

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    /** `@drawable/name` or `@color/name`, resolved to a file that exists, or a failure. */
    private fun assertResolves(reference: String, from: String) {
        assertTrue("$from has no android:drawable", reference.startsWith("@") && '/' in reference)
        val res = resDir()
        val (type, name) = reference.removePrefix("@").split('/', limit = 2)
        val found = when (type) {
            "drawable" -> res.listFiles { dir -> dir.name.startsWith("drawable") }.orEmpty()
                .any { dir -> File(dir, "$name.xml").isFile || File(dir, "$name.png").isFile }
            "color" -> res.listFiles { dir -> dir.name.startsWith("values") }.orEmpty()
                .flatMap { dir -> dir.listFiles { file -> file.extension == "xml" }.orEmpty().toList() }
                .any { file -> file.readText().contains("<color name=\"$name\"") }
            else -> false
        }
        assertTrue("$from points at $reference, which is not in res/", found)
    }

    @Test
    fun `the TV banner is 160 by 90dp, at mdpi and xhdpi`() {
        mapOf("drawable-mdpi" to (160 to 90), "drawable-xhdpi" to (320 to 180)).forEach { (dir, size) ->
            val banner = File(resDir(), "$dir/tv_banner.png")
            assertTrue("$dir/tv_banner.png is missing", banner.isFile)
            assertEquals("$dir/tv_banner.png is the wrong size", size, pngSize(banner))
        }
    }

    @Test
    fun `the legacy launcher icons are 48dp at every density`() {
        val sizes = mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
        sizes.forEach { (density, px) ->
            listOf("ic_launcher", "ic_launcher_round").forEach { name ->
                val icon = File(resDir(), "mipmap-$density/$name.png")
                assertTrue("mipmap-$density/$name.png is missing", icon.isFile)
                assertEquals("mipmap-$density/$name.png is the wrong size", px to px, pngSize(icon))
            }
        }
    }

    @Test
    fun `the adaptive icon has a background, a mark and a monochrome layer, all of them real`() {
        listOf("ic_launcher", "ic_launcher_round").forEach { name ->
            val file = File(resDir(), "mipmap-anydpi-v26/$name.xml")
            assertTrue("mipmap-anydpi-v26/$name.xml is missing", file.isFile)
            val icon = parse(file)
            assertEquals("$name.xml is not an adaptive icon", "adaptive-icon", icon.tagName)
            listOf("background", "foreground", "monochrome").forEach { layer ->
                val element = icon.getElementsByTagName(layer).item(0) as? Element
                    ?: error("$name.xml has no <$layer> layer")
                assertResolves(element.getAttribute("android:drawable"), "$name.xml <$layer>")
            }
        }
    }

    @Test
    fun `the mark and the monochrome layer are drawn on the 108dp adaptive canvas`() {
        listOf("ic_launcher_foreground", "ic_launcher_monochrome").forEach { name ->
            val vector = parse(File(resDir(), "drawable/$name.xml"))
            assertEquals("$name is not a vector", "vector", vector.tagName)
            val canvas = mapOf(
                "android:width" to "108dp",
                "android:height" to "108dp",
                "android:viewportWidth" to "108",
                "android:viewportHeight" to "108",
            )
            canvas.forEach { (attr, value) ->
                assertEquals("$name has the wrong $attr", value, vector.getAttribute(attr))
            }
            // The letter ש and the bar under it.
            assertEquals("$name should carry two paths", 2, vector.getElementsByTagName("path").length)
        }
    }

    @Test
    fun `the manifest uses these files`() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull { it.isFile }
            ?: error("cannot find AndroidManifest.xml from ${File(".").absolutePath}")
        val application = parse(manifest).getElementsByTagName("application").item(0) as Element
        assertEquals("@drawable/tv_banner", application.getAttribute("android:banner"))
        assertEquals("@mipmap/ic_launcher", application.getAttribute("android:icon"))
        assertEquals("@mipmap/ic_launcher_round", application.getAttribute("android:roundIcon"))
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
