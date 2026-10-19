# CLAUDE.md

Project-specific instructions for Claude Code sessions working in this repo.

## Every change ships itself: version bump + merge to `main`

This app updates itself (ADR-012): the running app on the TV/phone polls
`https://updates.idanck.com/latest.json` and offers to install a new build. CI
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

`web/` is Shidur for the computer (ADR-044): a Preact/TypeScript PWA and a dependency-free Node
server that proxies providers. Its CI is `.github/workflows/web.yml` (typecheck, `vitest`, build,
Docker image) and it runs fine in the cloud sandbox — `cd web && npm ci && npm run check` works
there, unlike Gradle. `android.yml` ignores `web/**`, so a web-only change builds and publishes no
APK and needs no `versionCode` bump; a change that also touches the app still follows the rules
above. Its parsers port the Android ones with the same fixtures: a rule changed in
`app/…/data/m3u`, `xmltv`, `epg`, `core/TextNormalizer` or the classifiers should be mirrored in
`web/src/data/`.

## Bundled Israeli radio: station data lives outside this repo

`radio.m3u` (station list, `group-title`s, names) is generated by
`build_radio_m3u.py` on the Pi and published to `il-epg.idanck.com/radio.m3u`
(see ADR-015's Consequences) — outside this repo and, in the Claude Code on the
web sandbox, outside reachable network egress too. Client-side radio
categorization (`data/radio/RadioCategoryClassifier.kt`) is a heuristic over
whatever the feed currently contains, not the source of truth — extend its
keyword lists as real station names turn out to need it, but the authoritative
fix for what stations exist, which category they should be in, or removing a
station outright belongs in the upstream script, not here.
