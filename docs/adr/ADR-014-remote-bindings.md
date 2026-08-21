# ADR-014 — OK opens controls, not a caption

**Status:** accepted

## Decision

Three default remote bindings change (`docs/03` section 3.4):

| Key | Was | Is |
|---|---|---|
| OK (short) | Channel info card | Transport bar |
| OK (long) | Settings ▸ Playback | Playback menu |
| Right | Mini guide | Channel filmstrip |

The channel info card is not deleted: it is still what a zap or a numeric channel entry flashes up,
and it still auto-dismisses on the existing timeout.

## Context

The phone has carried a transport row, a quality readout, a favourite toggle and a now/next pane
inside its player screen since ADR-011. The television had none of them. It had bare video, a
caption that named what was playing and offered nothing to do about it, and a remote. Play and
pause existed only as a media key that many television remotes do not carry; changing the aspect
ratio meant leaving the programme for a preferences screen; and neither shell could pick an audio
or subtitle track on the stream in front of you, although `PlayerEngine.audioTracks`,
`textTracks`, `selectTrack` and `disableTrackType` had all been implemented and had **no callers
anywhere in the app**.

That is what the user's report of "the television is behind the phone" amounted to in practice, and
it was a binding problem before it was a UI problem: the two most valuable keys on the remote were
spent on a caption and on a settings screen.

Two surfaces rather than one, because a remote wants two different things at two different moments.
The transport bar is what a single press should give you — the four or five actions worth no
further navigation — over video that keeps playing. The playback menu is everything else, in a list
a D-pad can walk. This is the "configurable bottom playback menu" of `docs/03` section 3.6; it is
not yet configurable, so FR-UI-20 stays Partial.

Right previously opened a single-channel timeline with neither `focusable()` nor `clickable()` on
anything in it — a picture of a schedule, on a device with no pointer. What a remote actually wants
at that moment is the next channel, so the filmstrip shows channels captioned with what is on now.

## Consequence

The overlay state had to stop being a boolean. `overlay != NONE && overlay != INFO` was spelled out
in four places and conflated two questions that are no longer the same: whether the video is still
the screen, and whether the arrows still zap. The filmstrip, transport bar and playback menu are
all drawn over playing video *and* own the D-pad. These are now `isPassthrough` and
`leavesDpadToShell`, and the four sites read one of them each.

Two smaller things follow from it:

- OK is tracked (`startTracking`) wherever a long press means something, which is now every
  passthrough overlay rather than only the bare screen. `onKeyLongPress` fires only for a key the
  activity asked to track, so registering it inside the old branch would have meant that once the
  transport bar was up, holding OK silently stopped opening the menu.
- Back gained a level. It collapsed any overlay to `NONE` in a single press, which — now that the
  playback menu is opened *from* the transport bar as often as from a long press — threw away the
  bar the user was working in and made Back read as a cancel.

FR-UI-11 (user-remappable keys) remains unimplemented, so these are defaults nobody can yet change.
That raises the cost of getting them wrong, which is why they are recorded here rather than left as
a diff.
