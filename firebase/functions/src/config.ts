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

/** Cost guard: no function scales past this many instances. */
export const MAX_INSTANCES = 10;
