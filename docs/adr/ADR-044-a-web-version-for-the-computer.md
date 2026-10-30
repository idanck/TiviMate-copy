# ADR-044 — A web version for the computer

**Status:** accepted, 0.18.0. From the owner's request of 2026-10-19: *a PWA version, so I can use
this on the computer.*
Its look follows ADR-046 (Merhav) from 0.20.0: the Studio colours and two typefaces this record reuses
are replaced by Merhav's tokens and Noto Sans Hebrew.

## Context

Shidur runs on Android television and phones (ADR-002). The owner wants it on a computer as well,
as an installed app rather than a page. A progressive web app is the way to get that on Windows,
macOS and Linux from one code base: Chrome and Edge install it with its own window, icon and
start-menu entry.

A browser, though, cannot do by itself what the Android app does through OkHttp and ExoPlayer:

- IPTV providers send no CORS headers, so a page may not read their playlists, guides or panel
  JSON.
- Most streams, and the Knesset channel in the built-in playlist, are plain HTTP: blocked as mixed
  content on an HTTPS page.
- A page cannot set `User-Agent`, `Referer` or `Cookie`, which providers gate on (ADR-018,
  FR-NET-01).
- An Xtream panel's live streams are raw MPEG-TS behind a redirect, which no browser plays
  natively; and the owner's provider allows TS only (no HLS — ADR-041's measurements).
- Browsers have no AC-3 decoder in their media pipeline, and Israeli sports channels commonly
  carry AC-3 audio.

## Decision

1. **A web app in `web/`, written for the browser, not compiled from the Android code.** Preact,
   TypeScript, Vite. The logic that decides what the user sees is ported with its rules intact —
   the M3U reader, Xtream, the XMLTV reader, the guide matcher and name normalisation, the
   Israeli sport/news and radio classifiers, channel identity and failover — and held to the
   Android unit tests' own fixtures (`web/test/data.test.ts`). Kotlin Multiplatform or a WebView
   wrapper were rejected: the first is a migration of the whole data layer for one more target,
   the second is not an app on a computer.

2. **It needs its own small server, which is also what serves it.** `web/server`, plain Node with
   no dependencies: the built app, and a proxy that every provider URL goes through — text through
   `/api/fetch` (gzip-decoded whatever the server said), media through `/api/stream` (ranges
   intact; HLS playlists rewritten so every segment, key and variant comes back through it, against
   the playlist's address after redirects), a stream-type probe for addresses with no extension,
   and `/api/remux`, which copies the video and re-encodes the audio to AAC with ffmpeg. Per-request
   headers travel as `h=<base64url JSON>` so a rewritten playlist hands them on to its segments.
   The server stores nothing and logs only failures, with credentials redacted.

3. **The proxy is guarded.** A password (`SHIDUR_WEB_PASSWORD`) puts a signed, stateless session
   cookie in front of every route that reaches out; ten wrong passwords lock an address out for
   ten minutes. Upstream addresses on private, loopback, link-local and CGNAT ranges are refused
   unless `SHIDUR_WEB_ALLOW_PRIVATE=1`, so a proxy on a home server is no way into the home
   network. Redirects are followed by hand so every hop passes the same check.

4. **Playback picks its decoder per stream:** hls.js for HLS, mpegts.js for MPEG-TS and FLV, the
   browser itself for progressive audio and video, a probe of the first bytes when the address
   says nothing. When the browser cannot decode the audio — a codec error, or AC-3 announced in the
   stream's media info — the same stream is asked for again through `/api/remux`. A channel's
   further addresses are tried in turn (FR-PLB failover); one that played and then dropped is
   reconnected after 5 s, 10 s, then every 15 s, as `ReconnectPolicy` does on Android; one that
   never started says why and waits for the user, so a dead address does not hold a provider's
   only connection.

5. **The user's data stays in the browser.** Sources (Xtream passwords included), channel lists,
   guides cut to the bound channels and to −12 h…+48 h, favourites and history live in IndexedDB;
   preferences in localStorage. The guide is read in a worker, streamed, never held whole. The
   Android backup format (`BackupManager`, format 1) is read and written, so playlists and
   favourites move between the television and the computer in either direction.

6. **One screen at a time, one video across them.** A single media element is laid over whichever
   slot the current screen offers — the channel list's stage, the guide's preview — so changing
   screen never restarts the stream, as the television keeps one player behind every overlay. With
   no slot it shrinks to a corner; radio is heard and drawn by its own screen. Layout follows the
   Hebrew television: right to left, the rail on the right, time in the guide running right to
   left. Studio's colours, its icon paths (ADR-033) and its two typefaces (ADR-019) are reused as
   they are.

7. **Scope of the first version:** live channels with groups, favourites, recents and the Israeli
   sport/news filters; the guide grid with programme details; radio by shelf and region; search
   over channels, stations and the guide; sources (M3U address, M3U file, Xtream), guide offset,
   User-Agent, backup import/export; keyboard shortcuts in place of the remote; media keys through
   the Media Session API; picture in picture. **Not in it:** recording and catch-up, VOD, sport and
   news multi-view (ADR-041, ADR-042), the parental lock, the screensaver, and English.

8. **Versioning and CI.** The web app carries the version in `version.properties`, like the APK.
   `.github/workflows/web.yml` typechecks, tests, builds and builds the Docker image. `android.yml`
   ignores `web/**`: a web-only change has nothing to publish to devices, and would otherwise
   rebuild an APK under the published versionCode, which the publish step rightly refuses.

## Consequences

- The web app needs somewhere to run its server. The repository ships a Docker image definition
  and a compose example; where it is deployed, and behind which HTTPS, is a deployment decision
  outside this record.
- A provider with `max_connections = 1` sees the computer as one more viewer: watching on it
  stops the television, and the other way round.
- Video the browser cannot decode is not converted; H.265 plays only where browser and hardware
  allow it. Converting video would cost a CPU core per viewer.
- Two implementations of the same parsing rules now exist. A rule changed on one side must be
  changed on the other; the shared fixtures in the web tests are where a drift shows first.
