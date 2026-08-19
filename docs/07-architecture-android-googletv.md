# 07 — Android / Google TV Architecture Notes

## 7.1 Manifest and form factors

To ship one binary for phone and TV:

```xml
<uses-feature android:name="android.software.leanback" android:required="false" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
<uses-feature android:name="android.hardware.microphone" android:required="false" />
```

- A launcher activity with `CATEGORY_LEANBACK_LAUNCHER` for TV, alongside the normal
  `CATEGORY_LAUNCHER` for mobile.
- `android:banner` (320×180 xhdpi) for the TV launcher tile, plus the standard icon set.
- Declare `android:supportsPictureInPicture="true"` and
  `configChanges="screenSize|smallestScreenSize|screenLayout|orientation"` on the player
  activity.
- Google Play requires explicitly opting the release into the Android TV form factor;
  TV builds are reviewed against 10-foot usability criteria (D-pad reachability, banner,
  no touch-only flows).

## 7.2 Player stack

- **AndroidX Media3** (`media3-exoplayer`, `-hls`, `-dash`, `-ui`, `-session`,
  `-datasource-okhttp`).
- `DefaultTrackSelector` for audio/subtitle/video track selection and preferred-language
  handling.
- `DefaultRenderersFactory` with
  `EXTENSION_RENDERER_MODE_PREFER` when software/extension decoders are selected.
- `DefaultLoadControl` values driven by the buffer preset.
- `MediaSession` + `MediaSessionService` for notification controls, background audio,
  Android Auto/Assistant integration and TV playback controls.
- Custom `DataSource.Factory` wrapping OkHttp to inject per-playlist User-Agent, headers,
  cookies, and to handle redirects and retry policy.

## 7.3 Recording pipeline

- Prefer a **remuxing sink** — read the transport stream and write it out without
  re-encoding. For MPEG-TS the simplest correct approach is a byte-passthrough writer on
  a second HTTP connection, independent of the playback pipeline, so recording is not
  coupled to what is on screen.
- For HLS sources, concatenate segments into a continuous TS, handling discontinuities.
- Run inside a foreground service (`FOREGROUND_SERVICE_MEDIA_PLAYBACK` /
  `...DATA_SYNC` as appropriate for the target API), with `WakeLock` + `WifiLock`.
- Schedule with `AlarmManager.setExactAndAllowWhileIdle`; request
  `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` where required by API level; re-arm on
  `BOOT_COMPLETED`.
- Write via SAF `DocumentFile` to a user-granted tree, so no broad storage permission is
  needed on Android 10+.

## 7.4 Google TV home-screen integration

Optional but high-value for discoverability and for feeling native:

- **Watch Next** (`TvContractCompat.WatchNextPrograms`): push "continue watching" entries
  for VOD, series and recordings.
- **Preview / recommendation channels** (`PreviewChannel`, `PreviewProgram`): a
  "Favourites" or "On now" row on the launcher.
- Requires `com.android.providers.tv.permission.WRITE_EPG_DATA` and careful lifecycle
  management so stale entries are removed.
- Handle launcher deep-link intents back into the right channel/programme.

## 7.5 TV Input Framework (TIF) — deferred

Registering as a TV input would make the user's channels appear inside the system Live TV
app. Evaluated and **deferred**: TIF integration for a third-party input is limited, and
DVR, timeshift and parental features through TIF generally require system-app
privileges that a sideloaded/Play-installed app does not have. The reference product is
likewise a standalone Leanback app, not a TIF input. Revisit post-v1.

## 7.6 DRM

Most IPTV live streams are clear, but plan for it:

- Widevine via Media3 `DrmSessionManager`, L1 where the device provides it, L3 fallback.
- Parse `#KODIPROP:inputstream.adaptive.license_key` / `license_type` style hints from the
  playlist as licence configuration.
- ClearKey support for DASH sources that use it.
- The app must degrade with a clear message when a stream requires DRM the device cannot
  satisfy — never a silent black screen.

## 7.7 Picture-in-Picture

- `PictureInPictureParams.Builder` with `setAspectRatio` and `setActions`; auto-enter on
  `onUserLeaveHint` when enabled.
- PiP on TV requires API 24+; on phones, API 26+ for the modern behaviour. Guard by
  `packageManager.hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)`.

## 7.8 Data layer

- **Room** for channels, groups, programmes, mappings, recordings, schedules, history.
  A 7-day guide for 1,000 channels is on the order of 10^5–10^6 rows — index on
  (channelId, startUtc) and page aggressively.
- XMLTV parsing streamed with a pull parser; never load the whole document into memory.
  Gzip handled transparently. Parse on a background dispatcher with progress reporting.
- **WorkManager** for playlist/EPG refresh; **AlarmManager** for recording (WorkManager is
  not precise enough for a scheduled recording start).
- DataStore for settings; encrypted storage for credentials
  (`EncryptedSharedPreferences` / Tink, or Keystore-wrapped keys).

## 7.9 Permissions to request (and justify)

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Streaming |
| `FOREGROUND_SERVICE` (+ typed variant) | Playback and recording services |
| `WAKE_LOCK` | Keep recording alive |
| `RECEIVE_BOOT_COMPLETED` | Re-arm schedules, auto-start on TV |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Recording start times |
| `POST_NOTIFICATIONS` | Reminders, recording status (Android 13+) |
| `WRITE_EPG_DATA` | Watch Next / preview channels (TV only) |

Explicitly avoid: broad legacy storage, location, contacts, phone state.

## 7.10 Store policy

- The listing describes a player. It must not name providers, imply access to content, or
  include screenshots showing recognizable copyrighted channel branding.
- Include a clear statement that the app supplies no content and requires the user's own
  subscription.
- Fire TV distribution is sideload-only unless Amazon Appstore review is pursued
  separately.
