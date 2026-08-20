# ADR-010 — Removing a channel means hiding it

**Status:** accepted

## Decision

The per-channel options offer **Hide**, not Delete, and there is no `Dao.deleteChannel`. Hidden
channels stay in the database, drop out of every list, and are restored from a Hidden view of
their own — one at a time or all at once (FR-ORG-13).

## Context

Users ask to remove channels they will never watch, and a playlist of ten thousand entries makes
that reasonable. The obvious reading is a delete.

A delete does not survive the next refresh. `SourceRepository.merge` keys on a channel's stable
identity and treats an absent row as new, so a channel the provider still lists is re-imported —
and re-imported *fresh*, with the favourite, custom name, number, position, guide binding and
decoder choice the user gave it all reset. The channel comes back and takes the user's work with
it. A control that behaves that way is a bug wearing a feature's label.

Making a delete durable needs somewhere to record that this identity was deleted — a tombstone
table or a column — consulted by `merge`. That is a schema change, a migration and a new failure
mode, in service of a capability no requirement asks for.

`hidden` already exists, is already excluded from every list query, is already carried through a
refresh by `merge` leaving user-owned fields unnamed, and is already round-tripped by
`BackupManager`.

## Consequence

- Nothing in this change touches the schema. `Database.VERSION` stays at 1 and `MIGRATIONS` stays
  empty, which removes the highest-risk class of change from work that cannot be compiled
  locally.
- Hidden channels keep occupying rows. Ten thousand hidden channels cost what ten thousand
  visible ones cost; the queries that matter are indexed on `(playlist_id, group_id, sort_index)`.
- The label says "Hide", not "Delete", and the confirmation says where the channel went. A
  control that quietly did less than its label would be the same dishonesty in the other
  direction.
- Hiding a whole group hides its channels too, via a subquery on `channel_groups`. Groups number
  in the tens, so the cost is negligible; if a pathological playlist proves otherwise, resolve
  the hidden set in Kotlin and pass an `IN` list instead.
