# TiviMate-copy — IPTV Player for Android & Google TV

Requirements repository for an **independent IPTV player application** targeting Android
phones/tablets and Android TV / Google TV.

TiviMate is used here only as a **functional benchmark**: the docs describe *what an IPTV
player of this class must do*, restated in original wording so they can be implemented
from scratch. No TiviMate source code, binaries, icons, artwork, layouts, strings or
branding are reproduced or reused in this repository or in the resulting app.

## Scope

| | |
|---|---|
| Target platforms | Android TV / Google TV (10-foot, D-pad), Android phones & tablets (touch), Fire TV (sideload) |
| Minimum Android | 6.0 (API 23) — TV variant may raise to 7.0 (API 24) |
| App type | **Player only.** The app ships no channels, no streams, no VOD. All content comes from a playlist the user supplies. |
| Primary input | M3U / M3U8, Xtream Codes API, Stalker / portal |
| Guide data | XMLTV, provider EPG (Xtream), embedded M3U EPG |

## Repository layout

```
docs/         Functional analysis, split by area
requirements/ Numbered, testable requirements (FR / NFR) + roadmap
```

| File | Contents |
|---|---|
| `docs/00-scope-and-platforms.md` | Product definition, device matrix, form-factor split |
| `docs/01-sources-playlists-epg.md` | Playlists, Xtream/Stalker, EPG sources, matching, offsets |
| `docs/02-playback.md` | Engine, decoders, protocols, tracks, subtitles, buffering, multi-view |
| `docs/03-ui-navigation.md` | 10-foot UI, guide layouts, remote mapping, touch UI, profiles, parental |
| `docs/04-recording-catchup.md` | DVR, scheduled/series recording, storage, catch-up/archive |
| `docs/05-settings-advanced.md` | User-agent, headers, external player, backup/restore, startup, diagnostics |
| `docs/06-licensing-and-tiers.md` | Free vs paid gating, device-count licensing, activation flows |
| `docs/07-architecture-android-googletv.md` | Leanback manifest, Media3, TIF/Watch Next, DRM, PiP, storage, policy |
| `docs/08-gaps-and-differentiators.md` | Known weaknesses of the benchmark → our differentiators |
| `docs/09-sources-and-confidence.md` | Where the analysis came from + confidence levels |
| `requirements/functional-requirements.md` | FR-* — numbered, testable, prioritized |
| `requirements/non-functional-requirements.md` | NFR-* — performance, reliability, compatibility, privacy |
| `requirements/roadmap.md` | Phased delivery plan (MVP → parity → differentiation) |

## Requirement conventions

- IDs: `FR-<AREA>-<nn>` / `NFR-<AREA>-<nn>`. IDs are **never reused or renumbered**.
- Priority: **MUST** (MVP), **SHOULD** (parity), **COULD** (differentiator), **WON'T** (out of scope for v1).
- Tier: `Free` / `Paid` — which entitlement level exposes the feature.
- Every FR must be phrased so it can be turned into an acceptance test.

## Legal position

- The app is a media player. It does not host, index, resell or bundle any channel,
  stream, or subscription.
- Store listing and in-app copy must contain no reference to obtaining content, and no
  provider names, to stay inside Google Play policy for streaming apps.
- Features and behaviours are not protected expression; **code, assets and layouts are**.
  Everything in this project is to be authored independently.

## Status

Requirements phase. No application code in this repository yet.
