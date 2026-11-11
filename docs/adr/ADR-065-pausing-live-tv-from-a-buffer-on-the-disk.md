# ADR-065 — Pausing live TV from a buffer on the disk

**Status:** accepted 2026-11-11, built in 0.31.0. FR-PLB-22 (`docs/02` §2.9).

## Context

On 2026-11-11 the owner asked for live television to pause, rewind and fast-forward. He chose the
buffer on the disk over a larger player buffer in memory and over the provider's own window. He
also chose the whole requirement in the first version: the bar through the buffer, half a minute
back and forward, back to live, and the buffer's length in Settings.

Two facts decide the design:

- **The provider's channels are one endless MPEG-TS stream.** Media3 plays it progressively: it
  has no length and no index, so it cannot be seeked in, and pausing it only stops the reading.
  The server then holds or drops the connection.
- **The owner's account allows one connection.** Anything that opens the stream a second time
  closes the first (ADR-041). The buffer cannot be a second reader beside the player's.

## Decision

**One reader, and the player plays local segments.** A `TimeshiftSession` owns the only
connection. It reads on a thread of its own, at the stream's pace, whatever the player is doing.
It hands the bytes to a `TsSegmenter`, which cuts them into segment files of about two seconds.
Each segment:

- opens on a picture the decoder can start from (the adaptation field's random-access flag, or an
  IDR/IRAP or sequence header);
- carries the stream's PAT and PMT;
- is timed by its PES timestamps (90 kHz, 33 bits, wrapping).

A clock that jumps, or a connection opened again, starts a segment marked as a discontinuity.
`TimeshiftWindow` lists the segments as a sliding HLS media playlist. The player is given
`timeshift://<session>/live.m3u8`, served from memory by `TimeshiftPlaylistDataSource`, with the
segments as `file://` addresses. Media3's own HLS live window then does the rest: pause, seek
within the window, a position and a duration, and the default position at the live edge.

- **Live offset six seconds, speed fixed at 1.0×.** A viewer behind live stays where he is and is
  never played faster to catch up.
- **The cut:** at a key frame once a segment is two seconds long, at any picture once it is six,
  so a stream with rare key frames still makes segments. Sound-only streams are cut on their
  audio.

**What is buffered:** live channels on the full-screen player (`Role.MAIN`) whose stream sniffs as
MPEG-TS, with the setting on.

Not buffered:

- radio (the host says so);
- Sport and News panels;
- catch-up, on-demand and recordings (`playUrl`);
- DRM channels.

A stream that turns out not to be TS is played directly, and so is one with no starting picture
within 15 seconds or a buffer that cannot write. In those cases the session fails with
`TimeshiftUnavailableException` and the engine plays the address directly at once, without
spending a retry. It remembers that address as direct for the rest of the channel. HLS channels
are played directly, as before; their pause is the provider's window.

**Failures look the same as before.** A dropped connection is opened again by the session, up to
two times in a row, and the segments after it are marked as a discontinuity. Past that, or on an
HTTP answer such as a 404, the session fails. The playlist then throws that same exception to the
player, so the engine's retries, failover and `ReconnectPolicy` see what a direct stream would
have given them. A viewer paused past the oldest segment gets `BEHIND_LIVE_WINDOW`, which the
engine answers by playing from the oldest it still has; it is not shown as an error.

**The background lets go.** When the host pauses for the background (`setPlayWhenReady(false)`:
no floating window), the session stops: no connection and no disk use behind the viewer's back.
Back in front, the channel starts again, live. A pause by the viewer (`togglePlayPause`) keeps the
buffer filling.

**Storage.** The buffer lives in `noBackupFilesDir/timeshift`, outside `cacheDir`, because the
cache budget's LRU (FR-SET-15) would otherwise delete segments while they play. Each session has
its own directory named by the clock. A directory left by an earlier process is deleted off the
main thread when the first session starts. The cap is the smaller of:

- the minutes in Settings (5, 10, 15, 30, 60 or 120; 15 by default);
- the free space less 500 MB.

Below 64 MB of room there is no buffer, and the channel plays directly. Three segments always
stay, whatever the caps.

**The UI.**

- **Television's transport bar:**
  - the buffer as a bar, with "−mm:ss behind live" in amber while behind;
  - the live badge only at live;
  - buttons for 30 seconds back, 30 seconds forward and "Back to live";
  - the remote's rewind and fast-forward keys on the video.
- **Phone's player:** the same bar and buttons. Its swipe already seeks, because the buffered
  item is seekable.

**Settings:** "Pause live TV" (`timeshift_enabled`) and how much to keep (`timeshift_minutes`).
The owner decided on 2026-11-11 that the switch is off by default, on new and existing devices
alike, and that the buffer keeps 15 minutes by default, with less and more to choose from.
Both are plain preferences, so the hand-over (ADR-060) and backups carry them as they are.

## Consequences

- **Zapping to a TS channel waits for the first two segments:** the first key frame, then about
  four seconds. With one segment the player started on it and stalled until the next was cut
  (seen on the living room's television, 2026-11-11). The player's start position is six seconds
  behind the newest segment's start, so a buffered channel starts a little later and sits a few
  seconds further from live than a direct one did.
- **Every hour watched with the setting on is written to the device's storage once.**
- **A reconnect longer than Media3's playlist-stuck limit** (three and a half target durations,
  about seven seconds) ends the session as an error. The engine's retry then starts a new buffer,
  and what was buffered is lost.
- **QA-29** checks the buffer on the television emulator:
  - pause, then about the pause's length behind;
  - rewind;
  - back to live;
  - one connection to the stream for all of it.

  It runs before QA-13, where the television emulator has been dying.
