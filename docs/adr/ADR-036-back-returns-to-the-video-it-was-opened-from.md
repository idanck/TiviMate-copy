# ADR-036 — Back returns to the video from what the video opened

**Status:** accepted. Amends ADR-016's "Back from any browse surface rises to Home" for two routes:
the channel list and the guide, when they were opened from fullscreen video. Every other route
into them, and every other browse surface, still rises to home. Decided by the owner on
2026-10-16 (round 3 of the Studio migration, `docs/design/studio/MIGRATION.md`); drawn as the
dashed arrows on the navigation map (`canvas/NavMap.dc.html`).

## Context

ADR-016 made home the television's root, and `MainActivity.stepUpOneOverlay` follows it to the
letter: Back from any browse surface goes to home. That is right for a screen the viewer chose in
the side menu or on the home screen. It is wrong for two screens the viewer reaches *from the
picture*, without leaving it in their head:

- **The channel list on its arrow** (D-pad Right in Hebrew, Left in English, from bare video or the
  info card). The viewer was watching, looked at what else is on, and changed their mind. Back
  took them to home — two presses from the programme they never meant to leave.
- **The guide on GUIDE, yellow, or the transport bar's guide button.** The same look away, with
  the picture still in its corner, and the same two presses back to it.

`lastBrowseOverlay`, the surface Back returns to from bare video, cannot carry this. It answers
"where does Back from the video go", not "how was this screen opened", and the channel list has to
stay in it whichever way it was opened.

## Decision

1. **A mark, beside the overlay.** `MainActivity.openedFromVideo` holds the browse surface on
   screen when, and only when, it was opened from the video. It is set by `openFromVideo`, which
   is called from exactly three places: the channel-list arrow and the GUIDE/yellow keys in
   `onKeyDown`, and the transport bar's guide button through `TvRoot`'s `onOpenFromVideo`.
2. **Every other move clears it.** `overlay` is a property whose setter clears the mark, so any
   write that is not `openFromVideo` — a side-menu choice, OK on a channel, Back, a deep link,
   the start-up surface, another screen — forgets it. Two choices change nothing on screen and
   still count as moves: choosing the side menu's lit entry (`SideMenu` now reports it), and OK
   on a channel inside the guide, which plays it in the guide's own picture (`TvRoot` re-asserts
   the guide).
3. **One new rung in `stepUpOneOverlay`.** After the passthrough rung and before the rung that
   raises every browse surface to home: `overlay == openedFromVideo -> overlay = NONE`.
4. **`lastBrowseOverlay` is unchanged.** The channel list is still recorded there when it opens,
   so the sequence is: video → arrow → list → Back → video → Back → list → Back → home. The guide
   was never recorded there, so Back from the video after the guide goes wherever the viewer was
   before it.

## Routes that keep their old behaviour

Back rises to home, as ADR-016 has it, from:

- the channel list or the guide chosen in the side menu, on the home screen ("guide", the
  channel and radio rails), or as the start-up surface;
- the channel list reached with Back from the video (it is `lastBrowseOverlay`);
- the channel list from the playback error's "channel list" button (ADR-034) — the picture had
  already failed, so there is no programme to go back to;
- the radio list, search, recordings, settings, on demand and adding a source, from anywhere.

## Alternatives rejected

- **Pushing the video onto a back stack.** A real stack would answer this and every future
  "where did I come from", and would also replace a ladder of six rungs that every report about
  Back has so far been fixed in place. It is a rewrite of the shell's navigation for two routes.
- **Recording the guide in `lastBrowseOverlay` as well.** Then Back from the video would reopen
  the guide, which the owner explicitly did not want: the guide is a glance, not a place.
- **Clearing the mark lazily, comparing at Back time.** Channels from the video, then the guide
  from the menu, then channels again from the menu would still read as opened from the video.
  Clearing on every write is the only version that cannot remember a route the viewer has left.

## Consequences

- The side menu's entries now carry `Role.Tab` and `selected` semantics, the way Material's own
  navigation rail marks its item. TalkBack says which screen is current, and the emulator QA uses
  it to tell the screens apart (QA-24).
- The phone is untouched: `MobileRoot` handles Back itself, and `stepUpOneOverlay` runs only on a
  television.
- Nothing here can be compiled where it was written (see `CLAUDE.md`); CI is the build gate, and
  QA-24 in `docs/qa/QA-PLAN.md` drives the sequences on the television emulator.
