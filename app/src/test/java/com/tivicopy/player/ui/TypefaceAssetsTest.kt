package com.tivicopy.player.ui

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled typefaces are the weights they claim to be (FR-UI-27, ADR-027).
 *
 * `ui/theme/Type.kt` maps each `FontWeight` to one file in `res/font/`, and nothing on the build
 * side checks that the file behind a name is actually that weight — a wrong drop, or a
 * regeneration that instanced the wrong axis value, compiles and ships, and the only symptom is
 * a screen that looks slightly off on a television nobody is standing in front of. This reads the
 * weight straight out of each file's `OS/2` table, which is the same number the renderer uses to
 * pick a face.
 *
 * Deliberately parsed by hand rather than through `android.graphics.Typeface`: the JVM suite has
 * no Android runtime (see `CLAUDE.md` — this app cannot be built or instrumented in the
 * environment it is developed in), and a TrueType table directory is a fixed-width header.
 */
class TypefaceAssetsTest {

    private fun fontDir(): File {
        // Gradle runs unit tests with the module directory as the working directory; a run from
        // the repository root is common enough to be worth surviving, as `StringsParityTest` does.
        val candidates = listOf(File("src/main/res/font"), File("app/src/main/res/font"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("cannot find res/font from ${File(".").absolutePath}")
    }

    /** `usWeightClass`: a big-endian `uint16` four bytes into the `OS/2` table. */
    private fun weightClassOf(file: File): Int = RandomAccessFile(file, "r").use { raf ->
        assertEquals("$file is not a TrueType outline font", 0x00010000L, raf.readInt().toLong())
        val tableCount = raf.readUnsignedShort()
        raf.seek(12)
        val tag = ByteArray(4)
        repeat(tableCount) {
            raf.readFully(tag)
            raf.readInt() // checksum
            val offset = raf.readInt().toLong()
            raf.readInt() // length
            if (String(tag, Charsets.US_ASCII) == "OS/2") {
                raf.seek(offset + 4)
                return raf.readUnsignedShort()
            }
        }
        error("$file has no OS/2 table")
    }

    @Test
    fun `each bundled weight is the weight its filename promises`() {
        val expected = mapOf(
            "ibm_plex_sans_hebrew_regular.ttf" to 400,
            "ibm_plex_sans_hebrew_medium.ttf" to 500,
            "ibm_plex_sans_hebrew_semibold.ttf" to 600,
            "ibm_plex_sans_hebrew_bold.ttf" to 700,
            "karantina_bold.ttf" to 700,
        )
        val dir = fontDir()
        expected.forEach { (name, weight) ->
            val file = File(dir, name)
            assertTrue("$name is missing from res/font", file.isFile)
            // A stray Git LFS pointer or a truncated download is a plausible way for this to go
            // wrong; a real weight of Plex Sans Hebrew is around 96 KB, and Karantina about 48 KB.
            assertTrue("$name is too small to be a font (${file.length()} bytes)", file.length() > 20_000)
            assertEquals("$name declares the wrong usWeightClass", weight.toLong(), weightClassOf(file).toLong())
        }
    }

    /**
     * The SIL Open Font License asks that its text travel with the font, so each family's licence
     * is packaged as an asset rather than only living in `docs/`. Nothing reads these files at
     * runtime, which is exactly why they are easy to delete by accident.
     */
    @Test
    fun `each font licence ships with the app`() {
        listOf("ibm-plex-sans-hebrew-ofl.txt", "karantina-ofl.txt").forEach { name ->
            val candidates = listOf(
                File("src/main/assets/licenses/$name"),
                File("app/src/main/assets/licenses/$name"),
            )
            val licence = candidates.firstOrNull { it.isFile }
                ?: error("$name is not in assets/licenses/")
            assertTrue("$name is not the OFL", licence.readText().contains("SIL OPEN FONT LICENSE"))
        }
    }
}
