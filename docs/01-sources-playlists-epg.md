# 01 — Sources, Playlists and EPG

## 1.1 Supported source types

| Type | Input the user provides | What we get |
|---|---|---|
| **M3U / M3U8** | URL or local file | Channel list, group titles, logos, tvg-id; sometimes an embedded EPG URL (`url-tvg`) |
| **Xtream Codes API** | Server URL + username + password | Live channels, VOD, series, EPG, catch-up windows, account expiry — all auto-configured |
| **Stalker / portal** | Portal URL + MAC | Channel list, EPG, catch-up (portal-dependent) |

Xtream is the richest path and should be the recommended onboarding route. M3U is the
lowest common denominator and must always work.

### M3U attributes to parse
`tvg-id`, `tvg-name`, `tvg-logo`, `tvg-shift`, `group-title`, `catchup`, `catchup-source`,
`catchup-days`, `user-agent`, `#EXTVLCOPT:` (http-user-agent, http-referrer), `#KODIPROP:`
(licence/DRM hints). Unknown attributes must be preserved, not discarded, so a later
version can use them.

### Xtream endpoints to consume
`player_api.php` for account info, live categories/streams, VOD categories/streams/info,
series categories/info, and short EPG; `xmltv.php` for the full guide. Stream URLs are
built per output format. Output format must be user-selectable between **MPEG-TS**
(default, lowest latency) and **HLS** (better on flaky networks and some devices).

## 1.2 Multiple playlists

- Unlimited playlists (paid tier); one playlist on the free tier.
- Each playlist has: display name, icon/colour, type, credentials, output format,
  user-agent override, auto-update interval, enabled/disabled flag.
- Playlists are kept **distinct** — the user switches between them; channels are not
  silently merged into one namespace. A cross-playlist *unified favourites* view is a
  differentiator we should add (see `08`).
- Deleting a playlist must offer to preserve favourites/groups in case it is re-added.

## 1.3 Playlist refresh

- Manual refresh, and scheduled auto-update (off / 6h / 12h / daily / weekly / custom).
- Refresh must be **non-destructive**: user-created groups, ordering, hidden flags,
  favourites, EPG mappings and per-channel settings survive a refresh, keyed on a stable
  identity (tvg-id → stream-id → normalized name, in that order).
- Report a diff after refresh: N added, N removed, N renamed. Newly added groups are
  hidden or shown according to a user setting.
- Never wipe local state when the source returns an empty or error response — treat as
  a failed refresh and keep the previous snapshot.

## 1.4 Channel organization

- Provider-supplied groups plus **user-created custom groups** (paid).
- Hide/show individual channels and entire groups.
- Rename channels and groups locally without affecting the source.
- Manual reordering of channels and groups (drag on touch, move-mode on D-pad), plus
  sort presets (provider order, A–Z, most watched, recently watched).
- Favourites, spanning live channels and (where available) VOD/series.
- Channel numbers: provider-supplied (`tvg-chno`) or auto-assigned, editable, used for
  direct numeric zapping.
- Logos from `tvg-logo` / Xtream / EPG icon; local cache; fallback to a generated
  monogram tile. Optional user-supplied logo per channel.

## 1.5 EPG sources

- **XMLTV** by URL (`.xml`, `.xml.gz`, `.zip`) or local file.
- **Provider EPG** via Xtream (`xmltv.php` / `get_short_epg`).
- **Embedded** `url-tvg` from the M3U header.
- **Multiple EPG sources per playlist with priority order** (paid): a secondary public
  XMLTV fills gaps left by the provider guide. Higher-priority source wins on conflict.
- Scheduled EPG auto-update with configurable interval; manual "Update guide now".
- Retention: keep at least 7 days forward where the source provides it, and keep past
  programmes for as long as catch-up windows allow (needed to render the archive).

## 1.6 EPG matching

This is where competing apps most visibly fail. Matching pipeline, in order:

1. Exact `tvg-id` ↔ XMLTV `channel id`.
2. Case/whitespace/diacritic-normalized name match.
3. Fuzzy match with a confidence score, after stripping common noise tokens
   (`HD`, `FHD`, `4K`, `UHD`, `SD`, country prefixes, `|`, `:`, bracketed suffixes).
4. **Manual mapping UI** — the user binds a channel to any EPG entry, searchable. Manual
   mappings are sticky and survive refreshes.

Surface a diagnostic screen: how many channels matched, how many are unmatched, one-tap
jump to map the unmatched ones. Channels with no guide must display a clear
"no guide data" state rather than an empty row.

## 1.7 Time handling

- Parse XMLTV timestamps with their offsets; store everything in UTC; render in the
  device timezone.
- **Global EPG time offset** in ± 15-minute steps, and **per-channel offset** override.
- Per-channel offsets must persist independently — a known defect in the benchmark is
  per-channel offsets reverting when other channels' EPG changes. This must be covered
  by a regression test.
- Handle DST transitions correctly: the guide must not shift by an hour twice a year.
- Honour `tvg-shift` from the M3U when present, as an initial value the user can override.
