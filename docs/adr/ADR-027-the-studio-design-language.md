# ADR-027 — The Studio design language

**Status:** accepted; all five stages landed (0.11.0 to 0.13.3). The side menu, drawn on home only
by stage 3, frames every browse screen since ADR-035. Supersedes the typeface and the palette of ADR-019, and the *shape* of the
television's navigation in ADR-020 and ADR-022. Lands in stages (see Consequences); this record
covers the language as a whole so each stage does not need its own.

## Context

The interface had grown from a Cellcom TV teardown (ADR-019, ADR-020) and then three passes to
make it one vocabulary (ADR-022, ADR-023, ADR-025). It was consistent, but it was still a
generic dark Material app with a blue accent: nothing in it said *broadcast*, and the two things a
viewer most needs to see at a glance on a television — what is on screen now, and where the remote
is — were drawn with the same colour.

A design session in Claude Design produced a complete replacement, named **Studio** (אולפן): a
canvas of every television and phone screen and component, a token sheet, and a migration plan.
All of it is in the repository at `docs/design/studio/` (`MIGRATION.md`, `studio.css`,
`studio-phone.css`, `canvas/`), and the canvas is the source of truth for sizes and states.

## Decision

**1. The language is broadcast graphics.** A lower third, a tally light, badges. Concretely:

- **Two lights carry the meaning.** *Tally* `#FF5A5F` is what is on screen or playing now. *Cue*
  `#FFB547` is, on a television, where the remote is — the only focus colour, a 2dp ring on a
  card or a fill on a button. On a phone, which has no remote, cue marks what is active or
  selected. *Next* `#9DC0F7` is what comes after the current programme.
- **The ground is night blue**, `#080C14`, with surfaces climbing in the same hue (`surface`,
  `card`, `card-focus`). This replaces ADR-019's blue-grey `#1B212B` ramp, which that record kept
  deliberately against the benchmark's black; Studio keeps the blue and takes it darker.
- **Two typefaces.** *Karantina* Bold for programme titles and the app's name only, *IBM Plex Sans
  Hebrew* for everything else. Both replace Rubik. See *Typefaces* below.
- **The channel on screen is highlighted** with a fill, a frame and a glow in the tally's hue, and
  that highlight is distinct from focus — a card can be on screen, focused, both or neither.

**2. Television navigation is a side menu.** A full-height menu on the right (the start edge in
Hebrew), 240dp wide, rows of icon and label, no capsule. Closed, it leaves a 56dp strip of icons
always visible at the same height they sit at when open, and content is inset by it. The top
bar carries the app's name on the right and the clock and date on the left; the clock stays in
the top-left corner. Top navigation is removed. This replaces ADR-020's collapsing rail and
ADR-022's floating capsule.

**3. The radio has a shifting background** against burn-in: four dark tones (`--st-amb-1..4`) on a
120-second cycle, with a pool of light travelling a path that never closes (47s and 76s periods,
the golden ratio) and a ±26° hue drift. Its equaliser is a separate decision (it needs the
player's audio) and gets its own record when it lands.

**Material stays underneath.** Every Studio colour that has a Material role is mapped onto it
(`background` ← ground, `surface` ← surface, `surfaceContainer` ← card, `onSurface` ← ink,
`onSurfaceVariant` ← ink-2, `primary` ← cue), so every existing screen and every focus treatment in
`ui/common/Focus.kt` takes the new palette without its call sites changing. What has no Material
role — the two lights, next, the on-screen fill, the guide's airing/future/past cells, the overlay
tints, the ambient tones — is in `StudioTheme.colors`, a `CompositionLocal` beside the theme. The
type ramp is the same: Material's roles are pointed at Studio's steps, and the steps themselves
are `StudioTheme.type`.

### Typefaces

Both families are published under the SIL Open Font License 1.1 and ship from `google/fonts`
(`ofl/karantina`, `ofl/ibmplexsanshebrew`) as static TrueType instances, so none of ADR-019's
variable-font instancing is needed. They are bundled **unmodified**: IBM Plex has a Reserved Font
Name, and under the OFL a subset is a Modified Version that may not keep it. Four Plex weights
(~96 KB each) and one Karantina (~48 KB) come to about 435 KB, against Rubik's 370 KB.

Karantina is reached only through `StudioTheme.type` (hero, title, title-s). The Material display
roles are Plex at those sizes, because in this codebase the display roles set the clocks.
Programme titles move to Karantina screen by screen as each is redrawn.

Plex Sans Hebrew's digits are tabular by default, which is what the canvas asks of times.

The licences ship at `assets/licenses/ibm-plex-sans-hebrew-ofl.txt` and
`assets/licenses/karantina-ofl.txt` (and in `docs/licenses/`), with the credit in Settings → About.

## Alternatives rejected

- **Keeping Rubik and only changing colour.** Rubik is a fine UI face but has no display voice;
  the title-as-broadcast-graphic is half of what makes the language read as a channel.
- **Karantina on the Material display roles.** Simpler, but it would put a condensed display face
  on every clock, which the canvas never does.
- **Subsetting the fonts to Hebrew + Latin**, as was done for Rubik. Saves perhaps 30% but
  conflicts with Plex's Reserved Font Name.
- **Migrating in one change.** Every merge to `main` publishes to every installed device
  (ADR-012), and this environment cannot build the app (`CLAUDE.md`). One change touching every
  screen would be unreviewable and, if wrong, wrong everywhere at once.

## Consequences

- The migration runs in five stages, each its own branch, pull request and update, in the order
  `docs/design/studio/MIGRATION.md` gives: (1) foundations — palette, type and fonts, no structural
  change; (2) surfaces, focus, metrics and motion; (3) television navigation; (4) television
  screens; (5) the phone. Between stages the app is a consistent mix: Studio's colour and type on
  the previous layout.
- Stage 2 puts Studio's shapes, focus and motion into the shared modifiers: the canvas's radii
  per shell in `TvMetrics` (and Material's `Shapes`), a 2dp cue ring on a television where it was
  3dp, `card-focus` as the focused fill, the tally on the card that is on screen, the cue as the
  focused button and chip, a shadow under every lift, and Studio's durations and curve. The guide's
  cells take the airing/future/past tones. Glow (`--st-tally-glow`) is not drawn: Compose has no
  coloured blur below API 28, and the fill and tally ring already carry the state.
- Stage 4 redraws the television's screens. Its first part is the home hero (live badge, the
  programme title in Karantina, time with progress and minutes left, watch / guide / favourite
  buttons, the next programme in "next" blue) and the zapping card as Studio's lower third (a
  number tile under the tally, the title, and a "next" panel), with the shared live badge,
  progress bar and button they are built from.
- Stage 4's radio part redraws the audio-only screen as Studio's radio (`AudioOnlyNowPlaying`):
  the shifting background, the station's disc in a halo, the song in Karantina with the artist
  under it, and play/pause and favourite as Studio buttons. How it meets the screensaver is
  ADR-030; its equaliser, measured from the player's own output, is ADR-031.
- Stage 5 redraws the phone. Its first part is the chrome: `PTopBar` (back, the title in
  Karantina, search), `PNavBar` (five tabs, the current one in a cue pill, recordings in
  on-demand's place: ADR-032) and `PMiniPlayer` (a card with the channel under a tally dot, the
  title, play/pause and a 2dp progress line), in `ui/mobile/PhoneChrome.kt`.
  Its second part is the player's details in portrait (`PNowPlaying`): a tally along the top of
  a live picture, the collapse button on a scrim, the live badge and channel, the programme title
  in Karantina, time with progress and minutes left, zapping around a cue play/pause, favourite /
  guide / view as `PButton`s, and the next programme in a panel.
  Its third part is the radio (`PRadio`, `ui/mobile/PhoneRadio.kt`): in portrait a station of the
  bundled radio takes the whole player screen — the shifting background, the disc in a halo, the
  song in Karantina, the equaliser, favourite / previous / play-pause / next / guide, and a strip of
  stations under national / regional / other chips with the one on air marked by the tally.
  Its fourth part is the channel list (`PChannelRow`, `ui/screens/PhoneChannelRow.kt`, and one
  row of `PChip`s for the filters and categories): the number on a tile in Karantina (a station's
  mark on a disc), what is on with its time and a progress line, and the channel on screen in the
  tally. The row keeps a favourite star the canvas does not draw (FR-ORG-07).
- Stage 3 replaces the home screen's floating capsule with the side menu (`ui/tv/SideMenu.kt`):
  a full-height 56dp strip of icons that opens to 240dp over the screen when focus enters it,
  dimming what is behind, with the destinations in Studio's order (home, channels, guide, radio,
  recordings, then search and settings under a hairline). The top bar (`StudioTopBar`) is pinned
  over the home screen with the app's name at the start and the clock and date at the end; the
  banner no longer draws a clock of its own. The other screens take the top bar as each is redrawn.
- Stage 1 changes how every screen looks at once, through `MaterialTheme`. Most visibly, focus
  turns from blue to amber everywhere, and the background darkens.
- Text sizes follow the canvas exactly, which takes the smallest television text below the 12sp
  floor `docs/03` set. That is a requirement change of its own: ADR-029.
- Light and "follow system" themes are withdrawn, since Studio is drawn dark only: ADR-028.
- `docs/design/README.md` and the older canvas (`build_canvas.py`) describe the pre-Studio system
  and are superseded by `docs/design/studio/` as each stage lands.
- Stage 6 draws what the five stages left. Its first part is the television's guide as the canvas
  lays it out: the focused programme beside the picture (channel, number, category, the title in
  Karantina, time and status, synopsis, and watch / from the start / replay / record), the tally
  along the top of the live picture with "on screen" over it, and Studio's top bar heading the
  guide, recordings and settings.
  Its second part is the television's two-pane settings and recordings: the sections in a panel
  at the start with the open one beside them, and the recordings list with the one the remote is on
  described at the end (state, title in Karantina, channel, time and length, watch / delete or
  cancel). Section rows carry their glyphs on both shells.
  Its third part is the television radio's station strip: a heading and national / regional /
  other chips over the stations' discs, the one on air ringed in the tally and the one under the
  remote in the cue; OK tunes it. It gives way to the task list (ADR-030).
  Its fourth part finishes the phone: "more channels" under Now Playing (the next two channels as
  Studio rows with what each is showing), the guide's programme sheet (`PGuide`: tapping a
  programme opens a sheet with its time, category, title, status and watch / from the start /
  replay / record / details), and the tally glow round what is on screen. The glow is a coloured
  shadow, which Android draws from API 28; older devices keep the fill and the ring without it.
