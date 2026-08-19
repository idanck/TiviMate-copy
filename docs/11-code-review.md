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
- **A refresh can race a per-channel edit.** Both write whole channel rows. The window is tiny
  and the loser is a single user edit, not data loss.
