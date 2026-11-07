# ADR-052 — A version for LG televisions (webOS)

**Status:** withdrawn 2026-11-06; replaced by [ADR-056](ADR-056-lg-televisions-from-webos-5.md). The webOS version was removed from the repo at the owner's request,
to be started again from scratch; this record is kept for what it found. Before that: accepted, webOS 0.1.0. From the owner's request of 2026-11-02: *a webOS version, to
submit Shidur to the LG store*; his choices that day: a separate folder rather than a build target of
`web/`, providers reached from the television itself rather than through a hosted proxy, and webOS 6
(2021) as the oldest television supported. The app is to be sold on the LG store as a skeleton with
no playlist (the owner's plan of 2026-10-19).

## What a television showed, before the removal

Tried on 2026-11-05 on an LG OLED65CX6LA (webOS 5, firmware 04.64.00, Chromium 68), on a branch that
was never merged (`feature/webos5-support`):

- The bundle built for Chromium 79 showed a black screen: class fields (Chromium 72) stopped the
  script; `flat`/`flatMap`, `matchAll`, `Object.fromEntries`, `queueMicrotask` and CSS `min()` are
  also newer than 68.
- Under the `transform` that scales `#app` (ADR-055) the television drew a `<video>`'s picture
  small and off its frame, and left it there in full screen. Scaled with `zoom` instead, the picture
  sat right in its frame.
- `scrollIntoView` did not scroll the lists under that transform; no scroll event came for a scroll
  set from script; and even with `scrollTop` set, the lists were not seen to move on the screen.
  Not solved when the version was removed.

## Context

The computer version (ADR-044) is the closest thing to a webOS app: an LG television runs packaged
web apps, written in HTML and JavaScript. But it leans on two things a television does not have:

- **Its server.** Every playlist, guide and stream goes through `web/server`. A packaged webOS app
  is opened from `file://` and is held to CORS like any page (LG's forum: packaged apps "do not have
  a CORS origin"); providers send no CORS headers, so the page alone cannot read them.
- **A current browser.** webOS 6 runs Chromium 79, webOS 22 Chromium 87 (LG's web engine table).
  The stylesheet uses cascade layers, container queries, `:has`, `:focus-visible`, `dvh`,
  `aspect-ratio`, logical properties and flex `gap`, all newer than 79. Module scripts are refused
  from `file://` on webOS 6.

And LG's QA has its own rules for the remote (LG's App Self Checklist): every control reachable
with the four arrows, OK and Back; every control shows a selection effect; on webOS 23–25 Back on
the first screen goes to the platform, not to a previous page. Channel up and down are not given
to apps on webOS.

## Decision

1. **`webos/` is its own copy of `web/src`, changed for the television.** Not a build target of
   `web/` (the owner's choice): the television version is free to change for the remote without
   putting the computer version at risk, at the cost of fixes being made twice. The parsers
   (M3U, Xtream, XMLTV, matcher, classifiers) stay identical to `web/src/data` and keep the same
   tests; a rule changed there is copied here.

2. **Providers are read by a JS service in the package (`webos/service/`).** A Node process the
   television runs beside the page, outside CORS: it sets User-Agent and Referer, follows redirects,
   unzips gzip whatever the provider says, and sends text back over the Luna bus in numbered parts
   of 256 KB (`fetch`), or the type and final address of a stream from its first bytes (`probe`).
   It is plain ES2017 CommonJS with no dependencies, because webOS 6 runs Node 8.12 — a test parses
   it as ES2017 and runs it against a local server. The page reaches the bus through
   `PalmServiceBridge` directly (`src/data/luna.ts`) rather than LG's webOSTV.js. Nothing runs
   outside the television: no proxy is hosted for buyers' streams.

3. **The television plays the stream itself.** A media element may load any address, and webOS's
   player decodes HLS, MPEG-TS and AC-3 in hardware, so the address goes straight to `<video>`
   (with its MIME type when the address does not show it). hls.js, mpegts.js and the server's AAC
   remux are gone. What is lost: a channel's own User-Agent and Referer are not sent with the
   stream, only with the playlist and guide.

4. **The guide is parsed in a worker, fed part by part by the page** (the worker cannot reach the
   bus), so a guide of hundreds of megabytes never sits in memory whole. The worker is inlined into
   the bundle: webOS 6 will not load a worker script from `file://`.

5. **The remote drives everything.** Arrows move focus to the nearest control in that direction on
   screen (`src/ui/spatial.ts`; up and down stay within the rail, the screen or the player), OK
   presses, Back goes outward — a dialog, a typed channel number, full screen, the controls over
   the picture, another screen — and on the channel list calls `platformBack`, which on webOS 6 and
   later asks whether to exit. In full screen the up and down arrows change channel. Numbers pick a
   channel; play and pause keys pause; red returns to the previous channel, green opens the guide,
   yellow search, blue radio. Every focus is the remote's, so focus is always shown (`:focus`
   throughout). OK on the channel already playing opens it full screen. Full screen is the app's
   own state, not the Fullscreen API.

6. **Built for Chromium 79.** Vite targets `chrome79` and emits one classic, deferred script with
   its CSS and worker inside, under relative addresses. PostCSS (`postcss.config.mjs`) flattens
   cascade layers and turns logical properties right to left; a small plugin adds a margin
   fallback for flex `gap`, switched on by `main.tsx` only where the browser lacks it. Container
   queries, `:has`, `aspect-ratio` and `dvh` were rewritten in the stylesheet. CI fails the build
   if any of them comes back. The app runs at 1280×720 (`appinfo.json`), which the television
   scales to the screen: the computer's 14 px text becomes 21 px on a 1080p panel, above LG's
   minimum. From webOS 0.3.0 it asks for 1920×1080 and draws the same
   1280×720 layout scaled to it (ADR-055).

7. **Xtream passwords are sealed in storage** (`src/data/vault.ts`): AES-GCM with a non-extractable
   key made on the television and kept in IndexedDB. Stored channel and guide addresses carry a
   marker in place of the password. LG's checklist asks that saved credentials be encrypted.

8. **Left out on the television:** audio remux (the television decodes AC-3), installing, signing
   in to a server, picture in picture, M3U files and backup files (a television has no file picker
   and no downloads folder).

9. **Versioning, packaging and CI.** The webOS version has its own version, in
   `webos/appinfo/appinfo.json`, because LG reviews every release by hand and it ships on its own
   schedule. `npm run package` builds the `.ipk` with LG's `ares-package` (`@webos-tools/cli`).
   `.github/workflows/webos.yml` typechecks, tests, builds, checks the bundle for anything newer
   than Chromium 79, and keeps the `.ipk` as an artifact. `android.yml` and `web.yml` ignore
   `webos/**`.

## Consequences

- Nothing here has run on a television yet. To confirm on a webOS 6 set (developer mode):
  playback of MPEG-TS, of HLS without an extension and of AC-3 audio through `<video>`; that the
  service starts, stays up through a long guide and can reach HTTPS providers with Node 8's
  certificate store; that `PalmServiceBridge` is there for a packaged app; the size and speed of
  the Luna replies; the margin fallback for flex `gap`; and the inlined worker.
- The app id `com.idanck.shidur` and the vendor name in `appinfo.json` are what the store will
  show and cannot change after the first submission; they are placeholders until the owner
  settles them.
- A provider that requires its own User-Agent on the stream itself will not play on the television.
- Two copies of the web code now exist (`web/`, `webos/`) besides the Android one. A parsing rule
  changed in any of them must be changed in the others.
- Submitting needs what is outside this repository: an LG Seller Lounge account, the self
  checklist filled with test results from a television, a UX scenario document, store images, a
  test playlist for LG's testers, and a decision on how the app is paid for (LG no longer bills;
  paid content goes through a third-party in-app purchase).
