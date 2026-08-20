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
    /**
     * Deliberately does not touch [Locale.setDefault]. Doing so leaked: the
     * [AppLanguage.SYSTEM] branch returns early, so switching back to "follow the device" left
     * the process default on the language the user had just abandoned, for every call that does
     * not pass a locale explicitly. Resource resolution comes from the configuration, which is
     * what this returns; the formatters in `TimeModel`, `TextNormalizer`, `CatchupUrlBuilder` and
     * `CacheBudget` all pass [Locale.ROOT] by hand and never wanted the default anyway.
     */
    fun wrap(base: Context): Context {
        val locale = stored(base).locale() ?: return base
        return base.createConfigurationContext(configurationFor(base.resources.configuration, locale))
    }

    /**
     * The configuration [wrap] applies.
     *
     * Split out from [wrap] so it can be asserted directly. What this has to get right is our
     * side — the locale *and* the layout direction — rather than the platform's resource lookup,
     * which is what `createConfigurationContext` exists to do.
     */
    fun configurationFor(base: Configuration, locale: Locale): Configuration = Configuration(base).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setLocales(LocaleList(locale))
        } else {
            setLocale(locale)
        }
        // Without this, choosing Hebrew on an English device gives Hebrew text inside a
        // left-to-right layout — a half-finished translation rather than a Hebrew interface.
        // Compose derives LocalLayoutDirection from the configuration, not from the script.
        setLayoutDirection(locale)
    }
}

