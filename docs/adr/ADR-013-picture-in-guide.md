# ADR-013 — The television guide is laid out around the picture, not over it

**Status:** accepted

## Decision

On the television, opening the guide **shrinks the live picture into a docked preview** rather than
covering it. The guide becomes a three-pane layout: a persistent channel rail, the preview, and the
grid. The picture keeps playing throughout, in view.

Television surfaces are also **full-bleed** — backgrounds reach the panel edge — with ordinary
content padding inside them instead of a safe-area frame. This supersedes the "5% safe margins" row
in `docs/03` section 3.1.

## Context

FR-UI-06 asks that the guide stay interactive while playback continues, and the old implementation
satisfied it literally: the guide was a full-screen sheet drawn at the user's overlay opacity, with
the stream still decoding underneath. Nothing of that stream was visible or watchable. In practice
opening the guide cost you the programme, and the way back was a Back press — on a remote, where
browsing the schedule while something plays is the entire reason a guide exists.

The overscan margin was a separate defect with the same symptom. `docs/03` asked for 5%; the code
applied a fixed 32dp/24dp padding *before* painting the overlay's background, so the background
stopped short of the edges and every browse surface floated inside a border of raw video. It was
also absolute rather than proportional, so it was the wrong size on every panel that was not the
one it was guessed against. Reported by the user as "the interface is not spread over the whole
screen", and correctly.

## The mechanism, and the two alternatives

There is exactly one `PlayerSurface` in the television shell. Its modifier switches between
`fillMaxSize()` and an offset-and-size taken from a slot the guide layout measures and reports
through `onGloballyPositioned`. The same composable node measures smaller, so nothing is disposed,
nothing re-attaches and nothing re-tunes.

Two other approaches were considered and rejected:

- **`movableContentOf`** does preserve the node across call sites, but only while both sites live
  in the same composition. A dock slot placed inside a lazy list, a `SubcomposeLayout`, a dialog or
  a popup silently degrades to dispose-and-recreate — a black frame and a re-attach, with nothing
  at the call site to warn you. The modifier swap has no such precondition to get wrong later.
- **A `graphicsLayer` scale and translate** on a permanently fullscreen surface does not work at
  all. A `SurfaceView` is composited by SurfaceFlinger *below* the window, so a Compose layer
  transform moves the View's drawing and not the punched hole: the video would stay full size
  behind a shrinking black rectangle. Making it work would need a `TextureView`, which `PlayerView`
  only selects from an `AttributeSet`.

The dock transition snaps rather than animating. `SurfaceView` resize and reposition are
asynchronous, and an animated dock tears on the mid-range boxes this app targets.

Two consequences are part of the decision rather than side effects:

- **The guide paints its panes individually and leaves the preview slot bare.** One full-screen
  scrim would have been simpler and would have tinted the live picture the layout exists to keep
  watchable.
- **The docked surface ignores the user's aspect choice and drops subtitles.** `toResizeMode` maps
  Fill and 16:9 onto `RESIZE_MODE_ZOOM`, which crops hard enough at preview size to read as a
  fault, and subtitle size is a fraction of view height — a fraction of 225dp is not readable. The
  full-size surface is where both belong.

## Consequence

The full-bleed decision has a cost worth stating plainly: on an older television that still
overscans, content near the edge can be cropped. That was accepted deliberately in preference to a
frame that made the app look windowed on every modern panel.

`PlayerView` also had to stop being focusable. It is a `FrameLayout`, Compose hands focus across
the interop boundary, and the video was therefore a D-pad stop with nothing on screen to show that
focus had landed there. Every control in this app is drawn by Compose; the surface is decoration.

## Addendum (2026-10-18, 0.17.6): two panes, and a grid that can be seen

The owner's report: the guide screen was badly arranged and the guide itself could barely be seen.
Measured on a 1080p panel (960×540dp), that was exact. The preview was 400×225dp — a third larger
than the Studio canvas's 576×324px, which is 288×162dp — and the rows were cut from the *whole*
panel's height (`TvMetrics.guideRowHeight`, a seventh of it, 77dp) although the top bar, the
preview's row, the toolbar, the day strip and the hour ruler came first. What was left under them
held fewer than two rows.

Four changes, all in the television shell; the phone's guide is untouched:

- **The preview is the canvas's 288×162dp.** The focused-programme panel beside it is the same
  height, and its synopsis now yields to the buttons rather than pushing them below the pane.
- **The channel rail is gone.** It listed the channels the grid's own channel column lists, a
  hand's width away, and tuned on OK exactly as that column does; it cost the programmes a fifth
  of the width. The canvas never had it. The guide is therefore two panes — the picture with its
  panel, and the grid — not the three this record first described.
- **The toolbar and the day strip are one row.** The toolbar's date is what the chosen chip says,
  and its clock is in the top bar; the window buttons (−2h, now, +2h) sit at the end of the strip.
- **The rows are cut from the height the grid actually has.** `PictureInGuide` measures what is
  left under the picture and `dockedGuideRowHeightFor` divides it into six rows, as the canvas
  draws, floored at 40dp — where the cell's two lines of Studio type still fit, now that each
  line carries its own line height rather than the body step's — and capped at the shared row's
  ceiling. Six rows on a 1080p panel, where there had been under two.
