# 10 — Implementation status

What the code in this repository actually does, requirement by requirement. The point of this
file is that a reader can tell implemented from not-implemented without running the app.

Legend: **Done** · **Partial** (works, with the limitation named) · **Not implemented**.

Entitlements are out of scope for this build by decision — see `docs/adr/ADR-007`. Every
requirement whose only reason to exist was tier gating is marked accordingly, and every `Paid`
feature that was otherwise in scope is simply available.

## SRC — Sources and playlists

| ID | Status | Notes |
|---|---|---|
| FR-SRC-01 | Done | `M3uParser`, fetched over OkHttp with the playlist's request profile. |
| FR-SRC-02 | Done | SAF document or a plain path; `SourceRepository.openLocal`. |
| FR-SRC-03 | Done | `XtreamClient`: account, live, VOD, series, `xmltv.php` wired as the guide URL. |
| FR-SRC-04 | Not implemented | Stalker/portal. `SourceType` has room for it; no client written. |
| FR-SRC-05 | Done | Including `#EXTVLCOPT`, `#KODIPROP` and preservation of unknown attributes. |
| FR-SRC-06 | Done | Per playlist, TS or HLS. |
| FR-SRC-07 | N/A | Tier gating removed; playlists are unlimited. |
| FR-SRC-08 | Partial | Name, credentials, user-agent, headers and interval are stored and honoured; only name, URL, credentials and format have an editing UI. |
| FR-SRC-09 | Done | Settings → Playlists → Refresh now. |
| FR-SRC-10 | Done | `RefreshWorker` runs hourly and refreshes each playlist when its own interval is due. |
| FR-SRC-11 | Done | `SourceRepository.merge`; user-owned fields are carried over untouched. |
| FR-SRC-12 | Done | An error or an empty list aborts the merge and records the failure. |
| FR-SRC-13 | Done | `RefreshReport`, shown in Settings. |
| FR-SRC-14 | Not implemented | Deleting a playlist cascades; no retain-favourites offer. |
| FR-SRC-15 | Partial | Account status and connection count are read and shown when testing a connection; no scheduled expiry warning. |

## ORG — Channel organization

| ID | Status | Notes |
|---|---|---|
| FR-ORG-01 | Done | Provider `group-title`, with an `Ungrouped` fallback. |
| FR-ORG-02 | Partial | Custom groups can be created, renamed, deleted and assigned to. Provider groups can be hidden but not renamed: `ensureGroup` matches them by name on every refresh, so a rename would orphan the group and have the provider's version recreated beside it. That needs a column remembering the original name, and a migration. |
| FR-ORG-03 | Done | Per channel and per group, from the channel options, persisted across refreshes. Hiding a group now hides its channels too — until this change it removed only the group's row from the column. |
| FR-ORG-04 | Partial | Channels: done, from the channel editor, and `customName` is never overwritten by a refresh. Groups: see FR-ORG-02. |
| FR-ORG-05 | Partial | Move-mode reordering from the channel options, on both shells. No drag handle on touch. Offered only where the list is in the playlist's own order, because a swap in Favourites or Recently watched would reorder somewhere the user cannot see. |
| FR-ORG-06 | Done | Provider order, A–Z, most watched and recently watched, from Appearance. |
| FR-ORG-07 | Done | Channels and VOD, each from a control on the row or tile, with a Favourites view for both. |
| FR-ORG-08 | Not implemented | Cross-playlist unified favourites. Every channel query is scoped to one playlist; `Dao.favouriteChannels` is the unused seam for it. |
| FR-ORG-09 | Done | `tvg-chno` or position, editable from the channel editor and preserved by a refresh once set. |
| FR-ORG-10 | Done | Cached by Coil with a generated monogram fallback, and a custom address can be set per channel. A picker for a local file is not offered — that needs a persistable URI grant. |
| FR-ORG-11 | Done | `ensureGroup` honours the setting, and only for groups it creates: one the user has chosen to show stays shown. |
| FR-ORG-12 | Done | Long press on touch, Menu on a remote. `dispatchKeyEvent` offers the view hierarchy the key before the activity's own Menu binding. |
| FR-ORG-13 | Done | A Hidden view beside Favourites and Recently watched, with per-channel and show-all restores. |

## EPG — Guide data

| ID | Status | Notes |
|---|---|---|
| FR-EPG-01 | Done | URL or file, gzip and zip detected from the magic bytes rather than the extension. |
| FR-EPG-02 | Done | Xtream `xmltv.php`. |
| FR-EPG-03 | Done | `url-tvg` / `x-tvg-url` from the `#EXTM3U` header. |
| FR-EPG-04 | Not implemented | Multiple prioritised EPG sources. The `source_priority` column exists. |
| FR-EPG-05 | Done | Scheduled through `RefreshWorker`, plus "Update guide now". |
| FR-EPG-06 | Done | Pull parser, nothing accumulated, progress reported every 2,000 programmes. |
| FR-EPG-07 | Done | `EpgMatcher`, four stages in strict order. |
| FR-EPG-08 | Done | `manualEpgId` wins over every other stage and is never recomputed, and is now settable — the channel options offer a searchable list of guide entries. Until this change the binding could only be reached from the database. |
| FR-EPG-09 | Partial | The match breakdown is reported in Settings and Diagnostics; the one-tap jump into mapping is not wired. |
| FR-EPG-10 | Done | Epoch-millis UTC throughout; rendered in the device zone. |
| FR-EPG-11 | Done | ±15-minute steps. |
| FR-EPG-12 | Done | See `docs/adr/ADR-008` — offsets shift the query, not the rows. The per-channel override is set from the channel options; zero means no override, so the channel falls back to the global offset. |
| FR-EPG-13 | Done | Covered by `TimeModelTest`. |
| FR-EPG-14 | Done | Whatever the source provides is stored. |
| FR-EPG-15 | Done | Retention is the longest catch-up window in the library, floored by the setting. |
| FR-EPG-16 | Done | Explicit "no guide data" in the list, the info card and the grid. |

## PLB — Playback

| ID | Status | Notes |
|---|---|---|
| FR-PLB-01 | Done | HLS, DASH, MPEG-TS, progressive; RTSP module included. |
| FR-PLB-02 / 03 | Done | Whatever the device's `MediaCodec` offers. |
| FR-PLB-04 | Done | `SelectiveRenderersFactory`, independent per renderer family, global from Settings and per channel from the channel options. |
| FR-PLB-05 | Done | One software retry, with a notice saying what changed. |
| FR-PLB-06 | Done | `PlayerEngine.audioTracks` / `selectTrack`. |
| FR-PLB-07 | Done | `setPreferredAudioLanguage` on the track selector. |
| FR-PLB-08 | Not implemented | Media3 exposes no audio offset; a stub would have read as a working control. |
| FR-PLB-09 | Partial | Media3 passes through where the device allows; no explicit UI switch. |
| FR-PLB-10 | Done | Rendered by `PlayerView`'s subtitle view. |
| FR-PLB-11 | Partial | Size scaling only. |
| FR-PLB-12 | Not implemented | External sidecar subtitles. |
| FR-PLB-13 | Partial | Seven options mapped onto Media3's four resize modes; documented in `PlayerSurface`. |
| FR-PLB-14 | Partial | `videoTracks()` enumerates renditions; no picker UI. |
| FR-PLB-15 | Not implemented | Auto frame rate. |
| FR-PLB-16 | Done | Five presets plus explicit millisecond values. |
| FR-PLB-17 | Done | Backoff, then failover down the URL list, with a notice. |
| FR-PLB-18 | Done | Auto-dismissing info card with now/next and a progress bar. |
| FR-PLB-19 | Done | Numeric entry with a configurable confirm timeout. |
| FR-PLB-20 | Done | `toggleLastChannel`. |
| FR-PLB-21 | Done | Optional resume on start. |
| FR-PLB-22 | Not implemented | Timeshift within the live buffer. |
| FR-PLB-23 | Partial | PiP on Home, on by default with the Settings switch as the way out, with a play/pause action in the window that tracks the intent flag (expand and close are the system's own controls); the aspect ratio is clamped into Android's accepted band, and a recreation inside the window (language change) restores the bare-video branch instead of squeezing the browse shell into it. Backgrounding *without* a window pauses and drops the video track — sound from a backgrounded app with no notification is a defect, not background audio. Still no explicit in-app trigger. |
| FR-PLB-24..27 | Not implemented | Multi-view, presets, adjacent pre-buffering. |
| FR-PLB-28 | Done | VOD and series both browse and play. A series tile opens a season/episode list (`VodScreen.SeriesEpisodes`); episodes are fetched by `AppViewModel.refreshEpisodes` when the series opens, stored, and played from `/series/` via `XtreamClient.episodeUrl`. A successful fetch also prunes episodes the panel stopped listing (kept ones would 404), served by `idx_vod_series` — schema version 2, the first real migration. What CI proves is the URL construction and the queries; that a panel then streams what it is asked for needs a real subscription and has not been checked here. |
| FR-PLB-29 | Partial | The continue-watching row and the resume columns have always existed. Nothing wrote them until 2026-08-20: `resume_position_ms` was read when playback started and never saved, so the row could not fill and every title restarted from zero. `AppViewModel.saveVodProgress` now writes it when on-demand playback is left and from `MainActivity.onStop`, and play-time reads the mark back from the database rather than trusting the tile's snapshot, which went stale and had the next save overwrite the real mark. Still Partial because a recording's resume position is read the same way and is still never written. |
| FR-PLB-30 | Partial | The RTSP module is on the classpath; untested. |

## UI, PRF, CU, REC, NET, SET

| ID | Status | Notes |
|---|---|---|
| FR-UI-01..06 | Done | D-pad throughout; overlays draw over playing video. |
| FR-UI-07 | Done by construction | Lazy channel axis plus a windowed query per visible page; not measured on a TV box. |
| FR-UI-08 | Partial | Details, watch, catch-up, record and favourite; no reminder or series action. |
| FR-UI-09 | Done | Channels, guide entries and VOD in one query over the whole playlist, debounced, reachable from the top bar on the phone and from the group column on the television. The channel clause also matches group names — typing a category surfaces its channels — outside a selected group, without resurrecting hidden groups (`ChannelQueriesTest`). Catch-up programmes are covered only in so far as they are still in the guide window. |
| FR-UI-10 | Done | Archive badge in the grid; recording state in the library. |
| FR-UI-11 | Not implemented | Remappable keys. Defaults are as `docs/03` specifies. |
| FR-UI-12 | Done | Key-code probe in Diagnostics. |
| FR-UI-13..15 | Done | Bottom navigation and full-screen browse screens; playback lives in `PlayerScreen` (portrait video-plus-details, landscape fullscreen with the list as a sheet) with `MiniPlayerBar` collapsing it Apple-Music-style (ADR-011, FR-UI-15 as reworded). Gestures unchanged, now on the player screen's video. The list opens anchored to the playing channel with a now-programme second line per visible row. Verified by construction and the JVM suite only — no Compose UI tests exist; the manual matrix is in `docs/11`. |
| FR-UI-16 | Partial | Two-pane above sw600dp — the smallest width, so a tall phone lying down is still a phone; no master/detail split view. |
| FR-UI-17..19 | Done | Four themes, overlay opacity and timeout, font scale that reflows. |
| FR-UI-20 | Not implemented | Configurable playback menu. |
| FR-UI-21 | Partial | `supportsRtl`, start/end padding throughout, Hebrew resources, D-pad Left/Right mirrored to the layout direction, and clock ranges isolated so they no longer render end-first in a Hebrew paragraph. This was previously marked Done on the strength of the padding alone, while the remote and every composed time range were still wrong. The guide grid should mirror by construction — it is laid out in widths, with no pixel offsets — but that has not been checked on a device. |
| FR-UI-22 | Not implemented | Chromecast sender. |
| FR-UI-23 | Done | Follow the device, English or Hebrew, from Appearance; applied by wrapping the base context, and the activity is recreated. See `docs/adr/ADR-009`. |
| FR-PRF-01..03 | Not implemented | Profiles. |
| FR-PRF-04 / 05 / 08 | **Not implemented** | The PIN is stored correctly — PBKDF2 with a salt — and the lock-scope switches persist, but **nothing ever asks for the PIN**: `ParentalPin.verify` has no caller, Settings opens unprompted and a locked channel plays on the first press. `hideLockedChannels` only filters the list, and it can be unticked in the same unprotected screen. Previously recorded here as Done. Found by the review of 2026-08-20; see `docs/11`. |
| FR-PRF-06 | Not implemented | Lock by EPG rating. The `rating` column is populated. |
| FR-PRF-07 | **Not implemented** | `PinAttemptLimiter` is written and has no caller, because nothing prompts for a PIN to rate-limit. Previously recorded here as Done. |
| FR-CU-01..04 | Done | Detection, backward scrolling, four URL conventions, standard transport controls. |
| FR-CU-05 | Done | Outside-window and unsupported are distinct messages. |
| FR-CU-06 | Not implemented | Dedicated catch-up browse view. |
| FR-REC-01..03 | Done | Instant, scheduled from the guide, configurable padding. |
| FR-REC-04 | Done | Records every episode the guide lists, and each guide refresh claims newly published ones 14 days ahead. |
| FR-REC-06 | Not implemented | Repeat rules (channel + weekday + window) as a fallback when the guide has no series data. |
| FR-REC-05 | Done | A series recording is refused outright when the guide carries no series metadata, with the reason stated. |
| FR-REC-07 | Partial | `RecordingScheduler.schedule` accepts a manual timer; no UI form. |
| FR-REC-08 | Partial | Concurrency limit enforced; provider connection limit is not cross-checked. |
| FR-REC-09 | Partial | Internal and SAF trees, which covers USB; no SMB. |
| FR-REC-10 | Done | Byte-exact MPEG-TS. |
| FR-REC-11 | Partial | Fixed template, documented. |
| FR-REC-12 | Done | Refused below the estimate, warned below the threshold. |
| FR-REC-13 | Not implemented | Retention rules. |
| FR-REC-14..17 | Done | Foreground service, exact alarms, boot re-arm, locks, honest wake warning. |
| FR-REC-18 | Done | Reconnect and continue, gaps counted and reported. |
| FR-REC-19 | Done | Every recording ends in a visible result record. |
| FR-REC-20 | Partial | Play, delete and browse; no rename, mark-watched, share, filter or sort. |
| FR-REC-21 | Partial | View and cancel individually; `cancelSeries` exists but has no UI entry point. |
| FR-NET-01 | Done | Global and per playlist, with presets. |
| FR-NET-02 | Partial | Stored and sent per playlist; no header-editing UI. |
| FR-NET-03 | Done | Parsed and applied per channel. |
| FR-NET-04 | Done | Timeouts and retry count. |
| FR-NET-05 | Done | Off by default, warned. |
| FR-NET-06 | Partial | 403 bodies are inspected for the usual geo phrasing. |
| FR-SET-01 | Not implemented | External-player handoff; the setting exists. |
| FR-SET-02 | Done | Boot receiver, behind a setting. |
| FR-SET-03..07 | Not implemented | Wake-from-standby, start behaviour beyond the three implemented, sleep timer, burn-in dimming. Exit confirmation (FR-SET-06) is done. Start behaviour (FR-SET-04) is now once per process and, on the phone, a resumed channel opens the Now Playing screen — previously it played invisibly behind the list, and a recreate (language change) restarted it over whatever was playing. |
| FR-SET-08 | Done | JSON export/import, credentials excluded by default. |
| FR-SET-09 | Partial | Any SAF location, which covers Drive; no SMB. |
| FR-SET-10 | Not implemented | Cloud sync. |
| FR-SET-11 | Not implemented | Reminders. |
| FR-SET-12 | Done | Started, finished and failed notifications. |
| FR-SET-13 | Done | Diagnostics screen, credentials redacted. |
| FR-SET-14 | Done | Copy report. |
| FR-SET-15 | Done | Budgeted, LRU-evicted, size shown, three clear actions. |
| FR-SET-16 / 17 | Not implemented | Verbose file logging, revert playback settings. |
| FR-LIC-* | N/A | See `docs/adr/ADR-007`. FR-LIC-10 (no advertising) holds — there is no ad code. |
| FR-TVI-01 | Done | Leanback launcher category and a TV banner. |
| FR-TVI-02..04 | Not implemented | Watch Next and preview channels. |
| FR-TVI-05 | Partial | Widevine/PlayReady/ClearKey configured from `#KODIPROP`; untested. |
| FR-TVI-06 | Done | DRM failure is terminal with an explanatory message. |
| FR-TVI-07 | Won't | Out of scope, as specified. |

## Non-functional

Verified by test: NFR-MAINT-02 (parsers), and the DST and offset behaviour behind
NFR-REL-05's spirit. NFR-MAINT-01 holds by structure — `core`, `data`, `player` and `record`
import no Compose or UI types.

The performance targets (NFR-PERF-01..06) are **not measured**. The design choices that serve
them are in place — a lazy channel axis with a windowed guide query, one batched query per
distinct offset, streamed parsing, and a bounded cache — but no numbers have been taken on a
reference device, and this file does not claim otherwise.

NFR-SEC-01/02 are implemented: credentials are Keystore-encrypted at rest and redacted
everywhere they could be shared. NFR-SEC-04 holds trivially — there is no telemetry code.
