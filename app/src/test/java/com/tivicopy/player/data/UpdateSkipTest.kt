package com.tivicopy.player.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tivicopy.player.BuildInfo
import com.tivicopy.player.data.prefs.Settings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Skip this version" and the checked-at stamp (FR-UPD-06).
 *
 * The skip is stored as a floor rather than a set, which is what makes it safe to leave behind:
 * a build newer than the skipped one is offered again, and a skip for something already
 * installed is forgotten rather than silencing the next release too.
 */
@RunWith(RobolectricTestRunner::class)
// The plain platform Application, never this app's: `App.onCreate` would drag WorkManager and
// the notification channels into a unit test.
@Config(sdk = [34], application = Application::class)
class UpdateSkipTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `a skipped version is remembered as a floor`() {
        val settings = Settings(context)
        settings.setUpdateSkippedVersion(12)

        assertEquals(12, settings.state.value.updateSkippedVersionCode)
    }

    @Test
    fun `a check records when it happened`() {
        val settings = Settings(context)
        settings.rememberUpdateCheck(1_700_000_000_000L, BuildInfo.versionCode)

        assertEquals(1_700_000_000_000L, settings.state.value.updateLastCheckUtc)
    }

    @Test
    fun `a skip survives while that version is still the newest one out there`() {
        val settings = Settings(context)
        val ahead = BuildInfo.versionCode + 5
        settings.setUpdateSkippedVersion(ahead)

        settings.rememberUpdateCheck(1L, ahead)

        assertEquals(ahead, settings.state.value.updateSkippedVersionCode)
    }

    @Test
    fun `a skip is forgotten once that version is what is running`() {
        val settings = Settings(context)
        settings.setUpdateSkippedVersion(BuildInfo.versionCode)

        settings.rememberUpdateCheck(1L, BuildInfo.versionCode)

        assertEquals(0, settings.state.value.updateSkippedVersionCode)
    }
}
