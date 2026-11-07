# ADR-053 — Sport and News mode on LG televisions

**Status:** withdrawn 2026-11-06; replaced by [ADR-056](ADR-056-lg-televisions-from-webos-5.md). The webOS version was removed from the repo at the owner's request,
to be started again from scratch; this record is kept for what it found. Before that: accepted, webOS 0.2.0. Decided by the owner on 2026-11-05: *bring split screens to the
LG version, in Sport mode and in News mode, the same as on Android.* Extends ADR-052; ports
ADR-041 (Sport mode), ADR-042 (News mode) and ADR-045 (four panels and the running strip).

## Context

ADR-052 left multi-view out of the first webOS version, as ADR-044 had for the computer: the app
had one player, `player/engine.ts`, and `PlayerHost` laid its one `<video>` over each screen. The
Android app has had Sport mode (two Israeli sports channels at once) since 0.16 and News mode (two
or four Israeli news channels, the headlines as a panel, a running strip across the top) since
0.19. The owner wants the LG version to behave the same, on the branch above the first webOS
version (PR 130), not inside it.

## Decision

**1. The same behaviour as Android, ported rather than redesigned.** Sport mode has two panels;
News mode two or four (`newsPanels`, two by default), one of which may be the headlines, and the
strip across the top (`newsTicker`, off by default). The panel in focus takes 58% of the width (and
of the height in a grid of four) and is the only one heard; the arrows move the remote and the
sound with it. OK on a panel opens its menu: full screen or back to the grid, change the channel
(in News mode, or the headlines), remove; in News mode also two or four screens and the strip. OK
on an empty panel opens the picker, on a failed one tries again. Back closes a drawer, then a full
panel, then leaves the mode, and the channel heard goes on full screen; leaving with nothing chosen
starts again whatever was playing before. A channel already on screen in another panel swaps the
two, players and all, rather than opening a second connection. Each mode remembers its grid by
channel key. The Hebrew strings are Android's.

- `state/panels.ts` is `SportSession.kt`: the slots, focus, the picker and the menu, assign and
  swap, the headlines' panel, two panels or four (`keptPanels`), step, pause, suspend and resume.
  Free of the DOM, and tested with stand-in panels.
- `ui/panelLayout.ts` is `SportLayout.kt`, measured from the start edge, so the first panel is at
  the top right.
- `player/panel.ts` is `PanelPlayer`: its own `<video>`, failover down a channel's addresses, and
  for a live stream that ends the next address and then the same one once more, after which the
  panel stops and says the provider closed it (ADR-041, Consequences). It does not reconnect by
  itself: past the account's limit each reconnect would close another panel's stream.
- In News mode the free-to-air copy of a station is offered and the provider's left out
  (`choicesFor`, Android's `FreeCopies`); a grid remembered with the provider's copy gets the free one.

**2. Sport and News are screens of their own, on the rail after Radio.** `#sport` and `#news`
draw `PanelsScreen` over the whole page, with no rail and no `PlayerHost`. Entering stops the
full-screen player; nothing else plays while the panels do.

**3. The remote, where webOS differs.** webOS gives apps no channel keys and LG remotes have no
Menu key, so a long OK is not used either. Up and down, when no panel lies that way, step the
panel's channel (the next sports or news channel not already on screen), as up and down zap in full
screen; with four panels they first move between the rows. Play/pause pauses every panel; the
digits and colour keys are swallowed, as Android swallows the digits.

**4. Panels render in their players' order, never their places'.** A swap changes only where each
panel is drawn. A `<video>` moved in the page is taken out of the document for a moment, and the
platform pauses a media element taken out of the document.

**5. The headlines.** `data/news.ts` ports `NewsFeed` (RSS 2.0 and 1.0, Atom, JSON Feed and JSON
lists; RFC 822, ISO 8601, Unix and bare-clock dates), `NewsDigests.merge` (eight a source, sixteen
in all, none older than a day, the same story once) and the catalogue of suggested sources.
`state/news.ts` is `NewsRepository`: what each source last said is kept in IndexedDB, every source
is read at most every ten minutes and only while News mode is in front of the viewer, side by side,
and one that fails keeps its last headlines. Reading goes through the service's `fetch`, as every
request of a packaged app must (ADR-052). The reader works on text with regular expressions, not
on a DOM, so it runs in the tests' Node as it does in the page.

Android keeps the sources under its screensaver; this version has no screensaver, so Settings
gains a card of its own, "מבזקים", with the suggested sources as switches and an address to add.
Nothing is on by default (ADR-037).

**6. Out of sight, the panels let go.** `visibilitychange` to hidden releases every panel and
keeps the channels; back in front they start again.

## Alternatives rejected

- **The full-screen engine for every panel.** It owns the app's single media session and its
  reconnect policy for one stream; four of it would mean flags in every one of them.
- **A long OK or the Menu key for the picker.** Not reported by LG remotes; the menu works on
  every remote that has OK, which is why Android added it (ADR-041, decision 11).
- **Arrows always stepping the channel.** Up and down then could not move between the rows of
  four panels.

## Consequences

- **Not confirmed on a television yet.** Whether webOS 6 decodes two `<video>` elements at once in
  a web app, let alone four, is not known; nor how it orders two hardware video planes. A panel the
  television refuses while another plays says that the device cannot play another channel at the
  same time, and the others play on. This is the first thing to check on the owner's LG C1.
- The owner's provider account allows one connection at a time (ADR-041, Consequences): two
  provider channels at once will close each other, whatever the television can decode. The free
  copies of the Israeli news channels need no account connection.
- The service decodes text as UTF-8 only. A feed served in windows-1255 reads as garbage; none of
  the suggested sources was checked for this from the television.
- `webos/` only: no APK, no `versionCode`. The webOS version goes to 0.2.0.
