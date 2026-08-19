# ADR-002 — One adaptive application module, not `:app-tv` plus `:app-mobile`

**Status:** accepted

## Decision

A single `:app` module that picks a UI shell at runtime.

## Context

`docs/00` section 0.5 sketches a multi-module split and section 0.5 already states that a
single installable artifact is preferred if the TV form factor still passes review. The
deciding argument is behavioural rather than structural: `docs/03` section 3.1 requires that a
channel hidden on the phone is hidden on the TV. With one module and one view model there is a
single source of truth and no synchronisation code at all.

The layer separation the multi-module sketch was protecting is preserved by package structure:
`core`, `data`, `player` and `record` contain no Compose or UI types, which is what
NFR-MAINT-01 actually asks for.

## Consequence

`MainActivity` chooses `TvRoot` or `MobileRoot` from `FEATURE_LEANBACK` / absence of a
touchscreen. One APK installs on both. If Play review later demands a separate TV listing, the
split is a build-flavour change, not a rewrite.
