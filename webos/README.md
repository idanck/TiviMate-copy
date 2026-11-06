# Shidur for LG televisions (webOS 5 and later)

Shidur as a packaged webOS app, for LG televisions from webOS 5 (2020, Chromium 68). Decisions and
scope: `docs/adr/ADR-056-lg-televisions-from-webos-5.md`.

**Step 1, now:** only the spike (`spike/`), a one-screen test that answers how the app may draw and
scroll on webOS 5. The app itself is built after the owner has chosen from its results.

## The spike

`spike/app/` is a plain page with no build step (ES2017, nothing newer than Chromium 68):

- a list of 100 rows, moved by ▲▼, and a right-to-left strip of 30 cells, moved by ◀▶;
- one, two or four `<video>`s in a box with a yellow outline (the local test picture's red edge
  should meet it on every side), and OK for full screen (the outline turns pink), Back to leave it;
- the modes, switched on the remote and kept across restarts:
  - **red** scale: `none` in the 720 package; `native` (laid out at 1920×1080, no scaling),
    `transform` (`scale(1.5)`) or `zoom` (`zoom: 1.5`) in the 1080 package;
  - **green** list: `focus` (the browser's own scroll on `focus()`), `into-view` (`scrollIntoView`),
    `scroll-top` (`scrollTop`, `overflow: auto`),
    `hidden` (`scrollTop`, `overflow: hidden`), `translate` (`translateY`, no native scroll);
  - **yellow** 1, 2 or 4 videos; **blue** videos inside the scaled frame or outside it, placed over
    their box; **7** local test picture or a network HLS stream; **9** diagnostics on or off
    (reading them lays the page out, which could hide a fault); **0** reload.

The top line shows the modes, and the number at the top left counts frames: if it stops, the screen
is not being redrawn at all. The block under the strip is what the DOM says (scroll positions,
scroll events, whether the row in focus is inside the list, each video's state and whether it sits
on its box). The DOM can say "yes" while the screen shows otherwise; only the screen counts.

Two packages, since webOS takes the resolution from `appinfo.json`:

```sh
npm ci            # in Termux: npm ci --force (@webos-tools/cli refuses Android otherwise)
npm run spike:package
# ipk/com.idanck.shidurspike.hd_0.1.0_all.ipk      1280x720
# ipk/com.idanck.shidurspike.fullhd_0.1.0_all.ipk  1920x1080
npx ares-install -d <tv> ipk/com.idanck.shidurspike.hd_0.1.0_all.ipk
npx ares-launch -d <tv> com.idanck.shidurspike.hd
npx ares-launch -d <tv> --close com.idanck.shidurspike.hd   # before launching a new build
```

`tools/cdp.mjs` reads and drives the page over the television's DevTools WebSocket (Node 22 or
newer, nothing to install), for when the DevTools page cannot be opened:

```sh
TV=<tv-address> npm run tv -- list
TV=<tv-address> npm run tv -- state
TV=<tv-address> npm run tv -- selftest
TV=<tv-address> npm run tv -- mode list translate
TV=<tv-address> npm run tv -- key Down 15
```

Only one client can attach at a time: stop `ares-inspect` first.

## Versioning

The webOS version is its own, in `appinfo/appinfo.json` (from 0.1.0, when the app exists): LG
reviews every release by hand, so it ships on its own schedule.
