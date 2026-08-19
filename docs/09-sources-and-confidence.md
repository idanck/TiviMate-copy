# 09 — Sources and Confidence

## 9.1 How this analysis was produced

The reference product publishes no exhaustive official feature list or comparison table.
The functional picture here was assembled from the store listing, official/affiliated FAQ
and feature pages, community user guides, version-history sites, reviews and forum
threads, then restated in original wording. Nothing here is copied from the product's
code, assets or marketing copy.

## 9.2 Confidence levels

**High confidence** — corroborated by the store listing and multiple independent guides:

- Android-only; Android TV / Google TV / Fire TV / phones; minimum Android 6.0 on current
  builds; package `ar.tvplayer.tv`; developer Armobsoft FZE / AR Mobile Dev.
- Support for M3U/M3U8, Xtream Codes API and Stalker portal.
- Free vs paid gating of: multiple playlists, recording, catch-up, multi-view, PiP,
  custom groups, parental PIN, custom user-agent, multiple EPG sources, backup/restore,
  auto frame rate, manual sorting, startup channel, audio passthrough.
- Companion-app-based licence activation with a 5-device cap.
- Per-channel hardware/software decoder toggles, buffer presets, EPG time offsets
  (global and per-channel), manual channel-to-EPG mapping.
- Recording to TS files on internal or USB storage, with auto-delete retention.

**Medium confidence** — consistent across several sources but not officially documented:

- The engine is ExoPlayer/Media3-based.
- Exact pricing (≈$2.99/month, $9.99/year with a 7-day trial, $33.99 lifetime, mid-2026)
  — varies by region and changes over time.
- Current build 5.3.3 (June 2026).

**Low confidence / unresolved** — treat as unknown, do not design around:

- Whether the free tier displays ads.
- The exact free-tier EPG window (1 vs 2 days).
- Whether external-player support is gated.
- Maximum multi-view panel count (sources say 4, 5 or 9).
- MPEG-DASH and RTMP support specifically.

## 9.3 Implication for this repository

Where the benchmark's behaviour is uncertain, **we choose our own behaviour** and record
it as a requirement rather than guessing at theirs. This document exists so that a
requirement is never justified by "because TiviMate does it" when we do not actually know
that it does.

## 9.4 Verification backlog

Before MVP freeze, verify empirically on real devices rather than from documentation:

- [ ] Concurrent `MediaCodec` instance limits on target TV boxes (drives multi-view max).
- [ ] Whether target boxes can be woken by `AlarmManager` from standby (drives DVR promises).
- [ ] Real channel-change latency achievable with a minimal buffer on MPEG-TS.
- [ ] HEVC/4K hardware decode coverage across the reference device set.
- [ ] Behaviour of SAF-based writes to USB storage on Fire OS.
- [ ] Current Google Play policy text for TV apps and for streaming-player apps.
