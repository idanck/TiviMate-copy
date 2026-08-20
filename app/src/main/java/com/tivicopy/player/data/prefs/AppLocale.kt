package com.tivicopy.player.data.prefs

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Applies the interface language the user chose (FR-UI-23).
 *
 * There are two mechanisms, because the platform only grew one of its own in Android 13:
 *
 * - **Android 13 and later** hand the choice to [LocaleManager]. The system then applies it to
 *   the whole process — every activity, the service, the application context — recreates the
 *   activity itself, remembers it across reinstalls, and shows it under the device's own
 *   per-app language setting. Nothing here intervenes on top of that.
 * - **Below Android 13** there is no such API, so the locale is applied by wrapping the base
 *   context of [com.tivicopy.player.App], [com.tivicopy.player.ui.MainActivity] and
 *   [com.tivicopy.player.record.RecordingService], and the activity additionally registers it
 *   as its own override configuration — see [overrideFor].
 *
 * `AppCompatDelegate.setApplicationLocales` was rejected as the single mechanism: below API 33
 * that backport applies the locale through AppCompat's registry of active delegates, and
 * `MainActivity` is a `ComponentActivity` with no delegate, so the call would be a silent no-op
 * on exactly the platform range ADR-001 exists to keep reaching. See `docs/adr/ADR-009`.
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
     * Hands the choice to the platform, on the versions that have somewhere to put it.
     *
     * Returns true when the platform took it, which also means the platform will recreate the
     * activity; false means this build has to apply the choice itself.
     */
    fun applyToPlatform(context: Context, language: AppLanguage): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            context.getSystemService(LocaleManager::class.java)?.let { manager ->
                manager.applicationLocales = language.tag
                    ?.let { tag -> LocaleList.forLanguageTags(tag) }
                    ?: LocaleList.getEmptyLocaleList()
                true
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * The language the platform is applying to this app, or null where it does not do that.
     *
     * Null is the signal that the fallback below has to run: either this is an older Android, or
     * [LocaleManager] is unreadable on this device. An empty list means the user has not chosen
     * one, which is [AppLanguage.SYSTEM].
     */
    fun platformLanguage(context: Context): AppLanguage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            context.getSystemService(LocaleManager::class.java)
                ?.let { AppLanguage.fromLocaleTags(it.applicationLocales.toLanguageTags()) }
        }.getOrNull()
    }

    /**
     * Brings the stored choice and the platform's own into agreement, on the versions where the
     * platform has one.
     *
     * Two ways they can differ, and they need opposite answers:
     *
     * - The platform has no opinion while a choice is stored. That is an install carried over
     *   from a build that applied the language itself, or preferences restored from a backup.
     *   The user did choose, so the choice is handed to the platform rather than discarded.
     * - The platform disagrees with what is stored. The user changed it from the device's own
     *   per-app language screen, which wins: the platform is what is actually being applied, so
     *   preferences follow it and the picker in Settings shows the truth.
     */
    fun reconcile(context: Context, settings: Settings) {
        val platform = platformLanguage(context) ?: return
        val stored = settings.state.value.language
        when {
            platform == stored -> Unit
            platform == AppLanguage.SYSTEM -> applyToPlatform(context, stored)
            else -> settings.setLanguage(platform)
        }
    }

    /**
     * [base], or a copy of it resolving resources in the chosen language.
     *
     * [AppLanguage.SYSTEM] returns [base] untouched, so the platform's own locale — including an
     * Android 13+ per-app language — keeps working exactly as it did. So does every context on
     * Android 13 and later, where [LocaleManager] has already applied the choice to the process.
     *
     * Deliberately does not touch [Locale.setDefault]. Doing so leaked: the [AppLanguage.SYSTEM]
     * branch returns early, so switching back to "follow the device" left the process default on
     * the language the user had just abandoned, for every call that does not pass a locale
     * explicitly. Resource resolution comes from the configuration, which is what this returns;
     * the formatters in `TimeModel`, `TextNormalizer`, `CatchupUrlBuilder` and `CacheBudget` all
     * pass [Locale.ROOT] by hand and never wanted the default anyway.
     */
    fun wrap(base: Context): Context = wrap(base, platformApplied(base))

    /**
     * [wrap] with the platform's answer already in hand, for a caller that has to ask
     * [activityOverride] the same question and should not ask the system twice.
     */
    fun wrap(base: Context, platformApplied: Boolean): Context {
        if (platformApplied) return base
        val locale = stored(base).locale() ?: return base
        return base.createConfigurationContext(configurationFor(base.resources.configuration, locale))
    }

    /**
     * The locale as an activity's own override configuration, or null when the platform is the
     * one applying it.
     *
     * This is what the base-context wrap alone was missing. `ResourcesManager` rebases every
     * `Resources` tied to an activity token onto that activity's override configuration whenever
     * the configuration changes — a resize, entering the floating window, a night-mode switch,
     * and on several devices once during the launch itself. A locale that lives only on the base
     * context is not in that override, so it is dropped on the first such update and the
     * interface silently returns to the system language. Registering it here is how AppCompat
     * has always carried its own configuration overrides.
     */
    fun activityOverride(base: Context, platformApplied: Boolean): Configuration? =
        if (platformApplied) null else stored(base).locale()?.let(::overrideFor)

    /**
     * True when [LocaleManager] has the choice, so nothing here should apply it a second time.
     *
     * Public because an activity needs both [wrap] and [activityOverride] to agree, and asking
     * twice is two calls into the system server on the launch path.
     */
    fun platformApplied(base: Context): Boolean = platformLanguage(base) != null

    /**
     * A configuration carrying the locale and nothing else.
     *
     * `Configuration()` is deliberately the no-argument constructor rather than
     * `setToDefaults()`: it leaves every field unset, which is what makes this a delta. A
     * "default" configuration would carry `fontScale = 1`, and applying it would quietly undo
     * the device's own font scale for this activity.
     */
    fun overrideFor(locale: Locale): Configuration = Configuration().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setLocales(LocaleList(locale))
        } else {
            setLocale(locale)
        }
        setLayoutDirection(locale)
    }

    /**
     * The locale a context is actually resolving strings in.
     *
     * Reported by Diagnostics: when someone says the interface is still in the wrong language,
     * this is the line that says whether the choice reached the resources at all, and it is the
     * one thing a device can answer that no test here can.
     */
    fun resolved(context: Context): Locale {
        val config = context.resources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val locales = config.locales
            if (locales.size() > 0) return locales[0]
        }
        @Suppress("DEPRECATION")
        return config.locale ?: Locale.getDefault()
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
