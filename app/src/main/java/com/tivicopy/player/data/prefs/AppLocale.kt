package com.tivicopy.player.data.prefs

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Applies the interface language the user chose (FR-UI-23).
 *
 * The mechanism is a wrapped base context rather than
 * `AppCompatDelegate.setApplicationLocales`. Below API 33 that backport applies the locale through
 * AppCompat's registry of active delegates, and [com.tivicopy.player.ui.MainActivity] is a
 * `ComponentActivity` with no delegate, so the call would be a silent no-op — on exactly the
 * platform range ADR-001 exists to keep reaching. It also only ever covers activities, which would
 * leave the recording notification and the notification channels speaking the system language.
 * See `docs/adr/ADR-009`.
 *
 * Deliberately reads [android.content.SharedPreferences] directly instead of going through
 * [Settings]: `attachBaseContext` runs before `Application.onCreate`, so the service locator does
 * not exist yet.
 */
object AppLocale {

    /** The stored choice, or [AppLanguage.SYSTEM] when nothing is stored or storage is unreadable. */
    fun stored(base: Context): AppLanguage = runCatching {
        val raw = base.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Settings.K_LANGUAGE, null)
        AppLanguage.fromStored(raw)
    }.getOrDefault(AppLanguage.SYSTEM)
    // The read is guarded because a direct-boot launch can reach attachBaseContext before the
    // credential-encrypted preference file is available. Falling back to the system language is
    // the right answer there, and it is what the user had before they ever opened settings.

    /**
     * [base], or a copy of it resolving resources in the chosen language.
     *
     * [AppLanguage.SYSTEM] returns [base] untouched, so the platform's own locale — including an
     * Android 13+ per-app language — keeps working exactly as it did.
     */
    fun wrap(base: Context): Context {
        val tag = stored(base).tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            config.setLocale(locale)
        }
        // Without this, choosing Hebrew on an English device gives Hebrew text in a left-to-right
        // layout: Compose derives LocalLayoutDirection from the configuration, not from the script.
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

/**
 * A context that resolves strings in the chosen language, for code that is not an Activity.
 *
 * `Application` is not rebuilt when the language changes — only the activity is recreated — so a
 * long-lived `Application` context keeps whatever locale it was attached with. Anything reading a
 * string outside the composition should go through this.
 */
val Context.localised: Context get() = AppLocale.wrap(this)
