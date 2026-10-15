# ADR-028 — Dark and AMOLED only

**Status:** accepted. Changes FR-UI-17 and `docs/03` §3.6 ("Themes: system / light / dark /
AMOLED black").

## Context

Settings offered four themes: follow the system, light, dark, AMOLED black. The Studio language
(ADR-027) is drawn dark only — its canvas has no light screen, and its two lights (a red tally,
an amber cue) and its night-blue ground are chosen against a dark field. A light Studio would be
a second design nobody has drawn.

## Decision

Two themes remain:

- **Dark** — Studio as drawn. The default, as before.
- **AMOLED** — Studio on a true-black ground for OLED panels. The ground and the surfaces that sit
  flat on it go black; cards keep their blue, so focus and the tally read the same in both.

*Light* and *Follow system* are removed from Settings, from `ThemeMode`, and from the strings. A
stored value of either no longer parses, and the preference reader's existing fallback lands it on
Dark — no migration step is needed.

## Alternatives rejected

- **Keep light by mapping Studio's roles onto the old light scheme.** It would work mechanically
  and look undesigned: amber focus and a red tally on white were never checked for contrast
  (NFR-UX-02).
- **Dark only, AMOLED removed too.** AMOLED costs one `copy()` of the palette and is a real
  choice on OLED televisions and phones, where true black is both lower power and less prone to
  retention.

## Consequences

- Anyone on Light or Follow system sees the app dark after this update, without being asked.
- `docs/03` §3.6 now reads "Themes: dark / AMOLED black". If a light Studio is ever drawn, it
  comes back as a new `ThemeMode` and a new record.
