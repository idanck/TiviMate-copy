/**
 * Fixed settings shared by every function (ADR-059).
 *
 * REGION is also written into the Android app (`data/account/FirebaseSetup.kt`) and the hosting
 * pages (`hosting/src/firebase.ts`): a callable function is addressed by its region, so changing
 * it here alone would leave both clients calling a function that is not there. `me-west1` (Tel
 * Aviv) is where Firestore is, and it has only the second generation of Cloud Functions, so no
 * function here may be a first-generation one.
 */
export const REGION = 'me-west1';

/** How long a code on the television stays valid. */
export const PAIRING_TTL_MS = 10 * 60 * 1000;

/** Rate limits, counted in fixed windows. */
export const LIMITS = {
  /** New pairing codes from one network address. A television asks for one every ten minutes. */
  createPerIp: { max: 12, windowMs: 10 * 60 * 1000 },
  /** Approvals by one account: a person approves a television now and then, not in a loop. */
  approvePerUser: { max: 20, windowMs: 10 * 60 * 1000 },
  /** Profile checks by one account: the app makes one after each sign-in, and again at the next
   * start only if that one failed. */
  profilePerUser: { max: 20, windowMs: 10 * 60 * 1000 },
} as const;

/**
 * The YouTube callables' limits (ADR-061), per account, or per address for a device that has not
 * signed in. They guard the project's daily YouTube quota, not the server.
 */
export const YT_LIMITS = {
  /** A screen of saved channels, refreshed while it is open: generous, since the cache answers most. */
  channels: { max: 120, windowMs: 10 * 60 * 1000 },
  /** Adding a channel by link or handle. */
  resolve: { max: 30, windowMs: 60 * 60 * 1000 },
  /** Searches: a fair share of the project's hundred a day. */
  search: { max: 30, windowMs: 24 * 60 * 60 * 1000 },
  /** Starting a connection, or handing over a phone's code. */
  link: { max: 10, windowMs: 10 * 60 * 1000 },
  /** The television asks every 5 seconds for up to 30 minutes. */
  poll: { max: 400, windowMs: 30 * 60 * 1000 },
  /** The subscriptions rail. */
  subscriptions: { max: 60, windowMs: 10 * 60 * 1000 },
} as const;

/** Cost guard: no function scales past this many instances. */
export const MAX_INSTANCES = 10;
