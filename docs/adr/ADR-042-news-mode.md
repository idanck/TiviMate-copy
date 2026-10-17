# ADR-042 — News mode: two Israeli news channels, or one and the headlines

**Status:** accepted, 0.17.0. Decided by the owner on 2026-10-17: *a news mode as well, two screens
only, where one of the screens can be chosen to show the flashes.*

## Context

Sport mode (ADR-041) put several channels on screen at once, on the television and the phone. The
owner asked for the same for the news, with two differences: only two panels, and the headlines
the screensaver already shows (FR-UI-33, ADR-037, ADR-038) as a choice for either of them.

The owner's account allows one stream at a time (ADR-041, first device report). One picture and
the headlines need one connection only, so this is the arrangement that works on that account.

## Decision

**1. One session for both modes.** Sport mode and News mode are never on screen together, so they
share Sport mode's `SportSession`, its players, its grid, its lifecycle and its remote, and differ
in a `Mode`: which channels are offered, where the grid is remembered (`news_slots` beside
`sport_slots`), and whether a panel may show the headlines. Opening one while the other is up
closes the first; the full-screen player stays down between them.

**2. Two panels, always.** `SportLayout`'s two: side by side on a wide screen, the one in focus
taking 58% of the width, and one above the other on a phone held upright. The phone's bar offers
no choice of how many.

**3. The headlines are a panel, chosen like a channel.** The picker's first row after "remove" is
the headlines; choosing it releases that panel's player and its connection. There is one panel of
headlines at most: choosing them for the other panel moves them there, swapping the two panels as
two channels swap. The panel is the screensaver's own scrolling column, from the sources chosen in
Settings, in its colours and at its speed, scaled to the panel. It is read while the panel is up
on the repository's ten-minute limit, the screensaver's, so the two never fetch twice between
them. With no source, or nothing read yet, the panel says where the sources are chosen.

**4. The sound stays with a picture.** The headlines have none. With them in focus, the sound stays
with the channel in the other panel, and leaving the mode plays that channel. An empty panel in
focus is still silence, as in Sport mode.

**5. A News mode opened for the first time** has the headlines in the second panel and the picker
open on the first: the one arrangement that works on a one-connection account.

**6. Israeli news channels only** (`data/news/IsraeliNewsChannels.kt`). Sport mode's first question,
is the channel Israeli, and then: its name says news (`news`, `חדשות`, `ניוז`, `i24`, the
Knesset), or it is one of the general channels by number (`CHANNEL 12`, `קשת 12`, `HOT Reshet 13`,
and Channel 14 under its old number, `CHANNEL 20`), or its guide id is one of theirs. Channel 9,
Channel 1 and the rest of the Israeli group are left out. An Israeli guide id now also counts in
the owner's own form, `il.channel-12`, as well as a provider's `channel12.il`.

**7. Where it opens.** On the television, "News" in the side menu after Sport, with a newspaper
glyph. On the phone, the same glyph in the top bar of Channels and Guide, beside Sport's ball.

## Alternatives rejected

- **A second session for News mode.** Twice the players, and every lifecycle hook, key and Back
  rung in the activity written twice, for two screens that can never be up together.
- **The headlines as an overlay across the bottom of one picture.** It hides part of the picture
  and is unreadable at a ticker's size on a phone. A panel of its own is what the owner asked for.
- **A choice of four panels.** The owner asked for two.

## Consequences

- On an account with one connection, two news channels behave as Sport mode's panels do: one plays
  and the other says the provider closed its stream. A channel and the headlines play together.
- The headlines' sources are the screensaver's. A viewer who has none sees, in that panel, where
  to add them.
- `SportSession`, `SportScreen`, `PhoneSport` and `SportLayout` keep their names though they now
  serve both modes.
