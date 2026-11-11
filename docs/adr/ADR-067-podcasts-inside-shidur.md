# ADR-067 — Podcasts inside Shidur

**Status:** accepted 2026-11-11, built in 0.32.1–0.32.5. Designed on the canvas pages
`טלוויזיה · פודקאסטים` and `טלפון · פודקאסטים`.

## Context

On 2026-10-17 the owner asked whether podcasts could play inside the app; the answer then was yes —
the player plays MP3/AAC on demand and the app already reads RSS — with two open choices: where
podcasts come from, and where they are entered. On 2026-11-11 he settled both:

- **The entry:** a destination of its own, not a shelf of the radio — the television's side menu
  after YouTube, and on the phone a tab (which made the bar four tabs and More, ADR-066).
- **The source:** Podcast Index for search and suggestions, with adding by RSS link always available.
- **Sync** between the same account's devices in the first batch, not later.

He asked for "a podcast interface with its own options": keeping podcasts, history, resuming where
one stopped, favourites, a personal library.

## Decision

### Data

A podcast is RSS 2.0 whose items carry an audio `<enclosure>` (`PodcastFeed`), with `itunes:*` and
the Podcasting 2.0 `podcast:chapters` and `podcast:transcript` read where present. An item without
audio is not an episode. Four tables (database version 3):

- `podcasts`, `podcast_episodes` — the feed's data, rewritten by each refresh. An episode the feed
  drops is kept while the viewer still has it starred, in the history or queued.
- `podcast_marks` — the viewer's state per episode: position, length, played, favourite, history,
  last heard, on which device, and when it changed.
- `podcast_queue` — what plays next, in order.

Marks and the queue are keyed by **feed address and guid**, not by row id, so a podcast removed and
added again, a restored backup and another device all land on the same marks.

**New** means neither started nor played and published since the show was added, counting back two
weeks: a show added today offers its last fortnight as new, not its archive.

**Played** means within 30 seconds of the end, or of the show's outro skip if longer. Its position
goes back to the start.

### Sources

- **By link:** an RSS address, with `feed://`, `itpc://`, `pcast://` and a bare host accepted.
- **Podcast Index:** two callables in `me-west1`, `pcSearch` and `pcTrending`, call it with the key
  and secret from Secret Manager (docs/14), so the app never holds them. Every answer is cached in
  `pcCache` (search a day, the rail six hours) and shared. The rail asks for Hebrew feeds
  (`he, he-il, iw, iw-il`); search lists Hebrew shows first. Until the secrets hold a key, both answer
  `not-configured` and the screen offers only adding by link.

### Playback

`PodcastSession` plays an episode through the full-screen player, from its mark, at the show's own
speed, and past its intro skip when starting at the beginning. The place is written every ten
seconds, on pause, on every switch away and from `onStop`. At the end the episode is marked played
and the next one starts: the head of the queue, else the show's next episode in its listed order.
The sleep timer stops the sound after 15–90 minutes or at the end of the episode. A podcast is sound
with no picture, so it plays on in the background and under a locked screen, like a radio station
(`isRadioPlaying`).

### The screen

One composition for both shells (`ui/podcasts/`), drawn with the shared rows, chips, buttons, fields
and sheets, so each shell gets its own focus and touch treatment:

- Five parts as chips: בשבילך (the episode stopped in, new episodes, continue listening, the
  library), הספרייה שלי, מועדפים, היסטוריה, גילוי והוספה.
- A show's page: in the library or not, play the newest, its settings, the episodes by filter.
- The player: transport, speed, sleep timer, star, the queue and the chapters.
- A long press (Menu on a television) on any episode opens its options; the gear on a show opens its
  settings (speed, intro and outro skip, order, remove from the library).

Back steps inside the screen first (`PodcastNav`): the television's Back through the activity, the
phone's through `MobileRoot`.

### Sync

While signed in (ADR-059), the library and each show's settings, every mark and the queue are kept
in the account: `users/{uid}/podcasts/{id}`, `users/{uid}/podcastMarks/{id}` and
`users/{uid}/podcastState/queue`, where `{id}` is a hash of the feed address (and guid). Each device
listens for changes and writes its own. **The newer mark wins, whole:** position, played and the star
were set by one person at one moment, and mixing fields from two moments would invent a state nobody
chose. The Firestore rules let an account read and write only its own documents, of a bounded size.

## Rejected

- **A shelf of the radio screen** — less to build, but a list of episodes does not fit a strip of
  station discs, and the owner wanted an interface of its own.
- **Apple's search** — no key, but about 20 calls a minute and terms written for promoting Apple's
  store.
- **A list kept on the Pi** — full control, but every show goes through the owner and there is no
  free search.
- **Merging marks field by field** — see Sync.

## Consequences

- The canvas's cards rails are drawn as rows on "בשבילך" (the library is a rail of covers); the
  star sits in a row's options and on the player rather than on every row.
- Not built in this batch: new-episode notifications, downloads for listening offline, transcripts,
  a podcast folder in Android Auto, the QR code that sends a link from the phone to the television.
- `PODCASTINDEX_API_KEY` and `PODCASTINDEX_API_SECRET` must exist in Secret Manager — `unset` is
  enough — before the Firebase deploy that carries the callables, or that deploy fails.
- A change to the podcast tables is a change to what a backup carries (`BackupManager`).
