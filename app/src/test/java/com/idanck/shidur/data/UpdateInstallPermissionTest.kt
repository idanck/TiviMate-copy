package com.idanck.shidur.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.idanck.shidur.data.db.Dao
import com.idanck.shidur.data.db.Database
import com.idanck.shidur.data.prefs.Settings
import com.idanck.shidur.data.update.UpdateBlocker
import com.idanck.shidur.data.update.UpdateManager
import com.idanck.shidur.data.update.UpdateManifest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Allow installs from this app" as something the screens can watch (issue #29).
 *
 * The grant is made on a screen of the platform's own, so a one-shot `canRequestPackageInstalls`
 * read is stale the moment the user acts on it: the prompt that named the permission had no way
 * to notice it had been given, and asked the user to start the whole check again. The permission
 * is state now, and everything that reads it republishes what it saw.
 */
@RunWith(RobolectricTestRunner::class)
// The plain platform Application, never this app's: `App.onCreate` would drag WorkManager and
// the notification channels into a unit test.
@Config(sdk = [34], application = Application::class)
class UpdateInstallPermissionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var database: Database

    @Before
    fun open() {
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        database = Database(context)
    }

    @After
    fun close() {
        database.close()
    }

    private fun manager(): UpdateManager =
        UpdateManager(context, Settings(context), Dao(database))

    private fun manifest(): UpdateManifest = UpdateManifest(
        versionCode = 99,
        versionName = "9.9.9",
        apkUrl = "https://updates.idanck.com/tivicopy-99.apk",
        sha256 = "0".repeat(64),
        sizeBytes = 16L * 1024 * 1024,
        minSdk = 23,
        notes = emptyMap(),
    )

    @Test
    fun `the permission is published as state, not only read on demand`() {
        val updates = manager()

        assertEquals(updates.canRequestInstalls(), updates.canInstall.value)
    }

    @Test
    fun `a refresh answers with what it published`() {
        val updates = manager()

        val granted = updates.refreshInstallPermission()

        assertEquals(granted, updates.canInstall.value)
        assertEquals(updates.canRequestInstalls(), updates.canInstall.value)
    }

    /**
     * The refusal and the screens have to be telling the same story: a dialog offering "allow
     * installs" beside a settings screen that thinks the permission is already there is exactly
     * the confusion this is meant to end.
     */
    @Test
    fun `the refusal that names the permission republishes it`() {
        val updates = manager()

        val blocker = updates.blockingReason(manifest())

        // Nothing has granted it here, and the permission is checked before every reason
        // below it, so this is the one a fresh install is refused for.
        assertEquals(UpdateBlocker.INSTALL_PERMISSION, blocker)
        assertFalse(updates.canInstall.value)
        // And the two always agree, whichever way the platform answered: the refusal names
        // the permission exactly when the flow says it is missing.
        assertEquals(!updates.canInstall.value, blocker == UpdateBlocker.INSTALL_PERMISSION)
    }
}
