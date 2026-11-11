# Shidur for LG televisions (webOS 5 and later)

Shidur as a packaged webOS app, for LG televisions from webOS 5 (2020, Chromium 68). Decisions and
scope: `docs/adr/ADR-057-lg-televisions-from-webos-5.md`.

**Now: 0.4.0**, the shell that updates itself (below), over 0.3.0's app: the channel list (the two built-in playlists, ADR-013 and ADR-015), one channel
playing in its box, full screen on OK — seen working on the television on 2026-11-07 as 0.1.0 —
the guide (Green, 0.2.0), the diagnostics for the interface that vanished after some minutes on
that television, and in 0.3.0 the radio (Yellow) and the answer to that fault the owner chose:
LG's `preferredType: 2` screen saver, and full screen by itself after 25 minutes without a key
(ADR-057, *The vanishing interface* and *0.3.0*). 0.2.0, 0.3.0 and 0.4.0 have not yet run on the
television. The order from here (the owner's, 2026-11-08), each step after the one before has been
seen working on the television (0.4.0 was asked for before 0.2.0 and 0.3.0 were):

1. **0.3.0** — radio, full screen after 25 idle minutes, the spike removed.
2. **0.4.0** — the shell that updates itself from `updates.idanck.com` (ADR-057, *0.4.0* and *As
   built*): built, not yet run on the television.
3. **Sport and News mode** — two or four videos, with a fallback to one (ADR-057, *Two or four videos*).
4. Then search, settings and the rest.

## Layout

- `src/` — the app (Preact), ported from `web/src`. `src/data/`'s parsers are `web/src/data/`'s
  byte for byte (`test/parity.test.ts`).
- `service/` — the JS service the app reads providers through, on the Luna bus (Node 8.12, ES2017).
- `shell/` — the shell (`boot.js`, ES5): the only script `index.html` loads. It decides whether the
  packaged bundle or a downloaded one runs, and asks for updates; `public-key.txt` is the half of
  the signing key it checks manifests with. `scripts/shell-plugin.ts` builds it.
- `public/polyfills.js` — what Chromium 68 lacks, loaded first in the page and in the guide worker.
- `appinfo/` — the manifest (`resolution: 1920x1080`, version), icons, splash.
- `scripts/` — `check-es.mjs`, `check-css.mjs` (nothing newer than webOS 5 ships),
  `check-shell.mjs` (what the build wrote for the shell), `package.mjs` (the `.ipk`).
- `tools/publish-bundle.sh` — signs and publishes a bundle, on the Pi only (below).
- `tools/cdp.mjs` — reads and drives the page on the television over DevTools.

## Build and check

```sh
npm ci              # in Termux: npm ci --force (@webos-tools/cli refuses Android otherwise)
npm run check       # typecheck, tests, build, check:es, check:css, check:shell
SHIDUR_SELF_UPDATE=0 npm run build   # the store's build: no update address, no key, no download
npm run package < /dev/null   # ipk/com.idanck.shidur_<version>_all.ipk
```

`ares-package` must run in the foreground with `< /dev/null`; in the background it fails with
`EBADF`.

## On the television (developer mode)

```sh
npx ares-launch -d <tv> --close com.idanck.shidur   # before installing a new build
npx ares-install -d <tv> ipk/com.idanck.shidur_0.4.0_all.ipk
npx ares-launch -d <tv> com.idanck.shidur
TV=<tv-address> MATCH=shidur node tools/cdp.mjs state   # what the app says about itself
TV=<tv-address> MATCH=shidur node tools/cdp.mjs key Down 5
TV=<tv-address> MATCH=shidur node tools/cdp.mjs shot          # what Chromium draws, as a PNG
TV=<tv-address> MATCH=shidur node tools/cdp.mjs idle 1        # take the screen after 1 idle minute, not 25
```

Only one DevTools client can attach at a time: stop `ares-inspect` first. The bottom-left corner of
the screen shows the version and the commit, so a new build is known to be the one running.

The remote: ▲▼ walk the channel list, ◀▶ move between the groups, the list and the buttons, OK plays
a channel and, on the one already playing, opens it full screen (or tries again after an error). In
full screen ▲▼ change channel, OK shows what is on, Back leaves it. Numbers pick a channel; red is
the channel before; **green opens the guide**, **yellow the radio**; ▶/⏸ pause. Back on the list
leaves the app.

The radio is the channel list's layout repointed at the radio playlist, as on Android: the menu on
the right (all, favourites, recent, then national, the regions from the north, other regions and
the genres, ADR-050), the stations, and the station that plays where the picture would be. OK on a
station plays it, OK on the one playing pauses it; Back or yellow returns to the channels.

After 25 minutes without a key, with a channel playing, the app puts the picture over the whole
screen by itself, whatever is open (the guide, a dialog); any key gives the screen back as it was
and does nothing else (`src/ui/idle.ts`). `cdp.mjs idle <minutes>` shortens the wait until the app
is closed, to see it.

In the guide the four arrows are the grid's own: ▲▼ change channel keeping the hour, ◀ goes to a
later programme, ▶ to an earlier one and then to the channel's name; OK on a programme opens its
details (OK again there plays the channel), OK on a channel plays it; ▲ from the first row reaches
the day chips, "עכשיו" and "עדכן לוח שידורים"; Back or green returns to the list. The grid never
scrolls: it moves its rows with `translateY` and its hours with `translateX` (`src/ui/grid.ts`).

`cdp.mjs state` also prints what the page says about itself for the vanishing-interface fault:
whether its tree is whole, whether it is visible, errors and webOS events with their times, the
heap, and one line a minute for the last minutes (`src/ui/watch.ts`).

## Updates without the phone (0.4.0)

The installed shell asks `https://updates.idanck.com/webos/manifest.json` 20 seconds after the
first screen is up, through the app's own service. A newer bundle whose manifest is signed with the
Pi's key, and whose files match it byte for byte, is stored and runs, silently, at the launch after.
One that throws before its first screen, or draws none in 60 seconds, is marked bad and never runs
again; the packaged bundle runs instead. The corner shows `· עדכון` while a downloaded bundle runs,
and `cdp.mjs state` prints the shell's lines: what runs, what is pending or bad, the last check.

To publish one: raise `appinfo.json`'s version (nothing of the shell), merge to `main`, and once
the `webOS app` run there is green, on the Pi:

```sh
set -a; source <the Pi's secrets file>; set +a
webos/tools/publish-bundle.sh --dry-run
webos/tools/publish-bundle.sh
```

A change to the shell itself (`index.html`, `shell/`, `service/`, the fonts, icons, `appinfo.json`
other than its version) fails `test/shell-guard.test.ts` until `SHELL` is raised, and reaches the
television only by installing from the phone. A reinstall clears what the app downloaded.

## The spike

Step 1's test page answered how the app draws and scrolls on webOS 5 (ADR-057, *Step 2*). It was
removed on 2026-11-08; `git show 0080724:webos/spike/app/spike.js` (and its siblings) brings it
back if a later round needs it.

## Versioning

The webOS version is its own, in `appinfo/appinfo.json`: LG reviews every release by hand, so it
ships on its own schedule, and nothing is published from CI. CI keeps the bundle
(`shidur-webos-bundle`) beside the `.ipk`; only the Pi signs and publishes it.
