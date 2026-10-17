# ADR-039 — The television's home screen is a still screen

**Status:** accepted. Amends FR-UI-31 (ADR-024, ADR-037): on the television, the home screen is
handed to the screensaver after the idle period, with the picture playing behind its banner or
without it. Moving video and the radio's own screen are still never replaced. Reported by the owner
on 2026-10-17. Amended by ADR-040 the same day: home stays a still screen, and the radio's own
screen is handed to the screensaver too.

## Context

The report: *the screensaver never comes on on the television, even after changing the time.*

The screensaver only takes a still screen (`screensaverApplies`), and what counts as still is
decided by `idleScene` from what the player reports and from `TvOverlay.videoFillsPanel` — whether
the live picture is what the panel *is*. ADR-024 answered that for the guide (a docked preview
beside a grid: still) and ADR-037 for a menu over a station (still). Home was answered yes,
because ADR-020 made its banner the picture at full size, so home with a channel playing behind it
was `MOVING` and never protected.

That answer was wrong on the screen's own terms:

- The banner's furniture — the top bar, the side menu's strip, the channel's name, the clock, what
  is on now and its progress, the apps strip and the first rail — sits over the picture and holds
  still for as long as the television sits there.
- One press of Down scrolls the banner away and hands the whole panel to the rails (ADR-020,
  decision 2): a wall of cards with the picture behind them at the overlay's opacity, which is as
  still as the guide.
- Home is the television's start screen (`StartBehaviour.HOME` by default) and where Back rises to
  (ADR-016). It is the screen a television is most often left on, so the screensaver never coming
  on there reads, correctly, as the screensaver not working at all.

## Decision

`TvOverlay.videoFillsPanel` is `isPassthrough` and nothing more: the picture is the screen only on
the bare video and the overlays that ride over it (the info card, the filmstrip, the transport bar
and the playback menu). Home, like the guide and every other browse surface, is a still screen.

What follows from that, with no other change:

- **Home with a channel playing behind it** gets the screensaver after the idle period, with the
  paused-or-playing picture line under the clock (`saverPlaying`).
- **Home with a station playing behind it** is a station under a menu (ADR-037): it gets the
  screensaver with "now playing", and the task list does not count down there — it belongs to the
  radio's own screen (ADR-030), which is unchanged.
- **Full-screen video is unchanged.** A film blanked ten minutes into a quiet scene is the worse
  bug (ADR-024).

## Alternatives rejected

- **Count home as still only once it is scrolled past the banner.** The banner's own furniture is
  still too, and the shell would have to report scroll position up to the activity for a
  distinction that does not change the answer.
- **Leave home moving and lower the default idle time.** The screensaver would still never arm
  there, whatever the time.

## Consequences

- `ScreensaverTest` pins home as a still screen, with a channel or a station behind it, and pins
  which overlays leave the picture as the screen.
- The phone is unaffected: it has no home overlay, and its still/moving answer is whether the
  player screen is open.
