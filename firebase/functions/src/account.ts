import { getAuth } from 'firebase-admin/auth';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions/v2';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import * as functionsV1 from 'firebase-functions/v1';
import { MAX_INSTANCES, REGION } from './config';

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

// Auth triggers exist only in the first generation of Cloud Functions.
export const createProfile = functionsV1
  .region(REGION)
  .runWith({ maxInstances: MAX_INSTANCES })
  .auth.user()
  .onCreate(async (user) => {
    try {
      // `create`, not `set`: the trigger may run twice, and the second run must not reset
      // entitlements the owner granted in between.
      await getFirestore().collection('users').doc(user.uid).create(newProfile(user.displayName));
    } catch (e) {
      if ((e as { code?: number }).code !== 6 /* ALREADY_EXISTS */) throw e;
    }
  });

/** Everything an account owns. Also the cleanup when the owner deletes a user in the console. */
async function deleteData(uid: string): Promise<void> {
  const db = getFirestore();
  await db.recursiveDelete(db.collection('users').doc(uid));
}

export const removeProfile = functionsV1
  .region(REGION)
  .runWith({ maxInstances: MAX_INSTANCES })
  .auth.user()
  .onDelete(async (user) => {
    await deleteData(user.uid);
  });

/**
 * Deletes the caller's account: the documents first, then the Auth user (Google Play's account
 * deletion requirement). Done on the server so it needs no recent sign-in on the device, which a
 * television signed in by pairing could not provide.
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
