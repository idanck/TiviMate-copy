# Non-Functional Requirements

ID scheme `NFR-<AREA>-<nn>`. All values are targets to be verified on the reference
device set (see `docs/09` verification backlog), not aspirations.

## PERF — Performance

| ID | Requirement |
|---|---|
| NFR-PERF-01 | Cold start to first rendered video frame on the resume channel: ≤ 4 s on a mid-range Android TV box, ≤ 2.5 s on a current phone. |
| NFR-PERF-02 | Channel change (key press → first rendered frame) ≤ 1.5 s on a wired gigabit connection with the Small buffer preset. |
| NFR-PERF-03 | Guide scrolling holds ≥ 55 fps average with 500 channels × 7 days loaded. |
| NFR-PERF-04 | Opening the channel-list overlay does not interrupt or stutter the playing stream. |
| NFR-PERF-05 | A full XMLTV parse of a 200 MB uncompressed guide completes without exceeding 200 MB of app heap and reports progress throughout. |
| NFR-PERF-06 | Steady-state memory during single-stream playback ≤ 350 MB on TV devices. |
| NFR-PERF-07 | Total on-disk cache (EPG + images) is capped at a configurable budget (default 500 MB) and evicted LRU; it never grows unbounded. |

## REL — Reliability

| ID | Requirement |
|---|---|
| NFR-REL-01 | Crash-free session rate ≥ 99.5% across the reference device set. |
| NFR-REL-02 | A scheduled recording that the platform permits (device on, app installed) succeeds ≥ 99% of the time in a 30-day soak test. |
| NFR-REL-03 | No user data loss on app kill, crash, forced update or storage removal during a recording; partial recordings remain playable. |
| NFR-REL-04 | A failed network operation never leaves the app in a state requiring reinstallation or re-adding a playlist. |
| NFR-REL-05 | Database migrations are versioned and tested; an upgrade never wipes playlists, favourites, mappings or recordings. |
| NFR-REL-06 | Every user-visible error states what failed and what the user can do, in plain language. Generic "an error occurred" is not acceptable. |

## COMPAT — Compatibility

| ID | Requirement |
|---|---|
| NFR-COMPAT-01 | Supported: Android 6.0+ (or 7.0+ if the PiP-driven ADR raises the floor), on Android TV, Google TV, Fire OS and phones/tablets. |
| NFR-COMPAT-02 | Reference device set for release sign-off: Chromecast with Google TV, Nvidia Shield, one Onn/Mi-class budget box, one Google TV television, Fire TV Stick 4K, one current phone, one ≥ 4-year-old phone. |
| NFR-COMPAT-03 | The TV build passes Google Play's Android TV quality review, including full D-pad operability and banner requirements. |
| NFR-COMPAT-04 | Screen support from 480p TV output up to 4K, and phone screens from 4.5" upward, without clipped or unreachable controls. |
| NFR-COMPAT-05 | The app functions correctly with a system display refresh rate the app did not set (AFR disabled or unsupported). |

## UX — Usability and Accessibility

| ID | Requirement |
|---|---|
| NFR-UX-01 | Every TV screen is reachable and operable using only D-pad, OK and Back. |
| NFR-UX-02 | Focus is always visible on TV, with a contrast ratio of at least 3:1 against the background. |
| NFR-UX-03 | Text and essential UI meet WCAG AA contrast (4.5:1 body, 3:1 large text). |
| NFR-UX-04 | Touch targets on mobile are at least 48×48 dp. |
| NFR-UX-05 | The app works with TalkBack: all controls have content descriptions and a sensible traversal order. |
| NFR-UX-06 | Full RTL layout support, verified in Hebrew, including the guide grid and time axis. |
| NFR-UX-07 | First-run to first playing channel takes no more than 3 screens for an Xtream user. |
| NFR-UX-08 | Localization: English and Hebrew at launch; all strings externalized, no hard-coded user-facing text. |

## SEC — Security and Privacy

| ID | Requirement |
|---|---|
| NFR-SEC-01 | Playlist credentials are encrypted at rest using Android Keystore-backed keys. |
| NFR-SEC-02 | Credentials are never written to logs, diagnostics, crash reports or analytics, and are redacted in any shared report. |
| NFR-SEC-03 | If cloud sync is enabled, credentials are encrypted client-side with a key the server never receives. |
| NFR-SEC-04 | No telemetry is collected by default; crash reporting is opt-in with a plain-language description of what is sent. |
| NFR-SEC-05 | The app requests only the permissions listed in `docs/07`; each is requested in context with an explanation. |
| NFR-SEC-06 | All network traffic to our own services uses TLS; user-supplied provider URLs may be plain HTTP but are flagged in the UI. |
| NFR-SEC-07 | Backup files containing credentials are encrypted with a user-supplied passphrase, or credentials are excluded. |

## MAINT — Maintainability

| ID | Requirement |
|---|---|
| NFR-MAINT-01 | Domain and data layers contain no Android UI dependencies, so a second platform is not a rewrite. |
| NFR-MAINT-02 | Parsers (M3U, XMLTV, Xtream, Stalker) are unit-tested against a corpus of real-world malformed samples with credentials stripped. |
| NFR-MAINT-03 | Every MUST requirement maps to at least one automated or documented manual test. |
| NFR-MAINT-04 | Releases are staged (internal → beta → phased production) so playback regressions are caught before wide rollout. |
| NFR-MAINT-05 | Architecture decisions that change a requirement are recorded as ADRs in `docs/adr/`. |
| NFR-MAINT-06 | No third-party dependency with an incompatible licence is introduced; a dependency licence report is generated per release. |

## LEGAL — Compliance

| ID | Requirement |
|---|---|
| NFR-LEGAL-01 | The app ships no channel list, no provider, and no mechanism to discover providers. |
| NFR-LEGAL-02 | Store listing, screenshots and in-app copy contain no provider names and no recognizable third-party channel branding. |
| NFR-LEGAL-03 | No code, asset, icon, layout, string or animation is derived from any competing product. All work is independently authored. |
| NFR-LEGAL-04 | A privacy policy and terms of use are published and linked from the app before first release. |
