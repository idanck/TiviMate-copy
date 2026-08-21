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
- **Reusing the existing `/home/idan/iptv-epg` Pi pipeline** (a separate, pre-existing project built
  around a commercial IPTV subscription). Rejected to keep this feature's legal footing — free-to-
  air only — independent of that unrelated project's.
- **Fetching and refreshing inside `ensureBuiltInPlaylist` itself.** A freshly inserted `Playlist`
  keeps `lastRefreshUtc` at its `0` default, which `RefreshWorker` already treats as immediately
  due, so bootstrap only needs to insert a row — no network call, no bespoke failure handling, and
  no network dependency in the unit tests that pin this behaviour.

## Consequences

- If the user deletes the built-in playlist from Settings, the next cold start silently reinstates
  it — intentionally self-healing, no "locked" flag or first-run preference to maintain.
- EPG and stream-URL freshness depend on a cron job on the Pi (`/home/idan/iptv-epg-il/sync.sh`,
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
