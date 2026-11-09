import { getAuth } from 'firebase-admin/auth';
import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions/v2';
import { formatCode, newCode, newSecret, normalizeCode, pairingUrl, secretMatches, sha256 } from './codes';
import { LIMITS, MAX_INSTANCES, PAIRING_TTL_MS, REGION } from './config';
import { clientIp, enforce } from './limits';

/**
 * Signing a television in from a phone (ADR-059, "QR and a short code").
 *
 * 1. The television calls [createPairing] with no account and gets a code, a secret and an
 *    address. It shows the code and the address as a QR code.
 * 2. On the phone, the person opens the address, signs in with Google and approves the code:
 *    [approvePairing] marks the pairing with their account.
 * 3. The television polls [claimPairing] with the code and its secret. Once the pairing is
 *    approved it gets a custom token for that account, once, and the pairing is deleted.
 *
 * The token is minted at claim time and handed only to the holder of the secret, so it is never
 * stored anywhere: neither the phone nor anybody who learns the code can take it.
 */

const pairings = () => getFirestore().collection('pairings');

/** Where the approval page lives: the project's own Hosting site unless PAIR_BASE_URL says otherwise. */
function baseUrl(): string {
  const configured = process.env.PAIR_BASE_URL?.trim();
  if (configured) return configured;
  const project = process.env.GCLOUD_PROJECT || process.env.GCP_PROJECT || 'localhost';
  return `https://${project}.web.app`;
}

export const createPairing = onCall({ region: REGION, maxInstances: MAX_INSTANCES }, async (request) => {
  await enforce('create', clientIp(request.rawRequest), LIMITS.createPerIp.max, LIMITS.createPerIp.windowMs);
  const secret = newSecret();
  const expiresAt = Date.now() + PAIRING_TTL_MS;
  // A clash among 8.5 × 10^11 codes is all but impossible, but `create` refuses rather than
  // overwrites, so a clash costs a retry instead of somebody else's pairing.
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = newCode();
    try {
      await pairings().doc(code).create({
        secretHash: sha256(secret),
        status: 'pending',
        createdAt: Timestamp.now(),
        expiresAt: Timestamp.fromMillis(expiresAt),
      });
      return { code, display: formatCode(code), secret, expiresAt, url: pairingUrl(baseUrl(), code) };
    } catch (e) {
      if ((e as { code?: number }).code !== 6 /* ALREADY_EXISTS */) throw e;
    }
  }
  throw new HttpsError('unavailable', 'Could not allocate a code. Try again.');
});

export const approvePairing = onCall({ region: REGION, maxInstances: MAX_INSTANCES }, async (request) => {
  const auth = request.auth;
  if (!auth) throw new HttpsError('unauthenticated', 'Sign in first.');
  // Only a person signed in with Google approves a television; a television signed in with a
  // custom token does not hand its account on to the next one.
  if (auth.token.firebase?.sign_in_provider !== 'google.com') {
    throw new HttpsError('permission-denied', 'Sign in with Google to approve a television.');
  }
  await enforce('approve', auth.uid, LIMITS.approvePerUser.max, LIMITS.approvePerUser.windowMs);
  const code = normalizeCode((request.data as { code?: unknown } | null)?.code);
  if (!code) throw new HttpsError('invalid-argument', 'That is not a code.');

  const ref = pairings().doc(code);
  await getFirestore().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const data = snap.data();
    if (!data || (data.expiresAt as Timestamp).toMillis() < Date.now()) {
      throw new HttpsError('not-found', 'The code was not found or has expired.');
    }
    if (data.status !== 'pending') throw new HttpsError('failed-precondition', 'The code was already used.');
    tx.update(ref, { status: 'approved', uid: auth.uid, approvedAt: Timestamp.now() });
  });
  logger.info('pairing approved', { uid: auth.uid });
  return { ok: true };
});

export const claimPairing = onCall({ region: REGION, maxInstances: MAX_INSTANCES }, async (request) => {
  const data = (request.data ?? {}) as { code?: unknown; secret?: unknown };
  const code = normalizeCode(data.code);
  if (!code) throw new HttpsError('invalid-argument', 'That is not a code.');

  const ref = pairings().doc(code);
  // The secret is checked before anything about the pairing is said, so polling somebody else's
  // code reads exactly like polling one that does not exist.
  const outcome = await getFirestore().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const stored = snap.data();
    if (!stored || !secretMatches(data.secret, stored.secretHash)) return { status: 'expired' as const };
    if ((stored.expiresAt as Timestamp).toMillis() < Date.now()) {
      tx.delete(ref);
      return { status: 'expired' as const };
    }
    if (stored.status !== 'approved' || typeof stored.uid !== 'string') return { status: 'pending' as const };
    tx.delete(ref);
    return { status: 'approved' as const, uid: stored.uid };
  });

  if (outcome.status !== 'approved') return { status: outcome.status };
  const token = await getAuth().createCustomToken(outcome.uid, { via: 'pairing' });
  logger.info('pairing claimed', { uid: outcome.uid });
  return { status: 'approved', token };
});
