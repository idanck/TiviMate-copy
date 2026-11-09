import { getAuth } from 'firebase-admin/auth';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions/v2';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { LIMITS, MAX_INSTANCES, REGION } from './config';
import { enforce } from './limits';
import { unlink as unlinkYoutube } from './youtube/link';

/**
 * The account's own document and its deletion (ADR-059).
 *
 * `users/{uid}` is created here rather than by the app, because `entitlements` is in it and only
 * the server, or the owner in the console, may write that (firestore.rules).
 */

/** What a new account starts with. Payment is not built yet; the owner turns these on by hand. */
export function newProfile(displayName: string | null | undefined) {
  return {
    createdAt: FieldValue.serverTimestamp(),
    displayName: displayName ?? null,
    entitlements: { premium: false, web: false },
  };
}

/** The account's name: from the ID token, or from Auth when the token has none (a custom token). */
async function displayNameOf(uid: string, tokenName: unknown): Promise<string | null> {
  if (typeof tokenName === 'string' && tokenName) return tokenName;
  try {
    return (await getAuth().getUser(uid)).displayName ?? null;
  } catch {
    return null;
  }
}

/**
 * Makes sure the caller's `users/{uid}` exists. The app calls it after every sign-in, and again
 * at the next start if the call failed. It replaces the first-generation Auth trigger, which
 * `me-west1` does not have (ADR-059).
 *
 * `create`, not `set`: a document that already exists is left exactly as it is, so a second call
 * cannot reset entitlements the owner granted by hand.
 */
export const ensureProfile = onCall({ region: REGION, maxInstances: MAX_INSTANCES }, async (request) => {
  const auth = request.auth;
  if (!auth) throw new HttpsError('unauthenticated', 'Sign in first.');
  await enforce('profile', auth.uid, LIMITS.profilePerUser.max, LIMITS.profilePerUser.windowMs);
  const ref = getFirestore().collection('users').doc(auth.uid);
  try {
    await ref.create(newProfile(await displayNameOf(auth.uid, auth.token.name)));
  } catch (e) {
    if ((e as { code?: number }).code !== 6 /* ALREADY_EXISTS */) throw e;
    return { created: false };
  }
  logger.info('profile created', { uid: auth.uid });
  return { created: true };
});

/** Everything an account owns. */
async function deleteData(uid: string): Promise<void> {
  const db = getFirestore();
  await db.recursiveDelete(db.collection('users').doc(uid));
  // A connected YouTube account (ADR-061): revoked at Google and forgotten with the account.
  await unlinkYoutube(uid).catch(() => undefined);
}

/**
 * Deletes the caller's account: the documents first, then the Auth user (Google Play's account
 * deletion requirement). Done on the server so it needs no recent sign-in on the device, which a
 * television signed in by pairing could not provide.
 *
 * A user the owner deletes in the console does not come through here, and their documents stay
 * in Firestore (ADR-059, *Consequences*).
 */
export const deleteAccount = onCall({ region: REGION, maxInstances: MAX_INSTANCES }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError('unauthenticated', 'Sign in first.');
  await deleteData(uid);
  try {
    await getAuth().deleteUser(uid);
  } catch (e) {
    if ((e as { code?: string }).code !== 'auth/user-not-found') throw e;
  }
  logger.info('account deleted', { uid });
  return { ok: true };
});
