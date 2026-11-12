import { FieldValue, getFirestore, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions/v2';
import { quotaDay } from './model';

/**
 * The YouTube Data API v3, called from the server only (ADR-061): the key never leaves the
 * Functions, and every unit spent is counted in `ytQuota/{day}` so the app can be told the day's
 * allowance is gone instead of getting an error it cannot explain.
 *
 * Quota, as YouTube documents it: 10,000 units a day for every method but search, which has a
 * bucket of its own of 100 calls a day; most read methods cost 1 unit. The day resets at midnight
 * Pacific time.
 */

const BASE = 'https://www.googleapis.com/youtube/v3';

/** Units this project stops spending at, short of the 10,000, so a burst cannot overrun. */
export const UNITS_STOP = 9_500;
/** Search calls it stops at, short of the 100. */
export const SEARCHES_STOP = 95;

/** YouTube refused for quota: today's units, or today's searches, are used up. */
export class QuotaError extends Error {
  constructor(readonly bucket: 'units' | 'search') {
    super(`youtube ${bucket} quota exhausted`);
  }
}

/** Any other refusal from YouTube, with its reason ("forbidden", "channelNotFound", …). */
export class YtApiError extends Error {
  constructor(readonly status: number, readonly reason: string) {
    super(`youtube ${status} ${reason}`);
  }
}

const quotaDoc = () => getFirestore().collection('ytQuota').doc(quotaDay(Date.now()));

/** Today's counters, read before a call that would spend from a bucket that is nearly empty. */
export async function quotaLeft(): Promise<{ units: boolean; search: boolean }> {
  const snap = await quotaDoc().get();
  const d = snap.data() ?? {};
  return { units: Number(d.units ?? 0) < UNITS_STOP, search: Number(d.searches ?? 0) < SEARCHES_STOP };
}

async function spend(bucket: 'units' | 'searches', amount: number): Promise<void> {
  try {
    await quotaDoc().set(
      // Kept two days, then Firestore's TTL removes it (firestore.indexes.json).
      { [bucket]: FieldValue.increment(amount), expiresAt: Timestamp.fromMillis(Date.now() + 2 * 86_400_000) },
      { merge: true },
    );
  } catch (e) {
    logger.warn('quota counter not written', { error: String(e) });
  }
}

/**
 * One GET to the Data API. [bucket] says which allowance it spends from; a call YouTube refuses
 * for quota marks that bucket as spent for the day, so the next calls stop before reaching it.
 */
export async function ytGet(path: string, params: Record<string, string>, key: string, bucket: 'units' | 'search' = 'units'): Promise<any> { // eslint-disable-line @typescript-eslint/no-explicit-any
  const url = new URL(`${BASE}/${path}`);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  url.searchParams.set('key', key);
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  await spend(bucket === 'search' ? 'searches' : 'units', 1);
  if (res.ok) return res.json();
  const body = (await res.json().catch(() => ({}))) as { error?: { errors?: { reason?: string }[] } };
  const reason = body.error?.errors?.[0]?.reason ?? 'unknown';
  if (res.status === 403 && (reason === 'quotaExceeded' || reason === 'dailyLimitExceeded' || reason === 'rateLimitExceeded')) {
    await spend(bucket === 'search' ? 'searches' : 'units', bucket === 'search' ? 1000 : 100_000);
    throw new QuotaError(bucket);
  }
  throw new YtApiError(res.status, reason);
}

/** A GET with the account's own access token (the subscriptions), spending from the same daily units. */
export async function ytGetAsUser(path: string, params: Record<string, string>, accessToken: string): Promise<any> { // eslint-disable-line @typescript-eslint/no-explicit-any
  const url = new URL(`${BASE}/${path}`);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const res = await fetch(url, { headers: { Accept: 'application/json', Authorization: `Bearer ${accessToken}` } });
  await spend('units', 1);
  if (res.ok) return res.json();
  const body = (await res.json().catch(() => ({}))) as { error?: { errors?: { reason?: string }[] } };
  const reason = body.error?.errors?.[0]?.reason ?? 'unknown';
  if (res.status === 403 && reason === 'quotaExceeded') throw new QuotaError('units');
  throw new YtApiError(res.status, reason);
}

/**
 * A POST with the account's own access token: `videos.rate`, the one write (ADR-069). [units] is
 * what YouTube charges for it (50 for a rating).
 */
export async function ytPostAsUser(path: string, params: Record<string, string>, accessToken: string, units: number): Promise<void> {
  const url = new URL(`${BASE}/${path}`);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const res = await fetch(url, { method: 'POST', headers: { Accept: 'application/json', Authorization: `Bearer ${accessToken}` } });
  await spend('units', units);
  if (res.ok) return;
  const body = (await res.json().catch(() => ({}))) as { error?: { errors?: { reason?: string }[] } };
  const reason = body.error?.errors?.[0]?.reason ?? 'unknown';
  if (res.status === 403 && reason === 'quotaExceeded') throw new QuotaError('units');
  throw new YtApiError(res.status, reason);
}
