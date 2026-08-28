# ADR-023: One surface vocabulary for the whole ten-foot shell

## Status

Accepted. Extends ADR-022, which introduced the floating navigation capsule and the round station
mark but applied both to the home screen alone.

## Context

ADR-022 landed the design pass on the home screen and stopped there. The result was worse than
having done nothing, in a specific and predictable way: the screen a viewer opens the app on now
looked like one app, and the four surfaces one button away from it looked like the one it replaced.

The shell had never had a shared vocabulary to break in the first place. Each surface had arrived
at a different time and brought its own idea of what a surface is:

- the **transport bar** and the **channel filmstrip** were edge-to-edge bands of `background` at
  the viewer's overlay opacity — the picture simply stopped at a horizontal line;
- the **playback menu** was a square-cornered plate of `surface` at a hard-coded `0.97`, the one
  surface in the shell that ignored the overlay-opacity setting entirely (FR-UI-18);
- the **zapping info card** — the surface a viewer sees more than any other, because every channel
  change puts it on screen — was a small opaque box in a corner, a logo and three lines of grey
  text laid out as a list rather than as something read from a sofa;
- the **home tiles** were 12dp cards and the **filmstrip tiles** were 10dp, with the same
  three-state colour and border logic copied into both files;
- the **channel list**, which is also the radio list, drew phone-sized rows: an 8dp corner and a
  2dp focus ring, which at three metres is a rectangle with a faint outline.

## Decision

**Two shapes, defined once** (`ui/tv/TvSurfaces.kt`):

- a **panel** — a floating plate the interface sits on: inset from the edges, rounded at 28dp,
  translucent at the viewer's own overlay opacity, and outlined by a hairline. The hairline is
  load-bearing rather than decorative: a translucent plate has an edge over a dark scene and none
  over a bright one, and without it the interface appears to bleed into the picture.
- a **card** — one focusable thing on a panel: rounded at 14dp, carrying the three states that
  have to be told apart across a room (resting, playing, focused) with a ring thicker than the
  app's usual one.

The navigation capsule, the transport bar, the filmstrip and the playback menu are all panels. The
home tiles and the filmstrip tiles are all cards. The channel and radio lists get the card corner
and ring through `TvMetrics`, so the phone shell — which reads `TvMetrics.Phone` — is untouched.

**The info card is a lower third.** No box: a gradient that fades the picture into legibility, the
identity and the live badge above the fold, and the programme title carrying the weight rather
than the channel name. Zapping asks "what is on this one", and the answer used to be set two steps
down the type ramp from the name of the channel.

**One live badge.** The station backdrop, the info card and the transport bar were each going to
answer "is this on air" in their own way. `LiveBadge` is shared, and so is the rule behind it:
live *and* not paused, since all three of those surfaces survive a pause.

**Roundness is a property of the card, not of the rail.** ADR-022 decided it per rail, which is
wrong in a way only a real playlist shows: Continue and Favourites are built across every playlist
on the device, so a station that had been played or starred was round on one rail and square on
another, on the same screen at the same time. `HomeCard.circular` is now set where the card is
built — and only while the art is the station's own mark, because a feed's artwork or a programme
icon in a disc is a picture with its ends cut off.

## Consequences

- Two surfaces keep a floor under the overlay-opacity setting rather than obeying it outright: the
  playback menu, which is several lines of track names read one by one, and the info card, which
  is the most-seen surface in the app. At the setting's own minimum of 0.3 both were illegible
  over moving video, and turning overlays down is not a request for that.
- The live badge is a fixed dark plate with white text rather than scheme colours. It has to read
  on live video, on a backdrop built from a station's hue, and on a themed panel; a plate from the
  scheme fights the first two, and at its original 45% black it composited to a mid grey against a
  light theme's surface and fell under 3:1.
- The badge in the transport bar is hidden rather than removed when playback stops. That bar is
  anchored to the foot of the screen and grows upwards, so dropping a line would slide the focused
  play/pause button down at the moment it is pressed.
- The info card drops its identity row entirely when the audio-only backdrop is behind it, the
  same rule the home banner already followed — otherwise a radio station is named twice and
  carries two live badges a few centimetres apart.
- `FocusableRow` now reads `LocalTvMetrics`. Every list in the app is built from that row, so this
  is most of what focus feels like in both shells; the phone's numbers are unchanged by
  construction, since `LocalTvMetrics` defaults to `TvMetrics.Phone` and only the television shell
  provides anything else.
- As ever, none of this could be compiled where it was written (see CLAUDE.md). CI is the gate.
