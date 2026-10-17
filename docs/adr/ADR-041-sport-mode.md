# ADR-041 — Sport mode: four Israeli sports channels at once, and the sound follows the remote

**Status:** accepted. First implementation of FR-PLB-24..26 (`docs/02` section 2.11), on the
television only. Decided by the owner on 2026-10-17:

1. *Up to four sports channels at once. One panel has the focus, is drawn a little larger than the
   others, and is the one heard.*
2. *Only Israeli sports channels.*
3. *The sound follows the focus.*
4. *The provider allows several connections at once.*

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

## Consequences

- Up to four streams and four video decoders at once. How many 1080p decoders a Google TV
  Streamer or a Chromecast with Google TV will run at once is not known (`docs/09` section 9.4).
  A panel whose decoder will not start says that the device cannot play another channel at the
  same time, instead of a generic error, and the other panels play on. This needs checking on the
  owner's devices.
- **First device report (2026-10-17):** only one of the four panels kept moving, and not
  necessarily the one in focus; the others stood on a frozen frame and said nothing. The likeliest
  reading is that the box holds fewer hardware video decoders than there are panels. A panel that
  could not get one fell through to a software decoder, which cannot keep up with live HD on a
  television box. These changes followed, in 0.15.1:
  - a panel in the background is offered hardware video decoders only (`SelectiveRenderersFactory`'s
    `videoDecoderFilter`). When they are all taken, it fails at once and says the device cannot
    play another channel at the same time, instead of freezing on a software decoder. The panel in
    focus keeps the software fallback, so the picture being watched and heard always gets a
    decoder. A panel the remote leaves while it is on software starts again on hardware, or stops.
    A channel set to software decoding on purpose, and a stream the device has no hardware decoder
    for at all, keep software wherever they are. The decision is made as each decoder is chosen,
    from the list Media3 offers for that stream, not guessed afterwards;
  - a stopped panel starts again when the remote turns to it, when a panel is emptied, and when
    another panel gives up on its channel. OK on it tries again too;
  - the panel in focus starts first, and the rest start once it has its decoder, is ready or has
    failed (eight seconds at most). Held panels show the loader meanwhile;
  - every panel writes the decoder it got, whether it is software, a running count of dropped
    frames and its errors to the log (`adb logcat -s SportPanel`), which is what settles the
    question per device.

  Not confirmed on the device yet. If a box turns out to decode only one or two pictures at once,
  the next decision is the owner's: fewer panels on that box, or panels that hand the decoder to
  the one in focus and hold a still frame meanwhile. Until then, a focused channel slow enough to
  start after the eight-second backstop can find the hardware decoders taken by the panels
  behind it; it then plays on software, which may stutter. Moving the remote never takes a
  hardware decoder away from another panel. A stream that every hardware decoder rejects for a
  reason of its own reads as the device's limit while its panel is in the background, and plays
  on software once the remote turns to it.
- Memory: each panel holds a few seconds of stream and at most 24 MB of buffer. The full-screen
  player's defaults allow well over 100 MB of video, and four of those would get a two-gigabyte
  box killed.
- The provider's connection limit is not checked. The owner's account allows several connections
  at once. A refused connection shows in its own panel as the server's refusal.
- Not in this version: the phone, saved layouts as named presets (FR-PLB-27, COULD), adding a
  channel to Sport mode from the channel menu, and a QA scenario. The emulator's mock provider has
  no Israeli sports channels to offer yet.
- The classifier is a heuristic over names that live upstream, in the owner's daily playlist and at
  the provider. Its word lists are extended as real names turn out to need it.
