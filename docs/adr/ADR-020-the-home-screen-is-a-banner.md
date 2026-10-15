# ADR-020 — The home screen is a banner, and the rail collapses

**Status:** accepted. Supersedes the *shape* of the home screen ADR-016 built; everything else in
ADR-016 — that the television's root is a home screen at all, what the rails are, and that video
never stops — still stands.
The collapsing rail is in turn superseded by ADR-027's side menu and closed strip.

## Context

ADR-019 records a teardown of Cellcom TV and adopts the typographic half of it. This record is the
rest: the layout findings, applied.

The owner's instruction was explicit — take the whole report, not the parts that were cheap. So
the parts that were previously argued away in ADR-019's rejected alternatives ("restyling the
screens to match Cellcom TV more closely — not now") are here instead, and that bullet is struck.

## Decisions

**1. The home banner is the picture, full-bleed.**

The live picture no longer docks into a boxed hero beside its caption. It fills the panel, and the
home screen is drawn over it: a gradient rising from the bottom so words stay legible over any
frame, the playing channel's logo and name in the leading corner, a clock in the trailing one,
and, at the bottom of the banner, what is on now with its times, a progress bar, and a *watch
fullscreen* button.

That button is the screen's entry point for the remote. The old hint line under the hero ("OK on
the channel playing above fills the screen") is gone: a screen that has to explain its own
gesture in a caption is one press short of a control.

Mechanically this means `TvOverlay.HOME` no longer sets `docksPlayer`. `OverlayScaffold` gained
an `inset` parameter and the home screen turns it off — a full-bleed banner cannot begin 24dp from
the edge — and applies `TV_CONTENT_INSET` to its own text instead.

**2. Everything is one scroller, banner included.**

The banner is the first item of the `LazyColumn` the rails live in, not a pinned header. Pressing
Down scrolls it away and hands the whole panel to the rails; scrolling back up brings the picture
back. That is what the benchmark does, and it is what makes the rails usable: pinned under a
banner tall enough to be a banner, they had barely one row of visible height on a 1080p panel.

Everything below the banner paints its own backdrop — at the viewer's own overlay-opacity setting
(FR-UI-18, 85% by default), as every other surface drawn over video in this shell does — so text
is readable wherever the list happens to be scrolled. That includes a tail spacer, without which
the last rail sits against bare video at the end of the scroll.

**3. The navigation rail collapses to icons and expands over the screen.**

Icons only until focus enters it; labels the moment it does. It is drawn in a `Box` above the
list, and the list is padded for its *collapsed* width only, so expanding it cannot reflow the
cards — which is the exact objection that kept the labels permanently visible in ADR-016, and it
is answered rather than overruled.

The report's own recommendation was to keep the labels accessible rather than copy the benchmark's
label-less rail outright. Every destination therefore carries its label as a `contentDescription`
whether or not it is drawn (NFR-UX-05).

**4. The guide gets a day strip, a clock, and a focused cell that lifts.**

The `-1d`/`+1d` buttons and the jump-to-date dialog are replaced by a horizontal strip of day
chips — yesterday, today, tomorrow, then weekday plus date, a fortnight either way — which is
FR-UI-05's jump-to-date in the shape a remote can use. Picking *today* lands on now rather than
on midnight. A clock sits in the toolbar, because a full-screen overlay takes the device's own
clock away with it. The focused cell scales to 1.05 and is raised in z-order, so it can be found
on a wall of cells from across a room.

The day strip normalises every day through `TimeModel.startOfLocalDay`. The dialog it replaces
snapped to UTC midnight, which in Israel meant picking a day landed the window at 02:00 or 03:00
local.

**5. A strip of installed streaming apps sits under the banner.**

A fixed catalogue (`ui/home/HomeApps.kt`) resolved against the box: each entry lists its packages,
television build first, and the first one with a launcher activity wins. Nothing installed, no
strip. The packages are declared in the manifest's `<queries>` block, one by one, because Android
11 and later hide undeclared packages whether or not they are installed — and `HomeAppsTest`
parses the manifest to prove the two lists agree, since the failure mode is a shortcut that
silently never appears.

**Package ids are verified, never guessed.** A guessed id costs nothing at runtime, which is
exactly why one can sit in the list for years while a viewer wonders where their app went.

## What was *not* copied, and why

- **The near-black background.** The report measured `#0A0A0D`; this app keeps its blue-grey
  ramp. That is the owner's stated preference, recorded in ADR-019 and in `Theme.kt`, and AMOLED
  mode is already there for anyone who wants the black.
- **2:3 poster cards on the home rails.** The report is right that its VOD rows use them, and
  this app's on-demand grid already does (`VodScreen`). The home rails draw channels and
  programmes, and a portrait poster of a channel logo is a tall box with a small mark in the
  middle of it.
- **Channels as columns in the EPG.** The report describes the benchmark's grid transposed. Its
  own recommendation is to copy the logic rather than the design, FR-UI-05 specifies channels
  down and time across, and every other guide this app is measured against does the same.

## Consequences

- The television shell's root looks materially different on first launch. Nothing about what it
  can do changed: the same destinations, the same rails, the same stream playing throughout.
- The guide loses roughly 40dp of grid height to the day strip on a 1080p panel — about half a
  row. The chips are a single line for that reason.
- `TvMetrics` gained `homeHeroHeight` and `homeNavRailCollapsedWidth` and lost `homeHeroWidth`;
  the banner's height comes from the panel's height, bounded so the first rail is never entirely
  below the fold. `TvMetricsTest` asserts that, and the two invariants it used to assert about
  the old side-by-side hero are gone with the layout they described.
- The app now asks the platform about fifteen other packages. That is visible in the manifest and
  is the whole extent of it: no permission, no query for "all packages", nothing read but a
  label, an icon and a launch intent.
- None of this could be run here (see `CLAUDE.md`), so it lands as a reviewed diff with the pure
  parts — the metrics arithmetic, the shortcut resolution, the manifest agreement — under test,
  and the composition itself checked by reading.
