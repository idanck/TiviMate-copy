# Shidur — Firebase

The server side of Shidur's accounts (ADR-059): Firestore rules, the Cloud Functions that pair a
television and delete an account, and the pages on Firebase Hosting (`/pair`, `/delete`, and `/privacy`, the privacy policy).
Setting the project up is a manual job: `docs/12-firebase-setup.md`.

| Path | What |
|---|---|
| `firestore.rules` | Who reads and writes what. No client writes anything. |
| `firestore.indexes.json` | TTL on `expiresAt` of `pairings`, `rateLimits` and the YouTube collections. |
| `functions/` | `createPairing`, `approvePairing`, `claimPairing`, `ensureProfile`, `deleteAccount`; and YouTube (ADR-061): `ytChannels`, `ytResolve`, `ytSearch`, `ytSubscriptions`, `ytLinkStatus`, `ytLinkStart`, `ytLinkPoll`, `ytLinkCode`, `ytUnlink`. All second-generation callables. Node 22, TypeScript. |
| `hosting/` | The approval and deletion pages, bundled with esbuild into `hosting/dist`. |
| `test/` | Rules and the whole pairing, against the emulators. |

## Running it

```
cd firebase
npm ci && npm --prefix functions ci
npm run check                            # typecheck, pages build, Functions unit tests
npm --prefix functions run build
npm run emulated                         # rules and pairing against the emulators (needs Java)
```

The emulators run under the `demo-shidur` project, which needs no credentials. The pages, opened
from `localhost`, talk to the emulators too.

## Deploying

`.github/workflows/firebase.yml` deploys on every push to `main` that touches `firebase/`, and when
started by hand on `main`, once `FIREBASE_SERVICE_ACCOUNT` and `FIREBASE_PROJECT_ID` are set.

The region, `me-west1` (Tel Aviv, where Firestore is), is in `functions/src/config.ts`,
`hosting/src/firebase.ts`, the app's `FirebaseSetup.kt` and the web server's
`web/server/firebase.mjs`, and in `test/pairing.test.ts`. Change them together. `me-west1` has only the second generation of Cloud Functions, so nothing here may
use `firebase-functions/v1`: that rules out Auth triggers, and is why `ensureProfile` exists.

Secrets, in Secret Manager (`docs/13-youtube-setup.md`): `YOUTUBE_API_KEY`, `YOUTUBE_TV_CLIENT_ID`,
`YOUTUBE_TV_CLIENT_SECRET`, `GOOGLE_WEB_CLIENT_ID`, `GOOGLE_WEB_CLIENT_SECRET`. A deploy fails while one
of them does not exist; an OAuth one set to `unset` only switches that route off. The emulators need
none of them.

Environment, read by the Functions at deploy time from `functions/.env` (not in git):

- `PAIR_BASE_URL` — the approval page's origin, when Hosting has a domain of its own. Defaults to
  `https://<project-id>.web.app`.
