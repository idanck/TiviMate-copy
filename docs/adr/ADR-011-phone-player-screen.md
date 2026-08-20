# ADR-011 — The phone browses full screen and plays in a Now Playing screen

**Status:** accepted

## Decision

The phone shell drops the always-visible video pinned above a half-height list. Browse screens —
the channel list, guide, on-demand grid, recordings and settings — own the whole display.
Starting playback opens a dedicated **Now Playing screen** over them (video on top in portrait
with identity, now/next and quick actions below; fullscreen in landscape), and Back collapses it
to a **mini player bar** docked above the navigation bar, with playback continuing. Every play
path — list, guide, search, programme dialog, on-demand, recordings — is "play and show it".

Three consequences are part of the decision rather than side effects:

- **FR-UI-15 is reworded.** "Portrait: video pinned top, list below" described the old layout,
  not a user need. The need is "browsing must not cost playback", which the mini player serves
  with the whole screen left for browsing.
- **PiP follows the Now Playing screen.** A Home press while browsing has no visible video to
  hand to the PiP window, so it no longer enters PiP; the open player screen does.
- **The video track is disabled while collapsed.** With no surface composed anywhere, decoding
  video is battery and bandwidth spent on frames nothing displays. The engine drops the track
  (not the stream) and restores it when the player screen or PiP returns.

## Context

The phone layout was reported by its actual user, tested on a phone, as the interface's biggest
problem: the list is compressed into two-thirds of the screen while browsing, and choosing a
channel does nothing visible — the list stays, the video above just re-tunes. The report asked
for three things at once: a full-screen list, a channel screen that selection navigates to, and
an interface that explains itself the way a music app does.

The alternative — keeping the pinned video and making the list expandable over it — keeps the
old model's cost (every browse screen designed around a reserved video slot) without answering
the navigation half of the report. The music-app pattern (full-screen browse, expandable player,
mini bar) is the established idiom for "content keeps playing while you browse", which is
exactly this app's shape.

The TV shell is untouched: fullscreen video with overlays *is* the ten-foot idiom (FR-UI-02),
and the report's TV-facing asks — land on the playing channel, focused — are list behaviours,
not shell changes.

## Consequence

Navigation state is still hoisted enums and flags — `playerOpen` joins `overlay` and
`settingsSection` in `MainActivity`, because `onUserLeaveHint` gates PiP on it. No
navigation-compose. The player screen registers its own Back handlers from composition, which
is what makes them outrank the shell's (the same mechanism ADR-era episode-list Back uses).
Landscape now browses too: fullscreen-landscape video is what the Now Playing screen shows,
rather than what rotation forces.

What this costs: one more surface to keep honest (the mini bar shows now/next, so the
programme-turnover refresh must run while the player is collapsed), and the resume-on-start
answer must be awaited before the player screen opens over it.
