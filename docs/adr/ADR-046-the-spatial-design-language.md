# ADR-046 — The spatial design language (מרחב, Merhav)

**Status:** accepted, 0.20.0. The owner approved the "מרחב" proposal boards on the canvas and
asked for every screen to be drawn in it and then implemented. Supersedes the
*visual* parts of ADR-027 — the palette, the two typefaces, the shapes, the focus colour and the
surfaces — and the typeface decision of ADR-019, whose method for bundling a font is reused here.
ADR-027's structure stands: the side menu and the top bar (with ADR-035), the radio's shifting
background, Material underneath. So do ADR-028 (dark and AMOLED only), ADR-029 (television text
follows the canvas) and ADR-033 (the app draws its own icons).

## Context

Studio (ADR-027) drew the interface as broadcast graphics: a lower third on a gradient, a red
tally, an amber cue that was the only focus colour, a night-blue ground, a condensed display face
(Karantina) for titles and IBM Plex for the rest. It was consistent, but it drew the interface
*instead of* the picture: plates and frames over the video, and amber meaning two things a viewer
needs to tell apart — where the remote is, and on the phone, what is chosen.

A design session produced a replacement, "מרחב" (spatial). Its premise is that the picture is the
environment: live video stays behind everything, and the interface floats above it in layers of
glass, with depth — shadows, a lit edge, blur — where Studio had hairlines and frames. The
canvas's skeleton (`studio.css`, `studio-phone.css`) kept Studio's `--st-*` token names on purpose,
so every board, and every line of code that maps to them, moved with the tokens.

## Decision

**1. Glass, in three thicknesses, and depth instead of lines** (`ui/common/Glass.kt`). Thin glass
(rows, tiles, chips and buttons at rest), glass (panels) and thick glass (dialogs, sheets, big
panels), each a *smoky* tint — dark at partial opacity, never milky white, which is what keeps the
secondary ink at 4.5:1 over the brightest part of a frame. A lit edge (light on the top rim fading
to a faint line) separates a dark plate from a dark picture without a frame; three shadows give
depth. Drawers — the side menu, the playback and channel menus, Sport and News mode's menus — are
not glass: solid next to the screen's edge for two thirds of their width, fading to 75% over the
last third towards the content, never blurred.

**2. Blur only where it is cheap and real.** A `RenderEffect` blurs a layer's own content; the
video under the interface is a separate surface no layer of ours can read, and capturing what is
behind every panel would cost the frames a modest television box does not have. So the app blurs
in one place: behind a dialog's window, on Android 12 and up, where the compositor does it and the
device has cross-window blur on (`rememberWindowBlurBehind`). There the glass keeps its translucent
tint. Everywhere else a panel takes the canvas's own fallback, `rgba(22, 27, 40, 0.92)` — at the
viewer's overlay opacity (FR-UI-18), which it still obeys — with no change of layout.

**3. Focus on a television is light, in two treatments only** (`ui/common/Focus.kt`):

- *Controls* — buttons, chips, keys, menu rows and list rows that act like menu items — become the
  *lit surface* (`#F4F6FB`) with dark content, lift, and grow (1.06; 1.03 for a row the width of
  its panel).
- *Content tiles* — channel cards, posters, guide cells, station discs, the channel list's rows —
  become lit glass in a white ring, lift and grow; their text stays white, because the thing being
  chosen is the picture or the programme.

Focus moves on a spring that settles in about 220ms with a slight overshoot; panels arrive in
360ms and leave in 200ms. With the system's animations removed (animator duration scale zero),
nothing grows or rises and colours cross in 120ms.

**4. The colours mean one thing each.**

- *Current* — amber `#FFB547`: where you are and what is chosen. The current screen in the side
  menu (amber glyph and label, amber glass with an amber edge, and a short amber bar on the
  screen's edge), the selected day, chip or segment, the chosen value in a list.
- *Cue* — on a television the lit surface (focus); on a phone, which has no focus, amber again,
  marking what is active (the current tab, the primary action, a check, a switch that is on).
- *Tally* — red, only for what is on screen, live, or recording now, and always with a word: the
  live badge is a pill of dark red glass with a glowing dot and its label.
- *Next* — pale blue `#A9C8FF`. *OK* and *error* for outcomes. Everything else in three inks (white,
  80%, 64%).

Amber never marks a status, a category, a favourite, loading or a message; a favourite is a filled
star in ink.

**5. One typeface: Noto Sans Hebrew**, 400 to 800 (`ui/theme/Type.kt`). The display voice is the
same family at 800, not a second font. It ships the way ADR-019 shipped Rubik: `google/fonts`
publishes it only as a variable font (`ofl/notosanshebrew`, `wdth` and `wght`), so the five weights
are cut as static instances with `fontTools.varLib.instancer` at `wdth` 100 and subset to Latin +
Hebrew + punctuation — the app's floor is API 23, where a variable font draws every weight at its
default, which for this one is Thin. About 44 KB a weight, 220 KB in all. Noto has no Reserved
Font Name, so the cut files keep the family's name. The OFL ships at
`assets/licenses/noto-sans-hebrew-ofl.txt` (and `docs/licenses/`), with the credit in Settings →
About. The ramps are the canvas's: on a television hero 44, title 28, small title 20, h1 17,
action 14, nav 14, body 12, meta 11, caption 10 (dp, the 1080p pixels halved); on a phone 32, 26,
22, 18, 16, 12, 16, 14, 13.

**6. Shapes and metrics** (`ui/theme/Theme.kt`, `ui/common/TvMetrics.kt`). Buttons, chips and
badges are pills; a control 11dp (16 on a phone); a card and a row 12dp (20); a panel, a dialog
and a sheet 22dp (28); a guide cell 12dp (8 on a phone). The side menu's rail stays 56dp and opens
to a 190dp drawer (it was 240); its rows are 32dp. Safe areas are unchanged.

**7. The phone floats too.** The five tabs sit in a capsule of thick glass above the foot of the
screen, the current one amber; the mini player is a capsule of thick glass with an amber play
button; a channel row is thin glass, and the one on screen a wash of the tally with a red edge.

**8. The token names do not change.** `StudioColors`, `StudioType` and `StudioTheme` keep their
names and their API; new tokens (the glass fallback, the lit edge, current, the live badge's glass)
are added beside them. `docs/design/merhav/MIGRATION.md` maps every canvas token and component to
the code.

## Alternatives rejected

- **Keeping amber as the television's focus colour.** It is the colour the side menu now uses for
  "you are here", and a viewer has to read both at once in the open menu. The lit surface reads
  across a room over any picture, which a translucent amber does not.
- **Real backdrop blur everywhere** — by capturing the content behind each panel, or a library that
  does. It cannot reach the video surface, which is most of what is behind; it costs frames on the
  boxes this app runs on; and it would be a new dependency this environment cannot build against.
  The canvas itself names the fallback.
- **Milky (white) glass.** Fails 4.5:1 for the secondary ink over a bright frame.
- **Keeping Karantina for titles.** The canvas draws one family; a condensed display face is
  Studio's broadcast voice, and Noto Sans Hebrew is also the face Android falls back to for Hebrew,
  so an app string and a system string next to it now agree.
- **The variable font at runtime, or Downloadable Fonts.** ADR-019's reasons stand: Thin below API
  26, and legibility that would depend on Play Services and a network fetch.

## Consequences

- Every screen in both shells changes at once, through the theme and the shared components. A
  screen that sized its own text by multiplying a display step for Karantina now scales from the
  Noto step; the screens get their own pass as each board is redrawn in Merhav
  (`docs/design/merhav/MIGRATION.md`). Line heights tuned for Karantina's condensed lines were
  opened up.
- The bundled fonts drop from about 435 KB to about 220 KB. `TypefaceAssetsTest` now also checks
  that no bundled weight is a variable font.
- The overlay-opacity setting sets how opaque in-window glass is; drawers no longer follow it (solid
  by the edge, 75% at the content), which is the canvas's rule for them.
- The lift's white glow and the live dot's soft red glow are approximated or left out where Android
  has no coloured blur (shadows are dark below API 28); the lit surface and the words carry the
  state.
- Two places keep a treatment the canvas does not show: the settings slider's row stays a content
  row (lit glass in a ring), because its thumb is the lit colour; and a phone list row stays bare at
  rest rather than thin glass, because most of them sit inside a sheet that is glass already.
- The launcher icon is still drawn from Karantina's outlines (`docs/design/studio/ICON.md`), so
  `docs/licenses/Karantina-OFL.txt` stays; the web version (ADR-044) is not part of this change.
- As ever, none of this could be compiled where it was written (`CLAUDE.md`). CI is the gate.
