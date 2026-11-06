# ADR-055 — The LG version draws at Full HD, and what an LG television's browser lays out differently

**Status:** withdrawn 2026-11-06; replaced by [ADR-056](ADR-056-lg-televisions-from-webos-5.md). The webOS version was removed from the repo at the owner's request,
to be started again from scratch; this record is kept for what it found. Before that: accepted, webOS 0.3.0. Decided by the owner on 2026-11-05: *the webOS version should be
at least Full HD,* and *fix the display problems found in the review, in one change.* Replaces the
resolution in ADR-052 decision 6.

## Context

ADR-052 had the app ask webOS for 1280×720 (`appinfo.json`), so that the stylesheet copied from the
computer version, sized for 14 px text, would come out at 21 px on a 1080p panel without changing a
size. The cost: the television draws the app's layer at 720p and enlarges it, so text and icons
are a little soft. Video is not affected — webOS plays `<video>` on a plane of its own at the
stream's resolution — and a web app's layer is 1920×1080 at most, even on a 4K panel.

A review of the app in a desktop Chromium at the television's size (2026-11-05), with flex `gap`
turned off as Chromium 79 has it, found four more things:

- On webOS 6 the space between an icon and its words was gone in every button with both, in the
  guide's "now" chip, the settings' headings and News mode's "headlines" label, and the dots of
  "live" and "on screen" touched their words. ADR-052 decision 6's margin fallback for flex `gap`
  is a rule on `> * + *`, and a rule reaches elements only: words written straight into a button
  are a text node, and a dot drawn by `::before` is a pseudo-element.
- In News mode, a picker or a panel's menu let the headlines panel's empty note show through and
  run into its rows.
- In the guide, the half hours scrolled under the channel column showed faintly through its corner,
  and the now pill covered the half hour beside it.
- Nothing had the remote when the app opened: the first press of an arrow only lit the rail.

## Decision

1. **`appinfo.json` asks for 1920×1080, and the app is still laid out at 1280×720**
   (`ui/scale.ts`). `#app` is a fixed 1280×720 frame, scaled to fill the window by a CSS transform
   (`--ui-scale`, 1.5 on a television), so text and icons are drawn at the panel's own pixels and no
   size in the stylesheet or the code changes. Every fixed layer (the player, dialogs, toasts, the
   split screens) is inside the frame and scales with it. The player, which is placed over its
   slot from `getBoundingClientRect`, divides by the scale.
   - *A transform, not `zoom`.* What the DOM reports about a transformed element is the same in
     every Chromium a television carries (79 to 120) and in a desktop one, so a check in a desktop
     browser says what the television will do. `zoom` changed what the DOM reports in Chromium 128.
   - `100vh` is the window's height, not the frame's; the one height that used it is now the
     frame's 720 px less its padding.
   - The computer version's narrow-window layouts are gone here: the frame is always 1280×720, and
     the window's width says nothing about the room the screens have.
2. **Words beside an icon are in a `<span>`**, and the dots and the logo's mark get their space
   under `html.no-flex-gap` in `styles.css`, so Chromium 79 spaces them as newer ones do.
3. **A drawer over the split screens is near-solid across**, and the panels beside it are dimmed
   while it is open.
4. **The guide's sticky column and ruler are opaque** (`--solid`), and a half-hour label the now
   pill would cover is left out (`ui/guideLayout.ts`).
5. **The remote starts on the rail**, on the screen it is on, as the first arrow press used to put
   it.
6. **webOS 0.3.0**: LG refuses a package with a version it has seen.

## Alternatives rejected

- **Every size in the stylesheet and the code multiplied by 1.5.** The same picture, with every
  length in CSS and in the code (the guide's minute width, row heights, logo sizes, the ticker) to
  change and keep changed, for nothing the transform does not give.
- **`zoom: 1.5` on the page.** Explained in decision 1.
- **Keeping 1280×720.** Softer text, which is what the owner asked to end.

## Consequences

- Not confirmed on a television. To check there: that the text is sharp at 1080p, that the video
  sits exactly in its slot (the guide's preview, the channel list's stage, the corner) and in a
  split screen's panels, and that Chromium 79 draws the scaled frame without a cost in frames.
- The computer version (`web/`) keeps its own layouts, and the guide fixes (decision 4) are not
  carried there yet.
