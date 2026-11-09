# ADR-060 — The package becomes `com.idanck.shidur`, and a bridge carries the data across

**Status:** accepted (2026-11-09). Supersedes the package half of ADR-018; amends ADR-012 (one
manifest per package) and ADR-059 (the Firebase apps).

## Decision

1. **The new package** is `com.idanck.shidur`, `com.idanck.shidur.debug` for debug builds. Both
   `applicationId` and `namespace` change, and the source tree moves from `com/tivicopy/player` to
   `com/idanck/shidur` (main and test; there is no androidTest). Left as they were, each for
   ADR-018's reason: `tivicopy.db`, the `.tivicopy.json` backup extension, `Theme.TiviCopy`, the
   Keystore alias, the user agent.
2. **The signing key does not change.** Everything below rests on it.
3. **A bridge moves the existing installs.** It is the old package's last build: the same code,
   built with `-Pshidur.bridge=true` (`app/build.gradle.kts`), which gives it `com.tivicopy.player`,
   a versionCode of its own (`bridgeVersionCode` in `version.properties`, 135, above the last
   published 134) and `BuildConfig.BRIDGE`. Devices take it as an ordinary silent update. The user
   confirms only two things, both of which Android requires: installing the new app (a new
   package), and removing the old one (a package the new app did not install).

## The update channel: two manifests

`UpdateManager` refuses an APK of another package (`verifySignature`). Had the new app been
published to `latest.json`, every install that had not yet taken the bridge — a television left off
for a few weeks — would have refused it forever.

- **`latest.json` holds the bridge, for good.** Any install of the old package, months from now,
  finds the bridge there. The bridge reads `latest.json` for its own updates too, so a fixed bridge
  can still reach those installs: raise `bridgeVersionCode` and publish it.
- **The new app reads `shidur/latest.json`** (`BuildConfig.UPDATE_MANIFEST_URL`). Its APKs go under
  `apk/` as before.
- **CI**: `android.yml` builds the new app and publishes it to `shidur/latest.json` only, after
  checking with `aapt2` that the APK is `com.idanck.shidur`; no step of it writes `latest.json` any
  more. `bridge.yml`, started by hand, builds the bridge, and publishes it to `latest.json` only when
  its `publish` input is on, on `main`, and only if: `shidur/latest.json` and the APK it names are
  already served (the order: the bridge never points at a download that does not exist);
  `latest.json` does not already hold `bridgeVersionCode` or a later one (once); and the APK is
  `com.tivicopy.player` with the release key.
- **The update host's Worker is not in this repository** (ADR-012). Whether it accepts
  `PUT /publish/shidur/latest.json` and serves `GET /shidur/latest.json` as it does `latest.json` is
  not known from here. If it does not, `android.yml`'s publish step fails with that message, nothing
  reaches a device, and `bridge.yml` refuses to run; the change is the Worker's, not this
  repository's.

## The bridge

- It works as before. Over it, a dialog: *Shidur is moving to a new version*, with *Move now* and
  *Later*, both reachable by the remote.
- *Move now* reads `shidur/latest.json` and runs the ordinary update path with a second
  `UpdateManager` (`ServiceLocator.handover`): the SHA-256, the signature compared with the running
  app's, and a package that must be exactly `com.idanck.shidur`; then the platform's install screen.
- **The data** is served by `HandoverProvider`, enabled only in the bridge, exported under a
  `signature` permission that both packages declare (`com.idanck.shidur.permission.MIGRATE`, with
  `.DEBUG` for debug builds: two packages signed with different keys may not declare one name). Every
  entry point checks again, by the caller's package name and signature (`HandoverAccess`), since
  `call()` is not covered by the manifest's permission on every Android version.
- Once the new app reports its import (`call("imported")`), the bridge shows only *Open the new
  Shidur* and *Remove this version*, and takes its own recording alarms down so a programme is not
  recorded twice. **It never deletes data**: the data goes when the user removes the package.

## What moves, and how

Not through the backup file (FR-SET-08). That file restores per-channel state only onto channels
that exist when it is read, and a new install has none, so favourites, hidden channels, numbers and
order would be dropped; it carries no history and no recordings, and leaves the PIN out by design.

The bridge writes a **snapshot** (`HandoverSnapshot`): a SQLite file with the user's tables copied
row for row, ids included, in one transaction against the live file attached to a separate
connection (attaching to the app's own would switch its write-ahead log off), plus every preference
with its own type, plus the counts. Ids are kept, so everything that names a channel by id still
names the same one.

| Moves | How |
|---|---|
| Playlists, with user names and passwords | Rows; the credentials leave the bridge unsealed (its Keystore key cannot leave it) and the new app seals them with its own. A credential the bridge cannot unseal is counted and reported. |
| Groups: hidden, locked, custom, order | Rows |
| Channels: favourites, hidden, locked, custom names and logos, numbers, order, guide offset and manual guide match, decoders | Rows |
| Watch history (`history`, and each channel's count) | Rows |
| VOD: favourites and where each was left off | Rows |
| Recordings: finished, failed and scheduled | Rows; the new app arms the scheduled ones on its next start |
| Every setting, the parental PIN's hash and salt included | Typed preferences |
| Sport and News mode's panels, the last and the start channel | Preferences holding channel ids, which still match |
| Settings lock, channel lock, hide-locked | Preferences |

| Does not move | Why, and what happens |
|---|---|
| The guide (`programmes`, `epg_channels`) | Downloaded again: every playlist is marked due, and the first refresh after the import loads it. |
| A recording folder chosen through the system picker | The grant belongs to the old package. The new app drops the setting and says to choose the folder again; recordings already in it play once the same folder is chosen, since their addresses are in that tree. A folder given as a plain path moves as it is. |
| Recordings saved inside the old package's own storage | Unreadable to another package. Only the QA kit records there; a real install records to a picked folder. |
| The update channel's record (last check, skipped version) | Another manifest, other numbers. |
| The Google account (ADR-059) | Firebase keeps its own state per package; the user signs in again, if at all. |
| Google TV's rows (Continue watching, the app's own row) | They belong to the old package and go with it; the new app writes its own. |

## The new app's first start

- `App.onCreate` asks `HandoverImporter.pending()`: not the bridge, nothing imported yet, the old
  package installed and signed with this key, and its provider there. While that holds, the rest of
  the start-up (bundled playlists, the refresh, the recording schedule, the launcher rows) waits:
  each writes into the tables the import replaces, and a refresh running beside it would write
  channels into playlist ids that are about to belong to other playlists.
- The import replaces the user's tables in one transaction and compares the counts (playlists,
  groups, channels, favourites, history, recordings, scheduled) with what the bridge sent; a
  mismatch rolls everything back. Then the settings, then the bridge is told.
- *Your data is here*, with the counts, and *Continue*, which restarts the app over the data it now
  holds. On the next screen: *Remove the previous version?* (`ACTION_DELETE` on the old package,
  with `REQUEST_DELETE_PACKAGES`). Refused, it comes back once on a later start, and never again.
- A failure says so, keeps the old package as it is, never offers to remove it, and offers *Retry*
  and *Not now*; *Not now* lets this start carry on without the data, and the next start tries again.

## Also changed by the rename

- **The background refresh.** WorkManager stores a job by its class name, and `KEEP` kept the old
  one; in the bridge the old class no longer exists, so its hourly refresh would have failed on every
  run. `RefreshScheduler` replaces the job once when the class name differs from the last one it
  scheduled.
- **The QA kit** drives `com.idanck.shidur.debug`. `qa.yml` also builds the bridge in the same job,
  so both carry one debug key, and QA-28 moves a full install from the bridge to a fresh install of
  the new app and compares the counts.

## Alternatives rejected

- **Keep the package (ADR-018).** It was the owner's call to move off a name that says
  "TiviMate copy" now, while there are two televisions to move rather than more.
- **The backup file, by hand.** Loses favourites and the rest of the per-channel state on a new
  install, and the history, the recordings and the PIN always.
- **Copy the database file whole.** Simpler, but the new app opens its database in
  `Application.onCreate`, before anything could fetch a file from another package, so it would have
  to be swapped under an open connection; and the credentials inside would still be sealed with a key
  the new app does not have.
- **Publish the new app to `latest.json`.** Strands every old install that has not yet taken the
  bridge.

## After the merge (the owner)

- Register `com.idanck.shidur` and `com.idanck.shidur.debug` in Firebase with the release key's
  fingerprints, and update the `FIREBASE_ANDROID_CONFIG` secret; until then the new app builds with
  no accounts, which it is meant to survive. Keep `com.tivicopy.player`: the bridge is that client.
- If the Worker refuses `shidur/`, change it there, then re-run the `main` build.
- After the new app's emulator QA, run `bridge.yml` on `main` with `publish` on.
- Follow the move on both televisions.
