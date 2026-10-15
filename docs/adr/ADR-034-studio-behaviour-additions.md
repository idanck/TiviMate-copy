# ADR-034 — What Studio's canvas adds to behaviour

**Status:** accepted. Studio stage 12 (`docs/design/studio/MIGRATION.md`, round 2 §5), landed in
parts 12a–12d. Approved by Idan before implementation, as §5 requires.

## Context

Round 2 of the Studio canvas did not only restyle screens. Five of its boards draw things the app
does not do: buttons on the playback error, an on-screen keypad for the PIN, a panel explaining
where search looks, a different "no guide data" row, and "details" and "record" on the transport
bar and in the playback menu. Stages 10 and 11 carried the look of those boards and left these
out, because each one changes what a press does rather than what a pixel looks like.

## Decision

1. **The playback error offers a way out** (`TvPlayError`, `PPlayError`; 12a). A settled failure
   — the engine has stopped retrying (FR-PLB-17) — puts buttons under its reason:
   - **Retry**, on both shells. It starts what failed through the entry point that started it
     (`AppViewModel.retryPlayback`): live, catch-up, on-demand or a recording, so the request
     profile, the PIN gate, the catch-up window and the resume mark are all asked again.
   - **Network settings** and **Channel list**, on the television only. The first opens the app's
     own Settings ▸ Network, where the user agent the error usually blames is set; the second
     opens the channel list.

   On the television the retry takes the remote's focus as the panel appears, but only while
   nothing else owns the D-pad. Arrows that do not move between the buttons fall through to the
   shell as before, so up and down still change channel from the error, as its hint says. While
   an overlay is up the panel is drawn without buttons, so an arrow running off the end of that
   overlay cannot land on a button behind it. The phone's picture is too small for a plate: the
   whole of it goes dark under the glyph, the reason and the retry, as the canvas draws it.
2. **The television's PIN has an on-screen keypad** (`TvPin`; 12b). Nine digits, then erase,
   zero and confirm, three to a row, with the focus starting on 5. The remote's number keys still
   type, wherever the focus is. The canvas labels the bottom-left key "cancel"; here it erases the
   last digit, because cancelling already has a place (Back, and the prompt's own Cancel). The
   phone keeps the system keyboard and has no keypad.
3. **Search says where it looks** (`TvSearch`; 12c). A panel beside the results on the television
   names what is searched — channel names, programmes in the guide, on-demand where the source
   offers it — and that the device keyboard takes the lower half of the screen while typing. It
   explains; it does not filter.
4. **A channel with no guide data is a striped row** (`TvGuideNoData`, `PGuideNoData`; 12c). The
   guide already kept such a channel in its place (FR-EPG-16); its lane is now the canvas's
   diagonal stripes in a soft hairline, so it reads as "nothing known" rather than as a programme
   that has ended. A channel whose programmes all fall outside the hours on screen gets the same
   row instead of an empty lane.
5. **Details and recording are one press from playback** (`PlayerTransport`, `PlayerMenu`; 12d).
   On a live channel whose current programme is known, the transport bar gains "Details", which
   opens the programme's sheet (watch, catch-up, record, series, favourite), and "Record", which
   schedules the programme on air now. The playback menu's first row is "Programme details and
   recording", opening the same sheet. Without a known programme there is nothing for either to
   act on, and neither is drawn.

## Alternatives rejected

- **Leaving the error as a message.** It named the problem and left the fix to remote bindings
  the viewer had to already know; the canvas's buttons are the bindings, written down.
- **An Android system network screen behind "Network settings".** The error the canvas words
  ("changing the user agent usually fixes this") is the app's setting, not the device's.
- **The keypad's "cancel" as drawn.** A third way to cancel, beside Back and the dialog's own
  button, at the cost of having no way to correct a digit without starting over.
- **Search scope as toggles.** The canvas draws an explanation, not a filter, and a filter is a
  feature of its own with its own state to keep.

## Consequences

- On the television the error panel owns OK while it is up: a long OK there no longer opens the
  playback menu, which has nothing to act on while nothing plays.
- The keypad and the error buttons are new focus targets in a shell where the activity also reads
  the keys. Digits are taken by the keypad before the activity sees them, so typing a PIN cannot
  also change channel.
- As ever, none of this could be compiled where it was written (`CLAUDE.md`). CI is the gate.
