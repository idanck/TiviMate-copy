package com.tivicopy.player.data

import android.app.Application
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.tivicopy.player.data.prefs.AppLanguage
import com.tivicopy.player.data.prefs.AppLocale
import com.tivicopy.player.data.prefs.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * FR-UI-23: the chosen interface language becomes a configuration carrying both the locale and
 * the layout direction, and following the device means not intervening at all.
 *
 * These assert the configuration this code builds, not the strings the platform then resolves
 * from it. `createConfigurationContext` is Android's job; a JVM unit test that appears to prove
 * it would be proving Robolectric's emulation of it instead. That the Hebrew resources are
 * complete and in sync is checked in the build, and that they actually render needs a device.
 */
@RunWith(RobolectricTestRunner::class)
// The plain platform Application: `App.onCreate` would drag WorkManager into a unit test.
@Config(sdk = [34], application = Application::class)
class AppLocaleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun clearStoredLanguage() {
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

    /**
     * The half that is easy to get wrong. Setting the locale alone gives Hebrew text inside a
     * left-to-right layout, which reads as a half-finished translation rather than a Hebrew
     * interface, so the layout direction has to travel on the same configuration.
     */
    @Test
    fun `choosing Hebrew sets the locale and mirrors the layout`() {
        val config = AppLocale.configurationFor(
            context.resources.configuration,
            AppLanguage.HEBREW.locale()!!,
        )

        assertEquals("he", config.locales[0].language)
        assertEquals(View.LAYOUT_DIRECTION_RTL, config.layoutDirection)
    }

    @Test
    fun `choosing English sets the locale and leaves the layout alone`() {
        val config = AppLocale.configurationFor(
            context.resources.configuration,
            AppLanguage.ENGLISH.locale()!!,
        )

        assertEquals("en", config.locales[0].language)
        assertEquals(View.LAYOUT_DIRECTION_LTR, config.layoutDirection)
    }

    /** The rest of the configuration is carried over, not rebuilt from defaults. */
    @Test
    fun `everything the configuration already carried survives`() {
        val base = context.resources.configuration

        val config = AppLocale.configurationFor(base, AppLanguage.HEBREW.locale()!!)

        assertEquals(base.densityDpi, config.densityDpi)
        assertEquals(base.screenWidthDp, config.screenWidthDp)
        assertEquals(base.uiMode, config.uiMode)
    }

    @Test
    fun `a stored choice reaches the wrapper as a different context`() {
        store(AppLanguage.HEBREW)

        assertNotSame(context, AppLocale.wrap(context))
    }

    /**
     * The process default is left alone deliberately.
     *
     * Moving it leaked: the "follow the device" branch returns early, so it would never have been
     * put back, and the process would keep formatting in a language the user had abandoned.
     * Resources come from the configuration, and every formatter in this app passes a locale.
     */
    @Test
    fun `wrapping does not move the process default locale`() {
        val before = Locale.getDefault()
        store(AppLanguage.HEBREW)

        AppLocale.wrap(context)

        assertEquals(before, Locale.getDefault())
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
