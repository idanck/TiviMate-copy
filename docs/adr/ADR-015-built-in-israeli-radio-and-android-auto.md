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

Flat: one root node (the playlist's Hebrew name) → one child `MediaItem` per station, each
already carrying its stream URL and metadata (title, artwork from `Channel.displayLogo`) —
resolved once, from the DB, not lazily per station. Selecting one station in the car (
`MediaLibrarySession.Callback.onSetMediaItems`) hands the **whole** ordered station list to the
player, positioned at the one picked, so the car's native skip-next/previous controls walk the
other stations for free — no custom session commands.

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
