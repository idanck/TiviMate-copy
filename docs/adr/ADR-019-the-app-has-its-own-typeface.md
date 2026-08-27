# ADR-019 — The app has its own typeface (and keeps its own background)

**Status:** accepted

## Context

A teardown of **Cellcom TV 112.0.16** on a Google TV Streamer — screenshots of every reachable
screen plus `apktool` over the APK — was done to see what a shipping Israeli television app does
that this one does not. It found a full-screen hero banner with a bottom gradient, a collapsed
icon rail down the inner edge, an EPG grid with a day picker and an enlarged focused cell, a strip
of streaming-app shortcuts, 2:3 poster cards and a bright focus ring with no fixed accent colour.
Those are layout, and ADR-020 carries them.

One finding was not layout at all, and is this record. Cellcom TV does not use the system
font. Its APK carries six weights of a brand family, `rag_sans_cellcomtv_2022_*.ttf` — ExtraLight
through Black — and every screen is set in it.

That is the whole difference in feel, and it is invisible in a screenshot until you look for it.
This app had no `FontFamily` anywhere: every `Text` fell through to the platform default, which on
Android TV means Roboto for Latin and whatever the device's Hebrew fallback happens to be. Two
faces by two designers in one line of text, chosen by neither of us.

## Decision

**1. The app bundles a typeface: Rubik.**

*Rag Sans CellcomTV 2022* is a commissioned brand face and is not for sale to us, and buying a
Hebrew family was ruled out. Rubik is the substitute: a geometric Hebrew/Latin sans by Hubert &
Fischer and Meir Sadan, in the same genre as Rag Sans, published under the SIL Open Font License
1.1 — the one free Hebrew UI family with a working weight range and Latin drawn by the same hand
as the Hebrew.

Four weights are bundled, not six: Regular 400, Medium 500, SemiBold 600, Bold 700. The type ramp
in `ui/theme/Type.kt` sets Bold for what is read first across a room (hero, section headings),
Medium for interface furniture (rail labels, tile titles, clocks), Regular for sentences. Cellcom
TV's Light and ExtraLight are deliberately not carried over: it draws them over its own controlled
artwork, whereas this app's small text sits over live video, where thin strokes lose NFR-UX-03.

The weights are **static instances**, cut from Rubik's `wght` variable font with `fontTools` and
subset to Latin + Hebrew + punctuation (~93 KB each; ~370 KB in the APK). Not the variable file
itself: Compose only applies `FontVariation` on API 26 and up, and this app's floor is API 23
(ADR-001), where a variable file renders every weight at its axis default — Light. Failing to
Light on a ten-foot display is the worst available failure, so the risk is removed at build time
rather than depended on at runtime.

Rubik has no glyph for everything the app can be asked to draw (▶ in a playback label, an emoji
in a channel name). Those still fall back per character to the platform font, as they always did.

**2. The colour ramp does not change. The background stays blue-grey.**

The teardown sampled Cellcom TV's base surface at roughly `#0A0A0D` — near-black, neutral. This
app's dark scheme is a blue-grey family based on `#1B212B`. That difference is now a decision
rather than an accident: the owner prefers the blue background to the benchmark's black, and the
AMOLED theme already exists for anyone who wants the black. Adopting the benchmark's typography
was not a reason to adopt its palette.

## Alternatives rejected

- **Licensing Rag Sans, or Ploni, or another commercial Hebrew family.** Ruled out on cost by the
  owner. The report suggested it; this is the answer.
- **Downloadable Fonts (the Google Fonts provider).** Zero APK weight, but it makes the app's
  basic legibility depend on Play Services being present and on a network fetch completing on a
  device that may be on a slow line — and the first frame after a cold start is exactly when it
  would not have resolved yet.
- **Shipping the variable font and using `FontVariation`.** See above: silently wrong below API 26.
- **Heebo or Assistant instead of Rubik.** Both are free and both are good; Heebo is a Hebrew
  companion to Roboto (i.e. more of what the app already had), and Assistant is humanist rather
  than geometric. Rubik is the closest free thing to what the benchmark actually uses.
- ~~**Restyling the screens to match Cellcom TV more closely**~~ — struck. This record originally
  deferred the report's layout findings; the owner asked for all of them, and ADR-020 carries
  them: the full-bleed banner, the collapsing rail, the guide's day strip and the app shortcuts.
  What is still deliberately *not* copied is listed there.

## Consequences

- Every screen in both shells changes appearance at once, because everything reads the ramp from
  `MaterialTheme.typography`. There is no per-screen opt-out and no second font in the app.
- The APK grows by roughly 370 KB. Each self-update (ADR-012) carries that once.
- The OFL requires the licence to travel with the font, so it ships at
  `assets/licenses/rubik-ofl.txt` (and is kept in the repository at `docs/licenses/Rubik-OFL.txt`),
  with a credit line in Settings → About.
- Regenerating the weights, if Rubik is ever updated upstream: instance `ofl/rubik/Rubik[wght].ttf`
  from `google/fonts` at 400/500/600/700 with `fontTools.varLib.instancer`, subset to Latin +
  Hebrew + punctuation, and drop the results into `res/font/` under the same names.
- Line heights were left where they were. Rubik's vertical metrics are close enough to the
  previous default that the existing ramp still clears descenders at every font scale; if Hebrew
  turns out to clip in a tight single-line row on a real panel, the fix is in one function.
