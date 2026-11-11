# ADR-066 — The phone's bar is four tabs and "More"

**Status:** accepted 2026-11-11, built in 0.32.0. Supersedes the tab list of ADR-032 and the
YouTube tab's place in ADR-061.

## Context

The phone's bottom bar had six tabs: Live, Guide, Radio, YouTube, Recordings and Settings. On
2026-11-11 the owner decided that podcasts get a destination of their own (ADR-067), which would make
seven. Drawn at 390dp on the canvas (`PNavOptSeven`), seven labels did not fit: "פודקאסט" ran into
"YouTube", and "הקלטות" into "הגדרות". Six with Settings moved to the top bar (`PNavOptGear`) did not
fit either: "פודקאסטים" ran past its own tab.

The canvas row "חלופות לפס התחתון" set five ways side by side: seven tabs; four and "More"; radio and
podcasts in one "Listen" tab; Settings in the top bar; and a drawer in place of the bar.

## Decision

The owner chose four tabs and "More" (`PNavOptMore`, `PNavOptMoreSheet`), with his own split:

- **In the bar:** Live (ערוצים), Radio (רדיו), Podcasts (פודקאסטים, from ADR-067), Settings
  (הגדרות), and **More** (עוד) last.
- **Under More:** the guide (מדריך), YouTube and the recordings (הקלטות).

"More" is a sheet, not a screen: the phone's sheet of thick glass (`StudioSheet`) with the three as
large tiles in a row, a glyph over its label. While one of them is on show, "More" is the lit tab, and
that tile is amber in the sheet. `MobileTab.MORE` exists so the bar has an item for it; it never
becomes the current tab.

Radio still leaves the bar with the radio playlist (ADR-015), so the bar can be three tabs and More.

## Rejected

- **Seven tabs** and **Settings in the top bar** — the labels do not fit (above).
- **One "Listen" tab for radio and podcasts** — fits, but adds a tap between them and parts the phone
  from the television, where both are entries of the side menu.
- **A drawer** — like the television, but hides every destination behind a button.
- **Choosing which tabs sit in the bar** (`בחירת הלשוניות בפס`, drawn in the sheet) — not built; the
  owner did not ask for it.

## Consequences

- The guide is one tap further than before. The guide start behaviour (FR-SET-04) still opens on it,
  and the player's guide button still goes straight there.
- A new destination that does not earn a tab goes under More.
