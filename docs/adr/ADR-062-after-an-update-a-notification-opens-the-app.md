# ADR-062 — After an in-app update, a notification opens the app

**Status:** accepted 2026-11-10, built in 0.28.1 (FR-UPD-09). Temporary: see "Follow-up".

## Context

After every in-app update (ADR-012) the app is gone and has to be opened again by hand. That is
Android's doing, not a bug:

- Replacing a package kills its process, so nothing of the old build is left to reopen anything.
- The new build does hear `MY_PACKAGE_REPLACED`, but since Android 10 an app in the background may
  not open a screen of its own, and that broadcast is not among the exemptions. `BootReceiver`
  also chose not to relaunch there, as an ambush mid-update.

## Decision

The owner's choice (2026-11-10): a notification, "Shidur was updated to X — press to open".

- `UpdateManager` marks the install just before committing the session (`UpdatedNotice.expect`),
  in a preferences file of its own (`update_notice`) that is never backed up, restored or handed
  over. A failed or refused install clears the mark.
- The new build's `BootReceiver`, on `MY_PACKAGE_REPLACED`, posts the notification only when the
  mark is there, names this build or an older one, and is younger than
  `INSTALL_HANDOFF_TIMEOUT_MS`. A sideload by hand stays quiet. The mark is consumed either way.
- Its own channel, `updates`, at `IMPORTANCE_HIGH`, so it is seen right away. Opening the app by
  any route clears it (`MainActivity.onResume`).
- The notification permission (Android 13+), which the app had never asked for, is asked for the
  first time "update" is pressed, from the dialog or from Settings, and only once. Granted or
  refused, the update goes ahead (`rememberUpdateStarter`).

## Alternatives rejected

- **Reopen the app from `MY_PACKAGE_REPLACED`, with "display over other apps"** — that permission
  is an exemption to the background-start rule, but it is granted by hand on a screen some
  televisions hide, and it is a sensitive permission for a Google Play review.
- **Only say, before the install, that the app will close** — explains the step, saves none of it.
- **Ask for the notification permission on the first launch** — would also cover the recording
  notifications, but the dialog has no evident reason there.

## Follow-up

Once Shidur is on Google Play: check on a device whether an **immediate** in-app update through
Google Play (Play Core In-App Updates) brings the app back by itself. Not yet verified here.

- If it does: remove this mechanism — `data/update/UpdatedNotice.kt`, its calls in
  `UpdateManager`, `BootReceiver` and `MainActivity`, `App.CHANNEL_UPDATES` (with
  `deleteNotificationChannel("updates")` so the channel leaves existing devices), the strings
  `channel_updates*` and `update_done_*`, and FR-UPD-09. Whether `rememberUpdateStarter` keeps
  asking for the notification permission is decided then, for the recording notifications.
- If it does not: keep it.
