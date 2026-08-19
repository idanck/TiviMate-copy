# Delivery Roadmap

Three phases. Each phase is shippable on its own; nothing in a later phase is a
prerequisite for an earlier one.

---

## Phase 1 — MVP: "it plays, on both form factors"

**Goal:** a person with an Xtream or M3U subscription can install it, add their source,
and watch TV reliably on a TV *and* on a phone.

**Scope:** all `MUST` requirements in
`FR-SRC-*`, `FR-ORG-*`, `FR-EPG-*`, `FR-PLB-*` (excluding PiP/multi-view/AFR),
`FR-UI-*`, `FR-PRF-04..05, 08`, `FR-CU-*`, `FR-NET-01`, `FR-SET-08, 12, 15`,
`FR-LIC-01`, `FR-TVI-01, 06`.

Headline items:
- M3U + Xtream ingestion, non-destructive refresh.
- XMLTV EPG with the full matching pipeline and manual mapping UI.
- Leanback TV UI: playback root, channel overlay, mini guide, full grid guide, search.
- Touch mobile UI: bottom nav, channel list, gestures, portrait/landscape.
- Media3 playback with per-channel decoder toggle, buffer presets, track selection,
  aspect ratio, failover.
- Catch-up.
- Favourites, custom groups, hide, reorder.
- Parental PIN.
- Local backup/restore.

**Exit criteria:** `NFR-PERF-01..04`, `NFR-REL-01`, `NFR-COMPAT-02..03`, `NFR-UX-01..06`
all met on the reference device set.

**Course-correction trigger:** if EPG matching accuracy on a corpus of real provider
playlists is below 90%, stop and invest in the matcher and mapping UI before adding
anything else — a player with a broken guide is not shippable.

---

## Phase 2 — Parity: "it does what the paid competitor does"

**Goal:** no reason left to prefer the incumbent on features.

**Scope:** the `SHOULD` requirements, plus the entitlement system.

Headline items:
- Recording: instant, scheduled, padding, manual timers, foreground service, exact
  alarms, boot re-arm, storage selection, results reporting, library. (`FR-REC-*`)
- Series recording and repeat rules, with the honest "no series metadata" warning.
- Timeshift.
- Picture-in-Picture.
- Multi-view (2×2).
- Auto Frame Rate.
- Multiple EPG sources with priority.
- Profiles.
- Stalker/portal support.
- Entitlement, device management, purchase and restore flows (`FR-LIC-*`).
- Diagnostics screen.

**Exit criteria:** `NFR-REL-02` (30-day DVR soak ≥ 99%) and a full pass of the MUST/SHOULD
regression suite.

---

## Phase 3 — Differentiation: "reasons to switch"

**Goal:** the four wedges from `docs/08` fully realised.

Ordered by expected impact:

1. **Cloud sync** (`FR-SET-10`) — favourites, groups, ordering, resume points, history and
   settings across devices, client-side encrypted. The most-requested missing feature in
   the category.
2. **DVR robustness** (`FR-REC-18`, retention rules, conflict handling) — recordings that
   survive stream drops and manage their own storage.
3. **VOD/series experience** (`FR-PLB-28..29`) — artwork, continue-watching, resume.
4. **Google TV home integration** (`FR-TVI-02..04`) — Watch Next and preview channels.
5. **Multi-view presets, adjacent-channel pre-buffering, Chromecast sender, external
   subtitles, cache/report tooling** — the remaining `COULD` items.

---

## Deferred / not in v1

- TV Input Framework integration (`FR-TVI-07`).
- iOS, Roku, webOS, Tizen, desktop, web.
- Any content discovery, aggregation or provider directory — permanently out of scope.

---

## Open decisions (record as ADRs before Phase 1 freeze)

| # | Decision | Why it matters |
|---|---|---|
| ADR-001 | minSdk 23 vs 24 | PiP on TV requires 24; 23 buys older-box reach |
| ADR-002 | Single adaptive module vs `:app-tv` + `:app-mobile` | Affects Play listings, build complexity, review |
| ADR-003 | Recording sink: second HTTP connection vs tap on the playback pipeline | Second connection is simpler and decoupled but consumes a provider connection |
| ADR-004 | Sync backend: self-hosted vs managed | Cost, latency, and the client-side-encryption story |
| ADR-005 | Free-tier limits (2 playlists, 1 concurrent recording) | Sets the whole monetization frame |
