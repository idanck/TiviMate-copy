# ADR-032 — The phone's tabs: recordings, not on-demand

**Status:** accepted. Amends the bottom navigation in `docs/03` §3.5, and reverses the change that
hid Recordings from the phone's tab bar.

## Context

The phone's bottom bar held Live, Radio, Guide, VOD and Settings. Recordings had a tab once and
was taken off it to keep the bar at five; the screen stayed, reachable from the television only.
Meanwhile ADR-016 established that this installation has no on-demand content: the Xtream client
exists, but no source here offers films or series, so the VOD tab opened onto an empty grid.

Studio's phone canvas (`docs/design/studio/canvas/PNavBar.dc.html`) draws five tabs — channels,
guide, radio, recordings, settings — and `MIGRATION.md` records it as decided: **recordings takes
on-demand's place.**

## Decision

1. The tabs are, in this order: channels (`MobileTab.LIVE`), guide, radio, recordings, settings.
   The current tab's icon sits in a cue pill; the others are ink-2 (`ui/mobile/PhoneChrome.kt`).
2. On-demand loses its tab but not its screen. Search still lists on-demand titles where a source
   has them, and opening a series from there shows `VodScreen` with no tab selected; Back returns
   to channels. A VOD tab restored from an earlier build is sent to channels.
3. Recordings on the phone is the shared `RecordingsScreen`, as on the television.

## Alternatives rejected

- **Six tabs.** Five is where labels stop fitting at 390dp in Hebrew; the canvas draws five.
- **Removing on-demand altogether.** An installation with an Xtream source that does carry it
  would lose its only way in. Behind search it costs nothing where it is empty.

## Consequences

- `docs/03` §3.5 now reads: bottom navigation Channels / Guide / Radio / Recordings / Settings;
  on-demand, where a source has it, is reached through search.
- The phone's chrome — top bar, tabs and mini player — is Studio's from this change (ADR-027,
  stage 5).
