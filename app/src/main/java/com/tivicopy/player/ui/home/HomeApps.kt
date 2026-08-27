package com.tivicopy.player.ui.home

/**
 * The strip of streaming-app shortcuts along the home screen (ADR-020, FR-UI-28).
 *
 * The Cellcom TV teardown found a row of app icons directly under its hero banner — Netflix,
 * YouTube, Spotify, Prime Video and the rest, as deep links straight into the installed app. On a
 * television that is not a decoration: this app is one of several things a viewer opens on that
 * box, and every one of them is otherwise three presses away through the system launcher.
 *
 * This file is the part of it that can be checked without a device, which is the same rule
 * `HomeRails` follows: a catalogue of packages and a pure resolution over "is this launchable",
 * with everything Android — the `PackageManager`, the icons, the intent — in `ui/tv/HomeAppStrip`.
 *
 * **Package ids here are verified, never guessed.** A wrong id is harmless at runtime (it simply
 * never resolves, so the shortcut never appears), which is exactly what makes a guessed one so
 * easy to leave in for years while a viewer wonders where their app went. Anything added later
 * belongs here only once someone has read it off a real device — `adb shell pm list packages`
 * or `cmd package resolve-activity` — and it must be listed in the manifest's `<queries>` block
 * as well, or Android 11 and later will hide it from us whether it is installed or not.
 */
data class AppShortcut(
    /** Stable identity for keys and tests; not shown to anyone. */
    val id: String,
    /** Shown only when the installed package refuses to give up its own label. */
    val fallbackLabel: String,
    /**
     * Candidate package ids, most television-specific first. The first one that is launchable
     * wins, so a box with both the leanback build and the phone build shows the leanback one.
     */
    val packages: List<String>,
)

/** One catalogue entry that turned out to be installed, resolved to the package that answered. */
data class LaunchableApp(
    val id: String,
    val label: String,
    val packageName: String,
)

object HomeApps {

    val CATALOGUE: List<AppShortcut> = listOf(
        AppShortcut("netflix", "Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient")),
        AppShortcut("youtube", "YouTube", listOf("com.google.android.youtube.tv", "com.google.android.youtube")),
        AppShortcut(
            "prime_video",
            "Prime Video",
            listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
        ),
        AppShortcut("disney_plus", "Disney+", listOf("com.disney.disneyplus")),
        AppShortcut(
            "apple_tv",
            "Apple TV",
            listOf("com.apple.atve.androidtv.appletv", "com.apple.atve.android.appletv"),
        ),
        AppShortcut("hbo_max", "Max", listOf("com.wbd.stream", "com.hbo.hbonow")),
        AppShortcut("spotify", "Spotify", listOf("com.spotify.tv.android", "com.spotify.music")),
        AppShortcut("plex", "Plex", listOf("com.plexapp.android")),
        // Read off the teardown itself (`docs/adr/ADR-019`), which is the only reason it is not a
        // guess: the app that research was done on is also the one this viewer actually has.
        AppShortcut("cellcom_tv", "Cellcom TV", listOf("com.cellcom.cellcom_tv")),
    )

    /**
     * The catalogue, in its own order, reduced to what this device can actually open.
     *
     * @param launchable whether a package is installed *and* has a launcher activity. Both halves
     *   matter: a package that is present but cannot be started produces a tile that does nothing.
     * @param label the installed app's own name, which is localised and is what the viewer reads
     *   on their launcher. Null falls back to the catalogue's plain-English one.
     */
    fun resolve(
        catalogue: List<AppShortcut> = CATALOGUE,
        launchable: (String) -> Boolean,
        label: (String) -> String? = { null },
    ): List<LaunchableApp> = catalogue.mapNotNull { entry ->
        val packageName = entry.packages.firstOrNull(launchable) ?: return@mapNotNull null
        LaunchableApp(
            id = entry.id,
            label = label(packageName)?.takeIf { it.isNotBlank() } ?: entry.fallbackLabel,
            packageName = packageName,
        )
    }

    /** Every package the catalogue can ask about, which is what the manifest has to declare. */
    fun declaredPackages(): List<String> = CATALOGUE.flatMap { it.packages }
}
