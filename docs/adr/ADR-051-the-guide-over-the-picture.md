# ADR-051 — The guide is drawn over the picture

**Status:** accepted. Decided by the owner on 2026-10-30: *no small preview window; the channel
being watched plays full screen behind the guide, showing through a lightly dimmed band at the top;
the grid on a dark backdrop; the focused programme on one thin line; the room freed spent on more
rows and a more prominent day switcher; and the guide has to move smoothly.* Supersedes ADR-013's
docked preview, and its two-pane layout as amended in 0.17.6. ADR-013's other decision — television
surfaces are full-bleed, with content padding inside them — stands, and the guide now follows it to
the edges as home does. Narrows ADR-047: the radio backdrop no longer appears in the guide either.

## Context

Since ADR-013 the television guide shrank the one player surface into a slot, 288×162dp since
0.17.6, beside a panel describing the focused programme, with the grid under both. On a 1080p panel
(960×540dp) the top bar, the slot's row, the day strip and the ruler left the grid about 184dp:
fewer than five rows at the 40dp floor. The guide was mostly a small picture and a large panel.

The picture still mattered to the owner — browsing the schedule while the programme plays is what
the guide is for — but not at that size, and not at the grid's expense.

## Decision

**1. Nothing is docked.** The picture fills the panel behind the guide, as it does behind home
(ADR-020). `TvRoot` no longer measures a slot or resizes the surface: `TvOverlay.docksPlayer`, the
dock rectangle and `PlayerSurface`'s `docked` mode (fit, no subtitles) are gone. Entering and
leaving the guide no longer resizes the `SurfaceView`, so there is no asynchronous resize to tear.

**2. Two backdrops, painted by the guide itself** (`GuideOverPicture`; `TvOverlay.GUIDE` is
`fullBleed` and `paintsOwnBackdrop`, so the scaffold paints no scrim and no inset):

- **The band** at the top — the top bar, the day switcher and the focused programme's strip —
  dims the picture by **30%**, the owner's figure. Under the top bar the dim deepens towards the
  top edge as `StudioTopBar`'s own scrim does over the picture on home (82% at the edge, 30% by
  110dp), so the clock reads over any frame.
- **The grid** sits on the dark ground at the viewer's **overlay opacity** (FR-UI-18), as the
  guide's panes always were: legible, and a plain fill for the rows to scroll over.

How the two meet: the band's 30% is fixed; the setting governs the grid, where it already applied.
The setting cannot go below 30% (the slider's range is 30–100%), so the grid is never lighter than
the band above it. The band's chips and its strip stand on thin glass of their own, so their words
hold over a bright frame whatever either number is.

**3. The focused programme is one line**: title, time range, where it stands now, its channel
("on screen · …" for the one playing), and as much of its synopsis as fits, ellipsised. One text
with its parts isolated, in the interface's direction whatever the title's language. The panel's
buttons (watch, from the start, replay, record) are gone from the guide; OK on a cell opens the
programme's details, which have them all.

**4. The room goes to rows and days.** At 1080p the band is 156dp (top bar 64, day switcher 52,
strip 32 and 8 under it), the grid keeps 24dp at its foot, and the rows are cut from what is left
less the ruler: **eight rows of 41.5dp** (`guideGridRowHeightFor`), where there were fewer than
five. The day switcher's chips are the large control height (40dp) with the chosen day in amber
("current", as the side menu marks the screen you are on), and its window buttons (−2h, now, +2h)
are plates rather than ghosts, since they sit on the picture. The content keeps the room the
scaffold's inset gave it, and is measured inside it, so an hour of guide is as wide as before
(214dp at 1080p, a little over three hours across).

**5. Moving about it is built for the box** (ADR-048's lessons):

- The grid's rows, and the day strip's chips, are built ahead while the screen is still
  (`QuietPrefetchScheduler`, `AheadPrefetch`, two past each edge, one scheduler for the screen),
  not by the lazy list's own prefetch, which never fits a frame there and asks again every frame.
- The line at the current time is drawn, not composed: the clock and the grid's horizontal scroll
  are read where it is drawn, so a minute's tick or a sideways scroll redraws and recomposes
  nothing. It used to recompose on every frame of a sideways scroll.
- The day chips' words read their colours where they are drawn, as `StudioButton` does; their
  plates and rings already were (`focusSurface`).
- The focused programme is held in a state only the strip reads, so a press in the grid
  recomposes the two cells focus moved between and one line of text.
- A step down the list refetches the visible slice's programmes, as it always has; a channel whose
  programmes come back the same now keeps the list it had, so its row is skipped rather than
  recomposed for a new list of the same programmes.
- The radio backdrop is not drawn behind the guide (ADR-047's reasoning: its moving light costs a
  frame budget on the box), so over a station the band shows the dimmed ground.

## Alternatives rejected

- **Keep the slot, smaller or larger.** Smaller is not worth watching; larger is ADR-013's
  original 400×225, which left under two rows.
- **One scrim over the whole picture.** Simplest, and either too light for the grid or too dark for
  the picture to read through the band.
- **The panel kept, over the picture.** Its buttons duplicated the details dialog and its height
  is what the rows needed.

## Consequences

- The guide shows eight rows at 1080p instead of under five, and the picture is the panel's,
  with the viewer's aspect choice and subtitles, rather than a fitted miniature.
- The band's top is the picture at 70%: a very bright frame makes it lighter than the grid; the
  chips and the strip carry their own plates for that reason.
- The guide is full-bleed: its backdrops reach the panel's edges. Its content keeps the old inset,
  so an overscanning television crops backdrop, not words.
- `TvOverlay.videoFillsPanel` is unchanged: the guide is still a still screen to the screensaver
  (ADR-040), the picture behind it notwithstanding.
- Not yet measured on the living-room Google TV Streamer. To check there: the first open (the grid
  measured and built under the band), a step down and up the rows, a step along a row to a cell
  past the edge (the shared sideways scroll), and that nothing keeps drawing once the remote
  stops.
