# ADR-021 — Motion, and why the interface was not smooth

**Status:** accepted

## Context

The owner's report was that the visual and transition effects "don't work, and it isn't smooth".
Both halves turned out to be literally true, for two unrelated reasons. Both were measured on the
Google TV Streamer the app actually runs on, over ADB, on the shipped 0.8.0 build.

### 1. There were no effects. At all.

Not one call to `animate*AsState`, `AnimatedVisibility`, `Crossfade`, `updateTransition` or any
other animation API existed anywhere in the app — zero files even imported
`androidx.compose.animation`. Every focus ring, every rail that opened, every overlay that
replaced another was a cut between two frames.

`dumpsys gfxinfo` says the same thing in numbers: sixteen presses of the D-pad on the home screen
produced **64 rendered frames** — three or four per press, which is a redraw, not an animation. An
interface with motion draws fifteen to twenty frames for the same press. Sitting still, the app
renders **zero** frames in ten seconds, so nothing was animating in the background either.

The device was not the cause: `window_animation_scale` and `transition_animation_scale` are 1.0
and `animator_duration_scale` is unset (so, the 1.0 default) — Compose's `MotionDurationScale`
had nothing to suppress.

### 2. The frames it did draw were expensive, because none of the code was compiled.

Minutes after a self-update, `dumpsys package dexopt` reports this package at
`status=verify reason=install`: nothing is compiled ahead of time, and every composable runs
interpreted until the JIT catches up. The same key sequence, same build, same screen, before and
after forcing `cmd package compile -m speed`:

| | as installed (`verify`) | AOT-compiled (`speed`) |
|---|---|---|
| Janky frames | 12.8% (legacy 29.7%) | **3.9%** (legacy 2.4%) |
| 50th percentile frame | 17 ms | 15 ms |
| 90th percentile | 53 ms | **17 ms** |
| 95th percentile | 150 ms | **21 ms** |
| 99th percentile | 200 ms | 36 ms |
| Missed vsyncs | 11 | **0** |

A 150 ms frame is a visible stutter. This is not a bug the app shipped — it is the ordinary state
of any sideloaded APK before Android's background dexopt has run — but this app installs its own
updates (ADR-012), so it re-enters that state on every release and spends the rest of the day
there. Worse, the profile ART would compile *from* contained no code of ours: the APK's
`assets/dexopt/baseline.prof` was 11 KB of rules merged in from the AndroidX libraries, and not one
line of it named a composable in this app.

## Decision

**1. One motion vocabulary, in `ui/common/Motion.kt`, applied everywhere focus or a surface
changes.**

- Focus *lifts*: the focused card, chip, rail item, guide cell or button grows by 4–6% on a
  spring and is drawn over what it overlaps. A spring rather than a duration because D-pad focus
  moves in bursts, and a spring interrupted mid-flight carries its velocity into the next tile.
- Colours *cross* rather than cut, over 120 ms — including in `FocusableRow`, which every list in
  the app is built from, so this one change is most of what focus now feels like.
- A surface *arrives*: fades in over 260 ms with a 12 dp rise. The navigation rail's width and
  its labels animate as it opens; the banner's title cross-fades when the programme changes.

**2. Animations are drawn, not recomposed.**

Scale and alpha are read inside `graphicsLayer`'s lambda, which is evaluated in the draw phase:
the frames of a focus lift redraw one node and recompose nothing. That is a deliberate constraint
of the design, not an optimisation applied afterwards — it is what makes motion affordable in the
interpreted window described above, and it keeps a card's growth from re-measuring the row it is
in (so its neighbours do not shuffle sideways as focus passes).

**3. Nothing loops.** With the screen idle the app still renders zero frames. No pulsing, no
shimmer, no idle carousel.

**4. The app's own code goes into the baseline profile.**

`app/src/main/baseline-prof.txt` adds whole-package rules for `ui`, `core`, `data` and `player`,
and `androidx.profileinstaller` is named explicitly in `build.gradle.kts` rather than left to
arrive transitively with Compose. ART now has this app's screens in the profile it compiles from
at its next background pass, where before it had only AndroidX's.

Whole packages rather than a generated method list: generating one properly needs a macrobenchmark
run against a real device, and CI has no device attached (`CLAUDE.md`). Over-compiling costs a
larger `.odex` and a slower dexopt pass, and under-compiles nothing.

## What this does not fix

The profile is installed on first launch and compiled at ART's *next background dexopt pass* —
typically overnight. The window right after a self-update, which is exactly when the owner looks
at a new build, is still interpreted. Nothing in the app can force compilation; `cmd package
compile` is a shell command.

## The dex-metadata (`.dm`) route was tried, on the real hardware, and does not work

This record first proposed handing the platform a **dex metadata (`.dm`) file** alongside the
APK during a self-update, so install-time dexopt would be profile-guided the way a Play install
is. That was built as far as a manual `PackageInstaller` session against the Google TV Streamer
and stopped there, because the platform itself refuses the one artifact this repository can
actually produce.

**What was tried.** `assets/dexopt/baseline.prof` — the profile already embedded in the APK for
`profileinstaller` (this ADR's own fix, above) — was extracted, renamed to `primary.prof` and
`primary.profm`, zipped into a `.dm`, and staged in a real `PackageInstaller` session alongside
`base.apk` (`pm install-create` / `install-write base.apk` / `install-write base.dm` /
`install-commit`; a session entry must literally end in `.apk`/`.dm` for the platform to pair
them — `tivicopy-update`, the name `UpdateManager` uses today, would not pair with anything,
though it still installs fine as the sole entry in a single-APK session). The commit succeeded and
`dumpsys package dexopt` reported `reason=install-dm` — the platform saw the file. But the
compiled result stayed `status=verify`, and `logcat` explains why:

```
profman : Error when reading profile: Profile version mismatch.
artd    : Merge skipped because there are no existing profiles
ArtService: … actualCompilerFilter=verify … extendedStatusFlags=[EXTENDED_BAD_EXTERNAL_PROFILE]
```

**Why.** `baseline.prof`'s header is `pro\0010\0` — a real, versioned ART profile format, just not
the version this device's `profman` (Android 14, API 34) reads. `profileinstaller` gets away with
the same bytes because it is a Kotlin re-implementation that decodes several known profile-format
versions itself and re-encodes whatever the running device wants before writing to
`/data/misc/profiles/cur/…` — a conversion step that happens **in the app's own process, at
launch**, which is exactly why the earlier measurement in this record already shows the good
numbers: that 6.8%-janky, 21–27 ms result was taken *after* the app had been opened once, letting
`profileinstaller` do that conversion, and *then* forcing a `speed-profile` compile. The
platform's native `install-dm` path has no equivalent conversion — `profman` expects one exact
on-disk version and rejects anything else outright, which is a difference between "the profile
went in through the app's own code" and "the profile went in through the installer," not a
difference in the compile itself.

That version is tied to the ART Mainline module, which updates independently of the Android OS
version on modern devices — there is no `minSdk`-shaped choice that is safe across this app's
fleet, only a value that happens to match whichever ART build a given device is running today.
Getting it wrong is not dangerous — the attempt above shows the platform just falls back to
`verify` and moves on, which is the status quo — but it is not a reliable win either, and there is
no way for `UpdateManager` to know in advance which version a given device wants or to verify
after the fact that the `.dm` did anything.

**Decision: not pursued.** The remaining gap — the day between a self-update and the OS's own
background dexopt pass — is a real, disclosed limitation, but closing it this way means adding
real code to the single most safety-critical path in the app (the self-updater) for a benefit
that, on the one real device this could be checked against, did not happen. The lower-risk
version of "close the gap" is a launch-time nudge: `profileinstaller` already runs on the first
launch after every update, so the actual remaining window is "one launch, plus however long the
OS's own idle-and-charging maintenance window takes to notice" — the same window every
sideloaded Android app lives with, not one this app introduced.

## Alternatives rejected

- **Cross-fading one overlay into another** (`AnimatedContent` over `TvOverlay`). It is the
  obvious way to get a screen transition, and it composes both screens at once: two channel lists
  querying, two guides fetching, and two screens calling `requestFocus` in the same frame. The
  entrance animation gives most of the effect for none of that.
- **Animating the rail's width from the animation's `State` in composition.** It re-measures seven
  rows per frame; read where it is read now it costs the same but only while the rail is opening.
  The rail is the one animation in the shell that re-measures at all, and it is bounded.
- **Turning on R8 (`isMinifyEnabled`)** in the same change. It would shrink the dex and is worth
  doing, but it changes what runs — reflection, Media3, the JSON parsers — and this change is
  already broad. Separate change, separate verification.
- **Lowering the animation to "focus ring only"** to be safe on old hardware. The floor is API 23
  but the deployment is a Google TV Streamer and a Chromecast; both draw a 1080p Compose frame in
  4–9 ms of GPU time once the code is compiled. The cost here is not the effects.
