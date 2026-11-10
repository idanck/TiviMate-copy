# ADR-061 — YouTube inside Shidur

**Status:** accepted 2026-11-09. Stage 1 (the server), stage 2 (the television's screen, 0.25.0) and the first part of stage 3 (search, the subscriptions rail, connecting an account, the states; 0.26.0) built; YouTube channels in the channel list and guide, and the phone (stage 4), follow.

## Decision

The owner's choices (2026-11-09): live YouTube channels, YouTube channel pages, his account's
subscriptions and search; videos play **inside** Shidur in YouTube's own player; designed for the
television and the phone together (the Studio canvas, pages "טלוויזיה · YouTube" and "טלפון ·
YouTube"). Then, for the build: search now and ask Google for more quota later; the data through a
Cloud Function with a shared cache; the account connection now, for test users only until Google
reviews the app.

### What YouTube's terms decide

From the YouTube API Services *Required Minimum Functionality* and *Developer Policies*:

- **Nothing in front of the player.** No banner, info card, clock, screensaver or dialog may be
  drawn over a YouTube player. The player always has a frame of its own and Shidur's interface sits
  beside or under it; a dialog pauses the player and empties its frame first.
- **One player that plays by itself per screen**, at least 200×200 px, and none in the background:
  leaving the player stops it. So no mini player over the phone's tabs.
- **No charge for watching in the player**, so YouTube is never part of the paid multi-view (Sport
  and News) mode.
- **The player is the IFrame Player API**; the native YouTube Android Player API is deprecated and
  must not be used. On Android it runs in a WebView with the app identified in the `Referer`.
- **No stream extraction**: nothing is played through ExoPlayer.

### Quota

The project's default allowance is 10,000 units a day for every method but search, and a separate
100 calls a day for `search.list`; read methods cost 1 unit; the day resets at midnight Pacific
time. Every unit is counted in `ytQuota/{day}`, the Functions stop a little short of either limit,
and the app is told `quota` or `search-quota` so it can say so (`TvYouTubeStates`).

### The server (stage 1)

Callables in `me-west1`, beside the account Functions (ADR-059):

| Function | Does | Cost |
|---|---|---|
| `ytChannels` | Channels and what is on them (live, announced, recent), from the shared cache `ytChannels/{id}`, fetched again after 10 minutes | per refresh of n channels: n + 2 units, and a search once a day for a channel with no known live stream |
| `ytResolve` | A channel from an ID, `@handle` or youtube.com address | 1 unit, then cached |
| `ytSearch` | Search (all, channels, live, videos), kept six hours per phrase in `ytSearch/{hash}` | 1 search + 1 unit |
| `ytLinkStatus`, `ytLinkStart`, `ytLinkPoll`, `ytLinkCode`, `ytUnlink` | Connecting an account: the TV flow (a code at google.com/device) on a television, a one-time server code on a phone | — |
| `ytSubscriptions` | The account's subscriptions (first 50, every six hours) and their newest videos, from the shared cache, an hour old at most | 1 unit + the channels |

- The API key and every OAuth secret are in Secret Manager; no key or token ever reaches the app.
- A connected account's refresh token is kept in `ytTokens/{uid}`, which no client can read; it is
  revoked and deleted by `ytUnlink` and by `deleteAccount`.
- A 24/7 live stream that has left a channel's recent uploads is found by a daily
  `search.list(eventType=live)` for that channel, and then checked by id until it ends.
- Limits per account, or per address for a device that has not signed in (sign-in is still behind
  its switch): `YT_LIMITS` in `functions/src/config.ts`.

### Stages

1. The server (this record).
2. Television: the player in a WebView, the YouTube screen, full screen, the list beside a shrunken
   player, the channel page.
3. Television: search, YouTube channels in the channel list and guide (numbers from 901), the
   states, connecting the account.
4. Phone: the YouTube tab, the player, the channel page, search, connecting.

### The television (stage 2, 0.25.0)

- `TvOverlay.YOUTUBE` is a browse screen, in the side menu after News; `TvOverlay.YOUTUBE_FULL` is
  the player alone. While either is up the full-screen player stands down (`AppViewModel.
  enterYouTube`), and it starts again as the visit ends, unless it ends in Sport or News mode.
- One player for the whole visit: `YouTubePlayerController` (`ui/common/YouTubePlayer.kt`), the
  IFrame API in a WebView loaded from `https://com.idanck.shidur/` so its requests carry the app's
  id as Referer. It takes no focus and no keys; Compose drives it through the API. It is drawn at
  one place in `TvRoot` (`YouTubePlayerLayer`), placed by absolute pixels over the screen's frame,
  the whole screen, or the shrunken rectangle beside full screen's list, and so is never made again
  between them. It pauses when the activity stops, behind a dialog and behind a channel page, and
  the screensaver never comes up while it plays.
- The saved channels are on the device (`YouTubeRepository`, SharedPreferences `youtube`), seeded
  with five Israeli news channels' own channels; "continue watching" is kept there too.
- The rails: live now on the saved channels, new from them, the channels themselves with a disc
  that adds one by address, @handle or ID (`ytResolve`), and continue watching. The subscriptions
  rail and search come with stage 3, with connecting an account.
- The remote in full screen: OK plays and pauses, ◀▶ jump ten seconds (not on a live stream),
  ▲▼ and Menu open the live list, CH+/CH− go to the next live channel, Back returns to the screen.
  The list closes after four seconds without a key.
- A video whose owner forbids embedding (errors 101/150) gets "Open in YouTube" instead.

### The television, stage 3, first part (0.26.0)

- Search is a page of YouTube's screen (`TvYouTubeSearch.kt`): Shidur's own keyboard (Hebrew and
  English, space, delete, voice through Android's speech service), the four kinds as chips, and
  the server asked only once typing has paused for 1.2 seconds and at least two letters stand.
  The remote's search key opens it while YouTube's screen is up. Pages (search, a channel) stack,
  and Back steps down them.
- The subscriptions rail is shown only to a device signed in to a Shidur account whose server has
  the television's OAuth client: `ytLinkStatus` decides between hiding it, inviting to connect,
  and the feed (`ytSubscriptions`). A connection that existed and is gone invites connecting again.
- Connecting is a dialog with the code Google gives, the address it gives and a QR code of that
  address, polling every interval until approved, denied or expired. The player waits, paused and
  hidden, behind it.
- A live stream that ends while it plays empties the frame and offers the next live stream on the
  saved channels.

## Rejected

- **Calling the API from the app with a key locked to the package.** Simple, but every device
  spends quota on its own, which does not last past a few users.
- **The RSS feed of a channel** to find new uploads at no quota: it is YouTube data fetched outside
  the API Services, which the Developer Policies forbid.
- **Handing the YouTube app the video** instead of an embedded player: the owner chose to play
  inside Shidur. The YouTube app remains the fallback for a video its owner does not allow to be
  embedded.

## Consequences

- Search stops for the day after about 95 searches across all users until Google raises the
  quota (an audit through YouTube's quota extension form).
- `youtube.readonly` has to pass Google's review before people outside the test-user list can
  connect an account; until then the rail invites only the testers.
- A deploy fails while any of the five secrets does not exist; `docs/13-youtube-setup.md` creates
  them, the OAuth ones as `unset` until their clients exist.
