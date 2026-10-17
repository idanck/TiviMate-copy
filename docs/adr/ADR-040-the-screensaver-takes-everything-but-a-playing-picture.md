# ADR-040 — The screensaver takes every screen except a playing picture

**Status:** accepted. Amends FR-UI-31 and FR-UI-32 (ADR-024, ADR-030, ADR-037). Supersedes
ADR-039, and reverses ADR-030's exception for the radio's own screen. Decided by the owner on
2026-10-17: *the screensaver should come on in every state except while a TV channel is playing.*

## Context

Up to now, whether the screensaver came on depended on the player and on where the shell had
drawn the picture (`idleScene`, `TvOverlay.videoFillsPanel`):

- A picture playing full screen was left alone, and so were the guide's docked preview and, until
  ADR-039, the home screen's banner — or not, depending on which overlay counted as "the picture
  is the screen".
- The radio's own screen was never handed over (ADR-030). Its shifting background protects the
  panel, so the screensaver would only replace one moving screen with another. A station under a
  menu was handed over (ADR-037).

The owner's report was that the screensaver never came on. ADR-039 fixed one cause, the home
screen. The owner then set the rule outright, and it is simpler than either rule before it.

## Decision

**1. One question: is a picture playing?** `idleScene` reads only the player: a stream that is
running, has a video track and has not failed is `MOVING`, and everything else is `STILL`. What
the shell draws over it no longer counts, so `TvOverlay.videoFillsPanel` and the phone's
`playerOpen` drop out of the decision.

| On screen | Before | Now |
|---|---|---|
| A channel or a film playing, full screen | left alone | left alone |
| A channel playing behind the guide, Settings or another menu | screensaver | left alone |
| A channel playing behind the home screen | screensaver (ADR-039) | left alone |
| The radio's own screen, a station playing | left alone (ADR-030) | screensaver |
| A station behind a menu or the home screen | screensaver | screensaver |
| A paused picture, nothing playing, an error | screensaver | screensaver |

A *film* counts as a channel here: a playing picture is somebody watching, whatever it comes from.
"Playing" stays the player's own `playing`, not the user's intent, as ADR-024 set it, so a stalled
picture is still a still frame.

**2. The task list moves into the screensaver** (FR-UI-32). It used to ride along the bottom of the
radio screen once the viewer had been away for the idle time. The screensaver now takes that
screen at that same moment, so the list would never be seen there. It is drawn under the
screensaver's clock instead, under the station's "now playing" line, one task at a time as before.
It is shown only while a station is behind the screensaver, never over a paused film. This is
where ADR-024 first put it.

## Alternatives rejected

- **Keep ADR-030's exception for the radio.** The owner's rule names one exception, and it is not
  the radio.
- **Keep guarding the guide and the menus over a playing channel.** They hold still around the
  picture, which is why ADR-024 guarded the guide. The owner's rule leaves them alone while a
  channel plays. The cost is accepted: a guide left open over a playing channel overnight is not
  protected.

## Consequences

- `IdleScene` has two values, `MOVING` and `STILL`. `idleScene` takes `playing`, `hasPicture` and
  `failed`, and `radioTasksApply` and `TvOverlay.videoFillsPanel` are gone.
- The radio screen (`AudioOnlyNowPlaying`) no longer takes a task list. Its station strip always
  has the bottom of the screen.
- The task list refreshes when the screensaver arms with a station behind it, and is rate-limited
  as before.
- `ScreensaverTest` pins the rule: a playing picture is left alone; a paused film, a playing
  station, nothing playing and an error are all still.
- Both shells follow the same rule. On the phone, a playing picture is left alone whether or not
  its player screen is open.
