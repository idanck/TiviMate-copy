# 03 — UI and Navigation

## 3.1 Two front-ends, one brain

| | TV (Leanback) | Mobile (touch) |
|---|---|---|
| Input | D-pad, OK, Back, colour keys, numerics | Tap, swipe, long-press, pinch |
| Distance | 10-foot | 1-foot |
| Type scale | Large, high contrast, no text smaller than 12sp effective | Standard Material scale |
| Focus | Explicit focus state on every element, no hover-only affordances | Ripple/press states |
| Overscan | 5% safe margins | Edge-to-edge, insets-aware |

Both share the same domain layer, so a channel hidden on the phone is hidden on the TV.

## 3.2 Core TV screens

1. **Playback (root)** — fullscreen video is the home screen, not a menu. The app should
   boot into video, not into a dashboard.
2. **Channel list overlay** — groups on the left, channels in the middle, now/next on the
   right, video still playing behind. Opened with OK or Right.
   - The group column carries Search, All channels, Favourites, Recently watched, Hidden
     channels and New group above the playlist's own groups. Hidden groups stay listed, drawn
     dimmed, so hiding one is not a one-way door.
   - Each channel row carries a favourite toggle. **Menu** on the remote, or a long press on
     touch, opens that channel's options: watch, favourite, edit, hide, parental lock, and move
     up or down. The same gesture on a group row opens the group's options.
   - The channel editor covers a local name, a logo address, the channel number and the group,
     each showing the provider's own value as its placeholder, plus one row that undoes all of
     it. None of it is sent anywhere: a refresh updates what the provider owns and leaves these
     alone.
   - Moving a channel is offered only where the list is in the playlist's own order. In
     Favourites the row above is not the row above in the full list, and in Recently watched the
     order comes from watch times.
3. **Mini guide** — a single-row horizontal timeline for the current channel.
4. **Full guide** — the classic grid: channels down, time across, current-time marker,
   horizontal scroll by hours/days, jump-to-now, jump-to-date.
5. **Programme details** — title, times, description, category, series/episode, poster,
   plus actions: watch, record, schedule series, catch-up, remind, add to favourites.
6. **Search** — across channels, programmes (future and past-in-catch-up), VOD and series. It
   owns its own query, spans the whole playlist rather than the selected group, and is debounced.
   The channel list's filter box is a different thing: it narrows what is on screen, stays
   visible while it is set, and is scoped to the group you are in.
7. **Recordings** — completed and scheduled.
8. **Settings**.

## 3.3 Guide behaviour requirements

- The guide must remain **scrollable and responsive while video keeps playing**.
- Rendering of a 500-channel × 7-day guide must be virtualized; scrolling must hold 60 fps
  on a mid-range Android TV box.
- Catch-up availability shown as a badge on past programmes.
- Recording state shown as a badge (scheduled / recording / recorded).
- Colour-key shortcuts on the TV remote where available (e.g. red = record).

## 3.4 Remote control mapping

- Full D-pad navigation; **no screen may require a pointer**.
- Configurable actions bound to: OK short/long, Up, Down, Left, Right, Back, Back-double,
  numeric keys, colour keys, Play/Pause, FFwd/Rew, Channel Up/Down, Menu, Info, Guide.
- Default bindings must be sane before customization: Up/Down = zap, Left = channel list,
  Right = mini guide, OK = info card, OK-long = playback menu, Back = up one level,
  Back-double = exit with confirm.
- A **learn/diagnostic screen** showing raw keycodes, so users of odd remotes can bind
  whatever their device actually sends. Cheap to build, disproportionately valuable.

## 3.5 Touch UI (phone/tablet)

This is our differentiator and must not be an afterthought.

- Bottom navigation: Live / Guide / VOD / Recordings / Settings.
- Vertical channel list with sticky group headers and fast-scroll.
- Gestures during playback: vertical swipe left = brightness, right = volume, horizontal
  swipe = seek (VOD/timeshift), double-tap sides = skip, pinch = aspect ratio.
- Browsing is full screen; playing opens a **Now Playing screen** over it (the music-app
  pattern, ADR-011): portrait shows the video above the channel's identity, now/next and
  quick actions; landscape is fullscreen video with the channel list one tap away as a sheet.
  Back collapses to a **mini player bar** above the navigation bar and playback continues
  (audio only — the video track is dropped while nothing displays it).
- The channel list opens anchored to the playing channel, and each row's second line says
  what that channel is showing now.
- PiP on Home press from the Now Playing screen; background audio optional.
- Tablet: two-pane master/detail.
- Chromecast **sender** support (cast the current stream to a Chromecast) — **COULD**,
  and notably absent from the benchmark.

## 3.6 Appearance

- Themes: system / light / dark / AMOLED black, plus an accent colour.
- Adjustable overlay opacity and auto-hide timeout.
- Font scale (small → extra large) that actually reflows the guide.
- Configurable bottom playback menu: which actions appear and in what order.
- Optional channel-logo-only vs logo+name list modes.
- Interface language: follow the device, English or Hebrew, chosen in the app rather than by
  switching the whole television. Choosing Hebrew mirrors the layout and swaps the D-pad's
  horizontal bindings with it. See `docs/adr/ADR-009`.
- Channel order: the playlist's own order, A–Z, most watched, or recently watched. Manual moves
  live in the first of those.
- Full RTL support including Hebrew, with correctly mirrored guide layout and
  right-anchored time axis. (The benchmark's RTL handling is not a strength.)

## 3.7 Profiles

- Multiple local profiles, each with its own playlists, favourites, groups, watch history,
  resume points and settings.
- Fast profile switch from the overlay.
- One profile may be marked "kids" and restricted to an allow-list of groups.

## 3.8 Parental controls

- PIN (4–6 digits), set once, required for the actions the user selects:
  entering Settings, opening locked groups, opening locked channels, switching profile,
  disabling parental controls.
- Lock by group, by individual channel, or by EPG content rating where XMLTV supplies one.
- Locked channels can be either hidden entirely or shown-but-blocked.
- Failed-attempt lockout with backoff.
- PIN stored only as a salted hash; recovery flow defined and documented.
