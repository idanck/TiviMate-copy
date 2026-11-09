import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { sha256 } from './codes';

export interface Window {
  start: number;
  count: number;
}

/**
 * One more attempt counted against a fixed window. Pure, so the arithmetic is tested without a
 * database: returns the window to store and whether the attempt is allowed.
 */
export function nextWindow(prev: Window | null, now: number, max: number, windowMs: number): { window: Window; allowed: boolean } {
  if (!prev || now - prev.start >= windowMs) return { window: { start: now, count: 1 }, allowed: true };
  if (prev.count >= max) return { window: prev, allowed: false };
  return { window: { start: prev.start, count: prev.count + 1 }, allowed: true };
}

/**
 * Counts an attempt by [key] (an address or an account, hashed so neither is stored as is) and
 * throws `resource-exhausted` once it is over [max] in the window. The document carries
 * `expiresAt`, so Firestore's TTL policy removes it (firestore.indexes.json).
 */
export async function enforce(scope: string, key: string, max: number, windowMs: number): Promise<void> {
  const db = getFirestore();
  const ref = db.collection('rateLimits').doc(`${scope}_${sha256(key)}`);
  const allowed = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const data = snap.data();
    const prev = data ? { start: Number(data.start), count: Number(data.count) } : null;
    const now = Date.now();
    const next = nextWindow(prev, now, max, windowMs);
    if (next.allowed) {
      tx.set(ref, {
        start: next.window.start,
        count: next.window.count,
        expiresAt: Timestamp.fromMillis(next.window.start + windowMs),
      });
    }
    return next.allowed;
  });
  if (!allowed) throw new HttpsError('resource-exhausted', 'Too many attempts. Try again in a few minutes.');
}

/** The caller's address as Google's front end saw it. */
export function clientIp(raw: { ip?: string; headers?: Record<string, unknown> }): string {
  const forwarded = raw.headers?.['x-forwarded-for'];
  const first = typeof forwarded === 'string' ? forwarded.split(',')[0]?.trim() : undefined;
  return first || raw.ip || 'unknown';
}
