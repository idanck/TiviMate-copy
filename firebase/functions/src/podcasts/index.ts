import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { defineSecret } from 'firebase-functions/params';
import { logger } from 'firebase-functions/v2';
import { HttpsError, onCall, type CallableRequest } from 'firebase-functions/v2/https';
import { sha256 } from '../codes';
import { MAX_INSTANCES, PC_LIMITS, REGION } from '../config';
import { clientIp, enforce } from '../limits';
import { authHeaders, categoryParam, configured, hebrewFirst, languageParam, normalizeQuery, podcastsFrom, type PcPodcast } from './model';

/**
 * Podcast Index (ADR-067): search and the popular rail of "גילוי והוספה", from the server only, so
 * the key and secret never reach the app.
 *
 * Secrets (Secret Manager, set by the owner; docs/14-podcast-index-setup.md):
 * `PODCASTINDEX_API_KEY` and `PODCASTINDEX_API_SECRET`, from api.podcastindex.org. Set to "unset"
 * until then: the callables answer `failed-precondition` with reason `not-configured`, and the app
 * offers only "הוספה מקישור".
 *
 * Every answer is kept in `pcCache/{hash}` — search for a day, the rail for six hours — so the same
 * phrase from anybody costs Podcast Index nothing until then.
 */
const KEY = defineSecret('PODCASTINDEX_API_KEY');
const SECRET = defineSecret('PODCASTINDEX_API_SECRET');

const BASE = 'https://api.podcastindex.org/api/1.0';
const USER_AGENT = 'Shidur/1.0';
const SEARCH_TTL_MS = 24 * 60 * 60 * 1000;
const TRENDING_TTL_MS = 6 * 60 * 60 * 1000;
const MAX_RESULTS = 40;

const base = { region: REGION, maxInstances: MAX_INSTANCES, secrets: [KEY, SECRET] };

function caller(request: CallableRequest): string {
  return request.auth?.uid ?? `ip:${clientIp(request.rawRequest)}`;
}

function ready(): { key: string; secret: string } {
  const key = KEY.value();
  const secret = SECRET.value();
  if (!configured(key, secret)) throw new HttpsError('failed-precondition', 'Podcast search is not configured.', { reason: 'not-configured' });
  return { key, secret };
}

async function pcGet(path: string, params: Record<string, string>, creds: { key: string; secret: string }): Promise<unknown> {
  const url = new URL(`${BASE}/${path}`);
  for (const [k, v] of Object.entries(params)) if (v !== '') url.searchParams.set(k, v);
  const res = await fetch(url, { headers: authHeaders(creds.key, creds.secret, Date.now(), USER_AGENT) });
  if (res.ok) return res.json();
  logger.warn('podcast index refused', { status: res.status, path });
  throw new HttpsError('unavailable', 'Podcast Index refused the request.', { reason: res.status === 401 ? 'not-configured' : 'unavailable' });
}

/** An answer from the cache when it is fresh, else from Podcast Index, then kept. */
async function cached(id: string, ttlMs: number, load: () => Promise<PcPodcast[]>): Promise<{ podcasts: PcPodcast[]; cached: boolean }> {
  const ref = getFirestore().collection('pcCache').doc(sha256(id));
  const hit = (await ref.get()).data() as { podcasts: PcPodcast[]; at: number } | undefined;
  if (hit && Date.now() - hit.at < ttlMs) return { podcasts: hit.podcasts, cached: true };
  const podcasts = await load();
  try {
    await ref.set({ podcasts, at: Date.now(), expiresAt: Timestamp.fromMillis(Date.now() + ttlMs * 2) });
  } catch (e) {
    logger.warn('podcast cache not written', { error: String(e) });
  }
  return { podcasts, cached: false };
}

/** Search by name or host: `{ q }` → `{ podcasts, cached }`, Hebrew shows first. */
export const pcSearch = onCall(base, async (request) => {
  const q = normalizeQuery(request.data?.q);
  if (!q) throw new HttpsError('invalid-argument', 'Type at least two letters.');
  const creds = ready();
  await enforce('pc_search', caller(request), PC_LIMITS.search.max, PC_LIMITS.search.windowMs);
  return cached(`search:${q.toLowerCase()}`, SEARCH_TTL_MS, async () => {
    const body = await pcGet('search/byterm', { q, max: String(MAX_RESULTS), clean: 'true' }, creds);
    return hebrewFirst(podcastsFrom(body, MAX_RESULTS));
  });
});

/**
 * The popular rail: `{ lang?, cat? }` → `{ podcasts, cached }`. Hebrew unless asked otherwise
 * (`lang: 'any'` for every language); `cat` is one of Podcast Index's category names.
 */
export const pcTrending = onCall(base, async (request) => {
  const lang = languageParam(request.data?.lang);
  const cat = categoryParam(request.data?.cat);
  const creds = ready();
  await enforce('pc_trending', caller(request), PC_LIMITS.trending.max, PC_LIMITS.trending.windowMs);
  return cached(`trending:${lang}:${cat ?? ''}`, TRENDING_TTL_MS, async () => {
    const body = await pcGet('podcasts/trending', { max: String(MAX_RESULTS), lang, cat: cat ?? '' }, creds);
    return podcastsFrom(body, MAX_RESULTS);
  });
});
