# ADR-058 — Home keeps the picture: the rails scroll in a panel

**Status:** accepted

## Decision

The owner's six changes of 2026-11-09, from photographs of home and the radio screen on his
Google TV Streamer, drawn first in the Studio canvas (`Main`, `HomeRails`, `Radio`, `Button`,
`ChannelCard`) and then built:

1. **The banner no longer scrolls.** Until 0.21.6 the banner was the first row of home's one
   `LazyColumn`, so ▼ from "המשך צפייה" to "ערוצים" scrolled the whole screen and the picture
   with it. Now the banner is pinned at the top (`TvMetrics.homeHeroHeight`, as before) and only
   the rails scroll, in a panel that fills the rest of the screen. The picture stays in sight
   above the panel however far down the rails the viewer goes.
2. **The panel is opaque.** Its ground is the theme's background at full alpha, not the
   viewer's overlay opacity. Above it the banner's shade thins in steps: the opaque ground at its
   foot, the viewer's overlay level (FR-UI-18) at 72% of its height, a light 30% shade at 40%,
   and nothing at the top.
3. **Smaller cards.** A home card is 144dp at 1080p (288px), a fifth smaller than 180dp. The
   canvas draws it at 256px through `ChannelCard`'s new `width` (the filmstrip keeps 320).
4. **Smaller banner buttons.** "מסך מלא", "מדריך" and the favourite star are `MEDIUM` (32dp, 64px)
   instead of `LARGE` (40dp, 80px).
5. **The radio categories are reachable.** On the radio screen ▲ from any station goes to the
   chip of the chosen category (`focusProperties.up`), not to whatever lies nearest above — from
   the far end of the row that was the play button, so the chips could not be reached at all.
   ◀▶ walk the chips and the stations follow the chip under the remote, without OK.
6. **A favourite is amber.** `StudioButton` has an `active` state for a toggle that is on: amber
   glass, a 2dp amber ring and an amber filled star at rest; under focus the surface is amber with
   dark content instead of white. Home's banner, the radio screen and the transport bar use it for
   the favourite. On the television's channel and radio lists a favourite's star is amber rather
   than white. The phone is unchanged: its cue is amber, so amber there means focus.

## Rejected

- **Shrinking the banner when the remote enters the rails.** It would give the panel more room,
  but it moves the picture's frame on every ▼ and ▲, which is what the owner asked to stop.
- **A gold star only, without the ring and the amber surface.** A filled star beside an outlined
  one is what was hard to tell apart from the sofa; the glyph alone does not change enough of the
  button to read at three metres, least of all under focus, where both were dark on white.

## Consequences

- The panel shows one rail and the head of the next at 1080p; the rest are a ▼ away. The banner
  height is unchanged, so `TvMetricsTest`'s "a slice of the first rail" still holds.
- Home's `LazyColumn` no longer holds the banner, so `KeepComposed` and the prefetch apply to the
  rails (and the apps strip) only.
