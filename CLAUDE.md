# CLAUDE.md

Project-specific instructions for Claude Code sessions working in this repo.

## Every change ships itself: version bump + merge to `main`

This app updates itself (ADR-012): the running app on the TV/phone polls
`https://updates.idanck.com/shidur/latest.json` and offers to install a new build. CI
(`.github/workflows/android.yml`) is what publishes that manifest and its APK —
but only under two conditions, both required:

1. **`version.properties`' `versionCode` must be strictly greater** than whatever
   is already published. The "Publish the update manifest" step refuses to
   publish otherwise (and errors loudly if `versionCode` matches but the build
   content differs). Bump `versionCode` — and normally `versionPatch`/
   `versionMinor` too — on every change meant to reach a real device, not just
   on request.
2. **The build must land on `main`.** The publish step only runs `if:
   github.ref == 'refs/heads/main' && github.event_name == 'push'`, and only for
   a signed build. Landing on a feature branch, or merely opening a PR, does not
   publish anything — the branch has to actually be merged into `main` and that
   push's CI run has to go green.

**Standing authorization:** once a change is implemented, validated as well as
this environment allows, and its branch pushed, merge it to `main` after CI is
green so the update actually publishes — without asking first each time. This
is the one exception to the general "confirm before merging to a shared branch"
rule, scoped specifically to landing a finished, validated change on `main` so
this app's self-update mechanism can pick it up; it does not extend to other
destructive or hard-to-reverse actions.

## Two packages: the app and the bridge (ADR-060)

The app is `com.idanck.shidur` since 2026-11-09; until then it was `com.tivicopy.player`. Installs
of the old package move through **the bridge**: the same code built with `-Pshidur.bridge=true`,
which keeps the old package, carries `bridgeVersionCode` from `version.properties`, offers the new
app and hands it the data through a signature-guarded provider (`data/handover/`).

- `latest.json` belongs to the bridge for good, so an old install that wakes up months later still
  finds its way. Only `.github/workflows/bridge.yml` writes it, by hand, on `main`, with `publish`
  on, after the new app is out at `shidur/latest.json`. `android.yml` never writes `latest.json`.
- An app change bumps `versionCode` as below; `bridgeVersionCode` is raised only to publish a fixed
  bridge.
- A change to the user's tables, to `Settings`' keys or to what a backup carries is also a change to
  what the hand-over moves: check `HandoverSnapshot.TABLES`, `importable()` and ADR-060's tables.

## One APK and one QA run per batch of changes, at the end

The owner's rule (2026-10-16): a series of related changes publishes **one** update and gets
**one** emulator QA run, after its last change has landed on `main` — not one of each per PR.

- **APK.** CI on a feature branch runs the unit tests and lint only (`SHIPS` in
  `.github/workflows/android.yml`); a release APK is assembled, signed and kept only on `main`,
  or on a run started by hand. When a batch lands as several PRs, merge them back to back and
  cancel any earlier `main` run of **Android build** before it reaches "Publish the update
  manifest", so only the last merge publishes. Every PR still bumps `versionCode` as above — a
  `main` build that does reach the publish step with an already-published code fails loudly.
- **QA.** `.github/workflows/qa.yml` runs on pushes to `main` that touch `qa/`, and by hand. Run
  it once, on `main`, after the batch's last merge, dispatching it by hand if that merge changed
  nothing under `qa/`.

A `main` run that sits in `queued` with no jobs and refuses both cancel ("completed" / "not
queued yet") and re-run ("already running") is stuck on GitHub's side (it happened to 0.33.0's
run, 2026-11-11). A re-run cannot replace it, and an empty commit is skipped by `paths-ignore`:
land a small change outside `web/` and `webos/` (a note like this one) to start a fresh `push` run.

## This session's environment cannot build the app

The Claude Code on the web / Claude Code Remote sandbox this repo is usually
edited in has its network egress locked down to an allowlist that does **not**
include `dl.google.com` — the host the Android Gradle Plugin and AndroidX
artifacts resolve from. `./gradlew` (build, test, lint) fails there with a
plugin-resolution error, not a code problem. Do not try to route around this
(no manual jar downloads, no disabling the repository check, no retrying) —
it is an organization egress policy, not a misconfiguration.

Practical consequence: a session in that environment cannot compile or run
tests locally before pushing. Review Kotlin/Compose changes extra carefully by
reading them back in full (brace balance, imports, exhaustive `when`s, correct
use of `@Composable` contexts — especially inside `LazyColumn`/`LazyRow`
content blocks, whose own lambda type is not `@Composable`, only the `item{}`/
`items{}` builders' content is) before pushing, then let GitHub Actions CI be
the actual build/test/lint gate. Do not claim a change "builds" or "passes
tests" from that environment without having actually run them somewhere.

## Two-shell architecture

`MainActivity` picks one of two Compose UI shells at runtime based on
`isTelevision`: `ui/tv/TvRoot.kt` (Android TV, D-pad, overlay-based navigation
over always-playing fullscreen video) and `ui/mobile/MobileRoot.kt` (phone,
touch, bottom navigation bar + a full-screen player). Both share one
`AppViewModel`/`ChannelListState`, including for the bundled Israeli radio
playlist (ADR-015) — Radio is not a separate screen implementation, it repoints
the same `ChannelListScreen` at a different playlist. A fix or feature that
touches shared state (`AppViewModel`, `ChannelListScreen`) usually needs to be
checked against both shells, not just the one you're looking at.

## The web version (`web/`) ships separately

`web/` is Shidur for the computer (ADR-044): a Preact/TypeScript PWA and a small Node server that
proxies providers. The server's one dependency is `firebase-admin`, for its Google sign-in mode
(ADR-059, stage 5); `package.json`'s `dependencies` are the server's alone, and the browser's
libraries are devDependencies, bundled by Vite. Its CI is `.github/workflows/web.yml` (typecheck, `vitest`, build,
Docker image) and it runs fine in the cloud sandbox — `cd web && npm ci && npm run check` works
there, unlike Gradle. `android.yml` ignores `web/**`, so a web-only change builds and publishes no
APK and needs no `versionCode` bump; a change that also touches the app still follows the rules
above. Its parsers port the Android ones with the same fixtures: a rule changed in
`app/…/data/m3u`, `xmltv`, `epg`, `core/TextNormalizer` or the classifiers should be mirrored in
`web/src/data/`.

`radio.m3u` (station list, `group-title`s, names) is generated by
`build_radio_m3u.py` on the Pi and published to `il-epg.idanck.com/radio.m3u`
(see ADR-015's Consequences) — outside this repo and, in the Claude Code on the
web sandbox, outside reachable network egress too. Client-side radio
categorization (`data/radio/RadioCategoryClassifier.kt`) is a heuristic over
whatever the feed currently contains, not the source of truth — extend its
keyword lists as real station names turn out to need it, but the authoritative
fix for what stations exist, which category they should be in, or removing a
station outright belongs in the upstream script, not here.

## Accounts: `firebase/` deploys separately (ADR-059)

The app signs in with Google on Firebase. `firebase/` holds the Firestore rules, the Cloud
Functions and the Hosting pages; its CI is `.github/workflows/firebase.yml`, which `android.yml`
ignores, and `cd firebase && npm ci && npm --prefix functions ci && npm run check` runs in the
cloud sandbox, and so does `npm run emulated` (rules and the whole pairing against the emulators).
Nothing about the Firebase project is in the repository: the app's `google-services.json` is the
`FIREBASE_ANDROID_CONFIG` secret, read by `app/build.gradle.kts` without the Google Services
plugin, and a build without it has no accounts and must still start. It needs a client for
`com.idanck.shidur` (the app) and keeps the one for `com.tivicopy.player` (the bridge, ADR-060).
The sign-in screen is behind
the remote switch `config/app.requireSignIn`, which the owner turns on himself — never ship a
change that makes it default to on. The Functions' region is written in four places
(`firebase/README.md`).

## The webOS version (`webos/`) ships separately too

`webos/` is Shidur for LG televisions from webOS 5 (2020, Chromium 68), ADR-057. Step 1 (the spike)
ran on the owner's television on 2026-11-07, and he chose: laid out natively at 1920×1080 with no
CSS scaling (1rem = 24 px; a computer-version size is px ÷ 16 rem), the video in a box with full
screen on OK, Sport and News mode with a fallback to one video. The spike was removed on 2026-11-08
(`git show 0080724:webos/spike/…` brings it back). Step 2, the app, is built in small steps, each
run on the television before the next: 0.1.0 is the channel list and one channel playing in its
box (on the television 2026-11-07), 0.2.0 adds the guide (its grid keeps two offsets of its own,
`webos/src/ui/grid.ts`, `translateY` for rows and `translateX` for hours), 0.3.0 the radio and full
screen by itself after 25 minutes without a key (`webos/src/ui/idle.ts`, with LG's
`screenSaverProperties.preferredType: 2` in `appinfo.json`), 0.4.0 a shell that updates itself
(`webos/shell/boot.js`; ADR-057 *As built*; on the television since 2026-11-11, where it took 0.4.1 by itself). The
shell's files are pinned by `webos/test/shell-guard.test.ts`: changing them means raising `SHELL`
in `webos/scripts/shell-plugin.ts`. Bundles are signed and published only from the Pi
(`webos/tools/publish-bundle.sh`); nothing in CI holds the key. Its CI is `.github/workflows/webos.yml`; `cd webos && npm ci
&& npm run check && npm run package` runs in the cloud sandbox. `android.yml` ignores `webos/**` and
`.github/workflows/webos.yml` — do not edit `android.yml` for a webOS change, since that builds an
APK. A webOS change that also touches `docs/` or `CLAUDE.md` does start an Android build on `main`,
which fails at "Publish the update manifest" (same versionCode, different APK): cancel that run
before it gets there rather than bump `versionCode` for no app change. The version is its own, in
`webos/appinfo/appinfo.json`, not `version.properties`.

- Everything shipped to the television is ES2019 syntax with no API newer than Chromium 68, the JS
  service ES2017 for Node 8.12 (`npm run check:es`), and the stylesheet holds no CSS newer than 68
  (`npm run check:css`). An API Chromium 68 lacks goes into `webos/public/polyfills.js` through its
  `define()`, which loads first in the page and in the guide worker. Inline styles are not lowered:
  no `insetInlineStart`, `marginInlineStart` and the like.
- Lists keep their own offset and move with `translateY` (`webos/src/ui/list.ts`); never make a
  list depend on `scroll` events or the browser's scroll position.
- The parsers in `webos/src/data/` (`m3u`, `xmltv`, `xtream`, `normalize`, `matcher`, `classify`,
  `guide`, `model`, `builtin`, `backup`) are `web/src/data/`'s byte for byte
  (`webos/test/parity.test.ts`): a rule changed there is copied here in the same change.
- A desktop browser run proves nothing about webOS 5. Never claim a change works on the
  television until the owner has confirmed it there; when he reports a result, say what it proves
  and what it does not.
- The television is reached from the owner's phone (Termux, `@webos-tools/cli` installed with
  `npm ci --force`; `ares-package` in the foreground with `< /dev/null`). Give him one command at a
  time and say what to look at on the screen. `tools/cdp.mjs state` prints what the app says about
  itself (`window.shidur.state()`). Its address is not written in the repo.
