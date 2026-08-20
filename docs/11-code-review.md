# 11 — Code review

Review of the implementation before merge. Everything listed as **Fixed** was corrected on the
same branch; the commit that carries each fix is named.

Method: read every file in `app/src/main` against the requirement it claims to satisfy, then a
second pass looking specifically for the failure modes this class of app is prone to —
main-thread I/O, unbounded growth, lifecycle mismatches, and silent no-ops.

---

## Correctness defects found

### 1. `SettingsRow` bound its trailing lambda to composable content, not the click action
**Severity: high.** `SettingsRow(title, subtitle, onClick, trailing)` put `trailing` last, so
every call site written as `SettingsRow("Playback") { navigate(PLAYBACK) }` was passing the
navigation call as `trailing` — a `@Composable` slot. The action would have run *during
composition*, on every recomposition, and the row would have had no click behaviour at all.
It type-checked, so nothing would have caught it before a device.

**Fixed** — `onClick` is now the last parameter, and `SettingsSwitch` passes `trailing` by name.
(`d5b5189`)

### 2. The `#EXTINF` display name was split on the last comma, not the first
**Severity: high.** `#EXTINF:-1 group-title="General",Channel 4, HD` yielded the name `HD`.
Any provider channel whose name contains a comma was silently truncated. Unquoted attribute
values cannot contain a comma, so the first unquoted comma is the unambiguous separator.
Covered by a regression test.

**Fixed** (`836caf9`)

### 3. A settings-driven player rebuild stopped playback
**Severity: high.** Neither the decoder selectors nor the load control can be swapped on a
running `ExoPlayer`, so changing the buffer preset or decoder mode replaces the instance. Two
things were missing: the new instance was never given a media item, and `PlayerView` kept a
reference to the released one — `AndroidView`'s `update` block only re-runs when a state it
reads changes, and `engine.exoPlayer()` is not observable. Either alone is a black screen.

**Fixed** — the engine publishes its live instance as a `StateFlow` the surface collects, and
re-prepares the current source after a rebuild. (`17a7d36`)

### 4. Cancelling one recording did not stop it
**Severity: high.** The copy loops tested `scope.isActive`, but cancelling a single recording
cancels its child job, not the service scope. `RecordingService.ACTION_STOP` would have marked
the recording cancelled while it kept writing.

**Fixed** — the loops observe `currentCoroutineContext().isActive`. (`4a57e74`, pre-merge)

### 5. Recordings on a SAF tree could not be deleted
**Severity: medium.** Only a display name was stored, and the delete path checked for a leading
`/`, so deleting a SAF recording removed the database row and left the file occupying storage
— with no way to find it again from the app.

**Fixed** — the document URI is stored and deletion handles both storage kinds. (`a200d61`)

### 6. The series-metadata warning fired on every one-off recording
**Severity: medium.** `scheduleProgramme` attached `NO_SERIES_METADATA` to single-programme
recordings, where it is irrelevant, and the details dialog repeated it unconditionally. It
would have trained the user to ignore the one warning FR-REC-05 exists to make count.

**Fixed** — series recording is now a real action, and the warning belongs to it. A series the
guide cannot describe is refused outright; a one-off recording says nothing about series.
(`3e9199f`)

### 7. Fuzzy guide matching could bind a channel to its sibling's guide
**Severity: medium.** `Sky Sports F1` and `Sky Sports F2` differ by one character in thirteen
and scored 0.75 — close enough to a usable threshold to be a real risk, and a wrong guide is
worse than no guide.

**Fixed** — digits are treated as decisive: any difference in the digit sequence caps the score
below any threshold a caller should use. Weights were rebalanced toward edit distance so
genuine near-misses (`Main Events` / `Main Event`) still match. (`836caf9`)

### 8. `java.time` was unresolvable in the build script
**Severity: build-breaking.** In a Kotlin DSL script the bare `java` prefix resolves to
Gradle's `JavaPluginExtension` accessor, not the package.

**Fixed** — imported instead. (`9a692e4`)

---

## Performance and resource defects

### 9. `SourceRepository.merge` was quadratic in channel count
Collecting each channel's alternate URLs re-derived every entry's identity inside the loop, and
identity derivation runs name normalisation. A 10,000-channel playlist would have cost on the
order of 10^8 normalisations per refresh.

**Fixed** — identities are computed once and indexed. (`4a57e74`, pre-merge)

### 10. The guide window map grew without bound while scrolling
`GuideScreen` accumulated `programmes = programmes + newWindow`, so a long scroll through a
500-channel guide kept every row's programmes alive.

**Fixed** — the map is replaced, bounded by what is on screen. (`4a57e74`, pre-merge)

### 11. The image cache was "bounded" by deleting files behind Coil's journal
`CacheBudget.enforce` walked the whole cache directory and deleted least-recently-used files.
Coil maintains a `DiskLruCache` journal in that directory; deleting entries out of band is
fragile, and Coil would otherwise have sized its own cache from free disk space — which is the
unbounded-growth failure FR-SET-15 exists to prevent.

**Fixed** — the app implements `ImageLoaderFactory` and gives Coil an explicit, budget-derived
disk cache. (`f31549d`)

### 12. Database access on the main thread in two screens
`RecordingsScreen` and `VodScreen` read from the database inside `LaunchedEffect`, which runs
on the composition dispatcher, and mutated it from click handlers.

**Fixed** — both moved to `Dispatchers.IO`. (`4a57e74`, pre-merge)

---

## Deliberate simplifications, recorded rather than hidden

- **Aspect ratio.** Seven user-facing modes map onto Media3's four resize modes; the mapping is
  documented in `PlayerSurface` rather than pretending each option is distinct.
- **Audio delay (FR-PLB-08).** Not implemented. Media3 exposes no audio offset, and a control
  that only wrote a status string would have read as a working feature.
- **Pinch to change aspect (FR-UI-14).** Bound to a long press. A pinch detector on the same
  surface contends with the vertical and horizontal drag detectors, which is a bad trade for a
  rarely used control.
- **Entitlements (`FR-LIC-*`).** Absent by decision — `docs/adr/ADR-007`.

`docs/10-implementation-status.md` records the full position, requirement by requirement.

---

## Not addressed

- **Performance targets are unmeasured.** `NFR-PERF-01..06` name specific numbers on specific
  hardware. The design choices that serve them are in place — a lazy channel axis, a windowed
  guide query batched per distinct offset, streamed parsing, a bounded cache — but no figures
  have been taken on a reference device, and nothing here claims otherwise.
- **`ProgrammeDetailsDialog` schedules on the main thread.** Two small queries and one insert
  behind a click. Worth moving if the recordings table ever grows large.
- **A refresh can race a per-channel edit.** ~~Both write whole channel rows. The window is tiny
  and the loser is a single user edit, not data loss.~~ **Fixed, and the original entry had it
  backwards** — see finding 13.

---

## Second pass — the channel management work

Read against the requirements the first pass marked done, asking not "does the code work" but
"can anyone reach it".

**13. Every per-channel edit rewrote the whole row, and the refresh was the loser.**
Renaming, reordering, the guide offset and the decoder choice all went through
`Dao.upsertChannel(snapshot.copy(...))`, which writes all thirty columns from whatever `Channel`
the caller is holding. A refresh landing between reading that snapshot and writing the edit would
have its provider fields — name, logo, urls, catch-up — pushed back to the stale values. The
"Not addressed" entry above described the loser as a single user edit; it is the refresh that
loses, and silently. It had never fired only because none of those four functions had a caller.
**Fixed** — a targeted setter per concept, plus a regression test that drives a refresh between
the read and the write (`ChannelQueriesTest`).

**14. Seven view-model functions had no callers, and `docs/10` recorded them as done.**
`setHidden`, `setLocked`, `renameChannel`, `moveChannel`, `setChannelEpgOffset`, `bindGuide` and
`setChannelDecoders` were fully implemented and unreachable. FR-ORG-03 and FR-ORG-04 were marked
Done on the strength of the data layer alone. **Fixed** — a channel options menu on both shells;
`docs/10` corrected, and the two rows that were overstated now say what is and is not there.

**15. Clock ranges reverse in a Hebrew layout.** `"${formatClock(start)} – ${formatClock(end)}"`
is two runs of strong left-to-right digits joined by a bidirectionally neutral dash; in a
right-to-left paragraph the dash takes the paragraph's direction and the times swap, so a
programme is drawn as ending before it began. Six call sites. FR-UI-21 was marked Done on the
strength of `supportsRtl` and start/end padding, neither of which touches this. **Fixed** —
`TimeModel.ltr` and friends wrap each run in isolates.

**16. The remote was not mirrored.** D-pad Left was hardwired to the channel list and Right to
the mini guide, so a Hebrew interface had a mirrored screen driven by an unmirrored remote.
**Fixed** — both follow the layout direction.

**17. Search wrote into the channel list's filter.** Two different questions shared one field:
opening search filtered the Live list, and closing it left that filter in place with nothing on
screen to explain the shrunken list. Results were also scoped to whichever group was selected.
**Fixed** — separate state, and a filter is now always visible once set.

**18. Every `LIKE` clause passed the user's text straight through.** A search for `100%` matched
everything beginning `100`, and `_` matched any character. **Fixed** — escaped, with `ESCAPE`
declared per clause. Covered by a regression test.

**19. Hiding a group hid only its row.** FR-ORG-03 names groups as well as channels, but the
channel query never consulted `channel_groups.hidden`, so a hidden group's channels stayed in All
channels. **Fixed** — a subquery, and a test.

### Third pass — a review of the whole branch, 2026-08-20

Run over `main..HEAD` at high effort. It found seven things this branch had caused or made
reachable, and eight that were already on `main`. The first group is fixed in `cded323`; the
second is split between the small ones, fixed alongside them, and the rest, recorded below.

**20. The programme dialog's favourite button could not un-favourite.** It flipped its own label
while calling the view model with the snapshot the shell captured when the dialog opened, so every
press wrote the same value. **Fixed** — the view model takes the state to write.

**21. Move up/down was offered under the sort presets added in the same branch.** A preset orders
by something other than `sort_index`, so the swap changed nothing visible and rearranged the
user's hand-made order. **Fixed** — `canReorder` checks the sort too.

**22. `AppLocale.wrap` moved the JVM default locale and never restored it,** so switching back to
"follow the device" left the process on the abandoned language. **Fixed** — it no longer touches
the default; resources come from the configuration, and every formatter here passes `Locale.ROOT`.

**23. The string context for non-composition code was rebuilt per access** — a `Configuration` and
a context per toast, from background threads. **Fixed** — cached on the locator, invalidated when
the language changes.

**24. The channel-list filter queried per keystroke with nothing cancelling the last one,** so
results could commit out of order. **Fixed** — held, cancelled and debounced.

**25. Group actions were reachable only from the group column, which phones do not draw.**
**Fixed** — the compact layout gets a manager dialog, since a chip has no long press.

**26. `EpgRepository.bindManually` still wrote all thirty columns.** **Fixed** — three columns,
written as three.

**27. Recordings ended with a null file path.** The destination was written to the row but not to
the snapshot every exit path builds its final update from, so `finish` put it back to NULL: the
library could neither play the file nor delete it. Pre-existing. **Fixed.**

**28. The recording concurrency limit counted only SCHEDULED recordings,** so one already running
did not count against it. Pre-existing. **Fixed.**

**29. Restoring a backup silently dropped group state.** The insert lost to the unique index on
`(playlist_id, name)` and nothing updated instead, so the hidden flag and ordering of every group
a refresh had recreated were discarded — and still reported as restored. Pre-existing. **Fixed.**

**30. A retry could fire against the wrong channel.** Retries are posted to a `Handler` with
nothing removing the callback and a backoff reaching fifteen seconds, so a retry armed for one
channel re-prepared whichever was playing when it landed. Pre-existing. **Fixed** — a generation
counter, bumped by every new source.

**31. The HTTP client cache was unbounded.** `RequestProfile` is built from live settings, so
dragging the timeouts slider added an entry per value for the life of the process. Less severe
than it looks — `newBuilder` shares the base client's pool and dispatcher, so no entry owns
threads — but a map that only grows is still a leak. Pre-existing. **Fixed** — bounded LRU.

### Not addressed in this pass

- **Backup skips a channel whose only change is its position.** `BackupManager.export` decides a
  channel is worth exporting from the favourite, hidden, locked, custom name, logo, offset,
  manual EPG id and decoder fields — not `number` or `sort_index`, which it then writes when it
  does export. A channel the user only reordered or renumbered is left out. Widening the test to
  include `sort_index` would export nearly every channel, since a refresh sets it on all of them;
  doing it properly needs a signal for "the user has reordered this playlist". Recorded here
  rather than half-fixed.
- **A focusable control inside a focusable row is unverified on a D-pad.** The favourite toggle
  on each channel row should be one step right, and `FocusableRow` now tracks `hasFocus` so the
  row keeps its ring while the toggle has focus — but no D-pad has been near it. If the focus
  order misbehaves, the same action is the first row of the options menu.
- **The guide grid's mirroring is reasoned, not observed.** It is laid out in widths with no
  pixel offsets and one shared scroll state, so it should mirror by construction. FR-UI-21 is
  marked Partial rather than Done for exactly this reason.
- **Parental controls are stored but never enforced (FR-PRF-04/05/07/08).** The PIN is hashed
  correctly and the lock-scope switches persist, but `ParentalPin.verify` and `PinAttemptLimiter`
  have no callers anywhere: Settings opens unprompted, a locked channel plays on the first press,
  and `hideLockedChannels` only filters the list and can be unticked in the same unprotected
  screen. This is a missing feature rather than a defect in a working one, and it is outside what
  this branch set out to do, so it is recorded here and `docs/10` is corrected from Done to Not
  implemented rather than half-built.
- **Series do not play (FR-PLB-28).** `XtreamClient.series` stores a `series:<id>` stream id and
  the grid sends it through the movie URL, which no panel serves, so every series tile answers
  with a playback error. `seriesEpisodes` exists with no caller. The honest fix is an episode
  list, which is a feature; `docs/10` now says plainly that series do not play.
  **Fixed on 2026-08-20** — see the section below.
- **Scheduling a recording blocks the main thread.** `ProgrammeDetailsDialog` calls the scheduler
  straight from `onClick`, and each schedule does a query, an insert and a `StatFs`. "Record
  series" iterates a fortnight of episodes, so it is dozens of blocking calls in one frame. The
  earlier entry above under-described it; the rule it breaks is the one `VodScreen` cites.
- **Free space is measured on the wrong volume.** `RecordingScheduler.freeSpaceBytes` runs
  `StatFs` against internal storage whenever the destination is a SAF tree, which is the only
  thing the picker can produce on Android 10 and later — so the space check and the low-space
  warning are both wrong in both directions for the common case.
- **Cold start does disk I/O on the main thread.** `Application.onCreate` opens the database,
  re-arms recordings and walks the whole cache tree before the first frame; `BootReceiver` does
  the same inside a broadcast's ten-second window.
- **`EpgMatcher` scores every unmatched channel against every guide entry** with a Levenshtein,
  after each guide refresh. Finding 9 bounded the normalisation; the comparison itself is still
  quadratic in the worst case.
- **`AppViewModel.unmatchedChannels` still has no caller.** It answers "which channels did the
  matcher fail to place", which belongs next to the match breakdown in Diagnostics as the
  one-tap route into mapping that FR-EPG-09 asks for. Now that the mapping dialog exists there
  is somewhere for it to lead, but wiring it is a separate change.


---

## Series playback, 2026-08-20

Raised as a question about the entry above rather than as a review finding, but it went the same
way: tracing why series did not play turned up a second dead feature next to the first.

### What was wrong

One URL carried three separate mistakes:

```
http://host/movie/user/pass/series:31.mp4
```

`series:31` is not a stream id; `/movie/` is the films endpoint and episodes are served from
`/series/`; and `.mp4` was a default, because `XtreamClient.series` never read
`container_extension`. The panel answered 404, and `PlayerEngine.handleError` classified that as
transient — so the viewer watched a spinner through the whole retry budget before getting a
generic failure that read like a broken provider.

The mistake underneath was conceptual. **A series has no URL. Only an episode does.** What was
missing was not a correct id but the step where a viewer picks one.

### Found while fixing it

- **`Dao.saveResume` had no callers.** `resumePositionMs` was read when playback started and
  never written by anything, so continue-watching (FR-PLB-29) could not fill and every title
  restarted from zero. `docs/10` claimed Done; corrected. Fixed here, because an episode list
  that cannot remember where you were is not worth much.
- **`Dao.vod`'s `seriesOnly` and `movieOnly` had no callers either**, and `seriesOnly` was wrong
  in a way that only mattered once episodes existed: `series_id IS NOT NULL` matches episodes too.

### Decisions worth keeping

- **Episodes are fetched when a series opens, not during a refresh.** `get_series_info` is one
  request per series; a playlist with a thousand series would make a thousand requests for
  screens nobody opened. They are then stored and read back, which is what gives each episode a
  row to hold a resume position and a favourite — and what a failed fetch falls back to.
- **Episodes carry an `episode:` stream id.** `vod` is keyed by `(playlist_id, stream_id)` and
  some panels issue film and episode ids from one space, so an unprefixed episode could overwrite
  a film's row and take its favourite and resume position with it. No schema change; `VERSION`
  stays at 1.
- **The episode list lives inside `VodScreen` with its own `BackHandler`.** It is registered from
  composition, which is what makes it outrank the handlers both shells set up beforehand — so
  Back returns to the grid without a new TV overlay, a new activity back rule, or a new term in
  the phone's `canGoBack`.

### Not proved here

That a panel actually streams from `/series/` needs a real subscription. CI proves the URLs and
the queries; `docs/10` says as much rather than claiming more.

---

## Phone interface restructure, 2026-08-20

The phone's owner, testing on a phone, reported the layout itself as the defect: the list
compressed under an always-pinned video, selection that visibly did nothing, and a filter that
could not answer "the HEB channels". The restructure (ADR-011) went through review before it was
pushed; seven findings, all confirmed, all **fixed (`72e8dad`)**.

### What was wrong

- **`resumeStartChannel` answered before looking.** It returned true on a non-zero id and did
  the `dao.channel` read in a fire-and-forget coroutine, so a start channel deleted by a refresh
  opened the new Now Playing screen over nothing — black video, no metadata — on every launch.
  It now suspends for the read and reports what actually happened.
- **The start effect replayed on every activity recreation.** `LaunchedEffect(Unit)` in
  `setContent` re-runs when a language change calls `recreate()`, and the shared view model
  survives — so the start channel tore down whatever was playing, and the new code would also
  have thrown the player screen open over it. Resume is now one-shot per process.
- **The scroll anchor's one-shot guard lived inside the non-empty branch** — the exact branch a
  transiently-empty filter tears down — so its comment ("never fights the user's scrolling")
  described the opposite of its behaviour: the refill re-anchored mid-typing. The guard moved to
  screen level.
- **The same anchor raced startup.** The list query and the start-channel read run concurrently;
  if the list landed first the anchor consumed itself against `currentChannel == null` and the
  headline behaviour silently no-opped. It now waits, keyed on both.
- **The mini bar's now/next went stale.** The programme-turnover refresh sat in `PlayerScreen`,
  which leaves composition on collapse — exactly when the mini bar is the only thing showing the
  title. The refresh moved to `MobileRoot`, which is always composed.
- **The now-line cost a query per row scrolled.** Every `firstVisibleItemIndex` change restarted
  the effect and ran `guideWindow`. Quantised to five-row blocks and debounced 120 ms, a fling
  now costs a handful of queries; `collectLatest`-style restart cancellation does the rest.
- **Collapsed playback kept decoding video nobody displayed.** Browse screens compose no
  `PlayerSurface`, but the stream's video track kept downloading and decoding. The engine now
  drops the video track (not the stream) while neither the player screen nor PiP is showing,
  and re-applies the choice when it rebuilds the instance.

### Decisions worth keeping

- **`NowPlayingInfo` exists because `currentChannel` deliberately lies.** An on-demand session
  leaves `currentChannel` in place so live state can be restored afterwards; captioning surfaces
  keyed off it would title a film with a live channel's guide. The sealed type names what the
  player is actually carrying, and the play functions now answer whether they started so the
  player screen never opens over a refused attempt (a series container, a missing recording file).
- **Back ordering by composition order, again.** The player screen's handlers are registered
  inside it, after every shell handler, so it wins while open — the same mechanism the episode
  list uses, with the sheet's handler registered after the close handler so it wins in turn.
- **The group-name clause is gated on "no group selected".** Inside a group every channel shares
  the group's name; matching it there turns the filter into a no-op. The hidden-group exclusion
  stays a separate conjunct, so a group-name match cannot resurrect a hidden group's channels —
  pinned by test.

### Not proved here

The JVM suite proves the Dao clause and the existing behaviours around it. Nothing automated
composes the new screens: portrait/landscape on a phone, the four start behaviours, PiP entry
and exit, RTL Hebrew layout, TV focus landing on the playing channel, and the cost of the
video-track drop on real streams all need a device pass. CI proves compilation and the 111-test
suite, not the phone in the hand.

---

## First device pass on the new phone shell, 2026-08-20

The 0.3.0 build reached its user's phone the same day, and the screenshots came back with four
reports: five-digit channel numbers wrapped inside their 36dp cell, the tab labels broke
mid-word ("Recording / s"), the landscape picture was "suddenly cropped", and two asks — a
play/pause control with quality details on the player screen, and a floating window on leaving
the app with pause and close controls. The cropped picture was not a bug in the mapping
(`AspectMode.FIT` renders letterboxed): the long-press gesture cycles the aspect mode, fires
easily by accident, and persisted a Zoom with nothing on screen to explain or undo it.

The round went through review before the push; eleven findings, all **fixed (`08dfd4c`)**.

### What was wrong

- **Every play/pause surface rendered `isPlaying` and toggled `playWhenReady`.** A rebuffering
  stream is not playing but has not been paused, so mid-stall the button showed Play and
  tapping it *paused* — the button then read correctly and the stream never came back. All
  surfaces now render and act on the intent flag, which `PlaybackStatus` carries explicitly.
- **Four copies of the toggle, one of them dangerous.** The mini bar, the transport row, the
  remote key and the floating window's action each flipped `playWhenReady` themselves, and the
  window's copy called `exoPlayer()` — which *builds* a player — so an action landing after
  `release()` on the way out of the app would construct and leak a codec. One
  `PlayerEngine.togglePlayPause()` now serves all four and refuses to build.
- **`pointerInput(Unit)` froze the gesture closures.** The activity handles rotation itself
  (`configChanges`), so the handlers kept their first composition's captures for the life of
  the screen: rotate mid-playback and the tap-for-channel-list still thought it was portrait.
  Keyed on the orientation now, with `size` read per event instead of cached.
- **The floating-window default exposed the TV shell.** `pipOnHome` on by default meant a
  Home press on an Android TV box squeezed whatever overlay was open into the window; only the
  phone shell had an `inPip` branch. The TV shell now renders bare video there too.
- **`Rational` went to Android unclamped.** Outside the 1:2.39–2.39:1 band the PiP call
  throws; `runCatching` swallowed it, so an ultra-wide stream would simply never get a window.
  Clamped now, and the in-window params refresh is keyed on the stream's dimensions so a
  failover reshapes the window instead of leaving the old shape.
- **Two hand-written aspect-label maps.** The player's new map duplicated Settings' list, and
  `getValue` on it turned any future `AspectMode` entry into a runtime crash on long-press.
  One exhaustive `AspectMode.labelRes()` now serves both, compiler-checked.
- Smaller: the channel-number cell is fixed at 48dp (a per-row minimum un-aligned the logo
  column), the transport KDoc contradicted the code it documented, and a duplicated
  channel re-lookup was hoisted.

### Decisions worth keeping

- **Aspect changes announce themselves.** Both routes into the cycle — the long press and the
  new button — post the mode they switched to. A persisted display mode with no feedback is
  how "the picture is suddenly cropped" happens.
- **The floating window adds only play/pause.** Expand and close are the system's own PiP
  controls; duplicating them as RemoteActions would spend the tiny action budget on buttons
  the window already has.
- **The quality line shows only what is known.** Resolution, frame rate, bitrate and codec
  each appear once the stream reports them, isolated LTR so the run cannot reorder in Hebrew.

### Not proved here

Still JVM-only: the PiP action's behaviour on real launchers, the clamped window on actually
ultra-wide streams, rotation mid-playback on a device, and whether 48dp holds the longest
channel numbers real playlists issue. The suite compiles and passes; the phone in the hand is
the next reviewer.

---

## Requested review of the merged state, 2026-08-20

Asked for outright rather than prompted by a defect: a fresh pass over everything that merged
today. Two sweeps ran — one over the series-playback round (#5), which had never been reviewed
by other eyes, and one over the *seams* between the two interface rounds (#6, #7), each of which
had been reviewed only in isolation. Six findings from the first, eight from the second, plus
two races the first sweep's own fix opened. All **fixed (`e26e496`, `b85c8ff`)**.

### The series round

- **Resume marks were lost to stale snapshots.** `playVod` started from the tile's
  `resumePositionMs`; the lists only re-read on their own triggers, so the snapshot went stale
  the moment another episode saved its place — and the next save overwrote the real mark.
  Play-time now reads the mark from the database, pinned by test (FR-PLB-29).
- **Pruned episodes lingered and 404'd.** `refreshEpisodes` only ever upserted. A successful
  fetch now deletes what the panel stopped listing, inside the same transaction, sparing the
  survivors' marks and the container row. Pinned by test.
- **A full disk could crash the app on exit** — `appScope` has no exception handler and the
  resume write was unguarded. Guarded.
- **Every series open walked the playlist's whole vod set** — no index served `episodes()`.
  `idx_vod_series(playlist_id, series_id)`: schema version 2, the first real migration.
- **The on-demand screen forgot everything on unmount** (tab switch, search overlay) because
  its state lived inside the screen the shells unmount wholesale. `VodScreenState` is hoisted
  into both shells now — the same lesson the episode list itself taught in the series round,
  one level higher.
- The favourite toggle was copy-pasted per tile; one helper serves the grid.

### The seams

- **Backgrounding without a PiP window kept the stream fully alive**: video decoded for no
  surface, and audio from a backgrounded app with no notification, no media session and no way
  to stop it short of reopening the app. `onStop` now pauses and drops the video track when
  nothing is showing the stream; `onStart` undoes both. Background audio as a *feature* needs
  a foreground media service and is future work, recorded here rather than half-shipped.
- **Recreation inside the PiP window lost the shell state.** Locale is not in `configChanges`,
  so a language change recreates the activity; `inPip`/`playerOpen` reset and the browse shell
  rendered squeezed into the window over a disabled video track. Both are restored on create.
- **A terminal failure made every play button a liar**: the player sits in IDLE after retries
  are exhausted, and the intent flag alone starts nothing. Resuming from IDLE now prepares
  again, which retries the source.
- **`release()` kept the dead session's URLs**, and `applySettingsChange` read a non-empty URL
  list as "had a source": the next session's first settings change resurrected the previous
  session's stream — silently, on the phone, as audio nothing on screen owned.
- **Zapping from the player screen walked the browse list** even when the playing channel was
  filtered out of it, leaping to the first row of a list the user cannot see; it now falls
  back to the playlist in its own order around the playing channel.
- Smaller: the landscape sheet stayed latched through portrait and popped back open on the
  next rotate; a language change dumped the user from Settings onto the Live tab (the tab is
  saveable now, and the guide start-redirect is one-shot across recreation); the screen stayed
  awake for video-less browsing; the mini bar's progress strip computed once and sat frozen —
  it ticks by the minute now; `twoPane` classified a tall phone lying down as a tablet
  (`smallestScreenWidthDp` now decides).
- **The fix's own races:** the deferred VOD start (reading the resume mark) could lose a
  two-tap race, or land after a live channel had interrupted it and replace it. A play
  generation, bumped on every source switch, pins the start to the latest tap.

### Not proved here

The suite compiles and passes (113 tests); backgrounding, PiP recreation, IDLE recovery and
the zap fallback are lifecycle behaviours only a device can exercise. Known gaps recorded, not
fixed: no media session (headset and Bluetooth keys do nothing on the phone), no error surface
on the mini bar, a recording's resume position is still never written.

## In-app updates, 2026-08-20

Reviewed while writing rather than after, because the environment cannot compile Android and CI
is the only compiler this change gets. The findings below were all caught in a self-review pass
over the finished diff and fixed before the first push.

The finding that started the feature is not in the app at all. Three consecutive CI builds —
0.3.0, 0.3.1, 0.3.2 — carry three *different* signing certificates, each generated minutes
before its own build: with no keystore configured, Gradle mints a throwaway debug key per
runner. Android replaces a package only when the certificate matches, so none of those builds
could ever have updated another, and the "installs over the previous version" note that went out
with 0.3.2 was wrong. A stable release keystore now lives in repository secrets and CI asserts
the APK carries it before publishing anything (`docs/adr/ADR-012`).

- **A background check could destroy an install in flight.** `RefreshWorker`'s daily check wrote
  its own state over `Downloading` and pruned the cache directory the download was writing into.
  `check` now returns immediately while an install is under way.
- **Refusals spoke in generic error text.** "Metered connection" arrived as *network error*,
  which is neither true nor actionable. Refusals are now a named `UpdateBlocker` with a sentence
  each: a recording in progress, the install permission, Wi-Fi-only, an older Android, no space.
- **A refusal threw the offer away.** `Blocked` replaced `Available`, so the install button
  vanished and the user had to re-check to get it back. `Blocked` carries the manifest and the
  button stays.
- **The recording guard read the database on the caller's thread.** Moved onto IO.
- **Abandoned downloads were never cleaned up** — 16 MB per attempt, invisible to the cache
  budget. The daily check prunes everything except the build still worth installing.
- **Discovery relied entirely on a deferrable worker.** A launch-time check was added; the
  once-a-day limit inside the manager keeps it free.
- **An unescaped quotation mark in a string resource** would have failed the resource compiler
  outright. Rewritten without it.
- **The installer's callback would never have arrived.** The status `PendingIntent` named an
  action and a package, and since Android 8 a manifest receiver does not receive implicit
  broadcasts — so the confirmation dialog would never have been launched and every install would
  have hung on "Installing…". The intent now names the receiver class outright, which also means
  the status can reach nothing else.

Not proven, and stated as such: no part of the install path has run on a device. The permission
prompt, the installer session, the silent self-update on Android 12+, and whether a scheduled
recording really survives the replacement are all first provable on the first signed build.

### Requested review of the update feature, 2026-08-20

A second pass over the same diff, run as a review rather than as self-review. Six findings, all
fixed before the merge.

- **The published version name would have read "040".** The workflow squeezed whitespace out of
  the whole `version.properties` stream before joining the three numbers, so `tr` ate the
  newlines `paste` was about to join on. Every device would have been offered "version 040".
  Reproduced against the repository's own file; the fix squeezes spaces per line and is verified
  to print `0.4.0`.
- **An update found overnight stayed invisible until the next day.** The daily interval outlives
  the process, so a check inside it returned nothing and left the UI idle — while Settings
  accurately reported that it had looked recently. The manifest is now persisted verbatim and
  the rate-limited path restores the offer from it.
- **Two taps started two downloads.** `downloadAndInstall` published `Downloading` only after
  suspending over the blocking checks, so both callers passed the guard and then raced over the
  same cache file, ending in a checksum mismatch. The claim is now taken atomically before the
  first suspension point.
- **A dropped confirmation wedged the feature.** If the platform asked for user action but named
  no intent — or the activity start was silently dropped, which is not an exception on Android 10
  and later — nothing moved the state on, and `Installing` then suppressed every later check for
  the life of the process. A missing intent is now reported as a failure, and the state carries
  when it began so it can lapse.
- **Failed installs leaked staged sessions.** Nothing abandoned the `PackageInstaller` session
  when writing or committing threw, so repeated failures burned through the per-UID session
  limit at ~16 MB each. The session is abandoned on failure, and stale ones from earlier
  attempts are dropped before a new one is created.
- **The retry re-fetched a file the device already had.** `download` wiped the cache directory
  unconditionally, including the verified APK the daily check had deliberately kept, which made
  the `keep` argument dead code. A file that still matches the manifest's size and hash is now
  reused.

Two tests cover the persistence half of the second finding; the suite is 129 tests. The install path itself
is still unproven on a device, for the reason given above.
