# ADR-003 — The recorder opens its own HTTP connection

**Status:** accepted

## Decision

`RecordingService` opens a second HTTP connection to the stream and writes the bytes straight
to disk, independently of the playback pipeline.

## Context

The alternative is to tap the ExoPlayer data source and tee the bytes. That saves one provider
connection, and costs:

- the recording becomes coupled to what is on screen — changing channel would end it;
- the bytes written are whatever the player asked for, so a rendition switch mid-recording
  produces a file that not every player can open;
- the recorder cannot run when the UI process is not playing anything, which is precisely the
  failure mode `docs/08` row 3 identifies in the benchmark.

## Consequence

A recording consumes one provider connection for its whole duration. That is stated in the
programme details dialog and in Settings rather than left for the user to discover. In
exchange the output is a byte-exact MPEG-TS that any third-party player opens (FR-REC-10), and
recording is genuinely independent of the UI (FR-REC-14).
