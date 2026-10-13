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
| FR-SRC-12 | Done | An error or an empty list aborts the merge and records the failure. So does a response that is not a playlist at all: until 2026-10-13 a bare line was taken as a channel whatever it held, so the HTML page a panel sends with a 200 for a suspended account "refreshed" into one junk channel and the merge deleted every real one, with the user's favourites and edits (found by the emulator QA run, `docs/qa/`). Only a line with a scheme or an absolute path is a bare URL now; a response of nothing but rejected lines is a parse failure. |
| FR-SRC-13 | Done | `RefreshReport`, shown in Settings. |
| FR-SRC-14 | Not implemented | Deleting a playlist cascades; no retain-favourites offer. |
| FR-SRC-15 | Partial | Account status and connection count are read and shown when testing a connection; no scheduled expiry warning. |
| FR-SRC-16 | Done | `SourceRepository.ensureRadioPlaylist`, `data/builtin/IsraelRadioStations.kt` — same idempotent bootstrap as the TV channels playlist (ADR-015). |

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
| FR-EPG-05 | Done | Scheduled through `RefreshWorker`, plus "Update guide now", and after every playlist refresh started from the app — adding a playlist included. Until 2026-10-13 only the first two existed, and the worker loaded a guide only when the playlist itself was due, so a new playlist showed an empty guide for up to its whole interval and a failed first guide download for the bundled channels waited a day. The worker now also retries while no guide has loaded at all, and no longer counts a playlist with no guide address as a failed run. |
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
| FR-PLB-06 | Done | `PlayerEngine.audioTracks` / `selectTrack` — which until now had **no callers in either shell**. A preferred language could be set in Settings; the track on the stream in front of you could not be chosen. The television's playback menu is the first caller. The phone still has no picker. |
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
| FR-PLB-17 | Done | Backoff, then failover down the URL list, with a notice. A live channel whose last source failed for want of a connection (no network, a timeout, a 5xx) no longer stops there: it goes round its sources again after 5 s, 10 s, then every 15 s for as long as the viewer stays on it and has not paused (`ReconnectPolicy`). Until 2026-10-13 the round of retries lasted about three seconds and the channel then sat dead with the network back — two minutes without a request after a 20-second outage in the emulator QA run. A stream that failed while the app was in the background is also prepared again when it comes back. |
| FR-PLB-18 | Done | Auto-dismissing info card with now/next and a progress bar. |
| FR-PLB-19 | Done | Numeric entry with a configurable confirm timeout. |
| FR-PLB-20 | Done | `toggleLastChannel`. |
| FR-PLB-21 | Done | Optional resume on start. |
| FR-PLB-22 | Not implemented | Timeshift within the live buffer. |
| FR-PLB-23 | Partial | PiP on Home, on by default with the Settings switch as the way out, with a play/pause action in the window that tracks the intent flag (expand and close are the system's own controls); the aspect ratio is clamped into Android's accepted band, and a recreation inside the window (language change) restores the bare-video branch instead of squeezing the browse shell into it. Backgrounding *without* a window pauses and drops the video track — sound from a backgrounded app with no notification is a defect, not background audio. The player holds audio focus, so another app starting its own sound pauses the window (and the radio's background service) instead of playing over it; the television picks the stream back up when the viewer returns to the app, the phone leaves it paused. Leaving the app with Back silences playback in `onPause` (non-blocking; the release stays in `onStop`), because a busy TV launcher can hold `onStop` off for seconds. Home with no window to go to now pauses in `onUserLeaveHint` for the same reason — until 2026-10-13 it waited for `onStop`, and the emulator QA run heard the channel still playing over the TV launcher 4 s after Home; a radio station is still left to `onStop`, which hands it to its background service. Closing the window with its X pauses the way a stop without a window does: the activity is stopped while it still counts as in the window, so the pause lands on the mode change that follows, and a closed window never hands the radio to its background service. Still no explicit in-app trigger. |
| FR-PLB-24..27 | Not implemented | Multi-view, presets, adjacent pre-buffering. |
| FR-PLB-28 | Done | VOD and series both browse and play. A series tile opens a season/episode list (`VodScreen.SeriesEpisodes`); episodes are fetched by `AppViewModel.refreshEpisodes` when the series opens, stored, and played from `/series/` via `XtreamClient.episodeUrl`. A successful fetch also prunes episodes the panel stopped listing (kept ones would 404), served by `idx_vod_series` — schema version 2, the first real migration. What CI proves is the URL construction and the queries; that a panel then streams what it is asked for needs a real subscription and has not been checked here. |
| FR-PLB-29 | Partial | The continue-watching row and the resume columns have always existed. Nothing wrote them until 2026-08-20: `resume_position_ms` was read when playback started and never saved, so the row could not fill and every title restarted from zero. `AppViewModel.saveVodProgress` now writes it when on-demand playback is left and from `MainActivity.onStop`, and play-time reads the mark back from the database rather than trusting the tile's snapshot, which went stale and had the next save overwrite the real mark. Still Partial because a recording's resume position is read the same way and is still never written. |
| FR-PLB-30 | Partial | The RTSP module is on the classpath; untested. |

## UI, PRF, CU, REC, NET, SET

| ID | Status | Notes |
|---|---|---|
| FR-UI-01..03 | Done | D-pad throughout; overlays draw over playing video. |
| FR-UI-04 | Done | **Previously recorded as Done and was not.** The mini guide was a `LazyRow` of the current channel's programmes with neither `focusable()` nor `clickable()` on any of them — on a remote, a picture of a schedule with nothing in it that could be selected. Replaced by a channel filmstrip: neighbouring channels captioned with what is on now, Left/Right to walk, OK to tune, playback uninterrupted. See `docs/adr/ADR-014`. |
| FR-UI-05 | Done | **Previously recorded as Done with two of its named parts missing.** The current-time marker was never drawn, and jump-to-date had a string resource but no control — reaching next Tuesday meant pressing +1 day seven times. Both are now in. The marker ticks on its own timer, because left to the grid's recompositions it froze wherever it was when the guide opened. |
| FR-UI-06 | Done | Satisfied literally before — the stream kept decoding under an 85%-opaque sheet, so opening the guide cost you the picture. The television guide now docks the live preview beside the grid instead of covering it (`docs/adr/ADR-013`). |
| FR-UI-07 | Done by construction | Lazy channel axis plus a windowed query per visible page; not measured on a TV box. The window used to be sized from a hardcoded guess of twelve visible rows, which was a correctness bug as well as a tuning one: on a panel fitting more than eighteen rows, every row past that rendered "no guide data" while the data sat in the database. It now asks the layout what is on screen. |
| FR-UI-08 | Partial | Details, watch, catch-up, record and favourite; no reminder or series action. |
| FR-UI-09 | Done | Channels, guide entries and VOD in one query over the whole playlist, debounced, reachable from the top bar on the phone and from the group column on the television. The channel clause also matches group names — typing a category surfaces its channels — outside a selected group, without resurrecting hidden groups (`ChannelQueriesTest`). Catch-up programmes are covered only in so far as they are still in the guide window. |
| FR-UI-10 | Done | **Previously recorded as Done on half the requirement.** The requirement is "catch-up availability *and recording state* are badged in the guide"; only the archive badge was drawn, so a scheduled programme looked exactly like an unscheduled one and the only way to check was to leave the guide for the recordings list. Both badges are now in the grid, matched by overlap of the programme's midpoint — a scheduled recording carries the pre- and post-roll padding, so its window is deliberately wider than the programme's and an equal-start match would never fire. Cancelled and failed recordings are not badged. |
| FR-UI-11 | Not implemented | Remappable keys. Defaults are as `docs/03` specifies. |
| FR-UI-12 | Done | Key-code probe in Diagnostics. |
| FR-UI-13..15 | Done | Bottom navigation and full-screen browse screens; playback lives in `PlayerScreen` (portrait video-plus-details, landscape fullscreen with the list as a sheet) with `MiniPlayerBar` collapsing it Apple-Music-style (ADR-011, FR-UI-15 as reworded). Gestures unchanged, now on the player screen's video. The list opens anchored to the playing channel with a now-programme second line per visible row. Verified by construction and the JVM suite only — no Compose UI tests exist; the manual matrix is in `docs/11`. |
| FR-UI-16 | Partial | Two-pane above sw600dp — the smallest width, so a tall phone lying down is still a phone; no master/detail split view. |
| FR-UI-17..19 | Done | Four themes, overlay opacity and timeout, font scale that reflows. |
| FR-UI-20 | Partial | The menu exists — a long OK over playing video opens it, with play/pause, aspect, and the audio and subtitle track pickers. It is not yet user-configurable in content or order, which is what the requirement actually asks for. Until this it was the *whole* thing that was missing: a long OK opened Settings ▸ Playback, a preferences screen that left the programme to change a default. See `docs/adr/ADR-014`. |
| FR-UI-21 | Partial | `supportsRtl`, start/end padding throughout, Hebrew resources, D-pad Left/Right mirrored to the layout direction, and clock ranges isolated so they no longer render end-first in a Hebrew paragraph. This was previously marked Done on the strength of the padding alone, while the remote and every composed time range were still wrong. The guide grid should mirror by construction — it is laid out in widths, with no pixel offsets — but that has not been checked on a device. |
| FR-UI-22 | Not implemented | Chromecast sender. |
| FR-UI-23 | Done | Follow the device, English or Hebrew, from Appearance. Android 13 and later hand the choice to `LocaleManager`, which applies it to the whole process and recreates the activity; below that it is applied by wrapping the base context *and* registering the locale as the activity's own override configuration — the wrap alone was reported as changing nothing on a device, because the platform rebases an activity's resources onto that override and drops what is not in it. Preferences and the platform are reconciled on start, so an upgraded install keeps its choice and a change made from the device's per-app language screen wins. The translation is compiled under `values-iw`: Android resolves resources against the language `ResourcesImpl.adjustLanguageTag` returns, which is `iw` for Hebrew, so a `values-he` folder is never looked up — the layout mirrored while every string stayed English. Diagnostics reports the stored choice, the platform's, the resolved locale and the layout direction. See `docs/adr/ADR-009`. |
| FR-UI-24 | Done | `TvOverlay.HOME` + `ui/tv/TvHomeScreen.kt`. The live picture is docked into the hero by the same `docksPlayer`/`onDockBounds` mechanism the guide uses, so nothing re-tunes. `StartBehaviour.HOME` is the new default and Back from any browse surface rises to it. The screen's shape has since changed — the picture is a full-bleed banner rather than a docked hero, so nothing docks on Home any more. See `docs/adr/ADR-016` and `docs/adr/ADR-020`. |
| FR-UI-25 | Done | `ui/home/HomeModels.kt` — `HomeRails.build` is pure and unit-tested (`HomeRailsTest`), which is what makes the rules checkable in an environment that cannot compile the app. Rails: Featured, Continue watching, Channels, Radio, Favourites, Starting soon; an empty one is dropped entirely. Recently-watched and favourites are screened against what the properly-scoped queries returned, because `Dao.recentChannels` and `Dao.favouriteChannels` filter hidden channels but not channels inside a hidden group. |
| FR-UI-26 | Done | `data/featured/` — a strict `org.json` parser shaped after `UpdateManifest`, a repository caching the raw document in preferences, a six-hour TTL, refreshed from `RefreshWorker` and on cold start. `Settings.featuredFeedUrl` is empty by default and empty means there is no rail. No feed is bundled: this is the mechanism, not a content decision. |
| FR-UI-27 | Done | `ui/theme/Type.kt` — four static weights of Rubik (SIL OFL) in `res/font/`, wired into the one `Typography` ramp every screen reads, so both shells change together. Cut from the variable font ahead of time because Compose ignores `FontVariation` below API 26 and this app targets API 23 and up. `TypefaceAssetsTest` checks the four files are the four weights they claim to be. Came out of the Cellcom TV teardown, which found that app shipping a six-weight brand family; the same report's near-black background was deliberately not adopted. See `docs/adr/ADR-019`. |
| FR-UI-28 | Done | `ui/tv/TvHomeScreen.kt` — the picture fills the panel and the screen is drawn over it: a gradient, the channel in one corner and a clock in the other, a *watch fullscreen* button where the remote lands, and one `LazyColumn` carrying banner, app strip and rails together. The rail collapses to icons and expands over the list rather than beside it, keeping its labels as content descriptions throughout. App shortcuts are `ui/home/HomeApps.kt` (pure, `HomeAppsTest`) plus a `PackageManager` resolution off the main thread; the manifest's `<queries>` block is asserted against the catalogue by the same test. See `docs/adr/ADR-020`. |
| FR-UI-29 | Done | `ui/screens/GuideScreen.kt` — a day strip (yesterday/today/tomorrow, then weekday plus date, a fortnight either way) replacing the jump-to-date dialog and the ±1d buttons, a clock in the toolbar, and a focused cell scaled and raised in z-order. Days are normalised through `TimeModel.startOfLocalDay`, which the dialog's UTC snap never did — picking a day used to land the window on 02:00 local. Shared by both shells, since the phone draws the same screen. |
| FR-UI-30 | Done | `ui/common/Motion.kt` — one vocabulary (a spring-driven focus lift, 120ms colour crossings, a 260ms fade-and-rise for an arriving surface) applied in `FocusableRow`, the home tiles and banner button, the app strip, the navigation rail, the guide's day chips and programme cells, and every overlay in `TvRoot`. Scale and alpha are read inside `graphicsLayer`, so a focus lift redraws one node and recomposes nothing. Before this the app contained no animation API calls at all: sixteen D-pad presses drew 64 frames. Idle still renders zero. See `docs/adr/ADR-021`. |
| FR-UI-31 | Done | `ui/common/Screensaver.kt` plus the idle clock and key dispatch in `MainActivity` — a still screen becomes black with a drifting clock, and an audio-only one keeps the station's colours, dimmed and slowly swinging in hue, with a wandering pool of light behind them. The drift is a Lissajous path whose axes are in the golden ratio, so it never closes into a loop that could itself wear in. Moving video is never replaced; the whole key gesture that dismisses it is swallowed; volume and mute neither wake it nor are eaten by it. Above both shells, so the phone gets it too. The decisions and the geometry are pure functions, tested in `ScreensaverTest`; the rendering is not, for the reason `docs/10` gives everywhere else. See `docs/adr/ADR-024`. |
| FR-UI-32 | Done | `data/tasks/` — `Settings.taskFeedUrl` is empty by default and `screensaverTasks` is off by default, so nothing appears on anybody else's television by accident. `TaskFeed` reads a Markdown checklist (Obsidian's `- [ ]` lines, headings as sections, wiki links, tags and a `📅` due date all reduced to printable text) or a JSON document, chosen by the first character of the body; `TaskRepository` mirrors `FeaturedRepository` — HTTPS only, cached verbatim in preferences, half-hour TTL, a failed fetch leaves the last good list up. One task at a time, twelve seconds each, cross-faded, riding the drift. `TaskFeedTest` covers both formats. See `docs/adr/ADR-024`. |
| NFR-PERF-08 | Partial | The motion is written to the budget, and the app ships a baseline profile covering its own packages (`src/main/baseline-prof.txt`) so ahead-of-time compilation can reach it. Measured on the Google TV Streamer after one launch (which lets `profileinstaller` seed the profile) and a background-equivalent compile: 6.8% janky frames, 21–27ms 90th/95th-percentile — inside the requirement's shape but past its exact numbers (under 5%, under 25ms). The window right after a self-update, before that first launch and the OS's own idle compile pass, is still interpreted (measured 150ms 95th-percentile there). A dex-metadata (`.dm`) file handed to `PackageInstaller` was built and tried against real hardware to close that window and does not work: the platform's native profile reader rejects the exact profile format this repository can produce (`profman: Profile version mismatch`, confirmed by `dumpsys package dexopt` and `logcat` on the Streamer), because that format is tied to the ART Mainline module rather than to `minSdk`, and updates independently of it. Not pursued further; see `docs/adr/ADR-021`. |
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
| FR-SET-08 | Done | JSON export/import, credentials excluded by default — and since 2026-10-13 excluded everywhere: without them, a playlist address loses its `username`/`password` parameters and any `user:password@`, and a guide address carrying them is left out (an Xtream playlist's is `xmltv.php?username=…&password=…`, and went out in clear text whatever the switch said). A restore matches a playlist on its stripped address too, so it updates the one it came from and keeps its working addresses. |
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
| FR-TVI-02 | Done | Recently watched channels are published to Google TV's "Continue watching" row (`tv/WatchNextPlan.kt` decides, `tv/WatchNextSync.kt` writes), capped at ten and expiring after thirty days, with an opt-out in Settings ▸ Playback. Poster art is the channel logo declared as 1:1 — see ADR-017 for why, and what the better answer would cost. |
| FR-TVI-03 | Not implemented | A preview channel of our own on the home screen. A separate, opt-in surface; not asked for. |
| FR-TVI-04 | Done | `shidur://channel/<id>`, its own intent filter, handled in `onCreate` and `onNewIntent`. |
| FR-TVI-05 | Partial | Widevine/PlayReady/ClearKey configured from `#KODIPROP`; untested. |
| FR-TVI-06 | Done | DRM failure is terminal with an explanatory message. |
| FR-TVI-07 | Won't | Out of scope, as specified. |

## AUTO — Android Auto

Sideload-only, per ADR-015 — no code here is gated on, or blocked by, Google Play's
Android-Auto-specific review. Not exercisable by the JVM unit test suite; requires a phone with
Android Auto's "Unknown sources" developer setting and a car or Desktop Head Unit.

| ID | Status | Notes |
|---|---|---|
| FR-AUTO-01 | Done | `auto/RadioMediaLibraryService.kt`, `auto/RadioBrowseTree.kt` — flat browse tree over the FR-SRC-16 playlist. |
| FR-AUTO-02 | Done | `auto/RadioPlayerFactory.kt` builds a dedicated ExoPlayer, never `ServiceLocator.player`. |
| FR-AUTO-03 | Done | `RadioSessionCallback.onSetMediaItems` hands the whole station list to the player positioned at the selection, so native skip-next/previous walks it. |
| FR-AUTO-04 | Done | `res/xml/automotive_app_desc.xml` + manifest service declaration; no Play-review-gated code path. |

## UPD — In-app updates

Written against a device that could not run it: the whole path — permission prompt, installer
session, replacement — needs a signed build on real hardware before any of this is proven. What
is claimed here is that the code exists and its logic is covered; see `docs/11`.

| ID | Status | Notes |
|---|---|---|
| FR-UPD-01 | Done | CI publishes `latest.json` beside the APK on every push to `main`, and refuses to publish a version code that does not advance. `UpdateManifest` parses it strictly. |
| FR-UPD-02 | Done | On launch and from `RefreshWorker`, rate-limited to once a day; "check now" in Settings → About is always immediate. |
| FR-UPD-03 | Done | `UpdatePrompt`, shown by both shells and by neither in picture-in-picture. Postpone lasts for the launch, skip until a newer build. A refusal on "allow installs from this app" opens that screen from the dialog itself and keeps the offer standing; `MainActivity.onResume` re-reads the permission, and the install resumes on the way back. |
| FR-UPD-04 | Done | `UpdateManager.downloadAndInstall` streams to the cache and commits a `PackageInstaller` session; progress in the dialog and in Settings. |
| FR-UPD-05 | Done | HTTPS-only manifest, SHA-256 computed over the download itself, signing certificate compared with the running app's before the session opens. |
| FR-UPD-06 | Done | Both switches in Settings → About, on by default. The skip is stored as a floor, so a newer build clears it. |
| FR-UPD-07 | Done | `blockingReason` refuses while `dao.activeRecordings()` is non-empty, and the refusal names itself. |
| FR-UPD-08 | Done | `BootReceiver` also answers `MY_PACKAGE_REPLACED` and re-arms the schedule. |

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

Two ten-foot requirements were failing outright until 2026-08-21 and are worth naming, because
neither had ever been recorded as failing:

- **NFR-UX-01** ("every TV screen is reachable and operable using only D-pad, OK and Back").
  It was not. Recordings sat behind a colour key many remotes do not carry, and on demand had no
  entry point at all — it could only be reached by searching for a series and picking it, so a
  viewer who did not already know a title in the catalogue could not open the catalogue. The
  channel list's group column now carries the guide, on demand, recordings and settings above the
  playlist's own groups.
- **NFR-UX-02** ("focus is always visible on TV, with a contrast ratio of at least 3:1").
  `VodTile` was `focusable()` with no `onFocusChanged` and no focus visual of any kind, so the
  catalogue was a grid of posters with an invisible cursor somewhere in it. The video surface was
  also a focus stop — `PlayerView` is a focusable `FrameLayout` and Compose hands focus across the
  interop boundary — so focus could land on the picture with nothing on screen to say so.

Several `focusable()` calls sat beside `clickable()`, which is focusable in its own right. Each
pair was two focus targets on one element, so a remote spent an invisible extra press crossing
every guide cell and every poster.

NFR-UX-02 was met screen by screen after that, but not *coherently*: an audit for ADR-025 found
seven different focus treatments in one app — five fills for "this is where focus is" and three
ring widths on the same television at the same moment — with the poster tile and the transport
buttons widening their ring from 0dp to 3dp on focus (which shifts their own contents inside the
clip at the moment they are pressed) and resting it on `Color.Transparent` (which is transparent
*black*, so a bright ring darkened on its way out rather than fading). Everything focusable now
goes through one `Modifier.focusSurface`, and every menu through one `AppDialog` on the shell's
own plate. `FocusVocabularyTest` holds the three invariants that were broken.
