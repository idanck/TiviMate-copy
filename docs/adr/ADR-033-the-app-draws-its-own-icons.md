# ADR-033 — The app draws its own icons

**Status:** accepted. Studio stage 9 (`docs/design/studio/MIGRATION.md`, round 2 §4). Completes
ADR-027's design language; no requirement in `docs/0*` changes.

## Context

Every glyph in the app came from Material's filled set: 38 different icons across 22 files,
pulled in through `material-icons-extended`. They are solid shapes at Material's weight, drawn
for a different system, and they disagreed with everything Studio puts beside them — the line
weight of its type, the hairlines of its panels, the round station mark. A solid star, a solid
gear and a solid calendar on one row also leave nothing to say "this one is on": in Material's
filled set everything is filled.

Studio's canvas draws a set of its own (`canvas/Icon.dc.html`, the "סט האייקונים" board on both
component pages): 43 glyphs, one 2-unit line on a 24-unit grid with round ends, solid only for
play, pause, record and a favourite that is set.

## Decision

1. **`ui/common/StudioIcons.kt`** holds the 43 glyphs as `ImageVector`s, built lazily from the
   canvas's SVG paths verbatim, stroked at 2 with round caps and joins, or filled for the solid
   four. Material's `Icon` draws them and tints them like any other vector.
2. **Every Material icon is replaced** by its Studio counterpart, using `MIGRATION.md` §4's map.
   Where the map predates a glyph the canvas later gained, the newer glyph wins: Material's
   `Storage` becomes `storage`, not `tv`; `ErrorOutline`, `Warning` and `SystemUpdate` become
   `alert` and `update`.
3. **Two glyphs follow the reading direction.** The canvas is drawn right to left, so its `back`
   points right and its `chevron-left` points left — toward the end of a Hebrew row. The app also
   runs in English (ADR-009), so these two are stored in their left-to-right form and marked
   `autoMirror` (`StudioIcons.Back`, `StudioIcons.ChevronEnd`). Media glyphs — rewind, forward,
   restart — are not mirrored: a player's arrows are the same in every language.
4. **`material-icons-extended` is no longer a dependency**, and `StudioComponentsTest` fails if a
   UI source imports Material's icons again.

## Alternatives rejected

- **Vector drawables in `res/drawable`.** Equally native, but a second copy of each path in a
  second format, edited by hand from the canvas and invisible to a code review of the screen
  using it. Kotlin builders keep the path next to its name and let `autoMirror` be a parameter.
- **Material's outlined set.** Closer in weight, still someone else's drawing, and the canvas
  already specifies every glyph the app uses.
- **Keeping the extended dependency "just in case".** R8 strips the unused icons from the APK, but
  the dependency is one of the heaviest in the build and its only remaining use would be a
  regression.

## Consequences

- Line glyphs read lighter than the filled ones did at the same size. The canvas's sizes (30, 26
  and 22px at 1080p; 24, 22, 20 and 18 on a phone) are applied screen by screen in stages 10 and
  11; until then each icon keeps the size its screen already gave it.
- A favourite is now told from a non-favourite by fill as well as colour (`star-on` against
  `star`), so the difference is not carried by colour alone.
- As ever, none of this could be compiled where it was written (`CLAUDE.md`). CI is the gate.
