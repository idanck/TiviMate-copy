# ADR-022: The navigation rail floats, and radio is round

## Status

Accepted.

## Context

ADR-020 rebuilt the ten-foot home screen around a full-bleed banner with a collapsing icon rail
down the inner edge. That shape works, but two things about it were never designed so much as
inherited:

- **The rail was a full-height flat column.** It ran the whole height of the panel at a single
  alpha, and the list beside it was inset by exactly its collapsed width. So the screen read as
  two panes — a strip and everything else — rather than as one surface. On a screen whose entire
  premise is that the video never stops, a permanent opaque column down one side is the piece of
  furniture that most makes it look like an app drawn over a television rather than a television.
- **Radio looked exactly like television.** The bundled Israeli radio playlist (ADR-015) is
  browsed through the same `ChannelListScreen` and appears on the home screen through the same
  card rail, drawn with the same 16:9 stills and the same rounded-square logo plates. Nothing on
  screen told a viewer which of the two lists they were in, and the audio-only Now Playing screen
  (the station backdrop) drew the station's mark as a cropped square thumbnail floating on a
  colour taken from it.

A round of design exploration produced three directions for the shell — a quiet cinematic one
whose navigation is a floating strip over fullscreen video, a content-forward one that keeps the
banner and enriches the rails, and a structural one that collapses seven destinations into four
top tabs. This ADR records what was taken from them.

## Decision

**The navigation rail is a floating capsule.** It is wrapped to its own content, centred
vertically, rounded, inset from the panel edge, and given a hairline border. It still expands over
the screen rather than beside it, so nothing reflows as focus crosses into it, and it still
collapses to icons with its labels carried as `contentDescription` at all times (NFR-UX-05).

Making it float forced a second change that is the actual substance of this one: **the rows of the
home screen are now full-bleed, and each keeps the rail's room clear in its own content padding.**
Previously the gutter was painted by the rail itself, because the rail was full height. A capsule
paints only where it is, so a list still inset by the rail's width would leave a column of bare
video running down the side of every rail, above and below the capsule — the same complaint
ADR-020 was written to fix. Full-bleed rows with inset content give the capsule an unbroken
backdrop to float on, and the arithmetic is width-neutral: what the cards get is unchanged.

**Radio is round, everywhere it appears.** `ChannelLogo` grew a `circular` option, taken by the
radio channel list and by the station backdrop; the home screen's radio rail draws its art as a
disc inside the same 16:9 box every other rail uses, so rail heights do not step. A logo on a
round plate is inset, because the largest square inside a circle is smaller than the circle — a
wordmark fitted edge to edge loses its first and last letters to the clip.

**The station backdrop says what it is.** The mark is a disc ringed with a halo of the backdrop's
own light; opposite the clock there is a *Live* badge, shown only for a stream that is both live
and not paused — this screen deliberately survives a pause, and catch-up off a radio station
reaches it too; and the second line names the programme when the player has one, which on
catch-up it does.

**The structural direction was not taken.** Collapsing the destinations into top tabs conflicts
with the rail rather than composing with it, and the radio category chips that direction wanted
already exist in `ChannelListScreen`. It stays available if the rail ever stops earning its place.

## Consequences

- One card on a rail now says which channel is playing, rather than leaving that to the focus
  ring — a rail scrolled past the focused card has no ring in view.
- `SectionHeader` pads its own label, so a rail heading subtracts that inset to line up with the
  cards beneath it. Before the rows were full-bleed the same alignment fell out of a bare `12.dp`
  that quietly encoded it; it is now named.
- `AudioOnlyNowPlaying` takes a `live` flag rather than inferring one. Both shells pass it, and
  the default is off: a caller that has not thought about it makes no claim rather than a wrong
  one.
- Nothing here can be compiled in the environment it was written in (see CLAUDE.md), so CI is the
  gate, as always.
