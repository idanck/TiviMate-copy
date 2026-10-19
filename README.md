# Shidur (שידור) — IPTV Player for Android & Google TV

An **independent IPTV player application** for Android phones/tablets and Android TV /
Google TV, together with the requirements it was built from.

TiviMate is used here only as a **functional benchmark**: the docs describe *what an IPTV
player of this class must do*, restated in original wording so they can be implemented
from scratch. No TiviMate source code, binaries, icons, artwork, layouts, strings or
branding are reproduced or reused in this repository or in the resulting app.

## Scope

| | |
|---|---|
| Target platforms | Android TV / Google TV (10-foot, D-pad), Android phones & tablets (touch), Fire TV (sideload) |
| Minimum Android | 6.0 (API 23) — TV variant may raise to 7.0 (API 24) |
| App type | **Player only.** The app ships no channels, no streams, no VOD. All content comes from a playlist the user supplies. |
| Primary input | M3U / M3U8, Xtream Codes API, Stalker / portal |
| Guide data | XMLTV, provider EPG (Xtream), embedded M3U EPG |

## Repository layout

```
app/          The Android application (Kotlin, Jetpack Compose, Media3)
web/          Shidur for the computer: an installable web app and its proxy server (ADR-044)
docs/         Functional analysis, split by area, plus ADRs
requirements/ Numbered, testable requirements (FR / NFR) + roadmap
```

| File | Contents |
|---|---|
| `docs/00-scope-and-platforms.md` | Product definition, device matrix, form-factor split |
| `docs/01-sources-playlists-epg.md` | Playlists, Xtream/Stalker, EPG sources, matching, offsets |
| `docs/02-playback.md` | Engine, decoders, protocols, tracks, subtitles, buffering, multi-view |
| `docs/03-ui-navigation.md` | 10-foot UI, guide layouts, remote mapping, touch UI, profiles, parental |
| `docs/04-recording-catchup.md` | DVR, scheduled/series recording, storage, catch-up/archive |
| `docs/05-settings-advanced.md` | User-agent, headers, external player, backup/restore, startup, diagnostics |
| `docs/06-licensing-and-tiers.md` | Free vs paid gating, device-count licensing, activation flows |
| `docs/07-architecture-android-googletv.md` | Leanback manifest, Media3, TIF/Watch Next, DRM, PiP, storage, policy |
| `docs/08-gaps-and-differentiators.md` | Known weaknesses of the benchmark → our differentiators |
| `docs/09-sources-and-confidence.md` | Where the analysis came from + confidence levels |
| `requirements/functional-requirements.md` | FR-* — numbered, testable, prioritized |
| `requirements/non-functional-requirements.md` | NFR-* — performance, reliability, compatibility, privacy |
| `docs/10-implementation-status.md` | **What is actually implemented**, requirement by requirement |
| `docs/adr/` | Architecture decision records |
| `requirements/roadmap.md` | Phased delivery plan (MVP → parity → differentiation) |

## Requirement conventions

- IDs: `FR-<AREA>-<nn>` / `NFR-<AREA>-<nn>`. IDs are **never reused or renumbered**.
- Priority: **MUST** (MVP), **SHOULD** (parity), **COULD** (differentiator), **WON'T** (out of scope for v1).
- Tier: `Free` / `Paid` — which entitlement level exposes the feature.
- Every FR must be phrased so it can be turned into an acceptance test.

## Legal position

- The app is a media player. It does not host, index, resell or bundle any channel,
  stream, or subscription — except one fixed built-in playlist of Israel's free-to-air
  public channels (see `docs/00-scope-and-platforms.md` §0.1), included only because this
  is personal software that is never distributed through a store.
- Store listing and in-app copy must contain no reference to obtaining content, and no
  provider names, to stay inside Google Play policy for streaming apps, should this ever
  be distributed through one.
- Features and behaviours are not protected expression; **code, assets and layouts are**.
  Everything in this project is authored independently: no TiviMate source, binary, icon,
  layout, string or artwork is reproduced or reused.

## Building

The project needs nothing but the Gradle wrapper and an Android SDK — there is no annotation
processing and no code generation step.

```sh
./gradlew testReleaseUnitTest    # parser, time-model and matcher tests
./gradlew assembleRelease        # APK in app/build/outputs/apk/release/
```

Without a release keystore the release build is signed with the standard debug key, so the
artefact installs directly for personal use. To sign it properly, set `RELEASE_KEYSTORE_PATH`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` in the environment.

CI (`.github/workflows/android.yml`) runs the tests and assembles the APK on every push, and
uploads it as a build artefact.

### The web version

`web/` is Shidur for a computer, an installable web app with the small server it needs to reach
providers. How to run it, with or without Docker: `web/README.md`.

## Versioning

`version.properties` at the repository root is the single source of truth:

```properties
versionMajor=0
versionMinor=1
versionPatch=0
versionCode=1
```

Bump `versionCode` on every build that leaves the machine. The version name, version code,
build type, git SHA and build timestamp are shown in **Settings → About** and in
**Settings → Diagnostics**, and are embedded in the APK filename
(`shidur-<version>-<buildType>-<sha>.apk`), so two installs can always be told apart.

## Architecture

One adaptive application module (`docs/adr/ADR-002`) that picks a UI shell at runtime:

```
core/     Errors, UTC time model, name normalisation — no Android UI types
data/     M3U, Xtream, XMLTV parsers · SQLite store · repositories · settings · backup
player/   Media3 wrapper: decoder selection, buffering, failover, diagnostics
record/   Foreground-service DVR: exact alarms, boot re-arm, MPEG-TS writer
ui/       Compose: shared screens, a TV shell and a touch shell
```

## Status

Phase 1 (MVP) plus the DVR core from Phase 2. See `docs/10-implementation-status.md` for the
requirement-by-requirement position, including what is deliberately not implemented.

Entitlements, billing and device licensing (`FR-LIC-*`) are **not** implemented — this build
is for personal use and every feature is simply available. See `docs/adr/ADR-007`.
