# ADR-025: One focus treatment, and one menu

## Status

Accepted. Completes ADR-023, which defined the shell's two shapes and applied them to four
surfaces. The single treatment stands; its colours, ring width and corners are restated in Studio
terms by ADR-027 (cue for focus, tally for the channel on screen, a 2dp ring, the canvas's radii).

## Context

ADR-023 gave the ten-foot shell a panel and a card, and told the channel and radio lists to take
their corner and their ring from `TvMetrics` so both shells stayed one app. It stopped where it
was looking. Asked whether the same design language reached the settings screens, the radio
lists and the menus, the honest answer was no — and an audit of every focusable element and every
menu in the app said so in numbers.

**Seven focus treatments.** Five different fills for "this is where focus is" (`primary` at 0.22
in a list row, at 0.28 in the navigation pill, at 0.30 in a guide cell and a day chip,
`surfaceVariant` on a card, a solid `primary` on a button), and three ring widths on the same
television at the same moment: 3dp on a card and on a list row, 2dp hard-coded on every guide
cell, on every day chip, on the navigation pill and on every row of the playback menu, and none
at all on a settings slider. A viewer moving a remote from the channel list into the guide
crossed two of them in one press.

Two of those treatments carried defects the codebase already had written rules against:

- the on-demand poster tile and the transport buttons widened their ring from 0dp to 3dp on
  focus, which moves the tile's own poster and the button's own icon inside the clip at the
  moment it is pressed;
- the poster tile, the transport buttons and every row of the playback menu rested their ring on
  `Color.Transparent`, which is transparent *black* — so a bright ring fading out darkened on the
  way, which is the exact thing `Motion.kt` and `TvSurfaces.kt` both say not to do.

**Twelve menus that were not the app's menu.** ADR-023 opened by naming the playback menu as the
one surface that ignored the overlay-opacity setting outright. Every *other* menu in the app was
a stock Material `AlertDialog`: the channel menu and its six sheets, the programme details, and
the four update prompts. Material's dialog is an opaque `surfaceContainerHigh` plate with no edge
and no relationship to FR-UI-18 at all. Long-pressing a channel and pressing OK on the transport
bar are one press apart, and they showed two different apps.

None of this is visible in a screenshot of any one screen, which is why every one of these
shipped. All of it is visible while using the app.

## Decision

**One focus treatment** (`ui/common/Focus.kt`). `Modifier.focusSurface(focused, shape, selected,
fill)` clips, fills and rings. The ring width comes from `LocalTvMetrics`, so a phone keeps 2dp
and a television gets 3dp without a call site knowing which it is on; the ring is one width in
every state; both colours cross rather than cut; and every resting value is its own colour at
alpha zero. `tvCard` and `FocusableRow` are now two thin choices on top of it rather than two
copies of it.

**Four fills, because the answer depends on what is behind the element**, and no more:

| Fill | Focused | Selected | Resting | For |
| --- | --- | --- | --- | --- |
| `TINT` | `primary` @ 0.22 | `surfaceVariant` | transparent | rows, chips, cells, nav pills |
| `RAISE` | `surfaceVariant` | `surface` | `surface` @ 0.65 | cards over video |
| `NONE` | — | — | — | tiles whose artwork is the content |
| `FILLED` | `primary` | `primaryContainer` | `surfaceVariant` | buttons |

`RAISE` exists because a card floats over moving video, where a translucent accent is a different
colour every frame. `NONE` exists because painting behind an app icon or a film poster does
nothing and painting over it hides the thing being chosen. `FILLED` is the one fill that draws no
ring: a button says it has focus by *becoming* the accent, and a ring around that is a second
answer to a question already answered.

**One menu** (`ui/common/Panel.kt`). The floating plate moves from `ui/tv/TvSurfaces.kt` into
`ui/common/`, where both shells reach it, and `AppDialog` draws Material's dialog on it: the
panel corner from the app's own shape ramp, `surface` at the viewer's overlay opacity with a
`MENU_MIN_OPACITY` floor under it, and the panel hairline. All twelve dialogs now use it. The
playback menu's own floor constant, which was a private duplicate of the same number, is now that
one.

**Corners come from the ramp.** The guide's cells and its empty-lane placeholder take
`shapes.extraSmall`, the day chip takes `shapes.large`, the on-demand tile takes the shell's own
`rowCorner`, and the app tile takes `TV_CARD_CORNER`. Building the design-system canvas found
that `MaterialTheme.shapes` was read exactly once in the whole app; the ramp described what was
drawn without being what drew it.

**A source-level guard** (`FocusVocabularyTest`). Three invariants, checked over the UI sources
because this repository has no instrumentation tier and cannot build the app in the environment
it is developed in: no menu outside `Panel.kt` uses Material's dialog, no ring width depends on
focus, no ring rests on `Color.Transparent`. Each is a defect that compiles, renders plausibly on
the screen it was written for, and is trivially reintroduced by copying the composable next door
— which is how the app came to have seven treatments.

## Consequences

- **Two states change appearance deliberately.** A card that is playing but not focused now
  carries the same ring width as a focused one at 45% alpha, rather than a thinner ring — width
  no longer means anything, alpha does. And the guide's chosen day is now drawn the way the
  playing channel is drawn in every list (`surfaceVariant` with a 45% ring) rather than with
  `primaryContainer`, so "this is the one" looks the same everywhere a viewer has just been.
- **The guide cell keeps a body colour of its own.** `surfaceVariant`, or `primaryContainer`
  while the programme is airing. That is about the programme, not about focus: a block in a grid
  has to be visible at rest, and the focus treatment layers over it rather than replacing it —
  which is what lets a focused cell still say whether it is the live one.
- **The banner's Watch button stays an outlined button** rather than becoming `FILLED`. It sits
  over live video, where a plate with no edge bleeds into a bright frame, so it keeps a hairline
  at rest. Its width no longer jumps; the colour crosses and the fill carries focus.
- **The settings slider still has no focus treatment.** It is a Material `Slider` with its own
  focus behaviour, and wrapping it in a focusable row would put two focus targets in one control
  on a D-pad. Left alone deliberately, and named here so the next audit does not rediscover it.
- Menus are now translucent where they were opaque. The floor is 0.92, so at every setting from
  the default down they are very nearly what they were; at 1.0 they are identical.
- As ever, none of this could be compiled where it was written (see `CLAUDE.md`). The guard test
  is a JVM test and runs; the rendering is CI's to prove.
