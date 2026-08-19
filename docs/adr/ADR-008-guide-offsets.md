# ADR-008 — Guide offsets shift the query, not the stored rows

**Status:** accepted

## Decision

A global or per-channel EPG offset is applied when reading the guide, by shifting the query
window and then shifting the returned programme times back. Programme rows in the database are
never rewritten.

## Context

`docs/08` row 7 records two defects in the benchmark: per-channel offsets that revert, and a
guide that shifts by an hour at a DST transition. Both follow from the same root cause —
storing corrected local times instead of instants.

Several channels routinely share one XMLTV entry (a provider lists `Channel HD` and
`Channel FHD` against the same guide id). If an offset were written into the programme rows,
correcting one of those channels would silently move the other, and the next guide refresh
would overwrite the correction. That is exactly the reported "offset reverts" behaviour.

## Consequence

- `FR-EPG-12` holds by construction: a per-channel offset cannot be disturbed by any other
  channel's changes, because nothing writes to the shared rows.
- `FR-EPG-13` holds because only instants are stored; a zone's DST rules are applied at render
  time by `java.time`.
- `EpgRepository.guideWindow` groups channels by offset so each distinct offset costs one
  windowed query rather than one query per channel.
