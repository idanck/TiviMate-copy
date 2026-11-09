# ADR-012 — The app updates itself from a manifest on its own host

**Status:** accepted; amended by ADR-060 (2026-11-09): one manifest per package. The app,
`com.idanck.shidur`, reads `shidur/latest.json`; `latest.json` holds the bridge of the old package
for good.

## Decision

TiviCopy checks a small JSON manifest at `https://updates.idanck.com/latest.json`, compares its
`versionCode` with the running build, and — on the user's word — downloads that APK and hands it
to the platform's `PackageInstaller`. The manifest and the binary are published by CI on every
push to `main`, from the same artefact the release attaches.

Two things this decision buys, and both are the point:

- **A stable signing key.** Android replaces a package only when the new APK carries the same
  signing certificate. Until now CI built with no keystore, so Gradle generated a throwaway debug
  key per run: three consecutive builds carried three different certificates, and none of them
  could install over another. A single release keystore, held in repository secrets, is what
  makes any of this possible; the build already read `RELEASE_KEYSTORE_PATH`, so only CI changed.
- **Recordings survive the replacement.** Replacing a package clears its alarms exactly as a
  reboot does. `BootReceiver` now also answers `MY_PACKAGE_REPLACED` and re-arms the schedule —
  a bug that predates this feature and that an update mechanism would otherwise multiply.

## Alternatives rejected

- **GitHub Releases from this repository.** The repository is private, so the app would need a
  credential to fetch a release asset, and a credential inside a distributed APK is not a secret.
- **A public releases-only repository.** Workable and free, but it publishes the binary to
  anyone; the owner preferred a host under their own control.
- **A store (Play, or an F-Droid repository).** Review cycles and a build pipeline this project
  does not need for a two-device personal install.
- **Silent, unattended updating.** Rejected as a default: an update that restarts the app in the
  middle of watching is worse than the problem it solves. The platform may skip its confirmation
  once the app is its own installer of record, but the *decision* to update stays the user's.

## Consequences

- Moving to the release key costs one uninstall-and-reinstall on each device, losing app data.
  Settings → Backup exports and restores it, and this is the last time it is needed.
- The update host is a Cloudflare Worker over R2. It serves the manifest and the APK, and accepts
  uploads only with a bearer token held in repository secrets. Reads are unauthenticated: the
  URL is unlisted and marked `noindex`, which is obscurity, not a security boundary — the
  integrity guarantees come from HTTPS, the published SHA-256 and the signature check.
- CI refuses to publish a build whose `versionCode` is not greater than the published one, unless
  the artefact is byte-identical (a re-run). Devices compare that number and nothing else.
