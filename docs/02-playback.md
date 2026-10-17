# 02 — Playback

## 2.1 Engine

**AndroidX Media3 (ExoPlayer)** is the chosen engine. It gives us, out of the box:
HLS, DASH, SmoothStreaming and progressive/MPEG-TS extraction; `MediaCodec` hardware
decoding with software fallback; track selection; CEA-608/708 and DVB subtitle rendering;
`MediaSession` integration; and a configurable `LoadControl` for buffering.

The reference product is widely understood to be ExoPlayer-based, which is consistent
with its decoder-toggle and buffer-preset behaviour, though this is not officially
documented by its developer.

## 2.2 Protocols and containers

| Must support | Notes |
|---|---|
| HLS (`.m3u8`) | Live and VOD, multi-variant, discontinuities, LL-HLS best-effort |
| MPEG-TS over HTTP | The default Xtream live path; long-running, no manifest |
| Progressive HTTP (mp4, mkv) | VOD |
| MPEG-DASH | Native in Media3; enable it |
| HTTPS with non-standard/invalid certs | Configurable leniency, off by default, warned |

`RTMP` and `RTSP` are **COULD** (Media3 has an RTSP module; RTMP needs an extension).
Not required for MVP.

## 2.3 Codecs and decoding

- Video: H.264, H.265/HEVC, and where the device supports it AV1 and VP9.
- Audio: AAC, MP3, AC-3/E-AC-3, and DTS where the device supports it.
- **Hardware decoding by default**, with an explicit **Hardware / Software toggle for
  video and for audio, independently**, available both globally and **per channel**, with
  the per-channel choice remembered. This is the single most effective user-side fix for
  "this one channel won't play" and must be easy to reach from the playback overlay.
- Automatic fallback: if hardware init fails, retry once in software and tell the user
  what changed.
- Vendor quirk switches (e.g. an Amlogic-style compatibility mode) behind an Advanced
  section, defaulted off.

## 2.4 Audio

- Audio track selection by index/language/codec, from the playback overlay.
- Audio channel/downmix mode (stereo / surround / passthrough).
- **Passthrough** of AC-3/E-AC-3/DTS to the AVR where supported (paid tier in the
  benchmark; we should consider making it free — it is a correctness feature, not a
  luxury).
- **Audio delay / sync offset** slider in ±10 ms steps, applicable to the current channel
  or globally.
- Remember the preferred audio language and auto-select it on every channel.

## 2.5 Subtitles

- Embedded tracks: CEA-608, CEA-708, DVB subtitles, WebVTT, TTML.
- Track selection, including "off" and "auto by preferred language".
- Styling: size, font, colour, background/box opacity, edge style, vertical position.
- External sidecar `.srt` / `.ass` for VOD — **SHOULD**, a known weakness of the benchmark.
- Live streams frequently carry no subtitles at all; the UI must say so rather than show
  an empty selector.

## 2.6 Video presentation

- Aspect ratio / scaling modes: fit, fill, zoom, stretch, 16:9, 4:3, original.
- Resolution/variant selection when the stream exposes multiple renditions, plus "auto".
- **Auto Frame Rate (AFR)** switching — match the display refresh rate to the stream
  (23.976/24/25/29.97/50/59.94), with a configurable switch delay and a
  "switch resolution too" option. Requires `Display.Mode` handling and is a genuine
  picture-quality differentiator on TVs.
- Optional video calibration: brightness/contrast/saturation — **COULD**.

## 2.7 Buffering and zapping

- Buffer presets (Minimal / Small / Normal / Large / Custom) mapped to explicit
  `DefaultLoadControl` min/max/playback buffer values; expose the millisecond values in
  Advanced so power users can tune.
- Target: channel change under **1.5 s** on a wired gigabit connection with a
  small buffer, measured from key press to first rendered frame.
- Pre-buffering of the adjacent channel on up/down zapping — **COULD**, big perceived-
  speed win.
- On stream failure: automatic retry with backoff, then automatic failover to the next
  declared source for that channel (playlists often list several URLs per channel), with
  a non-blocking toast explaining what happened. Never dump the user back to a black
  screen with no message.

## 2.8 Channel switching behaviour

- Up/Down zapping with an on-screen info card (channel number, name, logo, now/next with
  a progress bar), auto-dismissing after a configurable timeout.
- Direct numeric entry with a short confirm timeout.
- **Last channel toggle** (return to previous channel on a single key).
- Resume the last watched channel on app start (optional).
- History list of recently watched channels.

## 2.9 Timeshift (live pause)

- Pause, rewind and fast-forward within the live buffer for streams that permit it.
- Configurable timeshift buffer size and location (memory / disk).
- Clear on-screen indication of how far behind live the user is, and a "jump to live"
  action.

## 2.10 Picture-in-Picture

- Android PiP (`PictureInPictureParams`) so playback continues in a floating window while
  the user browses the guide, another app, or the launcher.
- Must be triggered explicitly *and* on Home press (configurable).
- Aspect ratio follows the video; PiP actions: play/pause, next/previous channel, expand.

## 2.11 Multi-view

- Simultaneous playback of multiple channels in a grid: 1, 2, 3 or 4 panels (2×2 max for
  v1 — more panels multiply provider connections and decoder pressure).
- Exactly one panel carries audio at a time; the user swaps the active panel with the
  D-pad or a tap.
- Per-panel channel picker, swap, mute, and expand-to-fullscreen.
- Guard rails: warn when the device reports insufficient concurrent decoder instances,
  and when the provider's connection limit is likely to be hit.
- Layout must be saveable and restorable as a named preset — **COULD**, differentiator.
- **Implemented as Sport mode (ADR-041):** Israeli sports channels only, and the sound follows
  the panel in focus. On the television, a two-by-two grid whose lines move toward the panels not
  in focus. On the phone, two panels or four, chosen in Sport mode's own bar: lying down they
  follow the television's grid, two side by side; held upright they stand one above the other.
  Presets and a connection-limit warning are not built yet.

## 2.12 VOD and series

- Browse Xtream VOD categories and series (season/episode tree) with poster art,
  synopsis, year, rating, duration.
- **Resume points** per item, synced across devices where sync is enabled.
- Continue-watching row.
- The benchmark's VOD experience is widely described as adequate but unremarkable; a
  properly designed VOD browser is a cheap differentiator.
