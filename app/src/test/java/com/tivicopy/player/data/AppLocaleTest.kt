package com.tivicopy.player.data

import android.app.Application
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.tivicopy.player.R
import com.tivicopy.player.data.prefs.AppLanguage
import com.tivicopy.player.data.prefs.AppLocale
import com.tivicopy.player.data.prefs.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * FR-UI-23: the chosen interface language reaches the resources, and Hebrew brings the layout
 * direction with it.
 *
 * The second part is the one worth pinning. Setting the locale alone gives Hebrew text inside a
 * left-to-right layout, which looks like a half-finished translation rather than a Hebrew
 * interface; the layout direction has to be set on the same configuration.
 */
@RunWith(RobolectricTestRunner::class)
// The plain platform Application: `App.onCreate` would drag WorkManager into a unit test.
@Config(sdk = [34], application = Application::class)
class AppLocaleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var systemDefault: Locale

    @Before
    fun rememberDefault() {
        systemDefault = Locale.getDefault()
    }

    /** `wrap` sets the process default, so it is put back rather than left for the next test. */
    @After
    fun restoreDefault() {
        Locale.setDefault(systemDefault)
        store(null)
    }

    private fun store(language: AppLanguage?) {
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (language == null) remove(Settings.K_LANGUAGE) else putString(Settings.K_LANGUAGE, language.name)
            }
            .commit()
    }

    @Test
    fun `choosing Hebrew resolves Hebrew strings and mirrors the layout`() {
        store(AppLanguage.HEBREW)

        val wrapped = AppLocale.wrap(context)

        assertEquals("שידור חי", wrapped.getString(R.string.nav_live))
        assertEquals(
            View.LAYOUT_DIRECTION_RTL,
            wrapped.resources.configuration.layoutDirection,
        )
    }

    @Test
    fun `choosing English resolves the default strings`() {
        store(AppLanguage.ENGLISH)

        val wrapped = AppLocale.wrap(context)

        assertEquals("Live", wrapped.getString(R.string.nav_live))
        assertEquals(
            View.LAYOUT_DIRECTION_LTR,
            wrapped.resources.configuration.layoutDirection,
        )
    }

    /**
     * Following the device has to mean not intervening at all — that is what leaves an Android 13
     * per-app language, and a plain device-language change, working as they did.
     */
    @Test
    fun `following the device hands back the same context`() {
        store(null)

        assertSame(context, AppLocale.wrap(context))
    }

    @Test
    fun `an unreadable or unknown stored value falls back to the device language`() {
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(Settings.K_LANGUAGE, "KLINGON").commit()

        assertEquals(AppLanguage.SYSTEM, AppLocale.stored(context))
        assertSame(context, AppLocale.wrap(context))
    }
}
