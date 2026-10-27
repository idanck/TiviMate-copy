# ADR-045 — News mode: four panels if wanted, and a running strip of headlines

**Status:** accepted, 0.19.0. Decided by the owner on 2026-10-27: *in News mode, a choice of a
split of four channels, with the option of a running strip of headlines across the top of the
screen.* Extends ADR-042; Sport mode is unchanged and keeps two panels (ADR-041, decision 10).

## Context

ADR-042 gave News mode two panels, one of which may be the headlines, and rejected a choice of
four because the owner had asked for two. `SportLayout` never stopped laying out four, and
`SportSession` never stopped holding four players (ADR-041, decision 10), so four panels are a
number, not a new grid. The headlines, until now, were a panel of their own or nothing.

## Decision

**1. Two panels or four, News mode only.** A preference, `news_panels`, two by default, read as
News mode opens. It changes while News mode is up:

- *On the television*, two more rows at the foot of a panel's menu (ADR-041, decision 11): "Four
  screens" or "Two screens", and the strip below.
- *On the phone*, two buttons in News mode's bar, a grid and a newspaper, each lit in the cue
  while it is on.

Four on a wide screen are `SportLayout`'s two-by-two grid, the panel in focus taking 58% of the
width and of the height; on a phone held upright, its column, the panel in focus taking 40%. The
menu's way back from full screen reads "back to all screens" when there are four.

**2. Going to two keeps what is being watched** (`SportSession.setPanels`, `keptPanels`). The
panel in focus stays, then the next panels with something in them, counting on from it and round,
moved to the front with their players, so nothing reconnects. The others let go of their
connections. Going to four adds two empty panels after the two there. Either way the grid is
remembered as it then stands.

**3. A running strip of headlines across the top** (`NewsTicker`, `ui/common/SportPanels.kt`).
A preference, `news_ticker`, off by default. The strip is a band of its own above the panels,
48dp on the television and 34dp on the phone, so it covers no picture: ADR-042 rejected the
headlines over a picture for hiding part of it. A "Headlines" plate in the cue stands at the start
edge; the headlines run toward it without stopping, rightwards in Hebrew, so each comes in from its
first word. Each headline is its source's dot and name in the source's colour, its time, and its
title. The strip shows what the panel of headlines and the screensaver show, from the same sources,
read on the same ten-minute limit, so neither fetches twice. Its speed is the screensaver's setting
times five: 10dp a second reads well going down a column and crawls across a screen. It moves frame
by frame as one layer, rather than in the column's dozen steps a second, which visibly jump
sideways at this speed. The panel of headlines and the strip can both be up at once.

**4. The bundled free-to-air playlist's channels count as Israeli.** iptv-org's guide ids carry
their feed after an `@` (`Kan11.il@SD`), so `IsraeliSportChannels.isIsraeli` read them as foreign,
and the bundled playlist (ADR-013), which has no group, offered nothing to News mode. The part from
the `@` on is now dropped before the country is read, on Android and in `web/` alike. With i24NEWS
added to that playlist the same day, it and Keshet 12, Channel 13, Channel 14, Kan 11 and the
Knesset channel are on offer from it.

## Alternatives rejected

- **Four panels for Sport mode too.** Not asked for; Sport mode keeps two.
- **The strip over the top of the pictures.** ADR-042's reason holds: it hides part of a picture.
- **Compose's `basicMarquee`.** It scrolls one piece of text and pauses between rounds; a ticker
  is many headlines that never stop, and the screensaver's column already had the copies-and-offset
  arithmetic (`feedCopies`, `feedOffset`) to reuse sideways.

## Consequences

- Four channels need four connections. On the owner's account, which allows one (ADR-041,
  first device report), three of four channels say the provider closed their streams; the
  headlines in one panel still need none. The bundled free-to-air channels are not the provider's,
  so they do not count against its limit.
- Four decoders at once on the Google TV Streamer were seen to run (ADR-041), and are not known on
  the other televisions.
- Not checked on a device from this change's environment, which cannot build the app.
