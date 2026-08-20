# 05 — Settings and Advanced

## 5.1 Network and request shaping

- **Custom User-Agent**, global and per-playlist. This is the single most common fix for
  403/404/503 responses from providers that filter by UA. Presets (VLC, Kodi, Lavf,
  common TV UAs) plus free text.
- **Custom HTTP headers** per playlist (Referer, Origin, Cookie, Authorization) — goes
  beyond the benchmark, which mainly exposes UA.
- Per-channel overrides inherited from `#EXTVLCOPT` / `#KODIPROP` in the M3U.
- Connection timeouts and retry counts, configurable.
- Redirect following, and an option to trust self-signed certificates (off by default,
  with an explicit warning).
- No proxy UI in v1; system/VPN routing is respected. Document that some providers block
  VPNs and some VPNs break playback — an in-app diagnostic should detect an obvious
  geo/IP block and say so.

## 5.2 External player

- Hand off playback to MX Player, VLC, Just Player, etc. via a standard implicit intent
  with title and headers where the target supports them.
- Configurable scope: never / VOD only / always / per-channel.
- Useful as an escape hatch when our decoder pipeline fails on an exotic stream.

## 5.3 Startup and lifecycle

- Auto-start on device boot (TV).
- Auto-start on wake from standby.
- Start behaviour: last channel / a specific channel / channel list / guide.
- Sleep timer (15/30/60/90/120 min, or "after this programme"), with a warning prompt
  before it fires.
- Exit confirmation on second Back press, configurable.
- Screensaver / burn-in protection: dim or hide static overlays after N minutes on a
  paused or static stream.

## 5.3a Interface language and channel organisation

- **Interface language** (FR-UI-23): follow the device, English, or Hebrew — chosen in the app,
  not by switching the whole device. Choosing a right-to-left language mirrors the layout and the
  remote's horizontal bindings with it. Applied by wrapping the base context and recreating the
  activity; `docs/adr/ADR-009` records why, and why the AppCompat route was rejected. The set of
  languages is bounded by `resourceConfigurations` in the build file.
- **Channel order** (FR-ORG-06): the playlist's own order, A–Z, most watched, or recently
  watched. Manual moves are stored in `sort_index`, which only the first of those reads, so
  switching away parks a hand-made order rather than losing it.
- **Show groups your provider adds later** (FR-ORG-11): applied when a group is first created by
  a refresh. A group the user has since chosen to show is never hidden again.
- **Hidden channels**: hiding is what "remove" means here, and it is reversible — hidden channels
  keep a view of their own in the channel list, restorable one at a time or all at once.
  `docs/adr/ADR-010` records why there is no delete.

## 5.4 Backup, restore and sync

- **Local backup/restore** of the full configuration (playlists, groups, favourites,
  ordering, EPG mappings, per-channel settings, key bindings, appearance) to a single
  portable file, with an option to include or exclude credentials.
- Backup targets: local file, SAF-picked location, Google Drive, SMB share.
- **Account-based cloud sync** — the real differentiator. Optional, opt-in, end-to-end
  encrypted where credentials are involved, with conflict resolution and a
  "this device wins / that device wins" prompt. Syncs favourites, groups, ordering,
  resume points, watch history and settings across the user's devices in near real time.
  The benchmark offers manual backup files only.
- Import from a competitor's backup format — **COULD**, a strong switching incentive,
  but only where the format is documented or trivially inspectable; no reverse
  engineering of protected formats.

## 5.5 Diagnostics

- A **Diagnostics** screen showing: playlist last-refresh result, EPG match statistics,
  current stream URL (redacted credentials), negotiated codecs and decoder type,
  bitrate, dropped frames, buffer health, and the last N errors.
- "Copy diagnostic report" that redacts credentials before sharing.
- Cache management: show cache size, clear EPG cache, clear image cache, clear all.
  Cache growth must be bounded by policy, not left to grow unattended — unbounded cache
  is a documented complaint about the benchmark.
- Optional verbose logging to a rotating local file, off by default.

## 5.6 Notifications and reminders

- Programme reminders from the guide, with configurable lead time and an
  "auto-switch to channel" option.
- Recording notifications: started, finished, failed.
- Account/subscription expiry warning from Xtream account info.

## 5.7 Settings that must NOT exist

- Anything that fetches, aggregates or suggests content sources.
- Any bundled default playlist.
- Any "community" list of provider URLs.
