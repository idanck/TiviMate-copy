# ADR-047 — The radio backdrop stays on the radio's own screen

**Status:** accepted, 0.20.5. Decided by the owner on 2026-10-30, from three options: *don't draw
the radio backdrop behind the browse screens; it appears only on the radio's own screen.*
Narrows ADR-015's backdrop, which until now filled the panel behind home and every browse screen
whenever an audio-only stream played.

## Context

The owner found the side menu's motion weak, "as if frames are missing". 0.20.1–0.20.4 moved the
menu's opening, its plate and its colours to the draw phase (ADR-046). Measured on the
living-room Google TV Streamer, on home with a station playing, three rounds of opening the menu,
moving six entries down and up and closing it: janky frames went from 46% to 26%, and frames on
which the main thread was the cause from 104 to 43.

What was left was the screen under the menu. With a station playing, the station backdrop — its
moving light (`studioAmbient`), its equaliser, its song in the display size — is drawn behind
home, and both redraw continuously. Every frame, idle or not, took about 28ms to draw, 18ms of
it on the GPU; without the backdrop, about 15ms and 6ms. Every animation on home was held to
some 35 frames a second while the radio played.

## Decision

The backdrop (`AudioOnlyNowPlaying`) is drawn while no browse screen is up — the bare picture and
the surfaces drawn over it (the info card, the transport bar, the playback menu, the mini guide)
— and in the guide's preview slot, where it is the picture. A browse screen covers it, so it is
not drawn there: it fades out as one arrives (`SurfacePresence`, 200ms) and back in as one leaves,
rather than cutting to black under a screen still fading in. Sport and News modes are unchanged:
it was never drawn under their panels.

Home's banner keeps its own station identity and its clock while a station plays, since nothing
behind it says either any more. While the backdrop is not composed the equaliser's measurement is
off as well (`Equalizer` starts and stops it with its own composition).

## Alternatives rejected

- **Leave it.** No change to how home looks over the radio, and home's animations stay at about
  35 frames a second while a station plays.
- **Freeze it under a browse screen and keep it as a cached layer.** The station would still show
  behind home, without the moving light and the equaliser; the most code of the three, for a
  picture mostly under home's rails anyway.

## Consequences

- Over the radio, home and the other browse screens look as they do over a stream with no picture:
  the ground and the screen's own furniture.
- The backdrop's play button and station strip are composed afresh when the radio's screen comes
  back, and claim the remote a frame later as they did when an overlay closed over them.
