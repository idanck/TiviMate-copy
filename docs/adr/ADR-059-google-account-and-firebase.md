# ADR-059 — A Google account, on Firebase

**Status:** accepted, stages 0 to 4 built (2026-11-09). Stage 5 (the web version) and stage 6
(sync) wait for the owner's decisions, listed under *Open* below. Partly supersedes ADR-007: the
app now has accounts and a place for entitlements, though nothing is gated by them yet.

## Decision

### The service

**Firebase**: Authentication with the Google provider, Firestore, Cloud Functions and Hosting, on
the Blaze plan with a budget alert. Nothing else: no Play Billing, no Stripe, no RevenueCat.
Payment is deferred; until it exists an entitlement is a field the owner sets by hand in the
console.

### Who has to sign in

| Platform | Sign-in |
|---|---|
| Android phone and Google TV | Required, free use included — behind the remote switch below |
| Web (`web/`) | Only a registered, paying subscriber (`entitlements.web`); stage 5, not built yet |
| iPhone | Later, not in this plan |
| webOS (`webos/`) | Out of scope; untouched |

**The remote switch.** The self-update (ADR-012) brings every build to every device at once,
the owner's father's television included. The sign-in screen therefore stands in front of the app
only while `config/app.requireSignIn` in Firestore is `true`. The document is readable by anyone
and writable by nobody but the console; a missing document or field is *off*. The app keeps the
last answer it got, so a television that starts offline is not let in merely because it could not
ask, and it never treats an empty local cache as an answer. The owner turns the switch on himself,
after signing in on his own phone and television.

### Signing in

- **Phone:** Credential Manager's *Sign in with Google* (`GetSignInWithGoogleOption`, the project's
  web client as the audience), then `signInWithCredential` in Firebase.
- **Television, two routes side by side:**
  1. The same Credential Manager button, drawn only on Android 14 (API 34) and later, where Google
     supports it on televisions with Google Play services 2026W30 or later
     ([form factors](https://developer.android.com/identity/form-factors)). A failure there says so
     and leaves the second route on screen.
  2. **Always:** a QR code and an eight-character code (`ABCD-EFGH`, no 0/O/1/I/L). The phone opens
     the approval page, signs in with Google and approves; a Cloud Function mints a custom token
     and the television calls `signInWithCustomToken`.

### The pairing, in detail

| Function | Caller | Does |
|---|---|---|
| `createPairing` | the television, no account | `pairings/{code}`: a hash of a 256-bit secret, `pending`, `expiresAt` in 10 minutes. Returns the code, the secret and the approval address. 12 codes per address per 10 minutes. |
| `approvePairing` | the phone, signed in **with Google** | Marks a pending, unexpired code `approved` with the caller's uid. A user signed in by custom token cannot approve, so one television cannot hand its account on to the next. 20 approvals per account per 10 minutes. |
| `claimPairing` | the television, every 4 s | With the right secret: `pending`, `expired`, or once approved a custom token — and the pairing is deleted, so it works once. A wrong secret reads as `expired`. |

A third function, `claimPairing`, was added to the plan's two: the token is minted when the
television claims it and handed only to the holder of the secret, so it is never written to
Firestore where the phone, or anyone who saw the code, could read it.

A television asks for at most three codes in a row (30 minutes) and then waits for OK, so one left
on the sign-in screen overnight costs nothing. Each function is capped at 10 instances.

### Data and rules

- `users/{uid}`: `createdAt`, `displayName`, `entitlements: { premium: false, web: false }`,
  created by the `createProfile` Auth trigger (with `create`, so a second run cannot reset what the
  owner granted). The owner reads their own document; **no client writes it at all**, so
  `entitlements` changes only from Functions or the console.
- `pairings/{code}` and `rateLimits/{id}`: Functions only; each carries `expiresAt` with a TTL
  policy (`firestore.indexes.json`).
- `config/{doc}`: readable by anyone, written only from the console.
- Everything else is closed. The rules are tested against the emulator in CI
  (`firebase/test/rules.test.ts`), and so is the whole pairing (`firebase/test/pairing.test.ts`).

### Deleting an account

Google Play requires that an account created in the app can be deleted from the app and from a web
link. Both call `deleteAccount`, which deletes `users/{uid}` recursively and then the Auth user, on
the server — so it needs no recent sign-in, which a television signed in by pairing could not
give. Settings → Account in the app, and `/delete` on the Hosting site. Deleting a user in the
console runs the same cleanup through the `removeProfile` trigger. Local playlists and settings
are not the account's and stay on the device.

### Nothing about the project is in the repository

The repository is public. `google-services.json`, the project ID and addresses come from GitHub
Actions secrets at build time:

- **Android:** `FIREBASE_ANDROID_CONFIG` holds the whole `google-services.json`. CI writes it to a
  temporary file for the APK build only; `app/build.gradle.kts` reads it without the Google
  Services plugin (which fails a build that has no file) and puts the client of the variant's own
  package into `BuildConfig`. A build without it — a local one, or the unit tests — has empty
  fields, and the app starts with no accounts at all (`AccountState.Unavailable`), never crashing.
- **Hosting:** the pages read the project's web configuration from Firebase Hosting's reserved
  `/__/firebase/init.json`, so the web configuration needs no secret. Sign-in runs on the site's
  own domain (`authDomain` is the page's host), which keeps the popup working in browsers that
  block third-party storage; it needs the site's `/__/auth/handler` among the web client's
  redirect addresses (docs/12, step 6).
- **Deploy:** `FIREBASE_SERVICE_ACCOUNT` and `FIREBASE_PROJECT_ID`. The Functions region,
  `europe-west1`, is not a secret and is written in three places that must agree:
  `firebase/functions/src/config.ts`, `firebase/hosting/src/firebase.ts` and `FirebaseSetup.kt`.

### The signing key's fingerprints

Sign in with Google on Android checks the APK's signing certificate against an Android OAuth client
of the same package. Each of these needs its SHA-1 (and SHA-256) registered on its app in the
Firebase project:

| Key | Package | Where it is |
|---|---|---|
| The CI release key (`RELEASE_KEYSTORE_B64`) | `com.idanck.shidur` | `keytool -list -v -keystore release.jks -alias <alias>` |
| The debug key of each machine that builds one | `com.idanck.shidur.debug` | `~/.android/debug.keystore`, alias `androiddebugkey`, password `android` |
| Play App Signing, if the app ever goes to Play | `com.idanck.shidur` | Play Console → Setup → App signing |
| The CI release key, for the bridge (ADR-060) | `com.tivicopy.player` | as above; registered already, kept while any install is on the old package |
| The debug key, for a debug bridge | `com.tivicopy.player.debug` | as above |

The package moved from `com.tivicopy.player` to `com.idanck.shidur` (ADR-060). The old package is
the bridge's now, and stays registered as long as it can still be installed.

A key missing from the list fails sign-in on that build with a configuration error, while the QR
route keeps working: it never touches the device's certificate.

### The local data layer is unchanged

The account sits beside the hand-written SQLite (ADR-006) and SharedPreferences, never under them.
Sync, when stage 6 builds it, goes above them too.

## Rejected

- **Remote Config for the switch.** One more SDK and fetch interval for a single boolean, when
  Firestore is already there and a listener gets the change within seconds.
- **The custom token in the pairing document,** for the television to read. Whoever can read
  the document can sign in as its owner; the claim function hands it to the secret's holder only.
- **The Google Services Gradle plugin.** It refuses to build without the file, so every local and
  test build would need the project's configuration.
- **The OAuth device flow** (Google's "TV and limited-input devices"). It would replace pairing on
  every television, but its scopes are limited and it would still need a Function to turn the
  result into a Firebase session; the plan chose a QR code to our own page.
- **A client write to `users/{uid}`,** even for `displayName`: one writable field next to
  `entitlements` is one rule mistake away from a free subscription.

## Consequences

- With the switch off, which is how the first build ships, no device changes behaviour. Settings
  gains *Account* at the top; nothing else moves.
- The APK grows by the Firebase SDKs (Auth, Firestore with its gRPC stack, Functions) and
  Credential Manager, several megabytes. Every launch reads `config/app` once.
- Behind the sign-in screen nothing plays: the player stops, Sport and News mode close, and the
  start behaviour waits until somebody is in. Back leaves the app. The update prompt still shows,
  so a build that fixes a broken sign-in can always reach a device stuck there.
- **Not gated:** the Android Auto radio (ADR-015), which has no screen to sign in on.
- `claimPairing` mints custom tokens, so the Functions' runtime service account needs the *Service
  Account Token Creator* role on itself (docs/12, step 9).
- Builds made before the `FIREBASE_ANDROID_CONFIG` secret exists have no accounts. The first build
  after it is set needs a `versionCode` bump like any other.
- `firebase/` has its own workflow (`.github/workflows/firebase.yml`), and `android.yml` ignores
  it: a Functions or rules change builds no APK.

## Open (the owner decides before the work starts)

1. **Stage 5 — how the web server checks the ID token.** (a) By itself, against Google's public
   keys: keeps the server free of dependencies, but the verification (key rotation, `aud`, `iss`,
   `exp`, `auth_time`) is ours to get right. (b) The Admin SDK: the reference implementation, about
   a dozen dependencies in a server that has none.
2. **Stage 5 — the password mode** (`SHIDUR_WEB_PASSWORD`): kept as the self-hosting option, or
   removed.
3. **Stage 6 — Xtream credentials and playlist addresses:** (a) synced, encrypted on the client
   with a key the server never sees, as docs/06 §6.5 says today; or (b) not synced, typed again on
   each device.
4. **Stage 6 — merging between devices:** the last write wins by timestamp, or another rule.
