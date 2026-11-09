# ADR-018 — The app is called Shidur, and the package is not renamed

**Status:** accepted; the package half superseded by ADR-060 (2026-11-09), which renames the
package to `com.idanck.shidur` and moves the existing installs with a bridge build. The name half
stands.

## Decision

The user-visible name changes from *TiviCopy* to **Shidur** — `שידור` in Hebrew, the ordinary
noun for "broadcast". The `applicationId` stays `com.tivicopy.player`.

## Why the name

*TiviCopy* was a working title derived from TiviMate, the product this one was benchmarked
against. `README.md` is careful that the benchmark is functional only — no source, artwork or
strings were taken — but a name that reads as "a copy of that" undersells an app that is now its
own thing, and it names a competitor on the launcher of a television in someone's living room.

*Shidur* says what the app is. It carries Israeli television and Israeli radio and nothing else
(ADR-013, ADR-015), and "broadcast" is the one word that covers both. It is short enough for a
launcher tile, it is a plain noun rather than anyone's brand, and it reads naturally in both
shipped locales: `Shidur` in `values`, `שידור` in `values-iw`.

## Why the package does not change

`UpdateManager.verifySignature` refuses a downloaded APK whose `packageName` differs from the
running app's:

```kotlin
candidate.packageName != context.packageName ->
    Outcome.Err(AppError(AppError.Kind.UnsupportedFormat, "different package"))
```

So a package rename does not merely inconvenience existing installs — every device already
running this app would reject every future update, client-side, forever, and go on polling for
one it will never accept. Android would treat the renamed build as a separate app and install it
alongside rather than over, with none of the old install's channels, favourites, guide bindings or
watch history.

ADR-012 already spent one uninstall-and-reinstall moving to a stable signing key and called it
"the last time it is needed". A cosmetic rename is not a reason to spend another.

## What changed, and what deliberately did not

Changed: `app_name` in both string tables, the two update-permission strings that name the app in
prose, the APK filename prefix, the CI artifact and release title, and `README.md`.

Left alone, each for a stated reason:

- **`applicationId` / `namespace` / the `com/tivicopy/player` package tree** — above.
- **`UserAgents.DEFAULT = "TiviCopy/1.0 (Android)"`** — the comment beside it warns that
  providers gate on this string. Changing it can break stream and playlist fetches for existing
  users, with no way to find out from here.
- **`Theme.TiviCopy`, `TiviCopyTheme`, `tivicopy.db`, the update host** — internal identifiers
  and stored state. Renaming the database orphans every install's data; renaming the rest churns
  files for no user-visible effect.
- **Earlier ADRs** — ADR-012, ADR-013 and ADR-015 name the app *TiviCopy* in their prose. They
  are a dated record of decisions taken when that was its name, and rewriting them would make the
  record less true, not more.

## Consequence

The launcher tile and the radio notification change name on the next update. Nothing else moves.
