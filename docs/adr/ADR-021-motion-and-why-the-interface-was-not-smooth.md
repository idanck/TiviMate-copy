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

## What this does not fix, and the option left open

The profile is installed on first launch and compiled at ART's *next background dexopt pass* —
typically overnight. The window right after a self-update, which is exactly when the owner looks
at a new build, is still interpreted. Nothing in the app can force compilation; `cmd package
compile` is a shell command.

The real fix for that window is to hand the platform a **dex metadata (`.dm`) file** alongside the
APK when the app installs its own update, so install-time dexopt is profile-guided the way a Play
install is. It means CI packaging the profile as a `.dm`, the update manifest carrying it, and
`UpdateManager` writing it into the `PackageInstaller` session. That is a change to the one
mechanism that keeps this app updatable at all, so it is recorded here as the next step rather
than bundled into a change about animation.

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
