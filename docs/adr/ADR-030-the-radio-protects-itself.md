# ADR-030 — The radio protects itself, and carries the task list

**Status:** accepted. Amends FR-UI-31 and FR-UI-32 (ADR-024), and FR-UI-30's "nothing animates
while the screen is idle", whose one exception now covers the radio screen as well as the
screensaver. **Reversed in part by ADR-040:** the radio's own screen is handed to the screensaver
like any screen that is not full-screen video, and the task list moved into the screensaver.

## Context

ADR-024 gave every still screen a screensaver after an idle period, and gave the radio an
*ambient* one of its own: the station's colours, dimmed, with a drifting pool of light — because
the radio's live screen was a still image (a gradient, a round logo, a name, a clock) and the
screen this app is most likely to be left on for hours. The task list (FR-UI-32) rode on that
ambient screensaver.

The Studio language (ADR-027) redraws the radio screen (`docs/design/studio/canvas/Radio.dc.html`)
on a background that never holds still: four dark tones on a 120-second cycle, a pool of light
travelling a path that never closes (47s and 76s periods), and a ±26° hue swing. That is already
what the ambient screensaver did, on the live screen itself. `MIGRATION.md` left open how the two
fit together. The owner chose:

## Decision

1. **The radio is never handed to the screensaver.** `screensaverApplies` is true only for a
   still screen (`IdleScene.STILL`); an audio-only scene protects itself with Studio's shifting
   background (`ui/common/AmbientBackdrop.kt`). Moving video is still never touched.
2. **The task list moves to the radio screen.** Once the viewer has been away for the
   screensaver's idle time — with the screensaver and its tasks switched on, as before — the radio
   screen shows the open tasks along its bottom edge, one at a time (`TaskRotation`), until a key
   is pressed. `radioTasksApply` and a timer in `MainActivity` decide when.
3. **The screensaver stays for every other still screen** — a paused film, an open guide, a menu
   left up — black with the drifting clock, as ADR-024 drew it, in Studio's typefaces.

## Alternatives rejected

- **Keep the ambient screensaver over the new radio screen.** Two moving backgrounds, one
  replacing the other after ten minutes, with the song, the station and the controls hidden for no
  gain in protection.
- **Drop the task list.** It was asked for (FR-UI-32), and the radio is where it is read.

## Consequences

- FR-UI-31 now reads: a still screen is replaced by a screensaver after the idle period; moving
  video and the radio are not, because neither holds still.
- FR-UI-32 now reads: the task list is shown on the radio screen after the idle period.
- The radio screen draws continuously while it is up, at twelve steps a second, through a
  `drawBehind` that recomposes nothing — the budget ADR-024 set for the screensaver.
- The ambient variant of `Screensaver` is no longer reached; it is left in place until the phone's
  stage of the migration, which redraws the same screen, confirms it is not needed there either.
