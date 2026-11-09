# 00 — Scope and Platforms

## 0.1 What the product is

A live-TV-first IPTV player. The user supplies a subscription (playlist URL or Xtream
credentials); the app turns it into a cable-like experience: channel list, electronic
programme guide, favourites, catch-up, recording, VOD/series browsing.

The app is **content-neutral infrastructure**. It ships with zero channels, performs no
content discovery of its own, and has no default/bundled provider — with two narrow,
deliberate exceptions: a single fixed built-in playlist of Israel's free-to-air public
channels (Kan 11, Kan Educational, Makan 33, Channel 12, Channel 13, Channel 14, the
Knesset Channel, Channel 9 and Hala TV, plus the free streams of i24NEWS, Channel 16 and
Channel 10 Kalkala), and a single fixed built-in playlist of curated Israeli radio stations,
audio-only. Both exist only because the app is personal software that never goes through
a store — the store-policy concern this section otherwise guards against does not apply —
and both are scoped narrowly: free-to-air public broadcasters and a curated radio list,
not a general-purpose provider list. See
`docs/adr/ADR-013-built-in-israeli-public-channels.md` and
`docs/adr/ADR-015-built-in-israeli-radio-and-android-auto.md` for the rationale.

The radio playlist is also browsable and playable through Android Auto, distributed
sideload-only (see ADR-015) — this app is not submitted for Google's Android-Auto-specific
Play Store review.

## 0.2 Benchmark summary

The reference product (TiviMate, package `ar.tvplayer.tv`, developer Armobsoft FZE /
AR Mobile Dev) is Android-only, remote-control-first, freemium, and licensed per
account with a 5-device cap. Current observed build at time of writing: 5.3.3
(mid-2026), minimum Android 6.0. It is the de-facto quality bar for Android TV IPTV
playback, and its weaknesses (see `08-gaps-and-differentiators.md`) define our
opportunity.

## 0.3 Device matrix (our targets)

| Class | Examples | Input model | Priority |
|---|---|---|---|
| Android TV / Google TV boxes | Chromecast with Google TV, Nvidia Shield, Mi Box, Onn | D-pad remote | P0 |
| Google TV / Android TV built-in | Sony, Philips, TCL, Hisense | D-pad remote | P0 |
| Android phone | any | Touch | P0 |
| Android tablet | any | Touch, larger layout | P1 |
| Amazon Fire TV / Firestick (Fire OS) | Stick 4K, Cube | D-pad remote, sideload | P1 |
| Chromecast receiver (cast target) | — | — | P3 / out of v1 |

Out of scope for v1: iOS, tvOS, Roku, Samsung Tizen, LG webOS, Windows, macOS, web.

**Deliberate divergence from the benchmark:** TiviMate runs on phones but is explicitly
not touch-optimized. We treat the phone/tablet touch UI as a **first-class P0 target**,
not a side effect. This is the single clearest product gap to exploit.

## 0.4 Minimum OS

- Baseline: **Android 6.0 (API 23)** for maximum device reach on older TV boxes.
- Acceptable alternative if it materially simplifies Media3/PiP/scoped-storage work:
  **Android 7.0 (API 24)** — PiP on TV arrives at API 24, which matters for a headline
  feature. Decision to be recorded as an ADR before MVP freeze.
- Fire OS: map to the equivalent Fire OS 6+ (Android 7-based) generation.

## 0.5 Form-factor split

One codebase, two presentation layers, shared domain and playback layers.

```
:app-tv        Leanback / D-pad UI
:app-mobile    Touch UI (or single app module with runtime UI selection)
:core-domain   Playlists, channels, EPG, entitlements, scheduling
:core-data     Parsers (M3U, Xtream, Stalker, XMLTV), DB, sync
:core-player   Media3 wrapper, decoders, tracks, buffering, recording sink
:core-ui       Shared theming/design tokens
```

A single installable artifact that adapts at runtime is preferred over two separate
store listings, provided the TV form factor still passes Play's TV review.

## 0.6 Explicit non-goals for v1

- Providing, aggregating, or recommending content sources.
- Acting as a TV Input Framework (TIF) input so channels appear in the system Live TV
  app — evaluated in `07`, but deferred (DVR/timeshift via TIF generally require
  system-app privileges).
- Torrent, Real-Debrid, or scraper integrations.
- Desktop or browser clients.
