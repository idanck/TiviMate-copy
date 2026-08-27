# ADR-017 — Recently watched channels are published to Google TV's "Continue watching"

**Status:** accepted

## Decision

The app writes its recently watched channels to the system TV provider's Watch Next table, so
they appear in the Google TV home screen's "Continue watching" row, and registers a
`shidur://channel/<id>` deep link so a card there tunes the channel it names.

This implements FR-TVI-02 and FR-TVI-04, both of which were `COULD` and not implemented.
FR-TVI-03 — publishing a preview channel of our own to the home screen — stays not implemented:
it is a separate surface the viewer has to opt into from the launcher, and nobody asked for it.

## Shape

The judgement is in `tv/WatchNextPlan.kt`, which is pure Kotlin in its own file: which channels
belong in the row, in what order, which are stale, and how a deep link parses. The
`ContentResolver` work is `tv/WatchNextSync.kt` and decides nothing. That split exists because
this repository has no instrumentation tier and the environment it is developed in cannot build
the app at all (`CLAUDE.md`) — so the half that can be tested on a JVM is all of the half that
can be wrong.

"Continue watching" means something slightly different for live television than for a film: there
is no position to resume, so a card promises "you were watching this, here it is again". That is
what `channels.last_watched_utc` already records, so the row is the recently watched list with
artwork, capped at ten and expiring after thirty days. Radio stations qualify on the same terms —
the bundled radio playlist is watch history too (ADR-015).

`setLastPlaybackPositionMillis` and `setDurationMillis`, which Google's guidance pairs with
`WATCH_NEXT_TYPE_CONTINUE`, are deliberately left unset. The launcher then draws no progress bar,
which is the truth about a live channel.

## Poster art is claimed as 1:1, and that is a compromise

The row is given `channel.displayLogo` — a plain https URL — with `ASPECT_RATIO_1_1`. These are
channel logos: square-ish marks with transparent margins, not 16:9 key art. Declaring 16:9 invites
the launcher to crop the sides off; declaring 1:1 has it letterbox a square into its card, which
is the less wrong of the two.

The better answer is to render a real 16:9 card — the logo centred on the channel's own accent
colour, which `StationAccentCache.accentFor` already computes and `auto/RadioArtwork.kt` already
knows how to letterbox safely. It is not done here because Watch Next takes a **URI**, not bytes,
so it would need a `FileProvider` (the manifest has no `<provider>` at all today), a file-paths
resource, cross-process URI grants and a cache-eviction policy — a large surface, none of which
can be verified from this environment, in service of how a shelf looks. If it reads poorly on a
real television, that is the change to make.

One consequence to keep in mind: the launcher fetches that URL itself, from another process.
These logos are public https URLs, so it works — a logo behind a user-agent gate would not.

## Guards

- **API 26.** `androidx.tvprovider`'s `WatchNextProgram` is `@RequiresApi(26)` and this app's
  `minSdk` is 23. Lint would normally catch a slip, but `lint { abortOnError = false }` means a
  `NewApi` finding is a warning in a log rather than a failed build — the failure would land as
  a `NoClassDefFoundError` on somebody's Android 7 box. Every reference is therefore confined to
  `@RequiresApi` members reachable only through `sync`/`clear`, which check the version first.
- **No provider, no sync.** Support is decided by asking the package manager whether the TV
  provider resolves, not by inferring from leanback or the UI mode: what the code needs to know
  is whether there is somewhere to write.
- **Everything in `runCatching`.** A lost launcher row is never worth an error over a stream that
  is playing.
- **Throttled.** `AppViewModel.play` fires on every channel-up press; zapping through twenty
  channels would otherwise be twenty cross-process round trips. Thirty seconds, plus an
  in-flight claim, with the hourly `RefreshWorker` sweep forcing past it — that sweep is what
  catches a channel a playlist refresh deleted.
- **An opt-out.** `Settings.watchNextEnabled`, on by default, and turning it off calls `clear`
  rather than merely stopping. Publishing viewing history to a surface outside the app with no
  way to take it back is not acceptable, and the cost is one preference and one branch.

## Deep links

A custom `shidur://` scheme rather than an https App Link: this app owns no web domain whose
links it should intercept, and `il-epg.idanck.com` serves playlists, not pages.

It gets its **own** `<intent-filter>` on `MainActivity`. Folding `VIEW`/`BROWSABLE` into the
existing `MAIN`/`LAUNCHER`/`LEANBACK_LAUNCHER` filter would have stopped the app appearing on the
home screen at all — an intent matches a filter only when every category in it matches.

The activity was already `exported="true"`, so this widens what other apps on the device can ask
of it. The whole reachable surface is one action: play a channel that is already in this device's
own database. The parser is correspondingly strict — a literal prefix, digits only, a positive
id — and refuses anything else rather than coercing it.

`MainActivity` is `singleTask`, so `onNewIntent` calls `setIntent`; without it `getIntent()` keeps
answering with the intent that started the process and a second card would replay the first one's
channel. The launch intent is handled too, but only when `savedInstanceState == null` — a
language change recreates the activity holding the same intent, and replaying it there would
re-tune out from under whatever the viewer had moved on to. `AppViewModel.playChannelId` marks
the start behaviour consumed so `resumeStartChannel` cannot race it and win.
