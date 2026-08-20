package com.tivicopy.player.data

import android.app.Application
import com.tivicopy.player.core.Outcome
import com.tivicopy.player.data.update.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The update descriptor (FR-UPD-01).
 *
 * This document decides which binary is installed over the running app, so the parser is
 * expected to refuse anything it cannot fully vouch for rather than fill in a default. Each
 * rejection below is a way a wrong or tampered manifest could otherwise reach the installer.
 *
 * Robolectric because `org.json` is a platform class: the stubbed android.jar returns defaults
 * for every call, which would make these assertions meaningless.
 */
@RunWith(RobolectricTestRunner::class)
// The plain platform Application, never this app's: `App.onCreate` would drag WorkManager and
// the notification channels into a unit test.
@Config(sdk = [34], application = Application::class)
class UpdateManifestTest {

    private val hash = "a".repeat(64)

    private fun json(
        versionCode: String = "11",
        versionName: String = "\"0.4.0\"",
        apkUrl: String = "\"https://updates.example.com/apk/app.apk\"",
        sha256: String = "\"$hash\"",
        sizeBytes: String = "1024",
        extra: String = "",
    ) = """
        {
          "versionCode": $versionCode,
          "versionName": $versionName,
          "apkUrl": $apkUrl,
          "sha256": $sha256,
          "sizeBytes": $sizeBytes$extra
        }
    """.trimIndent()

    private fun parsed(raw: String) = (UpdateManifest.parse(raw) as? Outcome.Ok)?.value

    @Test
    fun `reads every published field`() {
        val manifest = parsed(
            json(extra = ""","minSdk": 23, "notes": { "he": "עברית", "en": "English" }"""),
        )!!

        assertEquals(11, manifest.versionCode)
        assertEquals("0.4.0", manifest.versionName)
        assertEquals("https://updates.example.com/apk/app.apk", manifest.apkUrl)
        assertEquals(hash, manifest.sha256)
        assertEquals(1024L, manifest.sizeBytes)
        assertEquals(23, manifest.minSdk)
        assertEquals("עברית", manifest.notesFor("he"))
    }

    @Test
    fun `refuses plain http, so nothing on the path can swap the binary`() {
        assertNull(parsed(json(apkUrl = "\"http://updates.example.com/apk/app.apk\"")))
    }

    @Test
    fun `refuses a checksum that is not a sha-256`() {
        assertNull(parsed(json(sha256 = "\"abc123\"")))
        assertNull(parsed(json(sha256 = "\"${"z".repeat(64)}\"")))
    }

    @Test
    fun `accepts an upper-case checksum by normalising it`() {
        assertEquals(hash, parsed(json(sha256 = "\"${hash.uppercase()}\""))?.sha256)
    }

    @Test
    fun `refuses a missing or nonsense version`() {
        assertNull(parsed(json(versionCode = "0")))
        assertNull(parsed(json(versionCode = "-3")))
        assertNull(parsed(json(versionName = "\"  \"")))
    }

    @Test
    fun `refuses a size that is absent, empty or absurd`() {
        assertNull(parsed(json(sizeBytes = "0")))
        assertNull(parsed(json(sizeBytes = "${UpdateManifest.MAX_SIZE_BYTES + 1}")))
    }

    @Test
    fun `refuses something that is not JSON at all`() {
        assertNull(parsed("<html>404 Not Found</html>"))
    }

    @Test
    fun `only a higher version code counts as newer`() {
        val manifest = parsed(json(versionCode = "11"))!!
        assertTrue(manifest.isNewerThan(10))
        assertFalse(manifest.isNewerThan(11))
        assertFalse(manifest.isNewerThan(12))
    }

    @Test
    fun `notes fall back from a regional tag to the language, then to English`() {
        val manifest = parsed(json(extra = ""","notes": { "he": "עברית", "en": "English" }"""))!!
        assertEquals("עברית", manifest.notesFor("he-IL"))
        assertEquals("English", manifest.notesFor("fr"))
    }

    @Test
    fun `an absent notes block is simply absent`() {
        assertNull(parsed(json())!!.notesFor("he"))
    }
}
