# ADR-061 — YouTube inside Shidur

**Status:** accepted 2026-11-09. Stage 1 (the server) built; stages 2–4 (television, phone) follow.

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
