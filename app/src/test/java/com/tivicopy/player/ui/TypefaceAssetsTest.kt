package com.tivicopy.player.ui

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled typeface is the weights it claims to be (FR-UI-27, ADR-046).
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
            "noto_sans_hebrew_regular.ttf" to 400,
            "noto_sans_hebrew_medium.ttf" to 500,
            "noto_sans_hebrew_semibold.ttf" to 600,
            "noto_sans_hebrew_bold.ttf" to 700,
            "noto_sans_hebrew_extrabold.ttf" to 800,
        )
        val dir = fontDir()
        expected.forEach { (name, weight) ->
            val file = File(dir, name)
            assertTrue("$name is missing from res/font", file.isFile)
            // A stray Git LFS pointer or a truncated download is a plausible way for this to go
            // wrong; a real weight of the subset Noto Sans Hebrew is around 44 KB.
            assertTrue("$name is too small to be a font (${file.length()} bytes)", file.length() > 20_000)
            assertEquals("$name declares the wrong usWeightClass", weight.toLong(), weightClassOf(file).toLong())
        }
    }

    /** The four-letter tags in a TrueType file's table directory. */
    private fun tablesOf(file: File): List<String> = RandomAccessFile(file, "r").use { raf ->
        raf.readInt() // sfnt version
        val tableCount = raf.readUnsignedShort()
        raf.seek(12)
        val tag = ByteArray(4)
        List(tableCount) {
            raf.readFully(tag)
            raf.skipBytes(12) // checksum, offset, length
            String(tag, Charsets.US_ASCII)
        }
    }

    /**
     * Every weight is a static instance, never the variable font (ADR-019, ADR-046): below API 26
     * Compose cannot select a variable font's weight, and this one's default is Thin. A variable
     * file dropped into `res/font/` under a static name would pass the weight check above (its
     * `OS/2` still says something) and draw every screen in hairlines on an older television.
     */
    @Test
    fun `no bundled weight is a variable font`() {
        val dir = fontDir()
        dir.listFiles { file -> file.extension == "ttf" }.orEmpty().forEach { file ->
            assertTrue("${file.name} still carries an fvar table", "fvar" !in tablesOf(file))
        }
    }

    /**
     * The SIL Open Font License asks that its text travel with the font, so each family's licence
     * is packaged as an asset rather than only living in `docs/`. Nothing reads these files at
     * runtime, which is exactly why they are easy to delete by accident.
     */
    @Test
    fun `each font licence ships with the app`() {
        listOf("noto-sans-hebrew-ofl.txt").forEach { name ->
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
