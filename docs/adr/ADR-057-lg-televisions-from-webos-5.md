# ADR-057 — Shidur for LG televisions, from webOS 5 (2020)

**Status:** accepted, step 2 of 2 under way. Step 1, the spike, ran on the owner's webOS 5
television on 2026-11-07, and the owner chose from its results the same day (*Step 2* below). The
first build of the app, webOS 0.1.0, is the channel list and one channel playing in its box; it has
not yet been run on a television. Decided by the owner on 2026-11-06: *build the LG version again,
with webOS 5 (2020, Chromium 68) as the oldest television supported.* Replaces ADR-052, ADR-053 and
ADR-055 (withdrawn), whose findings it builds on.

Numbered ADR-056 while on its branch; renumbered 057 because ADR-056 on `main` is the Google TV
home-screen row.

## Context

The first LG version (ADR-052, webOS 6 and up, Chromium 79) was removed on 2026-11-06 to be started
again. On the owner's test television, an LG OLED65CX6LA (webOS 5, firmware 04.64.00, Chromium 68),
it showed three things (ADR-052, *What a television showed*):

1. **A black screen**: the bundle for Chromium 79 held class fields, a syntax error in 68, so no
   script ran. Also missing in 68: `Array.prototype.flat`/`flatMap`, `String.prototype.matchAll`,
   `Object.fromEntries`, `queueMicrotask`, `globalThis`; in CSS `min()`/`max()`/`clamp()`,
   `backdrop-filter`, flex `gap`, `inset`, logical properties, `:focus-visible`, `aspect-ratio`.
2. **The video in the wrong place**: under `#app { transform: scale(1.5) }` (ADR-055) the television
   drew a `<video>`'s picture small and off its frame, also in full screen. A `<video>` outside the
   scaled element, or `#app` scaled with `zoom`, was placed right.
3. **Lists that do not move on screen**: under the same transform, `scrollIntoView` did nothing; a
   `scrollTop` set from script changed the value but sent no `scroll` event, so the virtual list
   drew no more rows; with `scrollTop` set and a `scroll` event sent by hand, nothing moved on the
   screen either. Never tried with no scaling at all.

Never checked on webOS 5: the guide's right-to-left horizontal scroll (Chromium before 85 starts an
RTL scroller at its largest `scrollLeft` and counts down to 0, newer ones start at 0 and go
negative), and two or four `<video>`s at once (Sport and News mode) on what may be a single
hardware decoder.

## Decision

1. **webOS 5 is the floor.** Every line of the app runs in Chromium 68, and the JS service in
   Node 8.12 (webOS 5 and 6 both carry it).
2. **ADR-052's scope otherwise stands**: its own folder `webos/`, not a build target of `web/`; the
   parsers in `webos/src/data/` identical to `web/src/data/`; providers read on the television by
   a JS service on the Luna bus, with no hosted proxy; sold on the LG store as a skeleton with no
   playlist; its own version in `webos/appinfo/appinfo.json`, from 0.1.0.
3. **Built for Chromium 68**: Vite `target: 'chrome68'`, PostCSS `browsers: 'chrome 68'`, and
   polyfills for the missing APIs loaded first, in the page and in every worker. CI parses the
   bundle with `acorn` as ES2019 and fails on any API newer than Chromium 68
   (`webos/scripts/check-es.mjs`).
4. **A spike on the television before the app** (`webos/spike/`), because a desktop browser said
   nothing true about points 2 and 3 above. One screen, every mode switched at runtime:
   - *scale*: none (`appinfo.json` 1280×720, the television enlarges the layer); and at 1920×1080
     `native` (laid out at 1920×1080 with no scaling, every size in `rem` at 24 px),
     `transform: scale(1.5)`, `zoom: 1.5`;
   - *list*: the browser's own scroll on `focus()`, `scrollIntoView`, `scrollTop` on an
     `overflow: auto` list, `scrollTop` on an `overflow: hidden` list, and `transform: translateY`
     with no native scroll; the same for a right-to-left strip, which also reports the `scrollLeft`
     model;
   - *video*: one, two or four `<video>`s, inside the scaled frame or outside it placed over their
     slot; a local test picture whose red edge should meet the yellow outline, or a network HLS
     stream; and full screen.
   It is two packages (`com.idanck.shidurspike.hd` at 1280×720 and `.fullhd` at 1920×1080),
   since the page cannot change the resolution webOS gives it. `webos/tools/cdp.mjs` reads the
   same diagnostics over the television's DevTools WebSocket.
5. **The owner chooses the combination** from the spike's results, laid out as options with what
   each costs; the choice, and the owner's decisions on the features webOS 5 may change (the video
   always full screen behind the interface or in a box; Sport and News mode on one decoder; the
   guide's horizontal scroll), are added here before the app is built.

## Step 2: what the spike showed, and the owner's choices (2026-11-07)

Run on the owner's father's LG OLED65CX6LA (webOS 5, Chromium 68.0.3440.106), installed from Termux
with ares-cli, driven with `tools/cdp.mjs`, checked by the owner on the screen with the remote:

| Scale | List follows remote focus (on screen) | Video on its slot | Full screen fills | Layout fills screen |
|---|---|---|---|---|
| none (`.hd`, 1280×720) | yes | yes | yes | yes |
| **native (`.fullhd`, 1920×1080, 1rem = 24px, no scaling)** | **yes** | **yes** | **yes** | **yes** |
| transform `scale(1.5)` at 1080 | yes | yes | yes | yes |
| zoom 1.5 at 1080 | yes | yes | not checked | **no — drew at ~2/3 of the screen** (frame reported 853×480: Chromium 68 does not enlarge `rem` lengths under `zoom`) |

DOM self-test (`cdp.mjs selftest`), every scale mode: `scroll` events arrived in all native list
modes (`focus`, `into-view`, `scroll-top`, `hidden`); `translate` moved by `translateY` as expected.
The right-to-left strip uses the **old** `scrollLeft` model (starts at the maximum, counts down to 0).
After switching scale at runtime the list jumped to `scrollTop 0` while focus stayed lower: a spike
artefact, but a reason for the app not to rely on the browser keeping a scroll position.

**Not proven by the spike:** two or four `<video>`s at once, a network HLS stream (only the local test
picture was played), the guide's horizontal right-to-left scroll with the remote, and anything about
the real app's speed.

The owner's choices:

1. **Drawing: native 1080.** `appinfo.json` asks for `1920x1080`; the app is laid out at 1920×1080
   with no CSS scaling at all (no `transform`, no `zoom`), every size in `rem` with the root at
   24 px. A size from the computer version's 1280×720 frame is `px ÷ 16` rem, the same share of the
   screen (`ui/common.tsx` `rem`, `ui/icons.tsx`). With no scale, the player places the video at
   its slot's measured rectangle as it is.
2. **Video in a box, as on Android**, not always full screen behind the interface; full screen on OK
   (on the channel already playing), as on the Android TV shell.
3. **Sport and News mode (two or four videos): built, with a fallback to one video.** Not in 0.1.0;
   the design it is to follow is under *Two or four videos* below. Untested on webOS 5.

## How the app is built (step 2)

- **Chromium 68 throughout.** Vite and esbuild target `chrome68`; PostCSS `browsers: 'chrome 68'`.
  `public/polyfills.js` (plain ES5, no build) is the page's first script and the head of the guide
  worker's own script; `scripts/check-es.mjs --polyfills` learns what it provides from its
  `define(target, 'name', …)` calls and allows those APIs everywhere, and fails on any other API
  newer than 68, on syntax newer than ES2019 (ES2017 for the service), and on inline style keys
  Chromium 68 ignores (`insetInlineStart` and the like: PostCSS does not lower inline styles).
  `scripts/check-css.mjs` fails the build stylesheet on flex `gap`, `inset`, logical properties,
  `min()`/`max()`/`clamp()`, `backdrop-filter`, `:focus-visible`, `aspect-ratio`, `:is()` and the
  rest. The worker is its own script, built by `vite.config.ts` and also written to
  `.build/epg.worker.js` so the check reads it (inside the bundle it is only a string).
- **One stylesheet, linked**, not injected from script; one classic deferred script (webOS refuses
  module scripts from `file://`).
- **Lists keep their own offset** (`ui/list.ts`, `ui/common.tsx` `VirtualList`): the rows move with
  `translateY` in a box that never scrolls, ▲ and ▼ are the list's own, and a row is drawn, then
  focused with `preventScroll`, then known to be in view, with no `scroll` event waited for. Should
  anything scroll the box, it is put back to 0 and counted (`cdp.mjs state`: "stray scrolls"). The
  groups column, not virtual, scrolls by the browser's own `focus()` + `scrollIntoView`, which the
  spike showed working at native 1080.
- **Providers through the JS service on the Luna bus** (Node 8.12), no hosted proxy, with the bugs
  listed below fixed.
- **Its own version** in `webos/appinfo/appinfo.json`, 0.1.0; app id `com.idanck.shidur`.
- **Diagnostics**: `window.shidur.state()` (`ui/debug.ts`) says where focus is, the list's offset
  and drawn rows, the player's state and box, and each source's last result; `cdp.mjs state` prints
  it. Evidence beside the screen, never instead of it.

### Fixed in the port (the review's list, above)

- `vault.ts`: a password that cannot be opened is held aside and saved back as the sealed text it
  was, never as `''`; a key that fails to load is tried again on the next call, not remembered; a
  password that cannot be sealed now is never written in the clear.
- `db.ts`: a failed (or hung, after 10 s) IndexedDB open is forgotten, so the next call opens again;
  `state/store.ts` reads a failing storage as empty and starts anyway, and says so on screen.
- The guide refresh always ends through one `finish` (done, worker error, failed download, a
  15-minute limit), which aborts the download and the worker and clears "refreshing"; a failed write
  of the guide no longer skips it.
- `probeStream` gives up after 15 s and cancels its call. The player also gives up on a stream that
  shows no picture within 25 s, since the television's player may wait for ever with no error.
- **Backpressure** from the provider to the worker: every part is acknowledged (`ack` on the bus)
  only once the worker has parsed it, and the service pauses the provider's response while four
  parts (1 M characters) are unacknowledged; a read nobody acknowledges for 60 s ends as `stalled`.
- **Errors by cause**: the service names `dns`, `refused`, `reset`, `timeout`, `tls-cert`, `tls`
  and `unreachable` (with what Node said), and the app words each one (`data/sources.ts`). Node 8.12
  carries a 2018 certificate store: an HTTPS provider whose chain it does not know now reads as a
  certificate problem, not as "no connection".

### Two or four videos (Sport and News mode): the design to build, untested on webOS 5

The owner's rule: try to open every panel; where the television refuses another decoder, play one
picture and say so, in Hebrew, in the other panels. How a refusal is told apart:

- Panels start **one after another**: the next only once the one before is playing, has failed, or
  its time is up. Never together, so two panels cannot take the decoder from each other.
- A panel is **refused** when its `<video>` reports an error (`media.error`, or the `<source>`'s own
  `error` event, which leaves `media.error` empty), **or** when it stalls or shows no frame
  (`readyState` below `HAVE_CURRENT_DATA`, `videoWidth` 0) within a few seconds while another panel
  is playing. A refused panel is **not retried in a loop**: it stays as it is until the viewer acts,
  so it never takes the decoder back from the panel that is playing.
- If the first panel itself is the one that fails, it is the stream, not the decoder: the usual
  error and retry.
- **Unknown until the owner's test**: whether webOS 5 on this television gives a second decoder at
  all, how it refuses (an error, or a silent stall), and whether a refused panel disturbs the
  playing one. The owner gets a test for it when Sport and News mode is built.

### Not yet decided (for the owner)

- **The guide's right-to-left horizontal scroll.** Both are viable on webOS 5: detect the
  `scrollLeft` model at runtime (the spike's `detectRtlModel`, which found the old model), or move
  the grid with `translateX` and no native horizontal scroll, as the lists do vertically.
- **`webos/spike/`**: kept for later rounds (Sport and News mode's decoders, an HLS stream) or removed.

## Alternatives rejected

- **Building the app first and fixing it on the television.** That is what the first version did;
  the black screen and the scrolling cost a day each, without an answer for the scrolling.
- **Keeping webOS 6 as the floor.** The owner's test television is webOS 5.
- **`zoom: 1.5` at 1080.** Drew the layout at two thirds of the screen on webOS 5.
- **`transform: scale(1.5)` at 1080, or 1280×720 enlarged by the television.** Both worked in the
  spike; the owner chose native 1080, which needs no measuring against a scale and draws text at
  the panel's own pixels.

## Consequences

- Drawing and scrolling are decided (*Step 2*); what is built on them is still proven only on the
  television. A desktop run proves only that there is no script error.
- Built in small steps, each run on the television before the next: first the channel list and one
  channel in its box (0.1.0); then the guide, Sport and News mode, radio, search and settings.
  0.1.0 has no settings: it opens on the two built-in playlists (ADR-013, ADR-015), as every install
  does.
- To load a new build, close the app first (`ares-launch -d <tv> --close <app id>`): launching an
  app already in the background only brings it to the front.
- Back on the first screen closes the app (LG's rule); the spike lets Back only leave full screen.

## What the first version's code needs on webOS 5

A review of `webos/` as it stood at `e6725c0` (2026-11-06), for step 2. Paths are at that commit.

- **Polyfills, in the page and in the guide worker** (an inlined bundle of its own):
  `Array.prototype.flatMap` (`data/matcher.ts`, also in the worker; `store.ts`, `backup.ts`),
  `String.prototype.matchAll` (`data/m3u.ts`, `data/xmltv.ts`, in the worker), and
  `queueMicrotask`, which `@preact/signals` calls. CSS `min()` in `styles.css` is not lowered.
- **Inline styles are not lowered by PostCSS**: `insetInlineStart` (Chromium 87) placed every guide
  cell and the now line (`GuideScreen.tsx`), `marginInlineStart` (69) in `LiveScreen.tsx`. Both were
  broken on webOS 6 too.
- **Scrolling**: the virtual list (`ui/common.tsx`) learns its position only from `scroll` events
  and its `scrollTo` does not redraw; the guide reads `Math.abs(scrollLeft)` and writes `-x`, the
  new RTL model, so on 68 it lands at the far end; `spatial.ts` and `PanelsScreen.tsx` move focus
  with `scrollIntoView` and a plain `focus()`. Whatever the spike shows, the list should keep its
  own offset and redraw from the focused row, not wait for a `scroll` event.
- **The scale**: the player divides `getBoundingClientRect` by the scale (`PlayerHost.tsx`), and
  `PanelsScreen.tsx` and `spatial.ts` measure against `window.inner*`. Under `zoom` these would
  need measuring; with no scaling they go away.
- **More than one video**: the panels start their videos together and recognise a refused decoder
  only when another panel is already playing and `media.error` is set, which a `<source>` error
  leaves empty. On one decoder that reads as "unplayable" and the panels may take the decoder from
  each other on retry.
- **Bugs to fix in the port**: a transient key failure in `vault.ts` turns the password into `''`,
  which `store.ts` then writes back; a failed IndexedDB open (`db.ts`) is cached and nothing catches
  it, so the app never starts; a guide refresh whose `kv.set` throws stays "refreshing", and its
  download is never aborted; `probeStream` has no timeout; the service streams up to 768 MB over the
  bus with no backpressure and reports every HTTPS failure as `unreachable`.
