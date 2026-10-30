# ADR-040 — The screensaver takes every screen except full-screen video

**Status:** accepted. Amends FR-UI-31 and FR-UI-32 (ADR-024, ADR-030, ADR-037). Keeps ADR-039
(the home screen is a still screen), and reverses ADR-030's exception for the radio's own screen.
Decided by the owner on 2026-10-17, in two steps:

1. *The screensaver should come on in every state except while a TV channel is playing.*
2. *The guide and the menus too, so they do not burn in.* A guide or a menu left open over a
   playing channel is protected as well.

Narrowed by ADR-049: a live channel left on full screen on the television has a screensaver of its
own after a longer wait, which keeps the picture playing; and decision 3's stamp is taken when the
screen turns still.

## Context

Before this ADR, the screensaver left two things alone:

- a picture playing as the screen itself, which ADR-039 had narrowed to exclude the home screen;
- the radio's own screen (ADR-030). Its shifting background protects the panel, so the
  screensaver would only replace one moving screen with another.

The owner's report was that the screensaver never came on. ADR-039 fixed one cause, the home
screen. The owner then set the rule outright: the screensaver comes on everywhere except while
somebody is watching a channel.

## Decision

**1. Only a picture being watched is left alone.** `idleScene` returns `MOVING` only when all of
these hold:

- a stream is running;
- it has a video track;
- it has not failed;
- that picture is the screen (`videoFillsPanel`).

On the television the picture is the screen when no overlay is up, or when only one of the bars
that ride over the video is: the info card, the filmstrip, the transport bar or the playback menu
(`TvOverlay.videoFillsPanel`, which is `isPassthrough`). On the phone it is the screen while the
player screen is open. Everything else is `STILL`. `IdleScene.AUDIO_ONLY` is gone.

| On screen | Before | Now |
|---|---|---|
| A channel or a film playing full screen, or under the bars that ride over it | left alone | left alone |
| A channel playing behind the guide, Settings, the channel list or another menu | screensaver | screensaver |
| A channel playing behind the home screen | screensaver (ADR-039) | screensaver |
| The radio's own screen, a station playing | left alone (ADR-030) | **screensaver** |
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

**3. The countdown runs from the moment the screen turns still (0.17.4).** The owner's report:
the screensaver came on over Sport mode's grid, which is two pictures being watched. Two causes,
both in how "still" was measured rather than in the rule:

- `SportSession` counted a panel as playing only while its decoder was running that instant. A
  live stream stalls for a second now and then, and on the owner's one-connection account a panel
  the provider closed reconnects on its own; at each of those moments no panel was "playing", so
  the grid was a still screen. Now a panel is a picture being watched for as long as it has a
  channel that has not failed (`SportSession.showsPicture`): connecting, rebuffering and trying
  the next address count in; a panel the provider closed, or that cannot get a decoder, stands on
  a frozen frame and does not. The headlines count as before.
- The countdown was armed from the last key press alone. A viewer who had watched for longer
  than the idle period without touching the remote had a countdown that was already over, so the
  first moment the screen read as still handed it over at once. `screensaverDueAt` now runs the
  idle period from the later of the last key press and the moment the screen turned still
  (`stillSince` in the activity, stamped whenever the scene stops applying). This holds for the
  full-screen player too: a film that fails after an hour is left on its last frame for the idle
  period, then taken, instead of being taken at once.

## Alternatives rejected

- **Keep ADR-030's exception for the radio.** The owner's rule names one exception, and it is not
  the radio.
- **Leave everything alone while any channel plays, under any overlay.** This was the first reading
  of the owner's rule. The owner then asked for the guide and the menus to be protected too.
- **Hand the bars over the video to the screensaver as well.** The transport bar and the playback
  menu do not hide on their own. But they sit over a film somebody is watching, and blanking a film
  is the worse bug (ADR-024).

## Consequences

- `radioTasksApply` and the activity's separate radio countdown are gone. There is one countdown.
- The radio screen (`AudioOnlyNowPlaying`) no longer takes a task list. Its station strip always
  has the bottom of the screen.
- The task list refreshes when the screensaver arms with a station behind it, and is rate-limited
  as before.
- `ScreensaverTest` pins the countdown's start as well (decision 3), and `SportSessionTest` pins
  which panels are a picture being watched.
- `ScreensaverTest` pins the rule. A channel playing full screen is left alone. Still screens are:
  a channel under a guide or a menu, the home screen, a paused film, a station on its own screen,
  nothing playing, and an error. The test also pins which overlays leave the picture as the screen.
