# ADR-049 — A channel left on has a screensaver of its own

**Status:** accepted. Decided by the owner on 2026-10-30: *a screensaver while a TV channel plays
full screen, on its own idle timer, that keeps the picture playing.* Amends FR-UI-31 and narrows
ADR-040, whose one exception — a picture being watched — no longer covers a live channel left on
for longer than its own idle period. ADR-037's screensaver is unchanged for every other screen.

## Context

ADR-040 hands every screen to the screensaver except a picture somebody is watching, because the
picture is its own protection. A live channel is the one picture that is often not being watched:
the news on in the background all evening, or somebody asleep in front of the match. For hours the
panel then shows the channel's logo, its news strip and its score bug at full strength to nobody.

The owner asked for a screensaver for that case, with five conditions:

1. **Only a TV channel playing full screen.** Not the radio, not a menu, not a paused picture:
   those keep the clock's screensaver as ADR-040 has it.
2. **Its own idle timer**, an hour by default, set next to the screensaver's other settings in the
   same kind of control (half hours from 30 to 120 minutes).
3. **The look**, in Merhav (ADR-046; a designer is drawing it in parallel, and this follows the
   brief's text): the picture keeps playing, dimmed by about 30%; a running strip of headlines across
   the top, News mode's own; at the bottom left (in Hebrew) a large clock, the channel's mark, name
   and number, and the programme on air with its hours and a thin bar of its progress.
4. **Any key wakes it**, as it wakes the clock's.
5. **Burn-in.** The plate holds still over moving video for hours; it should be protected the way
   the clock's screensaver protects itself.

## Decision

**1. A scene of its own: `IdleScene.WATCHING`.** `idleScene` answers it where it used to answer
`MOVING`, when what is playing is a live channel (`NowPlayingInfo.Live`) and the shell is the
television. Everything else is as it was:

| On screen | Before | Now |
|---|---|---|
| A live channel full screen on the television, or under the bars that ride over it | left alone | **the channel's screensaver**, after its own wait |
| A film, a catch-up programme or a recording full screen | left alone | left alone |
| Sport and News modes' panels | left alone | left alone |
| A channel full screen on the phone | left alone | left alone |
| A station, a menu, the guide, home, a paused or failed picture | the clock's screensaver | the clock's screensaver |

A film, a catch-up programme and a recording run to an end somebody is waiting for; dimming one two
hours in is getting in the way of it. The phone's player is held rather than left on across a room,
in portrait it is not full screen, and the look was drawn for the television's safe area.

**2. Two screensavers, one countdown.** `screensaverFor` says which a screen goes to
(`SaverKind.CLOCK`, `SaverKind.CHANNEL`, or none); `saverDueAt` says when:

- **the clock** after `screensaverIdleMinutes`, from the later of the last key press and the moment
  the screen turned still (ADR-040, decision 3), as before;
- **the channel's** after `channelSaverMinutes`, from **the last key press alone**. A live stream
  rebuffers now and then, which for that second is a still screen; counting from the moment the
  picture came back would start the hour again at every stall, and on a provider that drops a frame
  every twenty minutes the screensaver would never come.

It is still one coroutine, armed and slept through (`MainActivity`).

**3. Its switches.** The screensaver's switch (`screensaverEnabled`) turns both off. The channel's
has a switch of its own (`channelSaverEnabled`, on) and its own wait (`channelSaverMinutes`, 60),
offered as a slider like the idle minutes with four stops, 30, 60, 90 and 120, one stop a press.
Dimming a picture somebody may be half-watching from across the room is a different thing to ask of
a television than blacking out a menu nobody is reading, and this app installs itself on other
people's televisions: one that wants the menu guard should not have to accept its channel dimmed to
keep it. Both rows are offered on the television only, where the screensaver exists.

**4. Changing from one to the other.** The screensaver on screen stays up through a change of scene
until the other one's own wait is over: a channel that stalls for a second under its screensaver is
not blacked out for it, and a channel that fails for good is, ten minutes later. One exception: a
picture that comes back under the clock's screensaver (a stream that recovered by itself, with
nobody there) goes to the channel's at once, since black over a channel that plays again is the
worse of the two. Which one is up is set as it comes up (`MainActivity.saverShown`), not read from
the scene every frame.

**5. What is drawn (`ui/common/ChannelScreensaver.kt`).**

- **The picture, dimmed:** one flat rectangle of the screensaver's black at 0.3 over the whole
  panel. It fades in over 1.2 seconds, as the clock's screensaver does.
- **The headlines across the top:** News mode's strip itself (`NewsTicker`, ADR-045), not a new
  ticker, as a bar with rounded corners floating inside the safe area rather than banded along the
  edge. It is drawn only when there are headlines to run — "news beside the clock" is on and the
  sources have some; with the switch off, or no sources, there is no strip at all, rather than the
  strip's "no headlines yet" line, which is an instruction for a screen somebody is using.
- **The plate, at the bottom on the end side** (`Alignment.BottomEnd`): the bottom-left corner in
  Hebrew and, mirrored as every layout in the app is, the bottom right in English. One plate of
  regular glass (`panelSurface`) at the overlay-opacity setting, never below 0.8, as the info card
  is: the time at 120px at 1080p; the channel's mark (`ChannelLogo`, with its monogram when there is
  no logo or it will not load), number and name; the programme's title, or "no guide data"; its hours
  and a 3dp bar of how far through it is. The time and the bar recompose once a minute.
- **The programme stays current.** The guide's "now" is refreshed only by a zap, and an hour
  unattended is usually a programme later than the last one. The screensaver asks for it again when
  the programme on screen ends, and once on arrival if it already has.
- **The safe area:** 48 by 27dp, the lower third's and the side menu's.

**6. Burn-in.** The whole of it — the strip and the plate — steps every three minutes, eased over
two seconds, to the next point of the clock's never-closing path (`shiftAt`, ADR-037), ±10dp rather
than the clock's ±6 because the time here is larger and its strokes are wider than six. The dimming
has no edge to wear in. The strip's text never stops; its label and its outline step with the rest.

**7. Waking.** It is the same `screensaverOn` as the clock's, so `MainActivity.dispatchKeyEvent`
does for it what it does for the clock: the press that wakes it, its repeats and its release are
swallowed and act on nothing underneath, and the volume keys neither wake it nor are eaten by it.
A tap on a phone is caught by the screensaver itself, as the clock's is.

**8. The clock's countdown, corrected.** ADR-040's decision 3 counts the clock's wait from the moment
the screen turned still. As built, the stamp was taken each time the countdown ran while the screen
was *not* still — when watching began, or at the last key press — so a film watched for longer
than the idle period without the remote still had a countdown that was over when it first stalled.
The stamp is now taken when the kind of screen changes (`SceneClock`), which is the moment it turned
still.

## Alternatives rejected

- **The clock's screensaver over a channel too, after the longer wait.** One screensaver, no new
  screen; but it takes the channel away, and the owner asked for the picture to keep playing.
- **Under the master switch alone.** One switch fewer, at the price of the choice described in 3.
- **Counting the channel's wait from the moment the picture last played.** Restarts at every stall.
- **Films, catch-up and recordings as well.** See 1.
- **The strip banded along the top edge, as News mode draws it.** Its words would sit 14dp from the
  edge of the panel, outside the safe area, and a band anchored to the edge cannot step without
  uncovering a sliver of picture above it.
- **The phone.** See 1; it can follow if it is asked for.

## Consequences

- **FR-UI-31** gains the channel's screensaver.
- **Every installation changes behaviour:** a live channel left on the television without the
  remote for an hour is now dimmed under a clock. The way out is Settings ▸ Appearance.
- **Cost while it is up:** the strip moves one layer a frame, as in News mode, for as long as it is
  shown; everything else wakes once a minute or every three minutes. With no headlines nothing moves
  but the picture, and the app draws nothing between minutes.
- `screensaverApplies` is gone, replaced by `screensaverFor`. `ScreensaverTest` pins the new scene,
  which screensaver each scene and pair of switches gets, each wait and the moment it counts from,
  the scene stamp, and the programme's bar.
- **To check on the box:** the plate's legibility over a bright picture; the strip's frame rate over
  live video; the plate in the bottom-left corner in Hebrew and the bottom right in English; that
  the waking press does nothing else; that the programme rolls over while it is up.
