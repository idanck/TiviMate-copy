# ADR-054 — Every panel of the split screen is a `PlayerEngine` of its own

**Status:** accepted, 0.21.2. Decided by the owner on 2026-11-05: *instead of `PanelPlayer`, each
screen of the split screen should be a `PlayerEngine`, and so get its stream on its own.* Replaces
ADR-041 decision 6's "each panel is a `PanelPlayer`, which is deliberately not a `PlayerEngine`",
and the alternative ADR-041 rejected, "four `PlayerEngine` instances". Sport mode and News mode
(ADR-041, ADR-042, ADR-045) are otherwise unchanged.

## Context

Since 0.15.0 Sport mode's and News mode's panels each had a player of their own, a `PanelPlayer`:
its own ExoPlayer, decoders and connection, but a smaller class than the full-screen player's,
written beside it. It kept a live channel's request profile, decoder choice, retries, failover and
reconnect rounds, and left out the rest: the viewer's buffer preset, the number of retries set in
Settings, the backoff, the one software retry when a hardware decoder will not start, the preferred
audio and subtitle languages and DRM. The owner wants each panel to be the same player as the full
screen, to see whether the panels play then as the full-screen player does.

ADR-041 had rejected that because `PlayerEngine` owned the media session, the floating window, the
launcher's "continue watching" row and the resume marks. Read again, it does not: those hang off
the one instance on the `ServiceLocator` (`AppViewModel`, `PlayerMediaSession`, `MainActivity`),
and the class itself holds none of them. A second instance has none of them either.

## Decision

**1. `PlayerEngine` has a role** (`PlayerEngine.Role`): `MAIN`, the full-screen player on the
`ServiceLocator`, and `PANEL`, one of the split screen's. `SportSession` holds four `PANEL` engines,
one per slot, where it held four `PanelPlayer`s; `PanelPlayer` is gone. A panel plays exactly what
the full-screen player plays, built the same way, with the same retries and failover.

**2. What a panel does differently** is only what several players at once need, all of it carried
over from `PanelPlayer` and none of it changing the full-screen player:

- *The sound* (ADR-041 decision 4). A panel plays at volume zero unless `setAudible` makes it the
  one heard, and only that one asks the system for audio focus. Without this, each panel starting
  would take the focus from the one before and pause it, and one panel would play.
- *No passthrough.* Encoded audio is decoded to PCM: a passed-through stream ignores the volume,
  and the output takes one at a time.
- *A ceiling of 24 MB on buffered bytes.* The buffer durations are the viewer's preset, as on the
  full screen; without a ceiling the load control sizes itself for a single player.
- *A live stream that ends* tries the channel's next address, then the same one once more, and is
  then left closed, saying so (`PlaybackStatus.closed`; ADR-041, first device report). The
  full-screen player still does not look for it.
- *Pause for every panel* (`setPaused`) holds across a retry or a failover.

**3. A panel's status is the full-screen player's** (`PlaybackStatus`), with `closed` added and
`failed` read from it: closed, or stopped trying (`errorDetail`). `PanelStatus` is gone.

**4. Retries wait on the main thread**, as the full-screen player's do: `SportSession` gives each
engine the scheduler `AppViewModel` gives the full-screen one, and watches each engine's status in
the view model's scope, for whether any panel is a picture being watched (ADR-040).

## Alternatives rejected

- **Keeping `PanelPlayer`, with the missing parts copied into it.** Two players to keep in step,
  which is what the owner asked to stop.
- **Several `PlayerEngine`s with nothing changed.** Each would take the audio focus from the one
  before it and pause it: one panel would play, for a reason that has nothing to do with the
  stream.

## Consequences

- A panel now retries as many times as Settings says, with the full-screen player's backoff, and a
  hardware decoder that will not start gets one software try before the panel says the device
  cannot play another channel at once. A software decoder for a 1080p picture beside three others
  may be more than a television box can carry; this needs looking at on the devices.
- A panel's buffer follows the viewer's preset, up to the 24 MB ceiling.
- Headphones pulled out pause every panel, as they pause the full-screen player (owner's call,
  2026-10-16). The split screen's own pause does not know it, so its mark stays off until the
  viewer presses play or pause.
- Whether the panels now play together on the owner's account is what this change is for, and is
  not known from this change's environment, which cannot build the app.
