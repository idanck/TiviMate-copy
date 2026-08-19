# ADR-006 — Hand-written SQLite instead of Room

**Status:** accepted

## Decision

The data layer uses `SQLiteOpenHelper` with hand-written SQL and explicit indices, rather than
Room as sketched in `docs/07` section 7.8.

## Context

Room would generate the same SQL from annotations, at the cost of an annotation processor
(KSP) whose version must track the Kotlin version exactly. The requirement Room was chosen for
is a fast, indexed, paged guide store — not Room itself.

The guide access patterns here are narrow and known: one windowed range query per distinct EPG
offset, plus point lookups for now/next. They are written once, in `Dao`, and are easier to
reason about as SQL than as generated queries.

## Consequence

- The project builds from a clean checkout with nothing but the Gradle wrapper and the Android
  SDK. No annotation processing, so no Kotlin/KSP version coupling.
- Migrations are explicit and additive in `Database.MIGRATIONS`, and `onUpgrade` never drops a
  table holding user state (NFR-REL-05).
- The cost is that schema changes are manual. That is acceptable for a schema this size, and
  the additive-only rule keeps the risk low.
