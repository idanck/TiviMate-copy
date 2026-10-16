# ADR-037 — The screensaver is a studio clock, with the news beside it

**Status:** accepted. Amends FR-UI-31 (ADR-024) — the screensaver no longer drifts — and adds
FR-UI-33. Keeps ADR-030 as it is: the radio's own screen is still never handed to the
screensaver. Decided by the owner on 2026-10-16 (round 4 of the Studio migration,
`docs/design/studio/MIGRATION.md`; canvas page "טלוויזיה · שומר מסך", board
`TvScreensaverCombo`, settings board `TvSettingsFlashes`).
Amended by ADR-038: Settings also offers a list of suggested sources; none is switched on.

## Context

ADR-024 replaced a still screen, after ten idle minutes, with a black panel and a small block of
text — clock, date, what is on — drifting across it on a Lissajous path that never closes. It
worked as a guard and was the one screen in the app that had never been drawn in the Studio
language (ADR-027): it was outside the design's scope.

On 2026-10-16 the owner asked for an alternative and chose, out of three boards on the canvas, the
one that puts a studio clock at one side of the panel and a column of news headlines at the other.
Four decisions came with it:

1. **The concept:** the clock and the headlines together — the clock on the right, the headlines on
   the left (in Hebrew; mirrored in English, as every layout in the app is).
2. **No drift.** Only the headlines move, in one smooth, continuous scroll from top to bottom.
3. **Soft colours** in the clock and the headlines, which can be switched off in Settings.
4. **When the radio is playing, the screensaver says which station.**

Two questions were left open on the canvas and were put to the owner before this was built:

- **Burn-in.** Without drift, the ring's dots, the hour marks and the date stand on the same pixels
  for hours — precisely the problem ADR-024 was opened for. The options were: leave it still, step
  it a few pixels every few minutes, or dim it gradually after a long time. The owner chose **the
  small step**.
- **Decision 4 against ADR-030.** ADR-030 never hands the radio's screen to the screensaver: the
  screen protects itself with Studio's shifting background. Changing that would put the
  screensaver over the radio and take the task list off it. The owner chose to **keep ADR-030**:
  "now playing" appears when the screensaver rises over a still screen while a station plays
  behind it — a menu, the guide or Settings left open over the radio.

## Decision

### 1. What is drawn (`ui/common/Screensaver.kt`)

- **The ground** is actually black (`StudioColors.saverGround`, `--st-saver-ground`).
- **A studio clock** at the start side, centred in height: sixty dots on a ring, those already
  passed this minute filled, the current one in ink; twelve hour marks; the time large and the
  date under it (`TimeModel.formatShortDate`, "חמישי 15.10").
- **Under the clock, what is behind the screensaver** (`SaverPlaying`, chosen by `saverPlaying`):
  - a station playing — "now playing", its disc, its name and the equaliser (ADR-031);
  - a picture — a "paused" badge (or the live badge, for live video under an overlay), the channel
    and the programme in Karantina;
  - nothing, or a stream that failed — the clock alone.
- **The headlines** down the end side, the whole height of the panel, fading into the black at
  the top and bottom. One round is a header line ("מבזקים · מ־3 מקורות · עד 20:56") and the
  headlines newest first, each with a bar and a chip in its source's colour, its time, and up to
  two lines of title; the column is that round repeated, translated continuously downwards. With
  no headlines the clock moves to the centre of the panel.
- **Colour** (on by default, `Settings.screensaverColours`): three soft tones outside every colour
  that means something (`StudioColors.saverTones`, `--st-saver-c1..c3`). The passed seconds run
  from the first to the second, the quarter marks take the first, a faint pool of it sits behind
  the ring, and all three swing ±26° in hue over two minutes (`st-amb-hue`). Each source takes a
  tone by its place in the settings list. Off, everything is drawn in the text colours. This is a
  deliberate exception to the colour rules, and it applies to the screensaver only.
- **Everything is drawn at 0.82**, as the canvas has it.
- On a phone held upright the clock sits at the top and the headlines fill the rest; anything wider
  than it is tall uses the television layout, scaled.

### 2. Burn-in: the small step

Every three minutes the whole layout eases, over two seconds, to the next point of the same
never-closing Lissajous path ADR-024 drifted along (`shiftAt`), stepping by the golden angle so
consecutive points are far apart. The travel is ±6dp — about twelve pixels on a 1080p panel, more
than a ring dot is wide — which nobody notices from a sofa and which means no dot, mark or digit
stands on the same pixels for more than three minutes. The headlines need no step: they never stop
moving. The rest of the panel is black.

### 3. The radio (decision 4, with ADR-030 kept)

`idleScene` now asks the radio the same question it asks video: is the stream's own screen what the
panel *is*? A station with nothing over it is `AUDIO_ONLY`, protects itself, and carries the task
list (ADR-030, unchanged). A station with a menu, the guide or Settings open over it is `STILL` —
which it always was in fact, a wall of unmoving text — and is now handed to the screensaver, which
shows the station as "now playing". Before this, that screen was never protected at all.

### 4. The headlines (FR-UI-33, `data/news/`)

- **Sources** are HTTPS addresses the owner adds in Settings ▸ Appearance, up to eight, each with a
  switch of its own. **The list is empty by default**, for the reason the task list's address is
  (ADR-024): the app installs itself on other people's televisions, and which newsroom scrolls past
  on somebody's screen is theirs to choose. With no active source the clock is shown alone.
- **Formats**, recognised by content: RSS 2.0, RSS 1.0, Atom, JSON Feed, and any JSON document or
  bare array whose items carry a title and a time (`NewsFeed`). Encoding is taken from the server's
  header or the XML declaration, because Israeli sites still serve windows-1255. Dates are read in
  RFC 822 (with the zone names feeds actually print), ISO 8601, Unix time, or a bare `20:51`.
- **Merging** (`NewsDigests.merge`): at most eight headlines per source, so one busy site cannot
  push the others off; nothing older than a day; the same story from two sources once; sixteen in
  all, newest first.
- **Fetching** (`NewsRepository`) follows `TaskRepository`: the default request profile, the last
  headlines kept in preferences so the screen has something before the first fetch returns, the
  rate limit on a stored timestamp. Every enabled source is fetched side by side; one that fails
  keeps its last headlines. It refreshes when the screensaver arms and every ten minutes while it is
  up, and at once when a source is added or switched back on — after an addition Settings says so if nothing readable was
  found at the address.
- **Settings** (canvas `TvSettingsFlashes`): "news beside the clock" (on), "colours in the clock and
  the news" (on), "scrolling speed" (slow — the canvas's own pace — normal, fast), and the sources
  with their switches, a field and a row to add one, and a long press to remove one.

## Alternatives rejected

- **Keep the drift.** The owner's decision 2: a clock that wanders is not one anybody can find at a
  glance, and the headlines already move.
- **Dim gradually after a long time,** alone or with the step. Dimming lowers the wear but still
  holds every pixel in place; the step moves them, which is what burn-in needs. Offered, not chosen.
- **Hand the radio's screen to the screensaver** (changing ADR-030). It would put two moving
  backgrounds one after the other and take the task list off the radio. Offered, not chosen.
- **Bundle Israeli news sources.** The same reason the task list ships with no address. The
  candidates were also never checked (MIGRATION.md, round 4).
- **Animate the headlines at frame rate.** Text moving this slowly reads as smooth at 25 steps a
  second; each step is a layer translation, so it costs one layer's redraw, not a recomposition.

## Consequences

- **FR-UI-31 now reads:** a still screen is replaced, after the idle period, by a black screensaver
  with a studio clock that steps a few pixels every three minutes; moving video and the radio's own
  screen are not replaced. **FR-UI-33 is added** for the headlines.
- **The radio under a menu is protected now.** It was a still screen ADR-030's wording left out.
- **FR-UI-30's exception is spent the same way:** the ring redraws once a second and recomposes once
  a minute; the headline column is a `graphicsLayer` translation 25 times a second; the step is two
  `Animatable`s read in a `graphicsLayer`. Nothing runs at frame rate.
- **The ambient skin is gone.** ADR-030 left it unreachable "until the phone's stage of the
  migration"; the phone's radio screen is Studio's (`PhoneRadio`), and this screensaver replaces the
  dark skin too, so the code is removed. The station-colour sampling it used (`rememberStationAccent`,
  `StationAccentCache` in `StationBackdrop.kt`) now has no caller and is left for a cleanup.
- **The task list no longer refreshes when the screensaver arms**, since the screensaver has not
  shown it since ADR-030; it still refreshes on a cold start and when the radio's list is due.
- **Nothing in the repository knows anybody's news sources**, which is what makes this safe to ship
  in a binary that installs itself on other people's devices.
