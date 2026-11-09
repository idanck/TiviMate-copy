/**
 * Fixed settings shared by every function (ADR-059).
 *
 * REGION is also written into the Android app (`data/account/AccountRepository.kt`) and the
 * hosting pages (`hosting/src/firebase.ts`): a callable function is addressed by its region, so
 * changing it here alone would leave both clients calling a function that is not there.
 */
export const REGION = 'europe-west1';

/** How long a code on the television stays valid. */
export const PAIRING_TTL_MS = 10 * 60 * 1000;

/** Rate limits, counted in fixed windows. */
export const LIMITS = {
  /** New pairing codes from one network address. A television asks for one every ten minutes. */
  createPerIp: { max: 12, windowMs: 10 * 60 * 1000 },
  /** Approvals by one account: a person approves a television now and then, not in a loop. */
  approvePerUser: { max: 20, windowMs: 10 * 60 * 1000 },
} as const;

/** Cost guard: no function scales past this many instances. */
export const MAX_INSTANCES = 10;
