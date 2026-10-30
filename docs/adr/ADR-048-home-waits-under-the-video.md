# ADR-048 — Home waits under the video

**Status:** accepted, 0.20.6; amended 0.20.7 to 0.20.11 (below). Decided by the owner on 2026-10-30, from two options: *home stays
built and hidden while the video plays.* Narrows ADR-043, under which the only browse screen
composed while it is not on screen is the one fading out.

## Context

The owner: from a channel, Back brings up the screen's semi-transparent backdrop too slowly, and
the frames can be told apart. A trace on the living-room Google TV Streamer (two presses of Back
from the full-screen picture to home) showed home built from nothing at every press, in two heavy
steps: the frame the press lands in (about 145ms, 104ms of it laying out the banner, the bar and
the menu), and, some 270ms later, the frame in which the rails arrive from their query (about
210ms, composing ten cards and their images). The 360ms fade was eaten by both: it jumped at the
start and froze in the middle.

Back from the video goes to the browse screen it was opened from (`MainActivity.lastBrowseOverlay`),
which is home most of the time.

## Decision

**1. Home waits, built, under the video.** While the screen Back would return to is home and the
video is up — bare, or with a surface drawn over it (the info card, the transport bar, the mini
guide, the playback menu) — the scaffold stays composed but is not placed (`BrowsePresence`). Not
placed, it draws nothing, the remote's search for focus passes it by, and it says nothing to
accessibility. Back places it and fades it in; nothing is composed or laid out. Any other browse
screen, and home under Sport or News mode, is let go once it has faded out, as before.

**2. Home comes back where it was left.** Its scroll and the control the remote was on are kept;
it is handed the remote there (`HomeFocusReturn.restore`) rather than claiming its banner again.

**3. The fade waits a frame.** Every browse screen arriving over the video is placed at once, so a
screen claiming the remote as it composes finds itself on screen, and its fade starts a frame
later: a screen that has to be built is built unseen, not in the first frames of its fade.

**4. The fade multiplies, it does not paint a layer.** The screen's opacity is applied to every
draw (`CompositingStrategy.ModulateAlpha`) rather than painting the whole screen into a layer of its
own each frame and blending that.

**5. Waiting, home lets changes settle.** Its rails are rebuilt four seconds after the last
change while it waits (zapping through channels rebuilds them once), and at once when it comes
back; rails that come out the same change nothing on screen.

## Alternatives rejected

- **Build it, then show it.** Keep the rails from the last visit and start the fade only once home
  is built. A small change, but about 0.3s after Back before anything appears.
- **Keep every browse screen.** The channel list and the guide query and claim focus as they
  compose; keeping them is a larger change than the owner asked for.

## Consequences

- Home costs its memory, and a rebuild of its rails a few seconds after a zap, while the video
  plays. Nothing of it is drawn.
- Home over the video no longer replays its content's rise when Back brings it up (the content
  rises when one browse screen replaces another, as before).

## 0.20.7

Checked on the living-room Google TV Streamer after 0.20.6 shipped.

- **The fade.** One frame of 70–90ms as home is placed again (laying it out and recording its
  draws: an unplaced screen keeps neither), then every frame of the fade on time, at 16.7ms. The
  fade starts after that frame, so it begins from nothing rather than jumping in. Before, a 145ms
  frame at the start and a 210ms one in the middle.
- **The remote came back to the wrong place.** Placed again, home was handed the remote by the
  platform before `HomeFocusReturn.restore` ran, on the favourite button every time, and that was
  noted as where the viewer had been. Nothing is noted from the moment home is left for the video
  until the hand-back (`HomeFocusReturn.hold`).
- **The app woke sixty times a second.** Home's list kept asking, every frame, to compose the next
  rail ahead of time, which never fits a frame on that box, so the request never ended; kept under
  the video, it ran all through the video too. Home's list now composes a row as it arrives
  (`NoRowPrefetch`), which is what the box was doing anyway.

## 0.20.8

Handing the remote back to "Full screen" moved focus during the fade, from the button the
platform had picked to that one, and a television button recomposed itself on every frame of its
colour crossing: two buttons, 7–8ms a frame, through 210ms of the 360ms fade, which ran at about
20 frames a second. `StudioButton` now reads its fill, edge and content colours where they are
drawn, so a crossing redraws the button and recomposes nothing, on home and wherever the button
is used.

## 0.20.9

The owner, once the fade was smooth: moving about home over the picture is not. A trace on the
same box, ten presses down, along a rail and back up:

- **Every press** cost 27–47ms in its first frame, and every frame of the motion after it
  recomposed both cards for 4–5ms, because the app's focus treatment (`focusSurface`) read its
  colour crossings where it was composed — the same fault 0.20.8 fixed in `StudioButton`. It now
  draws them, for every focusable surface in the app, the guide's cells among them. A tile on home
  no longer recomposes for focus at all to keep a flag only a rebuild reads.
- **A press along a rail** to a card not yet built froze for 92ms while four cards composed in
  one frame. A card takes 15–18ms to compose there, more than a frame, so a lazy row's own
  prefetch never ran; it asked again every frame instead, and the app woke sixty times a second
  for as long as the remote stayed on that rail — 0.20.7's loop, one level down. The rails now
  keep two cards composed past each edge, built while the screen is still
  (`QuietPrefetchScheduler`, `AheadPrefetch`).

## 0.20.10

Checked on the same box after 0.20.9: frames during a press's motion went from 13–17ms to 8–12ms,
a press along a rail no longer froze, and the app was still once the remote stopped. A step up or
down the rails, though, froze for 110–265ms: each rail coming into view was built from nothing in
the frame the list began to scroll (about 28ms a card there: 12 to compose, 5 to apply, 10 to
measure), and a rail scrolling away was let go, at 40–60ms more, to be built again on the way
back. The 0.20.8 trace of the same presses showed no such frames, and why was not established.

- **A rail, once built, stays built** (`KeepComposed`, pinning it in home's list): home is at most
  five rails, and every step after the first only moves them.
- **The next rail is built ahead**, while the screen is still, with the cards that fill it, on the
  same scheduler as the cards (`AheadPrefetch` on home's list, replacing `NoRowPrefetch`).
- **The scheduler works a step at a time** (composing or measuring one item) within its 40ms, and
  finishes at once only a request whose next step it would otherwise refuse every time.

## 0.20.11

0.20.10 on the same box: once the rails were built, every press cost one frame of 25–35ms and
then ran at 6–13ms a frame, up, down and along, and the app stayed still between presses. The
first way down after home was built still froze for 80–125ms at two of the rails: presses 0.9s
apart left too little stillness for the rail ahead to be finished, the scheduler waiting a full
quiet period between two runs. It now goes on 16ms after a run while nothing has drawn, and home's
list builds two rails ahead rather than one.
