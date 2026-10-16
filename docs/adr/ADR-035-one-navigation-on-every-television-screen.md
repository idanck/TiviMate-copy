# ADR-035 — One navigation on every television screen

**Status:** accepted. Extends ADR-027's side menu (stage 3) from the home screen to every browse
screen, and removes the channel list's own list of destinations, which ADR-016 had kept beside
the menu.

## Context

Reported from use: the navigation was not uniform — each screen the viewer chose was navigated in
a different structure. An audit of `TvRoot` bore it out. Of the nine browse screens:

| Screen | Before |
| --- | --- |
| Home | Studio's side menu (icons strip, opens over the screen) and the top bar |
| Channels, Radio | no side menu; a column of words above the groups — Home, Radio/Channels, Guide, VOD, Recordings, Settings, plus Search — in an order and a style of their own |
| Guide | the top bar, no navigation |
| Recordings, Settings | the top bar, no navigation |
| Search, On demand, Add source | neither |

So the side menu, which ADR-027 made *the* television navigation, existed on one screen. Leaving
home meant losing it and meeting either a different list or nothing, and the only way back to it
was Back. The Studio canvas never drew it that way: `Channels`, `TvSearch`, `TvVod` and
`TvEmptyChannels` all carry the closed strip, and `SideMenu.dc.html`'s `current` has a value for
every destination, not only home.

## Decision

1. **The side menu frames every browse screen.** `OverlayScaffold` in `TvRoot` draws one
   `SideMenu` over whichever overlay is up — home, channels, radio, guide, search, on demand,
   recordings, settings, adding a source — with the entry for that screen lit. It is one
   instance, outside the screen's entrance animation, so moving between screens does not fade
   the menu out and back in: only the lit entry changes. Each screen's content keeps the closed
   strip's room clear at its start edge (`sideMenuInset`), the same room home's rows keep.
2. **The entries are defined once**, in `SideMenu`, in Studio's order: home, channels, guide,
   radio, recordings, then search and settings under a hairline. Recordings takes the canvas's
   `record` glyph, which the phone's tab already uses, in place of the history glyph.
3. **Studio's top bar heads every screen** at home's size and place — the app's name after the
   strip, the clock and date at the end edge. Home pins its own over the banner and the guide
   heads the column beside its channel rail with its own, so the scaffold draws it for the rest.
4. **Choosing an entry hands the remote to the screen it opened.** Once that screen is composed,
   focus moves out of the menu towards the content, as a press would — unless the screen has
   already taken it for its own first focus (home's banner, the playing channel's row, the first
   poster), which is left alone. Choosing the entry already lit just closes the menu — but it is
   still reported as a choice, so a screen that had been opened from the video then rises to home
   on Back like one chosen from the menu (ADR-036). Each entry carries `selected` semantics for the
   screen it opens.
5. **The channel list's group column holds the list's own filters and groups only.** Its search
   row stays for a tablet, which has no side menu.
6. **On demand has no entry**, as on the phone (ADR-032): no source in this installation carries
   films or series (ADR-016), and search reaches the catalogue where one does. On the on-demand
   and add-source screens no entry is lit.

## Alternatives rejected

- **Each screen drawing its own `SideMenu`.** Simpler to wire, but nine copies of the same call
  drift — which is how the channel list came to have its own list — and the menu would fade with
  every screen change instead of staying put.
- **Keeping the group column's destinations beside the menu.** ADR-016 kept them as a second
  route while the rail was new. Two navigations on one screen, in two orders, is the complaint.
- **An on-demand entry.** It would open an empty grid here, which `docs/03` already rejects.

## Consequences

- Content on every screen but home starts 56dp further from the start edge (the strip and its
  gap, against the 24dp inset before), so the metrics measured from that box scale down slightly
  on a 960dp panel (0.95 to 0.89 of the reference). Nothing is clipped; the guide's hour is a
  little narrower.
- From the channel list, the guide or settings are one press towards the start edge and one OK
  away, where before they were a scroll to the top of the group column.
- Nothing here can be compiled where it was written (see `CLAUDE.md`); CI is the build gate.
