# ADR-056 — Shidur for LG televisions, from webOS 5 (2020)

**Status:** proposed, step 1 of 2. The spike (`webos/spike/`) is built and waits for a run on a
webOS 5 television; the app is not started until the owner has chosen, from its results, how the
app draws and scrolls. Decided by the owner on 2026-11-06: *build the LG version again, with webOS 5
(2020, Chromium 68) as the oldest television supported.* Replaces ADR-052, ADR-053 and ADR-055
(withdrawn), whose findings it builds on.

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
   - *list*: `scrollIntoView`, `scrollTop` on an `overflow: auto` list, `scrollTop` on an
     `overflow: hidden` list, and `transform: translateY` with no native scroll; the same four for
     a right-to-left strip, which also reports the `scrollLeft` model;
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

## Alternatives rejected

- **Building the app first and fixing it on the television.** That is what the first version did;
  the black screen and the scrolling cost a day each, without an answer for the scrolling.
- **Keeping webOS 6 as the floor.** The owner's test television is webOS 5.

## Consequences

- Nothing about drawing or scrolling is decided until the spike has run on the television. A
  desktop run of the spike only proves it has no script error.
- To load a new build, close the app first (`ares-launch -d <tv> --close <app id>`): launching an
  app already in the background only brings it to the front.
- Back on the first screen closes the app (LG's rule); the spike lets Back only leave full screen.
