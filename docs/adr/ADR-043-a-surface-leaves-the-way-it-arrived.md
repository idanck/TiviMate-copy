# ADR-043 — A surface leaves the way it arrived

**Status:** accepted, 0.17.2. From the owner's report of 2026-10-17: *on the television, the
visual transition between the screens, and the navigation, do not feel smooth.*

## Context

ADR-021 gave the shell its motion: a spring under focus, colours that cross, and a surface that
*arrives* — fades in and rises 12dp over 220ms (`screenEntrance`). It measured the result on the
Google TV Streamer and it was a different app. Read again against the owner's report, it had left
two things, both in `ui/tv/TvRoot.kt`.

**1. Every exit was a cut.** `screenEntrance` is an arrival and nothing else. When `overlay` moved
back to `NONE`, the surface simply stopped being composed, which is one frame with it and the
next without. That is every Back the television has: from home, from the channel list, from the
guide and settings, from the transport bar, from the filmstrip and the playback menu — and the
info card's own auto-hide, which is the dismissal a viewer sees most, several times an hour. The
arrival was animated; the far more frequent departure was not.

**2. One browse screen replacing another blinked the video through.** The entrance was keyed on
the overlay and applied to the *whole* scaffold column, scrim included, so that the scrim and the
first screen would fade in together. The same key replays it on every change of screen, and a
replay starts from alpha zero: at the first frame of the channel list after home, or the guide
after the list, the column — backdrop and all — was drawn at nothing and faded back in over 220ms.
For that time the live picture behind it showed at full strength, which reads as the video
flashing between two menus. The side menu was already kept out of it (ADR-035: it is the same
instance from one screen to the next); the top bar was not.

A third thing is as true as it was in ADR-021 and is not changed here: for the rest of the day
after a self-update, the app runs interpreted until ART's background pass compiles it, and the
owner's television took several updates on the day of this report. That is the part of "not
smooth" that is dropped frames rather than cuts, and ADR-021 says what was tried against it.

## Decision

**1. A surface is present, not merely composed: `SurfacePresence` in `ui/common/Motion.kt`.**
An `AnimatedVisibility` whose arrival is `screenEntrance`'s — the same fade and rise, the same
220ms and curve — and whose departure is a fade over `Motion.EXIT`, 160ms. Shorter than the
arrival on purpose: what is leaving has already been dismissed, and a departure as long as an
arrival reads as the remote not having answered.

While the surface is leaving, the remote is not left in it. If focus was inside, it is released,
so a press made in that window reaches what is underneath, as it would a frame later; and nothing
drawn directly in it can take focus again (`focusProperties { canFocus = false }`, which reaches
every focus target under it up to the next focus group — a list's rows keep theirs, and go with
the list a few frames later as they always did). The release is conditional on the surface
actually holding focus: what takes the screen next asks for focus a frame after it composes (the
station backdrop, the transport bar), and a blanket clear in the same pass as that request would
have thrown its answer away.

**2. Every surface in `TvRoot` is drawn through it.** The info card, the filmstrip, the transport
bar and the playback menu each had `screenEntrance(Unit)` and an `if`; each is now
`SurfacePresence(visible = overlay == …)`. The browse scaffold is wrapped as a whole, with no rise
of its own because the content inside rises on arrival anyway, and it goes on showing the last
browse screen while it fades: `lastBrowse` is a remembered holder that is updated in the same
composition as `overlay` changes, deliberately not a `MutableState`, which would have drawn the
screen just left for one more frame on the way to the next.

**3. The scrim belongs to the scaffold, and the entrance to the content.** The scaffold's backdrop
is `animatedColour`-crossed between the panel's opacity and nothing, so it stays put while one
browse screen replaces another and crosses in 150ms when the next screen wants a different one
(home paints its own; the list beside it wants the panel's). `screenEntrance(overlay)` moved from
the column to the content box, so the top bar and the side menu are now both steady from one
screen to the next, and only the screen itself fades and rises.

**4. Still one screen at a time.** ADR-021 rejected cross-fading one overlay into another because
it composes both, and that stands: a browse screen giving way to another is cut and the new one
arrives, exactly as before. Only a surface leaving *for the video* fades, and it is the one screen
composed while it does. The one consequence inside the view model is the radio: leaving the
station list for the video now waits `Motion.EXIT` before handing the channel list back, so the
stations do not turn into channels in the middle of the fade; leaving it for another browse
screen hands them back at once, as that screen cuts the list anyway.

## Consequences

- Back from any surface fades it over 160ms; moving between browse screens no longer drops the
  backdrop; the info card fades out on its timer instead of vanishing. Idle still draws nothing.
- For the 160ms a list spends leaving, its rows are still focusable, so an arrow pressed in that
  window may move focus inside the departing list rather than zap. Harmless, and the list is gone
  a few frames later with the focus it took.
- Not changed: the window after a self-update in which the app runs interpreted (ADR-021), and
  the guide's preview, which jumps into its slot rather than travelling there — a `SurfaceView`
  cannot be animated into place without a black frame (ADR-013).
- Not measured on the device from this session: the sandbox cannot build the app (`CLAUDE.md`),
  and ADB to the television was not reachable. The check is ADR-021's: `dumpsys gfxinfo` over a
  Back from the channel list should now show fifteen or so frames where it showed three.

## Alternatives rejected

- **`AnimatedContent` over the overlay** (a cross-fade). ADR-021's reasons: two lists querying,
  two guides fetching, two screens asking for focus.
- **Fading the content only and cutting the scaffold** on the way out. The scrim snapping off
  under a fading list is the same blink as before, backwards.
- **An exit as long as the entrance.** Studio has one panel duration and no exit duration; 220ms
  out reads as slow, and 160ms keeps the rule that a surface leaves faster than it arrives.
- **Clearing focus unconditionally on every exit.** It raced the next surface's own request, as
  above; the station backdrop's play button lost the remote on Back.
