# Shidur — Firebase

The server side of Shidur's accounts (ADR-059): Firestore rules, the Cloud Functions that pair a
television and delete an account, and the two pages on Firebase Hosting (`/pair`, `/delete`).
Setting the project up is a manual job: `docs/12-firebase-setup.md`.

| Path | What |
|---|---|
| `firestore.rules` | Who reads and writes what. No client writes anything. |
| `firestore.indexes.json` | TTL on `pairings.expiresAt` and `rateLimits.expiresAt`. |
| `functions/` | `createPairing`, `approvePairing`, `claimPairing`, `deleteAccount`, and the `createProfile`/`removeProfile` Auth triggers. Node 22, TypeScript. |
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

The region, `europe-west1`, is in `functions/src/config.ts`, `hosting/src/firebase.ts` and the app's
`FirebaseSetup.kt`. Change all three together.

Environment, read by the Functions at deploy time from `functions/.env` (not in git):

- `PAIR_BASE_URL` — the approval page's origin, when Hosting has a domain of its own. Defaults to
  `https://<project-id>.web.app`.
