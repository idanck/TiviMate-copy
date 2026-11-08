# Shidur for LG televisions (webOS 5 and later)

Shidur as a packaged webOS app, for LG televisions from webOS 5 (2020, Chromium 68). Decisions and
scope: `docs/adr/ADR-057-lg-televisions-from-webos-5.md`.

**Now: 0.2.0**: the channel list (the two built-in playlists, ADR-013 and ADR-015), one channel
playing in its box, full screen on OK — seen working on the television on 2026-11-07 as 0.1.0 —
and the guide (Green), plus the diagnostics for the interface that vanished after some minutes on
that television (ADR-057, *The vanishing interface*). The steps after it, each after the one
before has been seen working on the television:

1. **0.2.0** — the guide; and the fault's cause, from `cdp.mjs state` while it happens.
2. **Sport and News mode** — two or four videos, with a fallback to one (ADR-057, *Two or four videos*).
3. Then, in an order the owner has still to set: **radio** (the built-in stations by region,
   ADR-050), search, settings, and how the app is updated on the television without the phone
   (ADR-057, *The plan from here*).

## Layout

- `src/` — the app (Preact), ported from `web/src`. `src/data/`'s parsers are `web/src/data/`'s
  byte for byte (`test/parity.test.ts`).
- `service/` — the JS service the app reads providers through, on the Luna bus (Node 8.12, ES2017).
- `public/polyfills.js` — what Chromium 68 lacks, loaded first in the page and in the guide worker.
- `appinfo/` — the manifest (`resolution: 1920x1080`, version), icons, splash.
- `scripts/` — `check-es.mjs`, `check-css.mjs` (nothing newer than webOS 5 ships),
  `package.mjs` (the `.ipk`), `package-spike.mjs`.
- `spike/` — step 1's test page (ADR-057).
- `tools/cdp.mjs` — reads and drives the page on the television over DevTools.

## Build and check

```sh
npm ci              # in Termux: npm ci --force (@webos-tools/cli refuses Android otherwise)
npm run check       # typecheck, tests, build, check:es, check:css
npm run package < /dev/null   # ipk/com.idanck.shidur_<version>_all.ipk
```

`ares-package` must run in the foreground with `< /dev/null`; in the background it fails with
`EBADF`.

## On the television (developer mode)

```sh
npx ares-launch -d <tv> --close com.idanck.shidur   # before installing a new build
npx ares-install -d <tv> ipk/com.idanck.shidur_0.2.0_all.ipk
npx ares-launch -d <tv> com.idanck.shidur
TV=<tv-address> MATCH=shidur node tools/cdp.mjs state   # what the app says about itself
TV=<tv-address> MATCH=shidur node tools/cdp.mjs key Down 5
TV=<tv-address> MATCH=shidur node tools/cdp.mjs shot          # what Chromium draws, as a PNG
```

Only one DevTools client can attach at a time: stop `ares-inspect` first. The bottom-left corner of
the screen shows the version and the commit, so a new build is known to be the one running.

The remote: ▲▼ walk the channel list, ◀▶ move between the groups, the list and the buttons, OK plays
a channel and, on the one already playing, opens it full screen (or tries again after an error). In
full screen ▲▼ change channel, OK shows what is on, Back leaves it. Numbers pick a channel; red is
the channel before; **green opens the guide**; ▶/⏸ pause. Back on the list leaves the app.

In the guide the four arrows are the grid's own: ▲▼ change channel keeping the hour, ◀ goes to a
later programme, ▶ to an earlier one and then to the channel's name; OK on a programme opens its
details (OK again there plays the channel), OK on a channel plays it; ▲ from the first row reaches
the day chips, "עכשיו" and "עדכן לוח שידורים"; Back or green returns to the list. The grid never
scrolls: it moves its rows with `translateY` and its hours with `translateX` (`src/ui/grid.ts`).

`cdp.mjs state` also prints what the page says about itself for the vanishing-interface fault:
whether its tree is whole, whether it is visible, errors and webOS events with their times, the
heap, and one line a minute for the last minutes (`src/ui/watch.ts`).

## The spike

`spike/app/` is a plain page with no build step, two packages (`npm run spike:package`):
`com.idanck.shidurspike.hd` (1280×720) and `.fullhd` (1920×1080). It answered how the app draws and
scrolls on webOS 5 (ADR-057, *Step 2*); `cdp.mjs selftest` and `mode` drive it.

## Versioning

The webOS version is its own, in `appinfo/appinfo.json`: LG reviews every release by hand, so it
ships on its own schedule, and nothing is published from CI.
