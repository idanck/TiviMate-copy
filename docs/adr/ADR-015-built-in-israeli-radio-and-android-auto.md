# ADR-015 — A second bundled playlist: Israeli radio, plus sideload-only Android Auto

**Status:** accepted

> Numbering note: two existing files are both named `ADR-013-*`
> (`ADR-013-built-in-israeli-public-channels.md` and `ADR-013-picture-in-guide.md`). This ADR
> takes the next free number, 015; the existing collision is left as-is, not fixed here.

## Decision

TiviCopy seeds a second fixed playlist on cold start, alongside the Israeli TV channels one
(ADR-013): a curated list of Israeli radio stations, audio-only, pointed at a `radio.m3u`
hosted on the same Cloudflare Worker as the TV channels (`il-epg.idanck.com`). It is inserted
through the same ordinary playlist pipeline (`SourceRepository.ensureRadioPlaylist`,
`data/builtin/IsraelRadioStations.kt`) and refreshed by the existing `RefreshWorker` — no new
subsystem, no schema change, no `isRadio`/`audioOnly` field on `Channel`. "The radio category"
is identified the same way the TV channels playlist already is: a `Playlist.url` match, not a
new column.

This is a second, equally narrow exception to `docs/00-scope-and-platforms.md` §0.1 and
`docs/05-settings-advanced.md` §5.7, for the same reason ADR-013 gives: personal, non-store
software, so the Play Store provider-bundling policy does not apply, and the content stays
scoped and curated rather than becoming a general "add a source" feature (which ADR-013 already
rejected once, and this ADR does not reopen).

The radio playlist is additionally exposed to **Android Auto** through a `MediaLibraryService`
(`auto/RadioMediaLibraryService.kt`), browsable and playable from the car's own media template —
this app was never going to get a live-video feed onto a car screen: Android Auto's video
template is gated to a small set of Google-approved partners and out of reach for a personal
app, so the scope here is audio-only, through the standard Media category, exactly like a radio
app.

## Distribution: sideload only, not Play-reviewed

This app is not submitted to Google Play's Android-Auto-specific review, and is not expected to
be. Android Auto has its own developer-testing path for exactly this case: enabling "Unknown
sources" in the Android Auto app's own developer settings lets the car host load a
`MediaBrowserService`/`MediaLibraryService` app that has never been through that review — this
is a documented, intended testing mechanism, not a workaround. No code or manifest change is
gated on Play review; the manifest declares the media category
(`res/xml/automotive_app_desc.xml`, `<uses name="media"/>`) the same way a Play-reviewed app
would.

## Player isolation

Android Auto playback uses its **own** `ExoPlayer` instance (`auto/RadioPlayerFactory.kt`),
never `ServiceLocator.player` — the one instance the phone/TV UI shares. Playing radio in the
car and a channel in-app at the same time must not interrupt either.

This player is simpler than `com.tivicopy.player.player.PlayerEngine`, and deliberately does not
reuse it: `PlayerEngine` rebuilds its ExoPlayer on every `play()` call because different
channels can carry different per-channel headers. Every station in the bundled radio playlist
shares one `RequestProfile` (headers/user-agent are per-*playlist*, not per-channel —
`SourceRepository.profileFor`), so the radio player is built **once** per service lifetime, and
switching station is an ordinary queue seek within one `Player` playlist — never a rebuild, and
so never a moment where a `MediaSession` would be pointed at a released player.

## Browse tree and queue

Two levels. The root (the playlist's Hebrew name) holds a "Favourites" folder followed by one
folder per `RadioCategory` bucket — the categories the car renders as its top-level tabs — and
each folder holds its own stations as playable `MediaItem`s, already carrying stream URL and
metadata (title, artwork from `Channel.displayLogo`), resolved from the DB on every request
rather than cached, so a refresh or a hide/unhide needs no invalidation. Favourites is listed
even when empty: it is a tab, and a tab that only appears once a favourite exists cannot be
found in order to learn that favourites exist.

Selecting one station (`MediaLibrarySession.Callback.onSetMediaItems`) hands the player *that
station's own category* as its queue, positioned at the one picked, in the order the folder
listed it — so the car's native skip-next/previous controls walk the neighbouring stations
rather than jumping between unrelated ones.

Three things are presentation rather than structure, and all three live in `RadioBrowseTree`:

- **Regional stations are separated by where they broadcast.** The feed has one regional
  `group-title`, so `RadioCategoryClassifier.region` is a second heuristic over the station
  names inside it (`RadioRegion`: north, centre, Jerusalem, south, then unplaceable). Stations
  are sorted into that order — north first — and each carries its region both as Android Auto's
  `CONTENT_STYLE_GROUP_TITLE_HINT`, which draws a heading above each run, and as its own
  subtitle, so the split survives a head unit that ignores the hint.
- **The station that is playing says so.** Its row, and the folder holding it, carry a "now
  playing" subtitle, so coming back to the list to change station shows what is on. The player
  may only be read on the main thread and the tree is built off it, so the service's player
  listener pushes the id in (`setNowPlaying`) and asks the car to reload the folders it has
  already been served (`RadioSessionCallback.notifyBrowseRefresh`).
- **Queue items carry a square, re-rendered logo** (`RadioArtwork`). Station logos arrive in
  whatever size and shape their publisher used, and head units differ in what they do with the
  bitmap a session hands them — one draws it untouched, so a small or wide logo lands as a stamp
  in the middle of the Now Playing screen; another scales it to *cover* a wider card and crops
  the overhang. Each logo is therefore painted centred on a 512-square canvas, scaled to fit a
  safe area of `RadioArtwork.SAFE_AREA_FRACTION` (0.62) of that square rather than all of it: as
  large as a slot that shows the square whole can display it, with a transparent margin that a
  slot which crops gives up before the logo does. Only queue items — browse rows keep pointing
  at the logo URL, so a browse response never carries a bitmap per station across Binder.

## Alternatives rejected

- **A generic `isRadio`/`audioOnly` field on `Channel`.** Would need a schema migration for
  metadata fully derivable from which bundled playlist a channel belongs to — the same
  discipline ADR-013 already established for identifying its playlist. Revisit only if a real
  need for a cross-playlist "audio-only" concept emerges (e.g. a user's own radio playlist);
  not built speculatively for one bundled feed.
- **Reusing `PlayerEngine`/`ServiceLocator.player` for Android Auto.** Rejected for isolation
  (radio in the car must not interrupt, or be interrupted by, in-app playback) and for
  correctness: `PlayerEngine`'s rebuild-per-`play()` design assumes a `MediaSession` can be
  re-pointed at a fresh player on every switch, which the stable Media3 session API does not
  support without extra machinery — machinery this feature does not need, because every radio
  station shares one request profile.
- **A "show Israeli radio category" settings toggle.** `IsraelPublicChannels` has none —
  the playlist is simply always bootstrapped, deletable, and self-healing. Mirrored here for
  consistency; add a toggle later only if asked for.
- **Google's Android Auto video template / Play Store review.** Out of reach for a personal,
  sideloaded app (gated partner program) and unnecessary for the audio-only scope actually
  wanted here.

## Consequences

- If the user deletes the radio playlist from Settings, the next cold start silently reinstates
  it, same as the TV channels playlist.
- Curation, stream URLs and logos for the radio feed live outside this repo, on the same host
  as the TV channels (`il-epg.idanck.com/radio.m3u`), published by
  `/home/idan/iptv-epg-il/build_radio_m3u.py` and the existing `sync.sh` cron (twice daily,
  alongside `channels.m3u`/`guide.xml` — ADR-013's Consequences). Unlike the TV channels, there
  is no single free/legal upstream aggregate for Israeli radio to filter (`iptv-org/iptv`'s
  `streams/il.m3u` — the source ADR-013 already uses — carries no radio entries at all), so
  `build_radio_m3u.py` instead filters `radio-browser.info`'s public station index down to 8
  curated stations by name, the same "narrow curated set, not a general feature" posture as the
  TV channels. Logos are published once at setup (Wikimedia Commons where available, otherwise
  each station's own site), not re-published every cron run — matching how the TV logos already
  work.
- This ADR shipped (PR #18) before `radio.m3u` itself was ever published — the same class of gap
  ADR-013's Consequences already records for `channels.m3u` in 0.4.4: the app-side code assumed
  a feed that did not yet exist at the URL it pointed to, so the bundled playlist stayed at zero
  channels (and so invisible in practice) until the feed was actually built and published,
  after the fact, in this same change.
- `radio.m3u` carries two `group-title`s — "רדיו ישראלי" (the original 8 national stations) and
  "רדיו אזורי" (8 Second Authority-licensed geographic regional stations — Radio Darom, Radio
  Haifa, Radio Jerusalem, 104.5FM Tzafon, Lev Hamedina, Radio Tel Aviv, Kol Rega, Radio 90),
  added later. The regional set was verified against the regulator's own registry
  (`rashut2.org.il`), not just `radio-browser.info`'s name matching, after an initial pass turned
  up a few similarly-named but unlicensed small/internet-only stations. No app code change was
  needed for the second category: `M3uParser`/`SourceRepository.merge()` already turns each
  distinct `group-title` into its own `ChannelGroup`, so it surfaces as a separate, selectable
  category in the phone/TV UI on its own (visible by default — `Settings.showNewGroups` defaults
  to `true`). Android Auto's `RadioBrowseTree` is unaffected either way: it queries every channel
  in the playlist with no `groupId` filter, so it has always shown (and continues to show) both
  groups together as one flat list, regardless of category.
- The regional split (north/centre/Jerusalem/south) is client-side keyword matching on station
  names, exactly like the category classification above it and with the same caveat: it is
  presentation, not the source of truth. If the upstream `build_radio_m3u.py` ever publishes one
  `group-title` per region, that becomes the answer and `RadioCategoryClassifier.region` should
  give way to it rather than both being maintained.
- Station logos are fetched and re-rendered in-process for the car (`RadioArtwork`), warmed once
  per service start and cached in memory, with a failed logo remembered as failed so a broken URL
  is not re-fetched on every tap. A station tapped before the warm-up reaches it is rendered
  synchronously while its queue is built, so those fetches run on timeouts capped well below the
  stream timeouts — artwork must never be what keeps the music waiting.
- Testing Android Auto integration requires physical or emulated ("Desktop Head Unit") car
  hardware and a phone with "Unknown sources" enabled in Android Auto's developer settings —
  it cannot be exercised by the JVM unit test suite, only by `IsraelRadioStationsBootstrapTest`
  covering the data layer that feeds it.
- If the radio host URL ever moves, `IsraelRadioStations.LEGACY_M3U_URLS` records the superseded
  address the same way `IsraelPublicChannels.LEGACY_M3U_URLS` already does, so bootstrap
  corrects an existing row in place instead of stranding it beside a new one.
- Radio started out surfaced only as a `group-title` inside the Channels list (the entry above),
  reachable in practice only by making the radio playlist the active one — which then replaced
  the TV channels in Channels entirely, since the app has one active playlist at a time. Feedback
  was that this reads as "radio is inside Channels", not as its own thing, so it moved to a
  destination of its own on both shells (`docs/03-ui-navigation.md` §3.2/§3.5) —
  `AppViewModel.openRadio`/`closeRadio` point the same shared channel-list state at the radio
  playlist for as long as that tab/overlay is open, without touching the persisted active
  playlist, and `reload`/`selectPlaylist` now refuse to let the radio playlist become Channels'
  active one at all. No schema change and no second copy of the channel-list screen: Radio reuses
  `ChannelListScreen` end to end, so its `group-title` groups, favourites, hidden list and
  per-channel menu are the same feature working against a different playlist, not a rebuild.
- In-app radio playback used to be paused the moment `MainActivity` left the foreground —
  `onStop` treats a locked or turned-off screen the same as leaving the app entirely, which is
  correct for a live TV channel (nothing is showing its picture any more) but silenced a radio
  station for no reason, since there was never a picture to lose. `AppViewModel.isRadioPlaying`
  (a `Playlist.url` match against `IsraelRadioStations.M3U_URL`, identifying radio the same way
  everything else here does) now tells `onStop` to start `player/RadioBackgroundService.kt`
  instead of pausing. Unlike Android Auto's player above, this service is **not** a second
  isolated player — it plays through the very same `ServiceLocator.player` the UI already had
  running, and exists only to hold foreground-service priority and an ongoing notification while
  the activity is not visible; `onStart` stops it the moment the UI is back.
- The car's artwork shipped in 0.6.2 painting the logo edge to edge on that square, on the
  assumption — taken from how Android Auto is documented to draw artwork, not from a car, which
  this environment cannot reach — that it never enlarges the bitmap it is given. The first real
  head unit it ran on does exactly that, scaling the artwork to fill its Now Playing card: the
  logo came back enormous with the station name cropped off at the sides. 0.6.3 keeps the logo
  inside the safe area above instead — a size the car can shrink *or* crop into without cutting
  the name. The pure arithmetic of it (`logoBounds`) is pinned by `LogoBoundsTest`, since the
  rendering around it still needs a car to judge. Browse rows are untouched: they go on handing
  over the logo URL and letting the head unit size its own thumbnail.
- In-app, a station used to play against a black rectangle: an audio-only stream never produces a
  video frame, so the player surface stays the colour it was cleared to, and 0.6.3's logo-and-name
  card left the rest of the screen black (device report: "the radio screen is black"). 0.6.4 draws
  a backdrop in the station's own colour instead, sampled from its logo through Coil — the same
  logo the list it was picked from has already cached — with the clock, the date and a play/pause
  button over it (`ui/common/StationBackdrop.kt`, shared by both shells). The colour arithmetic is
  pure and pinned by `StationBackdropTest`; the layout still needs a screen to judge. A station
  whose logo is black-and-white, or whose logo cannot be fetched, falls back to the hash-derived
  hue its monogram tile already uses, so every station still gets its own colour rather than a
  neutral grey.
