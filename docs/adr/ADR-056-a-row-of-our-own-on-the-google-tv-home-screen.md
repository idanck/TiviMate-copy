# ADR-056 — A row of our own on the Google TV home screen

**Status:** accepted

## Decision

The app publishes one preview channel, named after the app ("שידור"), to the system TV
provider. Its cards are the viewer's favourite channels, in their channel-list order, then the
channels watched most recently that are not favourites, newest first — twenty at most, with at
least five places kept for recents when there are that many. A card's title is the programme on
now, where the guide knows it, and the channel's name otherwise; the channel itself is the logo on
the card. Google TV shows only a preview program's title under the card — the line beneath is the
launcher's own (a date, season and episode, a duration) and never the description — which is why
the programme cannot be a second line (checked on the owner's Streamer, 06/11/2026; the owner
chose programme-only titles over "channel · programme"). Selecting a card plays that
channel through the existing `shidur://channel/<id>` deep link (ADR-017).

This implements FR-TVI-03, which ADR-017 left unimplemented as "not asked for". It now is: the
owner asked for exactly this row on 06/11/2026.

## Why it is shown without the viewer opting in

ADR-017 described a preview channel as a surface "the viewer has to opt into from the launcher".
That holds for an app's second and later channels. The first one, published with
`PreviewChannelHelper.publishDefaultChannel` (which calls `requestChannelBrowsable`), is shown
straight away — checked on the owner's Google TV Streamer, whose home screen shows Emby's row
from an app that holds `WRITE_EPG_DATA` and no Engage SDK integration. So one channel is all
this publishes.

## Rejected

- **Engage SDK.** The supported way to reach Google TV's recommendation surfaces now, but it
  needs Play Store distribution, Google's approval and a scale threshold this app does not meet.
- **Several rows (Favourites, Recent, On now).** Every row after the first needs the viewer's
  approval from the launcher, and the owner asked for one row with both.
- **Reusing the Watch Next opt-out.** The two surfaces publish different things — this one
  includes favourites never watched — so each has its own switch in Settings ▸ Playback, and each
  removes what it published when turned off.

## Shape and guards

`tv/HomeRowPlan.kt` decides (pure Kotlin, `HomeRowPlanTest`), `tv/HomeRowSync.kt` writes, and
everything ADR-017 lists under "Guards" applies unchanged: API 26 behind `@RequiresApi`, a
provider check before any write, `runCatching` around all of it, a thirty-second throttle for
zapping with an in-flight claim, and an opt-out that deletes the channel rather than freezing it.

The card list is refreshed on cold start, on every channel change (throttled), on starring or
hiding a channel (forced), and by the hourly `RefreshWorker` sweep (forced). That sweep is also
what moves each card's programme title along with the schedule, so a title can lag a programme
change by up to an hour. Poster art is the channel logo declared as 1:1, for ADR-017's reasons.

## The channel is found by a query of our own, and only one is kept

On the owner's Streamer the row was published once and then never changed: every sync after the
first found no channel of ours through `PreviewChannelHelper.getAllChannels()`, published another
default channel, and wrote its cards there — rows the launcher never showed, because only the
first channel an app publishes is made browsable without the viewer's approval. The visible row
kept the cards of the first sync (06/11/2026, found through the sync log added for it).

So `HomeRowSync` now lists this app's channels with a plain `ContentResolver` query (`_id`,
`internal_provider_id`, `browsable`), keeps the browsable one — or the oldest — and deletes the
rest, cards included (the provider cascades the delete). Every channel of ours is treated as the
row's channel, whatever it is labelled, because this app only ever publishes one.
