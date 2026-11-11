# ADR-068 — The background player is a media card

**Status:** accepted 2026-11-11, built in 0.33.0.

## Context

A radio station (ADR-015) or a podcast episode (ADR-067) plays on behind a locked screen through
`RadioBackgroundService`. Its notification was a plain one: a generic play icon, the station's
name, one Pause button. Next to it Spotify showed what the platform draws for a media session — a
card in the notification shade and on the lock screen with the artwork, the title and artist,
previous/play-pause/next and the output switch. The owner asked for Shidur's to look like that
(2026-11-11), and of the two ways offered he chose the second:

- **A.** Keep the service, give its notification `MediaStyle`, the session's token and the
  metadata — a small change, but previous/next written by hand and two mechanisms to keep.
- **B.** Make the service a Media3 `MediaSessionService`, which builds and updates the card itself
  and is what a headset, a watch or a car's Bluetooth controls reach. Chosen.

## Decision

- `RadioBackgroundService` is a `MediaSessionService` with a session of its own
  (`shidur.background`) over `BackgroundSessionPlayer`, a `ForwardingPlayer` over the engine's
  current ExoPlayer. The engine builds a new ExoPlayer for every station, so the session is handed
  each one in turn (`MediaSession.setPlayer`), after the engine has given it its stream — never
  the empty player in between, which would take the card down and put it back.
- **What the card says** comes from the view model, published to `BackgroundPlayback.card`: the
  station's name and logo, or the episode's title, podcast and picture. The song is the engine's
  `streamTitle`. The engine's media items carry no metadata of their own, and its raw ICY title
  ("Artist - Song") must not stand in for the station's name, so `BackgroundSessionPlayer` answers
  `getMediaMetadata` itself and replaces the metadata in every listener callback. Kotlin's `by`
  delegation does not forward Java default methods, so its listener wrapper writes every callback
  out, and `BackgroundSessionPlayerTest` checks that none is missing.
- **Previous/next** change station on a radio station: the four skip commands are offered and
  handed to the view model's `zap`, lent through `BackgroundPlayback`. On a podcast they stay the
  player's own.
- **Stop** (the paused card swiped away, a stop key) pauses the engine's player and stops the
  service; it never stops the player, so the app comes back to the stream where it was.
- **The foreground promotion** is still made in `onStartCommand`, before anything else, with a
  placeholder under the id Media3 posts its card with: Media3 only promotes once its own controller
  has connected, after `onStartCommand` returns, and a service started with
  `startForegroundService` and released at once (QA-18) must not wait for that.
- The activity's own session (`PlayerMediaSession`) is no longer held by the service: in the
  background, the service's session is the one.

## Consequences

- The card appears only in the background, as the old notification did; a live television
  channel still pauses there (ADR-015), so it has no card.
- A live station has no length, so the card shows no progress bar; a podcast episode does.
- Media3 loads the artwork from the logo's URL itself.
- What the card looks like is the platform's and the phone maker's: checked on a real phone, not
  here.
