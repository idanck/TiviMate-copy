# ADR-026: The song a station is playing

## Status

Accepted. Extends ADR-015 (bundled radio and Android Auto) and ADR-024 (the screen protects
itself).

## Context

Asked: for the stations that can say what song is on — and maybe the next one — show it, on the
television and the phone, and add it to what Android Auto already shows.

There is no one source for this. A survey of all 92 stations in `radio.m3u` (October 2026, from
the Pi, which can reach them) found three kinds:

1. **The song rides inside the stream.** Shoutcast/Icecast servers interleave a
   `StreamTitle='Artist - Song';` block with the audio when the request carries
   `Icy-MetaData: 1`. About 35 stations fill it with a real song title, among them רדיו קול
   רגע, רדיו פלוס, רדיו נטו, רדיו לב העיר, רדיו המזרח, רדיו קצב מזרחית, רדיו חולמים, רדיו
   קול המרכז, רדיו מנטה, רדיו שירי דיכאון, רדיו שירי אהבה, רדיו נושמים אהבה, רדיו נושמים
   מזרחית, רדיו סהר, MUZIKA 106.4FM, Up2Dance, רדיו 69FM, עברי שש, רדיו כחול יוון, רדיו מזרח
   ישן, רדיו חמש ישראל, Classic FM, Jazz FM, Jazz24, Radio Swiss Jazz, Radio Suisse Classique,
   LifeGate, Radio Salsa.
2. **The field is there and empty, or holds a placeholder.** Every major station: גלגלצ and
   its six playlists, גלי צה"ל, כאן 88/ב/גימל/מורשת/רקע/קול המוסיקה, 99FM, 103FM, רדיוס 100FM,
   the Multix-CDN regionals (דרום, ירושלים, צפון, לב המדינה, חיפה, תשעים, 102 ת"א). Their field
   is empty or says "CDN - Powered By Multix". What they play is published, if at all, through
   each broadcaster's own website or app API: Kan's and Galgalatz's sites sit behind
   Cloudflare/Incapsula bot challenges, Triton's public now-playing feed returns nothing for
   Kan's mounts, and 100FM's app feed (`digital.100fm.co.il/app/` → a per-channel XML) is
   reachable but often blank.
3. **HLS** (כאן תרבות, קול נתניה, רדיו 5, ערוץ הכנסת, the two Radios digital channels): no ICY at
   all.

None of the three gives the *next* song. ICY is "now" only, and the station APIs seen show the
current item.

What the in-stream titles look like matters as much as whether they exist. Several stations send
Windows-1255 Hebrew that arrives as Latin-1 ("àäáä" for "אהבה"), one sends bytes already
destroyed upstream (U+FFFD), and several send a CDN or server name, a recording-software file
name, or the station's own name instead of a song.

## Decision

**Kind 1 only, in this change, through the player that is already reading the stream.**
ExoPlayer sends `Icy-MetaData: 1` for every progressive stream by itself and hands each block out
as an `IcyInfo` through `Player.Listener.onMetadata`. No request of our own, no per-station
code, no new network dependency. `player/StreamTitle.kt` turns the raw field into something fit
to show:

- Windows-1255 read as Latin-1 is read back as Windows-1255 — only when at least half the
  string's letters sit where Windows-1255 keeps the Hebrew alphabet and nothing is outside
  Latin-1, so "Café del Mar" stays French;
- placeholders ("powered by", AzuraCast, Adobe Audition), anything shorter than three
  characters, anything containing U+FFFD, and the station's own name are dropped — those
  stations show what they showed before, rather than noise.

Where it shows:

- **Television and phone:** `PlaybackStatus.streamTitle`, cleared on every new source. The
  station screen (`AudioOnlyNowPlaying`, both shells) shows it under the station's name, behind
  a note icon, laid out by its own text direction so a Hebrew song reads right-to-left under an
  English interface. The phone's compact layout, which had room for the name only, shows it on
  one line. The radio skin of the screensaver shows it under the name; the dark skin does not.
  The phone's background-playback notification uses it as its text.
- **Android Auto:** the car's own player listens for the same metadata and replaces the playing
  queue item with a copy carrying the song as its artist and in front of its subtitle. The
  station's name stays the title — the song is added, not substituted. Same URI, so ExoPlayer
  updates the item in place without re-preparing: no gap in the audio. The original subtitle is
  kept in the item's extras, so each song replaces the last; a station skipped back to is
  cleared of the song it was playing when it was left.

## Rejected, for now

- **Per-broadcaster APIs in the app** (kind 2). Each is a different undocumented endpoint, two
  are behind bot challenges an app cannot honestly pass, and every one would break silently when
  its website changes — ten fragile integrations in an app that cannot be built or tested in the
  environment it is usually edited from. If these stations are wanted, the place for it is the
  Pi, next to `build_radio_m3u.py`: a script that polls whatever each broadcaster exposes and
  publishes one `nowplaying.json` (station id → current, and next where a source gives it) on
  `il-epg.idanck.com`. The app would then read one file it controls the shape of, and a broken
  scraper is fixed on the Pi without an app release.
- **A "next song" line.** No source surveyed provides it for an in-stream station. It can only
  come from the Pi feed above, for whichever broadcaster's API includes it.
- **ID3 titles in HLS segments.** Untested against any bundled HLS station; not added on spec.

## What album art would need (assessed, not built)

Asked: what would it take for the song's cover to replace the station screen and the
screensaver's centre.

- **A lookup, because no station sends art.** ICY carries no image (one station's `StreamUrl`
  has an empty `picture=` parameter). Searching the title against a public catalogue works for
  Hebrew: Apple's iTunes Search API (`itunes.apple.com/search?entity=song&country=IL`, no key)
  found both Hebrew songs tried and the English one; Deezer's (`api.deezer.com/search`, no key)
  found two of three, at up to 1000×1000. Apple first, Deezer second.
- **A confidence check.** Station titles are free text ("Artist - Song", "Song-Artist", "04 -
  Song-Artist", whole-album uploads, live sets). A result must match both artist and title loosely
  — normalised, Hebrew and transliterated — or the station screen stays as it is; a wrong cover
  is worse than none.
- **Caching and a budget.** One lookup per distinct title, cached in memory, and none at all for
  titles that failed; songs change every 3–4 minutes, so this is a few requests an hour.
- **Burn-in (ADR-024).** A cover is a fixed, high-contrast square. On the station screen it
  takes the logo's place inside the existing layout; on the screensaver it must ride inside the
  drifting block, at the reduced lightness that skin already uses, never as a full-screen still.
- **Android Auto gets it almost free:** the same lookup sets the queue item's artwork, in place of
  the rendered station logo, for as long as the song plays.

Estimated as a change about the size of this one, plus the matching rules' tests.

## Consequences

- About a third of the stations now name their song on every screen; the rest look exactly as
  they did. Which stations those are is a property of each station's playout, not of this code,
  and can change without an app release in either direction.
- `StreamTitleTest` pins the cleaning against strings captured off the real streams. Whether a
  given station sends anything is not testable here.
- A station whose playout starts sending a new placeholder will show it until the list in
  `StreamTitle.PLACEHOLDERS` learns it.
