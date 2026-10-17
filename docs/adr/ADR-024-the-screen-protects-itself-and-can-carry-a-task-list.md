# ADR-024: The screen protects itself, and the radio's screensaver can carry a task list

## Status

Accepted. Adds FR-UI-31 and FR-UI-32, and amends FR-UI-30, whose "nothing animates while the
screen is idle" this is the single deliberate exception to. Amended by ADR-030: the radio is no
longer handed to the screensaver — its Studio screen protects itself — and the task list moved
onto the radio screen. Amended by ADR-039: the television's home screen is a still screen.
Amended by ADR-040: the screensaver takes every screen except full-screen video, the radio's own
screen included, and the task list is back in the screensaver.

## Context

The device report was short: *the picture is static and I am afraid of burning the pixels into
the television.*

It is a fair fear, and this app is unusually good at earning it. Four things it does, each
defensible on its own, add up to a still image held for hours on a panel that is never allowed to
sleep:

- **The screen is pinned awake.** `MainActivity` sets `FLAG_KEEP_SCREEN_ON` for the whole time the
  television shell is on screen, so the device's own screensaver and its display timeout never
  fire. That is right for watching and wrong for everything else.
- **A paused stream is a frozen frame.** Pausing does not dim, hide or move anything; the last
  decoded frame stays on the panel until somebody comes back, which may be tomorrow.
- **An overlay left open is a wall of unmoving text.** The guide keeps the picture playing but
  docks it into a preview slot and fills the rest of the panel with a grid that does not move.
  The channel list, settings and on-demand cover the picture entirely.
- **The radio backdrop is the worst of them, and the most used.** ADR-015 gave audio-only streams
  a screen of their own: a station gradient, a round logo in the centre, the station's name under
  it, a clock in the corner. It is the surface this app is most likely to be left on for six
  hours at a time, and apart from one digit a minute, none of it moves.

The two static overlays with the highest contrast — white text on a dark plate, a bright logo
centred on a dark gradient — are exactly the pattern that wears into an OLED panel, and that
leaves image retention on an LCD.

## Decision

### 1. A screensaver, on any still screen (FR-UI-31)

After a configurable idle period — no key, no touch; ten minutes by default — a still screen is
replaced by a full-screen surface on which nothing holds position:

- **Dark**, for a still picture of any kind: a paused film, an open guide, an error, a menu left
  up. The panel goes to **actual black**, so an OLED emits nothing across almost all of it, and a
  small block of text — clock, date, what is on — drifts across it.
- **Ambient**, for an audio-only stream, which is to say the radio. Blacking out a station
  somebody is *listening* to answers the right question wrongly, so the station's own colour
  stays: a gradient built from the same accent the backdrop uses, dimmed (0.20/0.04 against the
  live screen's 0.34/0.06), its hue swinging ±26° either side of the station's own, with a pool
  of light wandering behind the text on a period of its own.

Both drift the same way, and the path is the point. It is a Lissajous figure whose two axes are in
the ratio of the golden number, so it **never closes**: a circular or bouncing screensaver traces
one loop, and a loop retraced for six hours wears itself into the panel just as surely as standing
still would.

Three rules keep it from doing harm:

- **Moving video is never touched.** Video is its own protection, and a film blanked ten minutes
  into a quiet scene would be a far worse bug than the one this fixes. "Moving" means the stream is
  playing, has a picture, *and* that picture is the screen — the guide's docked preview is a moving
  stamp on a still page, and counts as still.
- **The first press only wakes.** The whole key gesture — down, repeats and the up — is swallowed,
  so waking with OK does not also open the transport bar and waking with Back does not walk up an
  overlay nobody saw.
- **Volume and mute are not interaction.** Turning a station down from the sofa is not looking at
  the screen; those keys pass straight through and wake nothing.

### 2. The radio screensaver can carry a task list (FR-UI-32)

The second half of the report: while the radio is playing, put something on that screen worth
having there — the things I still have to do about Alpha.

The constraint that shapes the whole design is that **this app is installed on other people's
televisions**. "My tasks" cannot mean a bundled account, a vault path or a personal URL. So it is
built exactly as the featured rail was (FR-UI-26, `FeaturedRepository`):

- a setting holds an **HTTPS address**, empty by default;
- while it is empty the feature does not exist — not an empty card, no card;
- a second setting switches the display on, and it is **off by default**, because a to-do list is
  the last thing that should appear uninvited on somebody else's television;
- the document is fetched at most every half hour, cached verbatim in preferences so it survives a
  cold start, and a failed fetch leaves the last good list on screen.

**Two formats, because the honest answer to "how do I get my tasks in there" is a text file.**

- **Markdown** — any document with `- [ ]` checklist lines. That is what a task already looks like
  in Obsidian, in a note synced to a NAS, in a README, in a file a script assembles. Ticked lines
  are dropped; `#` headings become the section each task is printed under and name the list;
  `**bold**`, `` `code` ``, `[[wiki links]]`, `[links](…)` and `#tags` are reduced to the words a
  television can print; a `📅 2026-09-01` due date is lifted out with its marker. A document with
  no checklist at all falls back to its plain bullets, so a list somebody keeps by hand works
  without them learning a syntax first.
- **JSON** — `{"version":1,"title":"…","items":[{"title":"…","note":"…","due":"…","done":false}]}`,
  or a bare array of those items, for a generator that would rather emit structure than prose.

One task at a time is printed under the clock, held for twelve seconds, then crossed to the next —
and because the whole block is drifting, each one appears somewhere its predecessor was not.

**How the author's own connection is made**, as a worked example rather than a rule: the Alpha
notes live in an Obsidian vault as `- [ ]` lines under `בריאות/אלפא/`. A script on the Pi — beside
`build_radio_m3u.py`, which already publishes `radio.m3u` to `il-epg.idanck.com` (ADR-015) —
concatenates the open lines from that folder into a single `tasks.md` and publishes it next to the
playlist. The television is then pointed at `https://il-epg.idanck.com/tasks.md` in Settings ▸
Appearance. Nothing about that arrangement is in this repository, and nothing has to be: the app's
half of it is an address and a parser.

## Alternatives rejected

- **Release `FLAG_KEEP_SCREEN_ON` when the screen goes still, and let the television sleep.** The
  strongest possible protection, and it was rejected for one case it gets wrong: "still" includes
  an overlay left open over a *playing* stream, and on some boxes standby stops playback. Silently
  killing a film somebody was listening to while they read the guide is not an acceptable price
  for a screensaver.
- **Pause playback when the screensaver arms.** Same objection, held more strongly. Nothing in
  this feature touches the player.
- **Shift the whole interface by a few pixels every few minutes** (the trick some televisions use).
  It protects a *static layout* and does nothing for a static *image*, which is the paused frame
  and the station logo — the two things actually at risk here. It would also have to move focus
  rings and hit targets under a D-pad.
- **A photo or video screensaver.** More light on the panel than the picture it replaced, and it
  would need a source of photos, which is a feature of its own.
- **Read the tasks from a calendar, a Google account or a Linear/Notion connector.** Each is an
  authentication flow, an SDK and a permission dialog, for a line of text on a screensaver. An
  address and a text file is a tenth of the work and works with all of them, because every one of
  them can be exported to one.
- **Show the tasks on the dark screensaver too.** The request was specifically about the radio, and
  it is the right instinct: a paused film is a screen somebody is coming back to in a minute, not a
  place for a to-do list.

## Consequences

- **FR-UI-30 gains its exception.** "Nothing animates while the screen is idle" was a real rule
  with a real reason — an unattended television redrawing itself for nothing costs frames and
  power — and this is the one thing that redraws an unattended television *precisely so that it is
  not holding still*. It is kept honest on that rule's own terms: the drift is driven at 12 steps a
  second rather than at frame rate, and every value it produces is read inside a `graphicsLayer` or
  `drawBehind` lambda, so a step redraws two nodes and recomposes nothing.
- **Both shells get it.** The phone pins the screen awake too, whenever the player screen is open,
  and phone panels are OLED more often than televisions are. The guard lives above both shells in
  `MainActivity`, so neither had to know it exists.
- **The idle clock is monotonic.** `SystemClock.elapsedRealtime`, not wall time: a television box
  corrects its clock a minute or two after boot, and a countdown measured against wall time would
  be cancelled or fired early by it.
- **A new preference that is on by default.** The screensaver is a guard, and a guard nobody
  switched on protects nobody. Its timeout is adjustable from 1 to 60 minutes and it can be turned
  off outright.
- **Nothing in the repository knows what anybody's tasks are**, which is the property that makes
  this safe to ship in a binary that installs itself on other people's devices.
