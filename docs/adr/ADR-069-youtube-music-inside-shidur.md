# ADR-069 — YouTube Music inside Shidur

**Status:** accepted 2026-11-12. Stage 1 (the server and the account's music documents) built;
stage 2 (the television, 0.34.0) built; the account's button and settings (0.35.0) built; search and
the phone to come.

## Context

The owner asked for a YouTube Music page in Shidur (2026-11-12) and chose, on the Studio canvas
(pages "טלוויזיה · YouTube Music" and "טלפון · YouTube Music", copied to
`docs/design/studio/canvas/`):

- **Where:** a tab inside YouTube's screen — a "וידאו | מוזיקה" switch at its top — not a menu item
  of its own; on the television and the phone together.
- **What:** a personal library, discovery, search, a full player and a mini player.
- **Where the data comes from:** the official YouTube Data API only. There is no official YouTube
  Music API; `ytmusicapi`, which reads YouTube Music's own pages, says of itself that Google neither
  supports nor endorses it, and is left out for now.

### What the official API gives for music, and what it does not

Checked against the Data API reference on 2026-11-12:

| The tab needs | The API | So |
|---|---|---|
| The account's playlists | `playlists.list mine=true` | From YouTube. |
| Artists | `subscriptions.list mine=true` (channels) | From YouTube, the subscriptions the video tab already reads. |
| Liked songs | `videos.list myRating=like`, at most 1,000; every kind of video | YouTube's likes, kept to the music category, **together with likes Shidur keeps itself**. |
| Liking from Shidur | `videos.rate` (50 units), needs a scope that writes | See *Open*. |
| Saved albums | Nothing | **Saved by Shidur**, in the account. |
| Listening history | Nothing (watch history is not readable) | **Kept by Shidur**, on every device of the account. |
| Mixes, recommendations | Nothing | "בחירה מהירה" is made by Shidur from its history, the likes and the artists. |
| Search | `search.list`, `videoCategoryId` only with `type=video` | Songs held to music; artists and playlists cannot be. |
| Popular | `videos.list chart=mostPopular` with `regionCode` and `videoCategoryId` | Israel's music chart. |

Not documented anywhere the owner or this record could find: whether a like given inside the
YouTube Music app is among `myRating=like`, and whether an album's playlist (the `OLAK5uy_…` ids)
reads through `playlistItems.list`. Both are found out at run time: the liked list is whatever
YouTube returns, and an album that does not read answers "not found".

## Decision

The owner's choices for the library (2026-11-12):

1. **Liked songs: both sources together.** YouTube's likes (music only) and the likes kept in Shidur
   are one list. A like given in Shidur is kept in Shidur and, with an account connected, sent to
   YouTube as well — once the scope question below is settled.
2. **Albums: saved by Shidur.** "שמירה בספרייה" on an album's page keeps it in the Shidur account,
   on every device; it is not saved in YouTube Music, and the page says so.
3. **History: kept by Shidur**, by day and device. It feeds "המשך האזנה" and "בחירה מהירה"; with no
   history yet "בחירה מהירה" is left out and the popular chart leads.

YouTube's terms (ADR-061) shape the player exactly as for video: the player is always visible, at
least 200×200, with nothing drawn over it, and nothing plays in the background. So the mini player
is the player itself — its frame shrunk to 356×200 on an opaque strip at the foot of the
television's screen, or a 212-tall card with a 200×200 frame above the phone's bar — and leaving
YouTube stops the music.

### The server (stage 1)

Callables in `me-west1`, beside the video tab's, with the same key, tokens and quota counters:

| Function | Does | Cost |
|---|---|---|
| `ytmChart` | Israel's popular music, shared by everyone, six hours (`ytMusic/chart-IL`) | 1 unit |
| `ytmPlaylist` | A playlist or an album and its songs (up to 200), six hours (`ytmPlaylists/{id}`); a private one is read with the caller's own token and kept under a key of the account's own | 2 + 1 per 50 items + 1 per 50 songs |
| `ytmLibrary` | `playlists`: the account's playlists, an hour. `liked`: its likes, up to 200, music only, half an hour (`ytmLib/{uid}`) | 1; up to 4 |
| `ytmSearch` | Songs, artists, albums, playlists or all, in the video tab's search cache under keys of its own, six hours | 1 search + 1 unit |
| `ytmRate` | A like given or taken back in Shidur, on YouTube too | 50 units |

- **The music category** is not written into the code: `videoCategories.list` (`hl=en`) is asked
  once a month and the category titled "Music" is used (`ytMusic/category`).
- **Search** spends from the same hundred searches a day and the same share per account
  (`YT_LIMITS.search`) as the video tab's. "all" is one call for every type, its videos then kept
  to music from their details.
- **The account's music documents**, written by the app (`firestore.rules`), each of the fields the
  app writes and of a bounded size: `users/{uid}/musicLikes/{videoId}`,
  `users/{uid}/musicAlbums/{listId}`, `users/{uid}/musicHistory/{id}` (`device` is `tv` or
  `phone`; the app trims the history). `deleteAccount` removes them with the rest of `users/{uid}`.

### Stages

1. The server and the account's documents.
2. Television: the switch on YouTube's screen, "בשבילך", the library (playlists, liked, artists,
   albums, history), an album's page, the player screen and the mini player; the likes, albums and
   history synced.
3. Television: search (songs, artists, albums, playlists).
4. Phone: the same, for touch.

### Two further choices (2026-11-12)

- **Liking on YouTube from Shidur: yes.** `videos.rate` needs a scope that writes, so a connection
  now asks for one beside `youtube.readonly` (`link.ts`, the phone's `PhoneYouTube.kt`), and the
  scope granted is kept with the token. The phone asks for `youtube.force-ssl`; the television asks
  for `youtube`, since Google's flow for TVs accepts only `youtube` and `youtube.readonly` of
  YouTube's scopes — 0.34.0 asked it for `youtube.force-ssl`, Google refused to give a code, and
  connecting on a television failed until the server was fixed on 2026-11-12 (the owner's choice, 2026-11-12: `youtube` on the
  television rather than read-only there). `ytmRate` sends the like; an
  account connected before this, or one that declined the write scope, answers `reconnect`, the
  like stays in Shidur and the tab says to connect again. A rating costs 50 units of the day's
  10,000. The owner adds the scope on the consent screen (`docs/13-youtube-setup.md`, step 5), and
  Google's review of the app covers it.
- **Albums and playlists told apart by a rule of thumb.** The API does not say which a playlist is;
  the playlists YouTube makes for an artist's albums have ids that begin with `OLAK5uy_`, as seen
  on YouTube and YouTube Music. Not documented anywhere, so it can be wrong: `isAlbumId`.

### The television (stage 2, 0.34.0)

- A switch at the top of YouTube's screen, "וידאו | מוזיקה" (`YtModeSwitch`); on music, the parts
  "בשבילך" and "הספרייה שלי" as chips that follow the remote (`TvYouTubeMusic.kt`). Search comes
  with stage 3.
- "בשבילך": what plays beside the frame, as on the video tab, and the rails under both — quick
  picks (`quickPicks`: the songs played most lately, then liked ones; left out with no history), the
  account's playlists or the invitation to connect, its artists (the subscriptions), the chart and
  the saved albums.
- "הספרייה שלי": playlists, liked songs (`mergeLiked`: Shidur's, then YouTube's), artists, albums
  saved in Shidur and the history by day and device; a line under the filters says where each
  comes from.
- A playlist's or an album's page: play, shuffle, and for an album "שמירה בספרייה", with the line
  that it is kept in the Shidur account, not in YouTube Music.
- The player screen: the song, previous / play / next, like, shuffle, repeat, and what comes next.
- One player for the visit, as for video. A queue (`YouTubeSession.queue`) moves on when a song
  ends, wherever the player is drawn; a song is never written into the video tab's "continue
  watching", and goes into the music history instead (`MusicRepository.notePlayed`).
- **The mini player is a column, not the canvas's strip.** The canvas drew the television's mini
  player as a 356×200 frame at 1080p — 178×100dp — and a YouTube player must be at least 200×200;
  in the player's page CSS pixels are dp. So on the library and a list's page the frame is 356×200dp
  at the top of a column at the end edge, with the song and its buttons under it.
- The likes, saved albums and history are kept on the device and, while signed in, in the account
  (`MusicRepository`): listened to, the first answer after sign-in joining what the device kept
  signed out; the history keeps the newest 300.

### The YouTube account (0.35.0)

Asked for by the owner (2026-11-12): connecting again — so an older connection gains the scope for
likes — needed a way in once an account was connected, and there was none to disconnect. Designed
on the canvas first (`TvYouTubeAccount`, `TvSettingsAccount`, `YtAccountChip`, `PYouTubeAccount`,
`PSettingsAccount`), in the two places the owner chose:

- **On YouTube's screen:** a button at the end of the top row (television: after the switch's row,
  `YtAccountChip`; phone: after search in the top bar) — the channel's picture and name, an amber dot
  when the connection cannot give likes, "חיבור YouTube" with none. It opens the account window
  (a dialog on the television, a sheet on the phone) or the connect window.
- **Settings › חשבון,** under Shidur's account: the channel and a status, "חיבור מחדש" or
  "החלפת חשבון", and "ניתוק".
- **The window:** who is connected (`ytLinkStatus` now answers the channel: `channels.list mine=true`,
  one unit, read when connecting and once for a connection made before); a read-only connection
  leads with "חיבור מחדש". "החלפת חשבון" and "חיבור מחדש" run the shell's own way of connecting and
  the new token replaces the old one; what was kept for the old one (`ytSubs`, `ytmLib`) is
  forgotten, and its token revoked when it is surely another grant (another client or another
  channel — revoking a grant of the same account and client would revoke the new token too).
- **Disconnecting** asks first, with the remote on "ביטול", and calls `ytUnlink`: the token is
  revoked at Google and forgotten, with the same caches, for every device of the Shidur account.
  Shidur's own likes, albums and history stay.
- The music tab's note that a like stayed in Shidur carries a "חיבור מחדש" button.

## Rejected

- **`ytmusicapi`**, for the albums, the "Liked music" list and the mixes the official API lacks: it
  is not supported by Google and may stop working, which is a risk to a product sold through Google
  Play. Left out for now, by the owner's choice.
- **A separate side-menu item for music**: the owner chose a tab inside YouTube's screen.

## Consequences

- Music search shares the project's hundred searches a day with video search until Google raises
  the quota.
- The likes, albums and history are the account's: a device that has not signed in keeps none.
- A like given inside the YouTube Music app shows in the tab only if YouTube returns it among the
  account's likes.
