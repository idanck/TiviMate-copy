package com.tivicopy.player.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tivicopy.player.R
import com.tivicopy.player.data.prefs.AppLanguage
import com.tivicopy.player.data.prefs.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FR-UI-23: choosing Hebrew has to reach the strings, not only the layout.
 *
 * This is the one thing the rest of the locale tests deliberately do not assert — they cover the
 * configuration this code builds — and it is exactly where the feature failed on a device: the
 * layout mirrored, proving the locale was applied, while every string stayed English.
 *
 * The cause is a name. Android resolves resources against the language
 * `ResourcesImpl.adjustLanguageTag` produces, and that maps `he` to `iw`, the code `he` retired,
 * on every release since Lollipop. A translation compiled under `values-he` is therefore never
 * looked up: the request says `iw`, the table says `he`, and the lookup falls through to the
 * default language without an error to notice.
 *
 * So this asserts the lookup itself rather than any rendering. The resource table under test is
 * compiled from the same folder the APK ships, which is the fact that decides it.
 */
@RunWith(RobolectricTestRunner::class)
// The plain platform Application: `App.onCreate` would drag WorkManager into a unit test.
@Config(sdk = [34], application = Application::class)
class HebrewStringsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** The value below lives in `values-iw/strings.xml`; the default one is "Appearance". */
    @Test
    fun `a Hebrew configuration resolves Hebrew strings`() {
        val locale = requireNotNull(AppLanguage.HEBREW.locale())
        val hebrew = app.createConfigurationContext(
            AppLocale.configurationFor(app.resources.configuration, locale),
        )
        assertEquals("מראה", hebrew.getString(R.string.settings_appearance))
    }

    /** And the untouched context keeps answering in the language the resources default to. */
    @Test
    fun `an untouched configuration resolves the default strings`() {
        assertEquals("Appearance", app.getString(R.string.settings_appearance))
    }
}
