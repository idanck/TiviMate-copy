# Shidur for LG televisions (webOS)

Shidur as a packaged webOS app, for LG televisions from webOS 6 (2021) on: live channels, the guide,
radio, Sport and News mode (two or four channels at once, and the headlines), search and favourites,
in Hebrew, driven by the remote. Decisions and scope: `docs/adr/ADR-052-a-version-for-lg-televisions.md`
and, for Sport and News mode, `docs/adr/ADR-053-sport-and-news-mode-on-lg-televisions.md`.

It is two things, packaged together:

- **The app** (`src/`): Preact + TypeScript, built with Vite into one classic script for Chromium
  79. A copy of the computer version (`web/src`) changed for the television; the parsers in
  `src/data/` stay identical to `web/src/data/`.
- **The service** (`service/`): a JS service the television runs beside the app, which reads
  playlists, guides and panel JSON from providers (a packaged app cannot: no CORS) and tells the
  player what a stream is. Plain ES2017 CommonJS, because webOS 6 runs Node 8.12.

`appinfo/` holds the manifest (`appinfo.json`: id, version, title, resolution), the icons and the
splash screen.

## Develop

Node 22 or newer.

```sh
npm ci
npm run dev        # the app in a desktop browser (providers without CORS headers will not load)
npm run check      # typecheck, tests, build
```

In a desktop browser there is no Luna bus, so the app reads providers with `fetch`, which only works
with ones that send CORS headers. Escape is Back; the arrows, Enter and the digits are the remote.

## Package and install

```sh
npm run build && npm run package   # ipk/com.idanck.shidur_<version>_all.ipk
```

To run it on a television: turn on developer mode (LG's Developer Mode app), register the
television with `ares-setup-device`, then `npx ares-install ipk/<file>.ipk` and
`npx ares-launch com.idanck.shidur`. `npx ares-inspect com.idanck.shidur` opens its developer tools.

## Versioning

The webOS version is its own, in `appinfo/appinfo.json`: LG reviews every release by hand, so it
ships on its own schedule. Raise it for every package uploaded; LG refuses the same version twice.
