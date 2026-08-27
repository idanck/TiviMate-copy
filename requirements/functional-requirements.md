# Functional Requirements

ID scheme `FR-<AREA>-<nn>`. IDs are permanent — never reused, never renumbered.
Priority: **MUST** (MVP) · **SHOULD** (parity) · **COULD** (differentiator) · **WON'T** (not v1).
Tier: `Free` / `Paid` · Platform: `TV`, `Mobile`, `Both`.

---

## SRC — Sources and Playlists

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-SRC-01 | The user can add a playlist from an M3U/M3U8 URL. | MUST | Free | Both |
| FR-SRC-02 | The user can add a playlist from a local M3U file. | MUST | Free | Both |
| FR-SRC-03 | The user can add an Xtream Codes source with server URL, username and password; channels, VOD, series, EPG and catch-up are configured automatically. | MUST | Free | Both |
| FR-SRC-04 | The user can add a Stalker/portal source with portal URL and MAC. | SHOULD | Free | Both |
| FR-SRC-05 | The parser reads `tvg-id`, `tvg-name`, `tvg-logo`, `tvg-chno`, `tvg-shift`, `group-title`, `catchup*`, `#EXTVLCOPT` and `#KODIPROP`, and preserves unrecognised attributes. | MUST | Free | Both |
| FR-SRC-06 | The user can select the Xtream stream output format (MPEG-TS or HLS) per playlist. | MUST | Free | Both |
| FR-SRC-07 | The free tier permits at most 2 playlists; the paid tier is unlimited. | MUST | — | Both |
| FR-SRC-08 | Each playlist has an editable name, icon/colour, credentials, user-agent, headers and refresh interval. | MUST | Free | Both |
| FR-SRC-09 | The user can refresh a playlist manually. | MUST | Free | Both |
| FR-SRC-10 | Playlists refresh automatically on a configurable schedule (off / 6h / 12h / daily / weekly / custom). | MUST | Free | Both |
| FR-SRC-11 | A refresh preserves user state (custom groups, ordering, hidden flags, favourites, EPG mappings, per-channel settings) keyed on tvg-id → stream-id → normalized name. | MUST | Free | Both |
| FR-SRC-12 | A failed or empty refresh never destroys the previous channel snapshot; the failure is reported. | MUST | Free | Both |
| FR-SRC-13 | After a refresh the app reports added / removed / renamed channel counts. | SHOULD | Free | Both |
| FR-SRC-14 | Deleting a playlist offers to retain its favourites and groups for re-import. | COULD | Free | Both |
| FR-SRC-15 | Account information from Xtream (expiry date, max connections, status) is displayed and a warning is raised before expiry. | SHOULD | Free | Both |
| FR-SRC-16 | The app ships a single bundled playlist of curated Israeli radio stations (audio-only), sourced from an externally-hosted feed the same way as the built-in TV channels (see ADR-015). | MUST | Free | Both |

## ORG — Channel Organization

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-ORG-01 | Channels are grouped by provider `group-title` by default. | MUST | Free | Both |
| FR-ORG-02 | The user can create, rename and delete custom groups and assign channels to them. | MUST | Free | Both |
| FR-ORG-03 | The user can hide/show individual channels and whole groups. | MUST | Free | Both |
| FR-ORG-04 | The user can rename channels and groups locally without altering the source. | SHOULD | Free | Both |
| FR-ORG-05 | The user can reorder channels and groups manually (drag on touch, move-mode on D-pad). | MUST | Free | Both |
| FR-ORG-06 | Sort presets are available: provider order, A–Z, most watched, recently watched. | SHOULD | Free | Both |
| FR-ORG-07 | The user can mark channels, VOD items and series as favourites, and browse a Favourites view. | MUST | Free | Both |
| FR-ORG-08 | A unified Favourites view spans all playlists. | COULD | Paid | Both |
| FR-ORG-09 | Channel numbers are taken from the source or auto-assigned, and are user-editable. | SHOULD | Free | Both |
| FR-ORG-10 | Channel logos are loaded from the source and cached locally, with a generated fallback tile and an option to supply a custom logo. | MUST | Free | Both |
| FR-ORG-11 | Newly added groups are shown or hidden per a user setting. | SHOULD | Free | Both |
| FR-ORG-12 | Per-channel options — favourite, rename, logo, number, group, hide, lock and reorder — are reachable from the channel list itself, by long press on touch and by the Menu key on a remote. | MUST | Free | Both |
| FR-ORG-13 | Hidden channels remain listed in a view of their own and can be shown again, individually or all at once. | MUST | Free | Both |

## EPG — Guide Data

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-EPG-01 | The app loads XMLTV guide data from a URL, including gzip/zip compressed files. | MUST | Free | Both |
| FR-EPG-02 | The app loads guide data supplied by the Xtream provider. | MUST | Free | Both |
| FR-EPG-03 | The app uses an EPG URL embedded in the M3U header (`url-tvg`). | MUST | Free | Both |
| FR-EPG-04 | The user can configure multiple EPG sources per playlist with an explicit priority order. | SHOULD | Paid | Both |
| FR-EPG-05 | Guide data refreshes on a configurable schedule and on demand. | MUST | Free | Both |
| FR-EPG-06 | XMLTV parsing is streamed, never loading the full document into memory, and reports progress. | MUST | Free | Both |
| FR-EPG-07 | Channels are matched to guide entries by exact tvg-id, then normalized name, then scored fuzzy match. | MUST | Free | Both |
| FR-EPG-08 | The user can manually map any channel to any guide entry; manual mappings survive refreshes. | MUST | Free | Both |
| FR-EPG-09 | A diagnostic view reports matched/unmatched channel counts with one-tap access to map the unmatched. | SHOULD | Free | Both |
| FR-EPG-10 | All programme times are stored in UTC and rendered in the device timezone. | MUST | Free | Both |
| FR-EPG-11 | A global guide time offset can be set in ±15-minute steps. | MUST | Free | Both |
| FR-EPG-12 | A per-channel time offset overrides the global offset and persists independently of any other channel's changes. | MUST | Free | Both |
| FR-EPG-13 | Guide alignment remains correct across DST transitions. | MUST | Free | Both |
| FR-EPG-14 | At least 7 days of forward guide data is retained when the source provides it. | SHOULD | Free | Both |
| FR-EPG-15 | Past programmes are retained for at least the channel's catch-up window. | MUST | Free | Both |
| FR-EPG-16 | Channels without guide data display an explicit "no guide data" state. | MUST | Free | Both |

## PLB — Playback

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-PLB-01 | The app plays HLS, MPEG-TS over HTTP, progressive MP4/MKV and MPEG-DASH. | MUST | Free | Both |
| FR-PLB-02 | The app decodes H.264 and H.265/HEVC, and AV1/VP9 where the device supports them. | MUST | Free | Both |
| FR-PLB-03 | The app decodes AAC, MP3, AC-3/E-AC-3, and DTS where supported. | MUST | Free | Both |
| FR-PLB-04 | Video and audio decoders can each be switched between hardware and software, globally and per channel, with the per-channel choice remembered. | MUST | Free | Both |
| FR-PLB-05 | If hardware decoder initialisation fails, the app retries once in software and informs the user. | MUST | Free | Both |
| FR-PLB-06 | The user can select the audio track by index, language or codec. | MUST | Free | Both |
| FR-PLB-07 | A preferred audio language is auto-selected on every channel. | SHOULD | Free | Both |
| FR-PLB-08 | Audio delay is adjustable in ±10 ms steps, per channel or globally. | SHOULD | Free | Both |
| FR-PLB-09 | Audio passthrough of AC-3/E-AC-3/DTS is supported where the output device permits. | SHOULD | Free | TV |
| FR-PLB-10 | Embedded subtitle tracks (CEA-608, CEA-708, DVB, WebVTT, TTML) can be selected and rendered. | MUST | Free | Both |
| FR-PLB-11 | Subtitle appearance (size, font, colour, background opacity, edge, position) is configurable. | SHOULD | Free | Both |
| FR-PLB-12 | External `.srt`/`.ass` subtitle files can be attached to VOD playback. | COULD | Free | Both |
| FR-PLB-13 | Aspect ratio and scaling modes (fit, fill, zoom, stretch, 16:9, 4:3, original) are selectable. | MUST | Free | Both |
| FR-PLB-14 | Stream variant/resolution is selectable, with an auto option. | SHOULD | Free | Both |
| FR-PLB-15 | Auto Frame Rate switching matches the display refresh rate to the stream, with a configurable delay. | SHOULD | Paid | TV |
| FR-PLB-16 | Buffer size is configurable via presets and explicit millisecond values. | MUST | Free | Both |
| FR-PLB-17 | On stream failure the app retries with backoff, then fails over to the next declared source for that channel, reporting what happened. | MUST | Free | Both |
| FR-PLB-18 | Up/Down zapping shows an auto-dismissing info card with channel number, name, logo and now/next progress. | MUST | Free | Both |
| FR-PLB-19 | Direct numeric channel entry is supported with a confirm timeout. | MUST | Free | TV |
| FR-PLB-20 | A single action returns to the previously watched channel. | MUST | Free | Both |
| FR-PLB-21 | The app can resume the last watched channel on start. | SHOULD | Free | Both |
| FR-PLB-22 | Live playback can be paused, rewound and resumed within a configurable timeshift buffer, with a visible offset-from-live indicator and a jump-to-live action. | SHOULD | Free | Both |
| FR-PLB-23 | Playback continues in Picture-in-Picture, triggered explicitly and optionally on Home press. | SHOULD | Paid | Both |
| FR-PLB-24 | Multi-view renders 2–4 simultaneous channels in a grid, with one active audio panel, per-panel channel selection, swap, mute and expand. | SHOULD | Paid | TV |
| FR-PLB-25 | Multi-view warns when device decoder limits or provider connection limits are likely to be exceeded. | SHOULD | Paid | TV |
| FR-PLB-26 | Multi-view layouts can be saved and restored as named presets. | COULD | Paid | TV |
| FR-PLB-27 | The adjacent channel is pre-buffered during zapping to reduce switch latency. | COULD | Free | Both |
| FR-PLB-28 | VOD and series are browsable with artwork, synopsis and season/episode structure. | MUST | Free | Both |
| FR-PLB-29 | VOD/series playback positions are stored and surfaced in a continue-watching view. | SHOULD | Free | Both |
| FR-PLB-30 | RTSP/RTMP sources are playable. | COULD | Free | Both |

## UI — Interface and Navigation

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-UI-01 | The TV interface is fully operable with a D-pad; no screen requires a pointer or touch. | MUST | Free | TV |
| FR-UI-02 | The app opens directly into fullscreen playback rather than a menu. | MUST | Free | TV |
| FR-UI-03 | A channel-list overlay shows groups, channels and now/next while video continues playing. | MUST | Free | Both |
| FR-UI-04 | A mini guide shows a single-channel horizontal timeline over playing video. | MUST | Free | Both |
| FR-UI-05 | A full grid guide shows channels against a time axis with a current-time marker, hour/day scrolling, jump-to-now and jump-to-date. | MUST | Free | Both |
| FR-UI-06 | The guide remains interactive while playback continues. | MUST | Free | Both |
| FR-UI-07 | Guide rendering is virtualized and holds 60 fps while scrolling a 500-channel × 7-day grid on a mid-range TV box. | MUST | Free | TV |
| FR-UI-08 | Programme details show title, times, description, category, series/episode and artwork, with actions: watch, record, record series, catch-up, remind, favourite. | MUST | Free | Both |
| FR-UI-09 | Search covers channels, future and catch-up programmes, VOD and series. | MUST | Free | Both |
| FR-UI-10 | Catch-up availability and recording state are badged in the guide. | MUST | Free | Both |
| FR-UI-11 | Remote key actions are user-configurable for OK (short/long), directions, Back, Back-double, numerics, colour keys and transport keys. | SHOULD | Free | TV |
| FR-UI-12 | A key-diagnostic screen displays raw keycodes so non-standard remotes can be bound. | SHOULD | Free | TV |
| FR-UI-13 | The mobile app provides a touch-native interface with bottom navigation, sticky-header channel list and fast scroll. | MUST | Free | Mobile |
| FR-UI-14 | Playback gestures on mobile: vertical swipe for brightness/volume, horizontal swipe to seek, double-tap to skip, pinch to change aspect ratio. | MUST | Free | Mobile |
| FR-UI-15 | The mobile app browses full screen and plays in a dedicated Now Playing screen — video above its details in portrait, fullscreen in landscape — with a mini player keeping playback reachable while browsing (ADR-011; originally "portrait pins the video top with the list below"). | MUST | Free | Mobile |
| FR-UI-16 | Tablets use a two-pane master/detail layout. | SHOULD | Free | Mobile |
| FR-UI-17 | Themes (system/light/dark/AMOLED) and an accent colour are selectable. | SHOULD | Free | Both |
| FR-UI-18 | Overlay opacity and auto-hide timeout are configurable. | SHOULD | Free | Both |
| FR-UI-19 | Font scale is adjustable and the guide reflows accordingly. | SHOULD | Free | Both |
| FR-UI-20 | The playback action menu is user-configurable in content and order. | COULD | Free | Both |
| FR-UI-21 | The interface fully supports RTL languages including Hebrew, with a correctly mirrored guide and right-anchored time axis. | SHOULD | Free | Both |
| FR-UI-22 | The app can cast the current stream to a Chromecast device from the mobile app. | COULD | Paid | Mobile |
| FR-UI-23 | The interface language can be chosen in the app — follow the device, English or Hebrew — independently of the device locale, and choosing a right-to-left language mirrors the layout. | SHOULD | Free | Both |
| FR-UI-24 | The television opens on a home screen: a navigation rail, the live picture as a hero with its now/next guide text, and horizontal rails of cards. Video keeps playing throughout. | SHOULD | Free | TV |
| FR-UI-25 | The home screen's rails are built from what the device already has — recently watched, the channel list, radio, favourites, and programmes starting soon — and a rail with nothing in it is not drawn. | SHOULD | Free | TV |
| FR-UI-26 | A featured rail can be pointed at a JSON feed of promoted items. No feed is bundled; with none configured the rail does not exist. | COULD | Free | TV |
| FR-UI-27 | The app draws its interface in a typeface it ships itself, covering Hebrew and Latin in one family, rather than the device's default fonts. | SHOULD | Free | Both |
| FR-UI-28 | The television home screen leads with the live picture as a full-bleed banner, a navigation rail that shows icons until focus enters it, and a strip of shortcuts to the streaming apps installed on the device. | SHOULD | Free | TV |
| FR-UI-29 | The guide offers its days as a strip above the grid, shows the current time, and lifts the focused cell out of the grid. | SHOULD | Free | Both |

## PRF — Profiles and Parental Controls

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-PRF-01 | Multiple local profiles each hold their own playlists, favourites, groups, history, resume points and settings. | SHOULD | Paid | Both |
| FR-PRF-02 | Profiles can be switched quickly from the playback overlay. | SHOULD | Paid | Both |
| FR-PRF-03 | A profile can be restricted to an allow-list of groups (kids profile). | SHOULD | Paid | Both |
| FR-PRF-04 | A 4–6 digit PIN can be set and is required for the actions the user selects (settings, locked groups, locked channels, profile switch, disabling parental controls). | MUST | Free | Both |
| FR-PRF-05 | Channels and groups can be locked individually, and either hidden or shown-but-blocked. | MUST | Free | Both |
| FR-PRF-06 | Content can be locked automatically by EPG rating where the guide supplies one. | COULD | Free | Both |
| FR-PRF-07 | Repeated failed PIN attempts trigger a backoff lockout. | SHOULD | Free | Both |
| FR-PRF-08 | The PIN is stored only as a salted hash and a documented recovery flow exists. | MUST | Free | Both |

## CU — Catch-up / Archive

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-CU-01 | Catch-up availability and window length are detected per channel from playlist attributes or provider account info. | MUST | Free | Both |
| FR-CU-02 | The guide can be scrolled backwards to past programmes with the same fluidity as forwards. | MUST | Free | Both |
| FR-CU-03 | Catch-up URLs are constructed for default/append, shift, flussonic and xc template modes, with UTC and local time variants. | MUST | Free | Both |
| FR-CU-04 | Catch-up playback supports pause, seek, speed control and jump-to-live. | MUST | Free | Both |
| FR-CU-05 | A failed catch-up request reports the specific cause (outside window / unsupported / auth). | SHOULD | Free | Both |
| FR-CU-06 | A dedicated catch-up browse view lists available past content by channel and day. | SHOULD | Free | Both |

## REC — Recording / DVR

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-REC-01 | The user can instantly record the channel being watched, defaulting to the remainder of the current programme, with custom or open-ended duration. | MUST | Free¹ | Both |
| FR-REC-02 | The user can schedule a recording from any future programme in the guide. | MUST | Free¹ | Both |
| FR-REC-03 | Configurable pre-roll and post-roll padding is applied to scheduled recordings. | MUST | Free¹ | Both |
| FR-REC-04 | The user can schedule a series recording for all future episodes. | SHOULD | Paid | Both |
| FR-REC-05 | If the EPG lacks series metadata the app states this at scheduling time and offers a repeat-rule alternative. | MUST | Paid | Both |
| FR-REC-06 | Repeat rules (channel + weekdays + time window) can be created without EPG data. | SHOULD | Paid | Both |
| FR-REC-07 | A manual timer (channel + start + duration) can be created without any EPG. | SHOULD | Free¹ | Both |
| FR-REC-08 | Scheduling detects conflicts against the concurrent-recording limit and the provider connection limit, and offers to cancel or shift. | SHOULD | Paid | Both |
| FR-REC-09 | The recording destination is selectable: internal storage, external USB, or SMB share. | MUST | Free¹ | Both |
| FR-REC-10 | Recordings are written as MPEG-TS without re-encoding and are playable by third-party players. | MUST | Free¹ | Both |
| FR-REC-11 | The output filename template is configurable. | COULD | Paid | Both |
| FR-REC-12 | Recording is refused when free space is insufficient, and the user is warned below a configurable threshold. | MUST | Free¹ | Both |
| FR-REC-13 | Retention rules (delete after N days, keep last N episodes, delete oldest below X GB free) are configurable and opt-in. | SHOULD | Paid | Both |
| FR-REC-14 | Recording runs in a foreground service independent of any visible UI. | MUST | Free¹ | Both |
| FR-REC-15 | Schedules use exact alarms and are re-armed after device reboot. | MUST | Free¹ | Both |
| FR-REC-16 | Wake and WiFi locks are held for the duration of a recording. | MUST | Free¹ | Both |
| FR-REC-17 | Where the platform cannot wake the device, the user is told at scheduling time that the device must stay on. | MUST | Free¹ | Both |
| FR-REC-18 | A dropped stream mid-recording triggers reconnection and continued writing to the same file, with the gap logged. | SHOULD | Paid | Both |
| FR-REC-19 | Every recording produces a visible result record (success / partial / failed with reason). | MUST | Free¹ | Both |
| FR-REC-20 | The recordings library supports play, resume, delete, rename, mark-watched, share, filter, search and sort. | MUST | Free¹ | Both |
| FR-REC-21 | Scheduled recordings can be viewed, edited and cancelled individually or by series. | MUST | Free¹ | Both |

¹ Free tier is limited to 1 concurrent recording; paid is unlimited (see `docs/06`).

## NET — Network and Request Shaping

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-NET-01 | A custom User-Agent can be set globally and per playlist, with presets and free text. | MUST | Free | Both |
| FR-NET-02 | Custom HTTP headers (Referer, Origin, Cookie, Authorization) can be set per playlist. | SHOULD | Free | Both |
| FR-NET-03 | Per-channel network options from `#EXTVLCOPT` / `#KODIPROP` are honoured. | SHOULD | Free | Both |
| FR-NET-04 | Connection timeout and retry count are configurable. | SHOULD | Free | Both |
| FR-NET-05 | Self-signed certificates can be trusted via an explicit, warned, off-by-default setting. | COULD | Free | Both |
| FR-NET-06 | The app detects an apparent geo/IP block and reports it as such rather than as a generic error. | COULD | Free | Both |

## SET — Settings, Lifecycle and Data

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-SET-01 | Playback can be handed off to an external player, scoped to never / VOD only / always / per channel. | SHOULD | Free | Both |
| FR-SET-02 | The app can start automatically on device boot. | SHOULD | Free | TV |
| FR-SET-03 | The app can start automatically on wake from standby. | COULD | Free | TV |
| FR-SET-04 | Start behaviour is selectable: last channel / specific channel / channel list / guide. | SHOULD | Free | Both |
| FR-SET-05 | A sleep timer can be set by duration or "after this programme", warning before it fires. | SHOULD | Free | Both |
| FR-SET-06 | Exit requires a second Back press, configurably. | SHOULD | Free | TV |
| FR-SET-07 | Static overlays are dimmed or hidden after a configurable idle period to reduce burn-in risk. | COULD | Free | TV |
| FR-SET-08 | The full configuration can be exported to and restored from a single portable file, with credentials optionally excluded. | MUST | Free | Both |
| FR-SET-09 | Backups can be written to a local file, a SAF location, Google Drive or an SMB share. | SHOULD | Free | Both |
| FR-SET-10 | Favourites, groups, ordering, resume points, history and settings sync across the user's devices via an opt-in account, with client-side encryption of any credentials and explicit conflict resolution. | SHOULD | Paid | Both |
| FR-SET-11 | Programme reminders can be set with configurable lead time and an optional auto-switch to the channel. | SHOULD | Free | Both |
| FR-SET-12 | Notifications are issued for recording started, finished and failed. | MUST | Free | Both |
| FR-SET-13 | A diagnostics screen shows refresh results, EPG match statistics, current stream URL with credentials redacted, negotiated codecs, decoder type, bitrate, dropped frames, buffer health and recent errors. | SHOULD | Free | Both |
| FR-SET-14 | A diagnostic report can be copied or shared with credentials automatically redacted. | COULD | Free | Both |
| FR-SET-15 | Cache size is displayed and clearable (EPG, images, all), and total cache is bounded by policy with LRU eviction. | MUST | Free | Both |
| FR-SET-16 | Verbose logging to a rotating local file can be enabled, off by default. | COULD | Free | Both |
| FR-SET-17 | Playback settings can be reverted to their previous values after a change. | COULD | Free | Both |

## LIC — Entitlement and Accounts

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-LIC-01 | The free tier is fully usable with no account. | MUST | Free | Both |
| FR-LIC-02 | A paid entitlement covers up to 5 devices on one account. | MUST | Paid | Both |
| FR-LIC-03 | Active devices are listed in-app and can be deactivated remotely. | MUST | Paid | Both |
| FR-LIC-04 | Entitlement is cached and honoured offline for at least 14 days. | MUST | Paid | Both |
| FR-LIC-05 | On expiry, paid features are disabled but playlists, favourites and recordings remain intact and accessible. | MUST | Paid | Both |
| FR-LIC-06 | Purchases can be restored on a fresh install without support intervention. | MUST | Paid | Both |
| FR-LIC-07 | In-app purchase is available on every device where store billing exists. | MUST | Paid | Both |
| FR-LIC-08 | A web checkout and a companion path exist for devices without store billing (e.g. Fire TV, sideloads). | SHOULD | Paid | Both |
| FR-LIC-09 | A time-limited full-feature trial is offered. | SHOULD | Paid | Both |
| FR-LIC-10 | No advertising is shown in any tier. | MUST | — | Both |

## TVI — Google TV / Platform Integration

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-TVI-01 | The app registers a Leanback launcher activity and TV banner and is fully usable without a touchscreen. | MUST | Free | TV |
| FR-TVI-02 | Continue-watching entries for VOD, series and recordings are published to Google TV Watch Next. | COULD | Free | TV |
| FR-TVI-03 | A preview/recommendation channel (e.g. Favourites, On Now) is published to the Google TV home screen. | COULD | Free | TV |
| FR-TVI-04 | Deep links from launcher entries resume the correct channel or programme. | COULD | Free | TV |
| FR-TVI-05 | Widevine-protected streams play where the device supports the required security level, with licence configuration read from playlist properties. | SHOULD | Free | Both |
| FR-TVI-06 | A stream requiring unsupported DRM produces a clear explanatory message, never a silent black screen. | MUST | Free | Both |
| FR-TVI-07 | The app registers as a TV Input Framework input so channels appear in the system Live TV app. | WON'T | — | TV |

## AUTO — Android Auto

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-AUTO-01 | The bundled Israeli radio stations (FR-SRC-16) are browsable and playable through Android Auto's media template. | MUST | Free | Mobile |
| FR-AUTO-02 | Android Auto radio playback uses a player instance isolated from in-app playback: playing a station in the car does not interrupt, and is not interrupted by, whatever is playing in the phone/TV UI. | MUST | Free | Mobile |
| FR-AUTO-03 | The car's native skip-next/previous controls cycle through the radio station list in order. | SHOULD | Free | Mobile |
| FR-AUTO-04 | The app is distributed to Android Auto sideload-only, via Android Auto's own "Unknown sources" developer setting — not through Google's Android-Auto-specific Play Store review. | MUST | — | Mobile |

## UPD — In-app Updates

| ID | Requirement | Prio | Tier | Plat |
|---|---|---|---|---|
| FR-UPD-01 | Each distributed build publishes a manifest describing the newest release: version code and name, APK URL, SHA-256, size, minimum API level and release notes. | MUST | Free | Both |
| FR-UPD-02 | The app checks that manifest in the background at most once a day, and whenever the user asks. | MUST | Free | Both |
| FR-UPD-03 | A newer build is offered to the user with its version, size and notes; the offer can be postponed or skipped, and never interrupts playback in picture-in-picture. | MUST | Free | Both |
| FR-UPD-04 | The user can download and install an offered update from inside the app, with visible progress. | MUST | Free | Both |
| FR-UPD-05 | A downloaded APK is installed only when its SHA-256 matches the manifest and its signing certificate matches the running app's; the manifest is fetched over HTTPS only. | MUST | Free | Both |
| FR-UPD-06 | Automatic checking and Wi-Fi-only downloading are settings, both on by default; a skipped version is remembered until a newer one appears. | MUST | Free | Both |
| FR-UPD-07 | An update is refused while a recording is in progress, and the reason is stated. | MUST | Free | Both |
| FR-UPD-08 | Scheduled recordings survive the app being replaced by an update. | MUST | Free | Both |
