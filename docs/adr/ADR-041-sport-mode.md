# ADR-041 — Sport mode: four Israeli sports channels at once, and the sound follows the remote

**Status:** accepted. First implementation of FR-PLB-24..26 (`docs/02` section 2.11), on the
television, and from 0.16.0 on the phone (decision 9). Decided by the owner on 2026-10-17:

1. *Up to four sports channels at once. One panel has the focus, is drawn a little larger than the
   others, and is the one heard.*
2. *Only Israeli sports channels.*
3. *The sound follows the focus.*
4. *The provider allows several connections at once.*
5. *On the phone too, with a choice of two panels or four.*

## Context

Section 2.11 has asked for multi-view from the start, and it was never built: the app had exactly
one player. `PlayerEngine` is the one full-screen player, and the media session, the floating
window, the launcher's "continue watching" row, resume marks and the radio's background service
all hang off it. `TvRoot` draws one video surface and moves it between full screen and the guide's
preview, and never draws a second.

Two facts about the owner's setup decided the scope. The daily playlist files the Israeli channels
under one group, `ישראל` (the provider's raw name is `ISRAËL| HEBREEW`), and files about 1,300
foreign sports channels under `ספורט | אנגליה`, `ספורט | גרמניה` and so on. And the provider allows
several connections at once, so four streams from one account are expected to work.

## Decision

**1. A mode of its own, reached from the side menu.** "Sport", with a ball glyph, after Radio. It
opens `TvOverlay.SPORT`, which `TvRoot` draws opaque and full-bleed, instead of the side menu and
the top bar: four pictures are the whole screen. Back leaves it.

**2. Israeli sports channels only** (`data/sport/IsraeliSportChannels.kt`). A heuristic over names,
as the radio's shelves are, because no playlist marks either property in a field of its own. Both
must hold:

- *Israeli:* the group names Israel or Hebrew, or the name opens with the provider's `IL` tag, or
  the guide id ends in `.il`.
- *Sport:* the name has `sport` or `ספורט` in it, or it is ONE, or it is one of Sport 5's channels
  that leave the word out (`5 LIVE`, `5 GOLD`, `5 STARS`, `5+`), or the guide id names sport, or
  the Israeli group is itself a sports group.

The first test is what keeps the foreign sports groups out. Every playlist but the bundled radio
is searched. Hidden channels and groups stay hidden, and a channel behind the parental PIN is left
out rather than asked for in each of four panels.

**3. A fixed two-by-two grid whose lines move toward the panels that are not in focus**
(`ui/tv/SportLayout.kt`). The panel in focus takes 58% of the width and of the height, so it is
the largest and keeps the screen's 16:9 shape. No panel ever changes place, so an arrow always
means the same neighbour, and nothing overlaps: two video surfaces covering the same pixels have no
defined order on Android. The change is not animated, because resizing four video surfaces every
frame on a television box costs more than the movement is worth. The first panel is at the top
right in Hebrew.

**4. The sound follows the remote, at once.** Every panel decodes its sound all the time, at
volume zero unless it is the panel in focus. Turning a muted panel's audio track off would save a
decoder, but turning it back on makes the player re-buffer, and a second of silence on every press
is not "the sound follows the focus". Only the audible panel asks the system for audio focus:
four players asking would each take it from the one before, and pause it. Panels decode encoded
audio (AC-3, E-AC-3) to PCM rather than pass it to an HDMI receiver. A passed-through stream
ignores the volume, so a muted panel would still be heard, and the output takes only one
passed-through stream at a time.

**5. The remote.**

| Key | In the grid | On a panel filling the screen |
|---|---|---|
| Arrows | Move the remote, and the sound, to the neighbouring panel | Nothing |
| OK | Fills the screen with the panel; on an empty panel opens the picker; on a failed one tries again | Back to the grid |
| Long OK, Menu | The channel picker for that panel | The channel picker |
| CH+ / CH− | The next or previous Israeli sports channel in the panel with the remote, stepping over channels already on screen | The same |
| Play/pause | Pauses or resumes every panel | The same |
| Digits | Swallowed: they tune the full-screen player | The same |
| Back | Leaves Sport mode, and the channel with the remote plays on full screen | Back to the grid |

With the picker open, Back closes it. A channel already on screen in another panel is marked in
the picker, and choosing it swaps the two panels, players and all, rather than opening a second
connection to the same stream.

**6. The full-screen player stands down.** Entering Sport mode stops `PlayerEngine`, after saving an
on-demand title's position, and `TvRoot` stops composing its surface. Leaving with Back plays the
channel with the remote through the full-screen player; leaving with nothing chosen starts again
whatever was playing before. Each panel is a `PanelPlayer`, which is deliberately not a
`PlayerEngine`. It keeps the channel's request profile, its decoder choice, retry with backoff,
failover down the channel's URLs and the reconnect rounds a live channel gets, and leaves out the
rest.

**7. The lifecycle.** Home silences the panels at once and offers no floating window, because
there is no single picture to keep showing. Stopping the activity releases them. Coming back
starts the same channels again, except any the parental lock now covers. The screensaver treats a
playing panel as a picture being watched (ADR-040).

**8. The grid is remembered.** The channels in the four panels are kept in preferences by id, and
Sport mode opens on them. With nothing to restore, it opens the picker on the first panel.

**9. The phone (0.16.0), with two panels or four.** The same `SportSession`, so the same rules:
the panel in focus is drawn larger and is the only one heard, a channel already on screen moves
rather than opening a second connection, and the panels let go whenever the app is out of sight.
`PhoneSport` draws it over everything, as the Now Playing screen is drawn (ADR-011), opened from
a ball in the top bar of Channels and Guide: the bottom bar's five places are taken (ADR-032).

- *The hand.* A tap gives a panel the focus and the sound, picks a channel for an empty one, and
  tries a failed one again. A double tap fills the screen with a panel and puts the grid back. A
  long press opens the picker, a sheet from the foot of the screen like every phone menu. Back
  puts a full panel back into the grid, then leaves; leaving plays the channel in focus in the
  Now Playing screen, as the television carries it on full screen.
- *The bar.* Back, the title, two panels or four, and pause for all. Held upright it has its own
  place above the panels. Lying down it rides over them and steps aside four seconds after the
  last touch; any touch brings it back.
- *Two or four.* The session always holds four players, and only the first two or four are on
  screen; the rest are empty and released. Going to two keeps the panel in focus and the next
  ones in order, moved to the front, players and all, and lets go of the others. The choice is a
  preference of its own, read as Sport mode opens. The television offers no choice and keeps four.
- *Where the panels go* (`SportLayout`). Lying down, four are the television's grid and two stand
  side by side, the one in focus taking 58% of the width. Held upright, they stand one above the
  other across the full width: two share the height 58 to 42, and in a column of four the one in
  focus takes 40% and the others 20% each. A two-by-two grid on an upright phone would make every
  picture a quarter of its width.
- The shared parts of a panel (the picture, an empty place, a failure, the caption, a picker
  row) live in `ui/common/SportPanels.kt`. The television's and the phone's hints differ only in
  saying OK or tap.

## Alternatives rejected

- **Four `PlayerEngine` instances.** The engine owns the app's single media session, the floating
  window and the resume marks. Four of it would mean four of those, or a flag in every one of them
  saying "not this one".
- **Equal quarters, with the focused panel scaled up over its neighbours.** That is the literal
  reading of "a little larger", but it overlaps video surfaces.
- **The focused panel always large, in one place, with the other three in a column beside it.**
  Every press would rearrange the screen, and an arrow would stop meaning a direction.
- **Muting by turning the audio track off.** Explained in decision 4.
- **Every sports channel, or every channel.** The owner asked for Israeli sports channels only.
- **On the phone, a tab of its own.** The bottom bar is at its five (ADR-032).
- **On the phone, always landscape.** Forcing the screen to turn would fight a phone held upright
  for two channels, which stand well one above the other.

## Consequences

- Up to four streams and four video decoders at once. How many 1080p decoders a Google TV
  Streamer or a Chromecast with Google TV will run at once is not known (`docs/09` section 9.4).
  A panel whose decoder will not start says that the device cannot play another channel at the
  same time, instead of a generic error, and the other panels play on. This needs checking on the
  owner's devices.
- **First device report (2026-10-17), and its cause.** On the Google TV Streamer, only one of
  the four panels kept playing, and not necessarily the one in focus. The other three stood on a
  frozen frame with no message. The device's own media records settled it:
  - all four panels got the hardware decoder (`c2.mtk.avc.decoder`, declared for four instances
    and 4K60), and none dropped frames, so decoding was not the limit;
  - three of the four audio tracks reached the end of their stream 1.6, 3.4 and 14 seconds after
    starting, and the fourth, the last to connect, played on. A live stream that ends was closed
    by the server;
  - the provider's account API reports `max_connections = 1`;
  - a direct test from the same home network, two streams of the account open at once, saw the
    first closed two to three seconds after the second opened, whatever user agent either sent.
    The account offers only the `ts` output, not HLS.

  So the account allows one stream at a time, and the provider closes the older streams when a
  newer one opens. The decision that the provider allows several connections (decision 4 above)
  does not hold for the account as it stands; Sport mode needs one connection per panel.

  The player treated a stream that ends as a channel that finished, not as a failure, so the
  panel froze without a word. From 0.15.1 a panel whose live stream ends tries the channel's next
  address, if it has one, and then the same one once more, since a clean close can also be a
  server restarting. If the stream closes again, the panel stops, lets go of its player, and says
  the provider closed it and that the account may allow fewer connections than there are screens.
  OK tries again. It does not keep reconnecting by itself: past the limit, each reconnect closes
  another panel's stream, and the panels would take the one connection from each other in turn.
  A stream that had played steadily for a minute earns its one automatic retry back.

  The full-screen player has the same blind spot, a live stream that ends leaves it on its last
  frame, and is not changed here.

  Open, for the owner: more connections on the account, or Sport mode reading the account's limit
  as it opens and starting only that many panels.
- Memory: each panel holds a few seconds of stream and at most 24 MB of buffer. The full-screen
  player's defaults allow well over 100 MB of video, and four of those would get a two-gigabyte
  box killed.
- The provider's connection limit is not checked. A refused connection shows in its own panel as
  the server's refusal, and a stream the provider closes says so (see the device report above).
- Not in this version: saved layouts as named presets (FR-PLB-27, COULD), a choice of two panels
  on the television, adding a channel to Sport mode from the channel menu, and a QA scenario. The
  emulator's mock provider has no Israeli sports channels to offer yet.
- On an account allowing one connection, the phone is in the same place as the television: one
  panel plays and the others say the provider closed their streams. The phone and a television on
  the same account also take the connection from each other.
- The classifier is a heuristic over names that live upstream, in the owner's daily playlist and at
  the provider. Its word lists are extended as real names turn out to need it.
