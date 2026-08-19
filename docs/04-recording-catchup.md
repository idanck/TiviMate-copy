# 04 — Recording and Catch-up

Two different things that users conflate:

- **Catch-up / archive** — the *provider* stores the past; we just request a time-shifted
  URL. No local storage, no scheduling.
- **Recording / DVR** — *we* store the stream locally. Needs scheduling, storage, and a
  live process.

## 4.1 Catch-up / archive

- Detect availability per channel from `catchup`, `catchup-source`, `catchup-days`
  (M3U) or the Xtream/Stalker account info.
- Show an archive badge on past programmes in the guide; scrolling backwards must be as
  fluid as scrolling forwards.
- Build the catch-up URL from the provider's template (supported modes: `default`/append,
  `shift`, `flussonic`, `xc`), substituting start time, duration/end, offset and
  UTC/local variants.
- Playback controls within a catch-up item: pause, seek, speed, jump to live.
- If a catch-up request fails, say *why* (outside window / not supported / auth) rather
  than showing a generic error.
- A dedicated "Catch-up" browse view (by channel, by day) — **SHOULD**.

## 4.2 Recording — functional

- **Instant record** of the channel being watched: record the remainder of the current
  programme by default, with an option to set a custom end time or open-ended.
- **Scheduled recording** from any future programme in the guide, with configurable
  pre-roll and post-roll padding (default 2 min / 5 min).
- **Series recording**: record all future episodes of a series. Requires series metadata
  in the EPG; if the EPG has none, the app must **say so at scheduling time** rather than
  silently recording nothing — a documented failure mode of the benchmark.
- **Repeat rules** as a fallback when series metadata is missing: same channel, same
  weekday(s), same time window.
- Manual timer: channel + start + duration, with no EPG involvement.
- Conflict detection at scheduling time: warn when concurrent recordings exceed the
  configured limit or the provider's connection limit, and offer to cancel/shift.
- No artificial cap on simultaneous recordings beyond hardware/connection limits, but a
  user-configurable maximum.

## 4.3 Recording — storage

- Target selectable at setup: internal storage, external USB/USB-HDD, or a network share
  (SMB) — SMB is **SHOULD**, not MVP.
- Container: **MPEG-TS** by default (remuxed, no re-encode) so files stay playable in any
  external player; MP4/MKV remux as an option where the source permits.
- Filename template configurable, defaulting to
  `{Channel}/{Series}/{S00E00} - {Title} - {yyyy-MM-dd HH-mm}.ts`.
- Free-space guard: refuse to start a recording that cannot plausibly fit; warn at a
  configurable threshold.
- **Retention**: auto-delete after N days, keep only the last N episodes per series, and
  a global "delete oldest when below X GB free" rule. All opt-in.
- Scoped storage: user picks the destination tree via the Storage Access Framework;
  we never request broad legacy storage permissions.

## 4.4 Recording — reliability (the differentiator)

The benchmark's most-cited weakness is that recordings only happen if the device is awake
and the app is running. We should do better:

- Recording runs in a **foreground service** with a persistent notification, independent
  of whether any UI is visible.
- Schedules use exact alarms (`AlarmManager.setExactAndAllowWhileIdle`) and re-arm on
  boot (`BOOT_COMPLETED`).
- Hold a partial wake lock plus a WiFi lock for the duration of a recording.
- Where the platform allows it (many Android TV boxes never truly sleep; some do), wake
  the device ahead of the scheduled start; where it does not, **tell the user clearly at
  scheduling time** that the device must remain on.
- Battery-optimization exemption requested once, with an explanation, on phones.
- Robustness: if the stream drops mid-recording, reconnect and continue writing to the
  same file rather than aborting; log the gap.
- Every recording produces a result record (success / partial / failed + reason) visible
  in the UI. Silent failure is not acceptable.

## 4.5 Recording — playback and management

- Recordings library with thumbnail, channel, date, duration, size, series grouping.
- Play, resume from position, delete, rename, mark watched, export/share.
- Filter and search; sort by date/channel/size.
- Scheduled list: view, edit padding, cancel, cancel-whole-series.
- Recordings written to a shared location must be playable by other devices/apps — no
  proprietary container or encryption.

## 4.6 Constraints to document in-app

- VOD/catch-up content generally cannot be recorded; only live streams.
- Recording consumes a provider connection for its whole duration.
- On Fire TV, external USB requires OTG and additional setup.
