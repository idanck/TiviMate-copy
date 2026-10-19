# Shidur for the computer (web app)

Shidur as an installable web app (PWA) for a computer: live channels, the guide, radio, search
and favourites, in Hebrew, in Studio's design. It installs from Chrome or Edge as an app with its
own window and icon. Decisions and scope: `docs/adr/ADR-044-a-web-version-for-the-computer.md`.

It is two things, and needs both:

- **The app** (`src/`): Preact + TypeScript, built with Vite. Everything the user sets up —
  playlists, accounts, favourites, the cached channel lists and guides — stays in that browser
  (IndexedDB).
- **The server** (`server/`): plain Node, no dependencies. It serves the built app and is the
  proxy every playlist, guide and stream goes through, because a browser cannot reach IPTV
  providers by itself (no CORS headers, plain-HTTP streams, User-Agent and Referer a page may not
  set, raw MPEG-TS). With ffmpeg installed it also re-encodes AC-3 audio, which browsers cannot
  play, to AAC.

## Run it

Node 22 or newer.

```sh
cd web
npm ci
npm run build
npm start                      # http://127.0.0.1:8787
```

During development, `npm run dev` runs the server and Vite (http://localhost:5173) together.

### With Docker

From the repository root (the image reads `version.properties`):

```sh
docker build -f web/Dockerfile -t shidur-web .
docker run -d -p 127.0.0.1:8787:8787 -e SHIDUR_WEB_PASSWORD=… shidur-web
```

or `web/compose.example.yml`.

### Configuration

All optional, all environment variables:

| Name | Default | |
|---|---|---|
| `PORT` | `8787` | |
| `HOST` | `127.0.0.1` | `0.0.0.0` in the image |
| `SHIDUR_WEB_PASSWORD` | — | A password in front of the proxy. Set it whenever the server can be reached from anything but this machine. |
| `SHIDUR_WEB_SECRET` | from the password | Key the session cookie is signed with. |
| `SHIDUR_WEB_ALLOW_PRIVATE` | off | `1` lets playlists name addresses on the local network (refused by default, so the proxy is no way into the LAN). |
| `SHIDUR_WEB_USER_AGENT` | `TiviCopy/1.0 (Android)` | What providers see, as from the Android app (ADR-018). |

Serve it over HTTPS (a reverse proxy or a tunnel) when it is reachable from outside: the
installed app, the service worker and the session cookie all expect a secure origin, except on
`localhost`. A login proxy in front of it (Cloudflare Access, for one) can take the password's
place: the manifest is requested with credentials and the service worker never caches the login
page, so the app installs and works behind it.

## Check it

```sh
npm run check     # typecheck, unit tests, production build
```

CI runs the same and builds the Docker image (`.github/workflows/web.yml`). A change under `web/`
alone builds no APK (`android.yml` ignores it).

## Keyboard

↑/↓ or PgUp/PgDn change channel, digits jump to a channel number, Space pauses, F is full
screen, M mutes, P is picture in picture, Backspace returns to the previous channel, `/` searches,
Alt+1…5 switch screens. The list is in Settings too.

## Known limits

- A provider that allows one connection at a time (`max_connections = 1`) counts the computer and
  the television as two: watching on one stops the other.
- Video the browser cannot decode is not converted — H.265 plays only where the browser and the
  graphics hardware support it. Audio is converted when ffmpeg is present.
- `localStorage['shidur.debug'] = '1'` writes what the player's decoders report to the console.
