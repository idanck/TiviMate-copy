# ADR-009 — The interface language is the platform's above Android 13, and a base-context wrap below it

**Status:** accepted

## Decision

FR-UI-23 adds an in-app language picker. On Android 13 and later the choice is handed to the
platform's own `LocaleManager.setApplicationLocales`, which applies it to the whole process and
recreates the activity itself. Below that there is no such API, so it is applied by overriding
`attachBaseContext` in `App`, `MainActivity` and `RecordingService`, wrapping the incoming
context with `createConfigurationContext` for the chosen locale, additionally registering that
locale as the activity's own override configuration, and recreating the activity when the choice
changes. `AppCompatDelegate.setApplicationLocales` was rejected as the mechanism for the older
range.

The wrap alone was the original decision; see the amendment at the end for what a device did
with it.

`AppLanguage.SYSTEM` is the default and returns the context untouched, so the platform's own
locale — including an Android 13 per-app language, which `locales_config.xml` now makes
available — keeps working exactly as before.

## Context

The Hebrew translation has been complete since the first release, but nothing in the app could
select it: the interface followed the device locale, so a Hebrew interface meant switching the
whole television to Hebrew. FR-UI-21 asks for a Hebrew interface; it did not ask how the user
reaches one, and nothing else in `requirements/` covers an app language setting. That is a new
requirement, which is what brings this here rather than being a plain code change.

`AppCompatDelegate.setApplicationLocales` is the platform-blessed route, and it does not fit:

- Below API 33 it applies the locale through AppCompat's registry of active
  `AppCompatDelegate`s. `MainActivity` is a `ComponentActivity` and has none, so the call would
  be a silent no-op — on precisely the platform range ADR-001 exists to keep reaching.
- Making it work means converting to `AppCompatActivity`, which requires re-parenting
  `Theme.TiviCopy` off `android:Theme.Material.NoActionBar` onto an AppCompat theme, and with it
  the delegate's view-inflation interception and night-mode handling. That is a large change to
  the launch path, on a project that cannot run a build locally.
- It reaches activities only. The notification channels, the recording service's ongoing
  notification and the failover notices are all resolved outside one, and would have kept
  speaking the system language.

Providing `LocalContext` and `LocalLayoutDirection` in Compose instead was also rejected: it
desynchronises `LocalContext.resources` from `LocalConfiguration`, leaves every non-Compose
`getString` on the system locale, and would have to be threaded through `AlertDialog`, which
composes in its own sub-composition.

## Consequence

- The choice reaches everything, because the wrap happens at the context each component is
  built from rather than at one screen.
- A language change recreates the activity. Playback survives it — `onStop` releases the player
  only when the activity is finishing, which `recreate()` does not set.
- `Application` is attached once and is not rebuilt, so code holding the application context
  resolves strings through a `localised` wrapper instead. A recording already running keeps the
  language it started in until it finishes.
- Notification channel names are stored by the platform at creation, so the picker re-declares
  the channels; creating one that already exists updates its name.
- Every formatter in `TimeModel`, `TextNormalizer`, `CatchupUrlBuilder` and `CacheBudget` passes
  `Locale.ROOT` explicitly, so setting the process default cannot destabilise parsing, matching
  or filenames.
- The set of languages stays bounded by `resourceConfigurations` in the build file. Adding one
  means adding it there, in `locales_config.xml`, and in `AppLanguage`.

## Amendment — what the device said

The first implementation applied the language by wrapping the base context and nothing else, on
every version. Reported from the device: choosing Hebrew changed nothing, and the interface
stayed in English.

Two things were wrong with it, and both are addressed above.

- A locale that lives only on an activity's base context is not part of that activity's override
  configuration. `ResourcesManager` rebases every `Resources` held under an activity token onto
  that override whenever the configuration changes — a resize, the floating window, a night-mode
  switch, and on several devices once during the launch itself — and anything that is not in the
  override is dropped. The interface then returns to the system language with nothing in the
  logs to say so. `applyOverrideConfiguration` is how AppCompat has always carried its own
  overrides, and it is what the activity now does below Android 13.
- Android 13 has an API for exactly this, and it had simply not been reached for. It applies the
  choice to every context in the process rather than the three this app remembers to wrap, it
  survives a process death without a wrap at all, and it is the same setting the device's own
  per-app language screen writes — so the two can no longer disagree. `LocaleManager` is
  therefore the mechanism wherever it exists, and the wrap is the fallback below it.

The consequences of that split:

- The two can drift apart in one direction that matters: an install upgraded from a build that
  applied the language itself has the choice in preferences and nothing in the platform. On
  Android 13 and later the choice is handed over on the next start, rather than being discarded.
  A change made from the device's per-app language screen goes the other way and wins, because
  it is what is actually being applied.
- Preferences stay the single thing the picker reads, on every version.
- Diagnostics now reports the stored choice, the platform's, the locale the app actually
  resolved, and the layout direction. A report of this shape is the only way to tell these
  failures apart without the device in hand.
