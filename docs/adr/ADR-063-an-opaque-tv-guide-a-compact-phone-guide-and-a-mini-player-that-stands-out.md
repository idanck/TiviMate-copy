# ADR-063 — An opaque television guide, a compact phone guide, and a mini player that stands out

**Status:** accepted 2026-11-10, built in 0.29.0. Amends ADR-051 (the television's guide no longer
shows the picture) and ADR-020 (on the phone the strip of days folds into the chosen day's chip).

## Context

The owner raised three things on 2026-11-10, from a screenshot of the phone's guide:

1. **The phone's guide had lost its purpose.** Above the first row sat the top bar, a row with the
   date, the window's moves and the clock, the strip of days and the hour ruler; under the last row
   the mini player and the tabs. The channel column took 190dp of a ~410dp screen. What was left
   showed about seven rows of an hour and a half, the titles cut short.
2. **The television's guide should be opaque.** Since ADR-051 the channel plays on full size
   behind the guide, showing through a band at the top dimmed by 30%, with the grid on the dark
   ground at the overlay opacity. He wants no picture under the guide; the sound of the channel
   playing goes on.
3. **The phone's mini player was hard to see** on every screen but the player's: thick glass like
   the tabs under it and like the rows behind it, it read as one more row.

Design proposals were drawn on a Claude Design canvas ("שידור — מדריך ונגן רדיו": options 1א/1ב,
2 with three ways to mark what is heard, 3א/3ב/3ג), and he chose.

## Decision

1. **Phone guide — the compact grid (1א).** One row over the grid: the chosen day as an amber chip
   ("היום 10/11 ▾") that opens the strip of days under it and folds it away once a day is picked,
   then −2h, now and +2h. The clock is dropped from the row (the phone's status bar has it). The
   grid's metrics are overridden for this screen alone (`GuideScreen`): a 64dp channel column that
   stacks the number over a two-line name, 56dp rows and a 176dp hour, so about two hours of a dozen
   channels fit. `TvMetrics.Phone` is left as it was, because the television's metrics are scaled
   from it.
2. **Television guide — opaque, and two marks for what is heard ("both").** The guide paints the
   whole panel with the ground; the band, its 30% dim and the top bar's scrim over it are gone.
   The picture keeps playing behind, unseen, so its sound goes on and nothing is re-tuned when the
   guide closes. What is heard is said twice:
   - a pill centred in the top bar (`HeardPill`): an amber equaliser that moves with the sound,
     "ממשיך להתנגן", the channel and its programme;
   - the channel's own row in the grid, its cell lit with Merhav's "current" (amber glass, amber
     edge) and the equaliser where the tally dot was.
   The overlay-opacity setting (FR-UI-18) no longer applies to the guide; it still governs the
   other overlays.
3. **Phone mini player — the amber edge (3ב).** Opaque (amber glass over the drawer's dark), a
   1.5dp amber edge at 70%, a 10dp shadow, and a little more room above the tabs. The channel line
   becomes "מתנגן עכשיו · <channel>" in amber, after an amber equaliser. The equaliser is composed
   only while the app is at least started, so `AudioSpectrum` measures nothing under a locked screen;
   then the live dot stands in for it.

`Equalizer` takes a colour (default: Studio's light bars).

## Alternatives rejected

- **1ב, a "now and next" list as the phone guide's default view.** The most legible on a phone,
  but no view across the day, fewer rows per screen, and a second screen to keep.
- **Only the pill, or only the lit row, on the television.** The pill alone says nothing in the
  grid; the row alone scrolls out of view.
- **3א, a light mini player.** The most visible, but foreign to the dark design and glaring at
  night.
- **3ג, one dock tinted with the station's colour.** Needs a colour taken from each logo, and a
  logo close to the ground's colour would stand out no better than before.
- **Pausing the video behind the opaque guide.** It would stop the sound, which the owner wants
  kept.

## Consequences

- The television decodes a picture nobody sees while the guide is open, as it did under the
  dimmed band; nothing changes in playback.
- On a phone the strip of days is one tap further away; reaching a day is a tap on the chip and a
  tap on the day.
- The design canvas in `docs/design/studio/canvas/PMiniPlayer.dc.html` shows the new mini player.
