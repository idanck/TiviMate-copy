# ADR-057 — Shidur for LG televisions, from webOS 5 (2020)

**Status:** accepted, step 2 of 2 under way. Step 1, the spike, ran on the owner's webOS 5
television on 2026-11-07, and the owner chose from its results the same day (*Step 2* below). The
first build of the app, webOS 0.1.0 (the channel list and one channel playing in its box), ran on
the same television on 2026-11-07 and the owner confirmed it on the screen (*0.1.0 on the
television* below), with one fault (*The vanishing interface*). 0.2.0 adds the guide and the
diagnostics for that fault; it has not yet been run on a television. Decided by the owner on
2026-11-06: *build the LG version again, with webOS 5 (2020, Chromium 68) as the oldest television
supported.* Replaces ADR-052, ADR-053 and ADR-055 (withdrawn), whose findings it builds on.

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

- ~~**The guide's right-to-left horizontal scroll.**~~ Decided for 0.2.0: `translateX`, no native
  horizontal scroll (*0.2.0: the guide* below).
- **The vanishing interface**: which option, once `state` has shown the cause (above).
- **The guide's layout**: as the computer version (the picture in a box above the grid, 0.2.0) or
  over the picture as Android's (ADR-051).
- **Where radio falls** in the order after Sport and News mode; **how the app is updated** on the
  father's television (*The plan from here*).
- **`webos/spike/`**: kept for later rounds (Sport and News mode's decoders, an HLS stream) or removed.

## 0.1.0 on the television (2026-11-07)

Installed from Termux on the owner's father's LG OLED65CX6LA (webOS 5, Chromium 68). The owner
confirmed on the screen: the list follows the remote, the channel plays in its box, OK opens full
screen, ▲▼ zap in full screen, Back leaves it. `cdp.mjs state` agreed (the player element exactly
on its slot, the focused row inside the list). What this proves: native 1080 with no scaling, the
list's own offset, the video in a box and full screen all work on webOS 5 with the real app; what
it does not: anything about the guide, two videos, or hours of use. One wrong channel ("ערוץ 1 HD"
shows One 2) is the provider's stream, handled in the Pi's playlist script, not here.

### The vanishing interface

> When he stays on a channel in the view with the channel list, after a few minutes only the
> picture in its box remains on the screen; everything else on the page disappears.

Nothing in 0.1.0 hides the interface on a timer (the only timer in `PlayerHost.tsx` hides the
full-screen info after 4 s), and a desktop browser cannot show it, so the cause was not known when
it was reported. Candidates, and how 0.2.0 tells them apart:

| Candidate | What would show it |
|---|---|
| The app emptied or unmounted its tree (a render that threw, a state that hides the screen) | the count of elements under `#app` falls; the screen's element is gone; a removal is logged with the time; an error is logged |
| An uncaught error froze Preact (it stops rendering after an error nobody catches) | an error with its time; "renders" stops growing while the clock ticks |
| Memory | Chromium's heap, in the snapshot every minute |
| webOS hid or suspended the page (`visibilitychange`, `webOSRelaunch`, a `pagehide`) | the event with its time; animation frames stop arriving |
| The television drew over the page (its screen saver) | **everything above reads normal**: the tree is whole, no error, frames arrive, the page is `visible`; only the screen shows otherwise |

0.2.0 adds `ui/watch.ts` for this: uncaught errors and unhandled rejections with their time, a
render that throws (`ui/Guard.tsx`, which also puts a Hebrew message on the screen instead of a
frozen page), the webOS and page events received, whether animation frames still arrive, the
count of elements under `#app` and every removal of a whole screen, Chromium's heap
(`performance.memory`), and a one-line snapshot of all of it every minute (the last 45 kept). All
of it is in `shidur.state()`, so one `cdp.mjs state` while the fault is on the screen says which
row of the table it is. `cdp.mjs shot` saves what Chromium itself draws (the video plane is the
television's and shows black): if the interface is in the picture but not on the screen, the
television covered it.

**What LG's documentation says** (read on 2026-11-08 through search extracts of the pages, since
`webostv.developer.lge.com` is not reachable from the cloud sandbox; the owner can open them):

- *Screensaver* guide (`webostv.developer.lge.com/develop/guides/screensaver`): the screen saver
  starts after a period without a key, and "does not appear while a video plays full screen"; on
  OLED televisions "the screensaver timeout is just a few minutes"; an app may declare in
  `appinfo.json` `"screenSaverProperties": { "preferredType": 2 }` — type 2, "OSD or UI components
  while playing video", dims only the interface's area and extends the timeout to 30 minutes
  (type 3, still images, dims the whole screen after 30 minutes). Types 2 and 3 apply to OLED
  televisions from webOS 5.0.
- LG's forum, an LG answer to the same symptom (`forum.webostv.developer.lge.com/t/basic-web-app-after-a-while-the-background-turns-black/3843`,
  a clock beside a video that is not full screen; after a while everything but the video went
  black): "looks like the screen saver is activated, covering everything except the video being
  played". Also `/t/keep-screen-on-prevent-screensaver-blackout/740`: "there are no specific
  methods or APIs" to keep the screen saver away in a web app; "just play your video with full
  size screen".

This matches what the owner saw, on an OLED, after a few minutes, with the video in a box. It is
**not yet proven**: the diagnostics above will show whether the page was whole when it happened.
If they do, the cause is the television's own behaviour and the choice is the owner's:

| Option | For | Against |
|---|---|---|
| **A. Declare `preferredType: 2`** in `appinfo.json` | LG's documented way; one line; the interface is dimmed, not blanked, and only after 30 minutes | still happens after 30 minutes without a key; LG's description of how type 2 looks could not be read in full from here |
| **B. The app goes full screen by itself** after N minutes without a key (its own idle timer, as the Android screensaver of ADR-049 does the other way round) | the television's screen saver never comes (video full screen); the picture is what stays; the OLED's static interface is protected; any key returns to the list | behaviour the viewer did not ask for; N to choose (LG's type-2 figure is 30 minutes) |
| **C. Nothing** | nothing to build; LG's screen saver protects the OLED | the fault stays as it is; any key wakes the screen |
| **D. The undocumented Luna call** `com.webos.service.tvpower/power/registerScreenSaverRequest` to refuse the screen saver (a community finding) | the interface never goes | not in LG's documentation; may fail LG's review when the app goes to the store; a static interface for hours on an OLED is what the screen saver is there to prevent |

A and B can be combined. Not decided; waiting for the owner's `state` and his choice.

## 0.2.0: the guide

Ported from `web/src/ui/GuideScreen.tsx` under this ADR's rules, for the remote:

- **The grid never scrolls.** It keeps two offsets of its own (`ui/grid.ts`): the rows move with
  `translateY`, the hours with `translateX`, each in a box that clips them; a stray scroll is put
  back to 0. This decides the item left open above (*Not yet decided*): the right-to-left
  horizontal axis does not use the browser's `scrollLeft` at all, so Chromium 68's old RTL model
  (confirmed on this television) never comes into it. Time runs right to left: a cell is placed
  with `right: x`, x growing with time from the day's start at the channel column's edge, and the
  strip is moved to the right to show later hours.
- **The remote is on the grid, not on a cell.** The grid's box is the one focusable element; the
  arrows move a position of the grid's own (row, cell), the cell at it is drawn as focused, and OK
  opens it. So nothing is ever focused off screen, and no `scroll` event is waited for. ▲▼ keep
  the hour (the position's time is kept across rows, as Android's guide does); ◀ goes to a later
  programme, ▶ to an earlier one and then to the channel's own cell; OK on the channel plays it
  (full screen if it is already on). ▲ past the first row leaves the grid for the controls above
  (`ui/spatial.ts`, the guide is one section). A programme's details open in a dialog
  (`ui/common.tsx` `Dialog`): Back closes it and focus returns to the grid.
- **Green opens the guide** from the list or from full screen, and closes it; Back closes it too.
  A "לוח שידורים" button is in the list's stage as well. The guide shows the list's current
  group (the groups column stays on the list screen).
- **Layout as the computer version's**: the picture in its box above with what is on, the day
  chips (yesterday, today, tomorrow, the day after), "עכשיו", "עדכן לוח שידורים" and the guide's
  status, one line on the programme the remote is on (ADR-051's strip), the grid under them: seven
  rows of 3.75 rem at 1080, 6 px a minute (about four hours across). Android's guide is drawn over
  the picture at full size instead (ADR-051); whether the television version should follow it is
  for the owner, after he has seen this one.
- No inline logical properties (`right`, `top`, `width` only); the worker and the refresh are as
  fixed above; `appinfo.json` 0.2.0.

Unproven until the owner runs it: that `translateX` moves the strip on webOS 5 as `translateY`
moves the lists (the spike proved only the vertical), that seven rows of cells redraw fast enough
on the television, and the dialog's focus.

## The plan from here

The order, decided with the owner on 2026-11-08: **0.2.0** the guide and the fault above → **Sport
and News mode** (decided: build it, fall back to one video if the television refuses a second
decoder; the design is under *Two or four videos*) → the rest, in an order still open:

### Radio stations (a step to place)

The built-in radio playlist already loads (97 stations in 0.1.0's `state`), but there is no radio
screen. The step ports the computer version's `RadioScreen` and Android's `ChannelListScreen` in
radio mode (ADR-015: the same list repointed at the radio playlist, no screen of its own), with
the menu the owner decided on 2026-10-30 (ADR-050): one flat list — national, north, centre,
Jerusalem, south, other regions, then the genres — each station placed by its name
(`data/classify.ts`, already byte for byte the computer version's). Radio is heard, not seen: the
player keeps the sound with no slot (`PlayerHost` already hides the picture for radio). Where it
falls in the order after Sport and News mode is the owner's to say.

### Updating the app on the television without the phone each time

Every build today needs the owner's phone in Termux on the father's network: `ares-install` over
SSH to the television in Developer Mode. The owner asked whether the app can be updated remotely.
From LG's documentation (search extracts, 2026-11-08; the pages are named so he can read them),
without a recommendation:

| Option | How | For | Against | What LG says |
|---|---|---|---|---|
| **1. A hosted web app**: a small packaged shell whose page loads the interface from a server the owner controls | `ares-generate -t hosted_webapp`: the package holds `appinfo.json` and an `index.html` that redirects to the remote address; the interface is deployed to the server | updates by deploying, no reinstall; LG names the type for apps "expected to go through a lot of changes" | needs a server up whenever the television opens the app; offline behaviour not stated by LG; the page then runs from an http(s) origin, not `file://` — the Luna service stays packaged, but whether a remote page keeps the app id's bus privileges is **not stated by LG**; storage is per origin and LG says a hosted app's data is cleared when the total reaches its limit; the shell itself still needs a reinstall when `appinfo.json` or the service change | *Web App Types* (`/develop/getting-started/web-app-types`): "the actual content of the app is hosted on a remote web server… you can update the content at any time without having to worry about pushing updates to devices"; store policy on remote content: not stated |
| **2. The app downloads its own new bundle** and runs it on the next launch | the packaged page keeps a copy of the bundle in web storage (localStorage is 16 MB from webOS 3.5; IndexedDB's quota not stated) and loads it as a Blob script, falling back to the packaged one | works offline once downloaded; no server at launch | nothing in LG's documentation supports or forbids it; packaged-app storage is cleared when the app is updated or removed; LG's review for the store tests the package it is given, and a bundle that replaces itself may be questioned; two copies of the app to keep straight | forum answers quoting LG: "downloading files in a web app to a TV device is not supported" — only web storage, no file system |
| **3. Installing from the owner's home over the network** (`ares-install` from the Pi or the X270) | the television's Developer Mode SSH (port 9922) reached through a tunnel or VPN, or a small device on the father's network that runs `ares-install` | nothing changes in the app; the owner's own machines | **security**: Developer Mode's SSH key is fetched over plain HTTP (port 9991) with a six-character passphrase that is shown on the screen and does not rotate, and the DevTools port (9998) is open to the network with no login — exposing that network, or the television, beyond the father's LAN widens all of it; a VPN into the father's network is the usual answer, and costs a device there | LG documents only "a PC connected to the same network"; `ares-setup-device` takes any host and port |
| **4. The LG Content Store** (the end goal, ADR-052/057) | publish; users' televisions install from the store | no Developer Mode at all; the father's television updates as any store app | every version is reviewed by LG by hand ("if there is any change to the app after being approved, the change has to go through a separate approval process"); the review's length is not stated by LG (forum: days to a month); a mandatory self-checklist with every submission; whether installed apps update by themselves is not stated in the developer documentation | *App Approval Process* (`/distribute/app-approval-process`), *App Self-Checklist* |

**Developer Mode's session** (LG's *Developer Mode app* page, `/develop/getting-started/developer-mode-app`,
and LG's forum): the session has a remaining time shown in the Developer Mode app ("Remain
Session"); "Developer Mode is disabled after ten reboots without a network connection, or after a
reboot once the remaining session time has run out. After Developer Mode is disabled, the
installed apps that you were using on Developer Mode are uninstalled." It is extended with the
app's **EXTEND** button while the television is on a network; "if the session time runs out, you
cannot extend the session time". From the command line, `ares-extend-dev` was removed in CLI 3.0;
its replacement, per LG's forum answer, is `ares-launch com.palmdts.devmode -p extend=true -d
<tv>`, and the same answer puts the maximum session at **1000 hours** (about 41 days; older
third-party sources say 50 hours for earlier firmware). The length is not written on LG's page
itself as read here; the app on the father's television shows the figure that applies to it. So
the father's television needs its session extended before it runs out, or Shidur disappears from
it with every other sideloaded app — one more reason the question above matters.

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
- Built in small steps, each run on the television before the next: the channel list and one
  channel in its box (0.1.0, on the television 2026-11-07); the guide (0.2.0); then Sport and News
  mode, and radio, search and settings in an order still open (*The plan from here*). There are no
  settings yet: the app opens on the two built-in playlists (ADR-013, ADR-015), as every install
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
