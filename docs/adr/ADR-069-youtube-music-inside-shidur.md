# ADR-069 — YouTube Music inside Shidur

**Status:** accepted 2026-11-12. Stage 1 (the server and the account's music documents) built; the
television and the phone to come.

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
| `ytmSearch` | Songs, artists, playlists or all, in the video tab's search cache under keys of its own, six hours | 1 search + 1 unit |

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

1. The server and the account's documents (this record).
2. Television: the switch on YouTube's screen, "בשבילך", the library (playlists, liked, artists,
   albums, history), an album's page, the player screen and the mini player.
3. Television: search; Shidur's likes and albums synced.
4. Phone: the same, for touch.

## Open

- **Liking on YouTube from Shidur** needs `videos.rate`, which the read-only scope the account is
  connected with (`youtube.readonly`, ADR-061) does not allow. Asking for a scope that writes means
  every connected account connects again, and Google's review of the app covers that scope too.
  Until the owner decides, a like given in Shidur is kept in Shidur only.
- **Albums and playlists in search** are one kind to the API. The canvas draws "אלבומים" and
  "פלייליסטים" as separate chips; stage 3 shows them as one until there is a way to tell them apart.

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
