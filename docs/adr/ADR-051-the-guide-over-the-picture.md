# ADR-051 — The guide is drawn over the picture

**Status:** accepted, 0.21.0; amended 0.21.1 (below). Decided by the owner on 2026-10-30: *no small preview window; the channel
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

## 0.21.1: the guide opens without freezing

A trace of 0.21.0 on the living-room Google TV Streamer, opening the guide from home's banner,
showed two frames of about 800ms each:

1. Home was let go (183ms), and the guide was composed with all eight rows as "no guide data"
   stripes, its data not yet arrived. 526ms of layout.
2. The data arrived and all eight rows composed their cells at once, at 18–40ms a row, then
   361ms of layout, which includes building them.

Moving inside the guide was as intended: 26–54ms for a press's first frame, 17–21ms after it,
nothing recomposed per frame, and no frames at all at rest.

- **The rows come in one a frame** (`RowGate`, `GuideScreen.kt`). The guide opens with its band,
  its ruler and its channel column, and an empty plate of glass in every lane.
  - Once a row's programmes have arrived it waits its turn, top row first, one row a frame. The
    write that lets it through is made inside the frame, so the row is composed in that frame.
  - The rows wait from the opening, and again from each new window (a day, −2h, now, +2h), until
    every row that was waiting has been through.
  - A row composed after that draws its programmes at once: scrolled to, or built ahead while
    the screen is still (`AheadPrefetch`).
  - A row asks for frames only once its programmes are here, so nothing wakes while a fetch is
    out.
  - A lane whose fetch has not come back is an empty plate, not "no guide data".
  - The phone's guide draws each row as its programmes arrive, as before.
- **The landing waits for its row** to draw its programmes, and for that row to be on screen,
  before it focuses the programme on air.
- **A programme cell costs less to build.** What a cell was:
  - **Modifiers:** the focus lift (a `zIndex`, a graphics layer, one `animateFloatAsState`);
    width, height, padding; a clip (a second layer); its background; the lit edge (a border with
    a remembered brush) on cells still to come; the focus surface (two `animateColorAsState`, a
    third layer, a cached draw); a focus observer; the landing's requester on one cell; `clickable`;
    padding.
  - **Inside:** two `Row`s and two to four Material `Text`s.
  - **Per cell, every time:** two copies of the body style, a new shape, and the catch-up word
    looked up again.
  - **So:** three animations each launching a coroutine, and three layers, in every cell of
    every row.

  What it is now:
  - **The focus treatment is built the first time the remote reaches the cell**, and kept. A
    cell at rest drew nothing with it anyway. It starts a frame at rest, so the first focus still
    crosses in rather than cutting.
  - **The shape, the four text styles and the catch-up word** are worked out once for the grid.
  - **Bare text** with those styles, and a `Row` only around the live dot or a badge.
  - **The result:** a cell at rest is one layer, two texts and no animations. The day chips keep
    their treatment: they are a handful, and their resting state is a visible plate.
- **Home is not let go** when the guide opens from it (ADR-048, 0.21.1).

