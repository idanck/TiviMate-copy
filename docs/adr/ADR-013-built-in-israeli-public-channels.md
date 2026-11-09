# ADR-013 — One bundled playlist: Israel's free-to-air public channels

**Status:** accepted

## Decision

TiviCopy seeds a single fixed playlist on first cold start — Kan 11, Kan Educational, Makan 33,
Channel 12, Channel 13, Channel 14, and the Knesset Channel — pointed at a curated `channels.m3u`
and `guide.xml` hosted at `il-epg.idanck.com`, a dedicated Cloudflare Worker + R2 bucket
(`il-epg-host`) separate from the app's own update host. It is inserted through the ordinary
playlist pipeline (`SourceRepository.ensureBuiltInPlaylist`,
`data/builtin/IsraelPublicChannels.kt`) and refreshed by the existing `RefreshWorker` — no new
subsystem, no schema change.

This is the one deliberate exception to `docs/00-scope-and-platforms.md` §0.1 ("no default/bundled
provider") and `docs/05-settings-advanced.md` §5.7 ("no bundled default playlist"). It is narrow on
purpose:

- **Personal, non-store software.** The Google Play provider-bundling policy those sections guard
  against does not apply to a build that is never distributed through a store.
- **Free-to-air public broadcasters only**, not a general content-source feature — the channels
  bundled are already free and open to the Israeli public over the air; nothing paid or
  general-purpose is included.

## Alternatives rejected

- **A general "add curated source" feature.** Would reopen exactly the provider-bundling/content-
  discovery concern §0.1 and §5.7 exist to close off. Rejected in favour of one hardcoded playlist.
- **Pointing directly at `iptv-org/iptv`'s full `il.m3u`.** That list carries ~40 Israeli channels,
  most of them commercial/pay-TV, not just the 7 free-to-air ones. A small Pi-side script filters
  it down to the 7 known `tvg-id`s before publishing, so the app only ever sees the curated subset.
- **Reusing the existing `iptv-epg` pipeline on the Pi** (a separate, pre-existing project built
  around a commercial IPTV subscription). Rejected to keep this feature's legal footing — free-to-
  air only — independent of that unrelated project's.
- **Fetching and refreshing inside `ensureBuiltInPlaylist` itself.** A freshly inserted `Playlist`
  keeps `lastRefreshUtc` at its `0` default, which `RefreshWorker` already treats as immediately
  due, so bootstrap only needs to insert a row — no network call, no bespoke failure handling, and
  no network dependency in the unit tests that pin this behaviour.

## Consequences

- If the user deletes the built-in playlist from Settings, the next cold start silently reinstates
  it — intentionally self-healing, no "locked" flag or first-run preference to maintain.
- EPG and stream-URL freshness depend on a cron job on the Pi (`iptv-epg-il/sync.sh`,
  twice daily) that re-filters `iptv-org/iptv`'s `il.m3u` and re-runs `iptv-org/epg`'s `yes.co.il`
  grabber for the 7 channels, publishing both to the Worker. That job lives outside this repository.
- Channel logos are not supplied by any upstream feed, so they are hosted alongside the playlist
  and EPG on the same Worker (`il-epg.idanck.com/logos/*.png`), sourced from Wikimedia Commons and
  the Knesset's own site.
- 0.4.4 shipped pointing at `updates.idanck.com/il-epg/*` — an address on the update host that was
  never created, because the decision to use a dedicated host landed after those constants were
  written. Every install showed the playlist as `NotFound` until 0.4.5. Two things follow: the
  Worker now answers on a domain of its own rather than a `workers.dev` name that a DNS filter
  could quietly swallow, and `IsraelPublicChannels.LEGACY_M3U_URLS` records superseded addresses so
  bootstrap corrects an existing row in place instead of stranding it beside a new one. Any future
  move of this host must add the outgoing URL to that list.
- **2026-10-27: i24NEWS and Channel 16.** The owner had i24NEWS (Hebrew; `i24NEWSHebrew.il@SD`,
  guide from `yes.co.il`'s `CN28`) and Channel 16 (`Channel16.il@SD`, no guide source found) added
  to the Pi-side filter, so the playlist now carries nine channels. Both are open, unencrypted
  streams on iptv-org's Israeli list; their logos sit beside the others on the Worker. Only the
  Pi's `build_channels_m3u.py` and `config/il.channels.xml` changed, outside this repository.
- **2026-11-09: Channel 9, Hala TV and Channel 10 Kalkala; the provider list drops the duplicates.**
  The owner had the two remaining Idan+ channels added — Channel 9 (`Channel9.il@SD`, guide from
  `yes.co.il`'s `TV50`) and Hala TV (`HalaTV.il@SD`, `PT63`; iptv-org marks the stream "Not 24/7"
  and `yes.co.il` returned no programmes for it on the first grab) — plus Channel 10 Kalkala, which
  is free but online-only. iptv-org does not carry Channel 10, so the Pi script appends it from an
  `EXTRA` list (`Channel10Kalkala.il@SD`, the official Redge CDN stream its own player uses, guide
  from `CN32`). The playlist now carries twelve channels. Logos for the three come from Wikimedia
  (Commons for Channel 9, the Hebrew Wikipedia infobox SVGs for the other two). In the same change
  the owner had the separate provider pipeline (`iptv-epg/channel_map.json`, `"exclude": true`)
  drop the twelve provider entries that duplicate free channels — Channel 9 / 9 HD, 11, 12, 13,
  14, i24NEWS, Makan, Hala TV and the HOT copies of 9, 12 and 13 — from every playlist it writes,
  Emby's included. Only Pi-side files changed, outside this repository.
