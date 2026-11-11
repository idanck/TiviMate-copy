import { createHash } from 'node:crypto';

/**
 * Podcast Index (ADR-067), the pure half: what is sent, and what of the answer the app is given.
 * Nothing here touches the network or Firestore, so it is tested on its own.
 */

/** A podcast as the app draws it in "גילוי והוספה" and search: enough to show and to add it. */
export interface PcPodcast {
  /** Podcast Index's feed id. */
  id: number;
  title: string;
  author: string | null;
  image: string | null;
  /** The RSS address the app reads itself once the podcast is added. */
  feedUrl: string;
  language: string | null;
  categories: string[];
  description: string | null;
  episodeCount: number | null;
  /** The newest episode's time, UTC milliseconds, when Podcast Index knows it. */
  newestUtc: number | null;
}

/**
 * The four headers every call carries. `Authorization` is the SHA-1 of the key, the secret and the
 * time in Unix seconds, in hex; Podcast Index accepts the time within a few minutes of its own.
 */
export function authHeaders(key: string, secret: string, nowMs: number, userAgent: string): Record<string, string> {
  const date = String(Math.floor(nowMs / 1000));
  const hash = createHash('sha1').update(key + secret + date).digest('hex');
  return {
    'User-Agent': userAgent,
    'X-Auth-Key': key,
    'X-Auth-Date': date,
    Authorization: hash,
    Accept: 'application/json',
  };
}

/** A search phrase: trimmed, spaces folded, at least two letters, at most 100. */
export function normalizeQuery(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const q = raw.replace(/\s+/g, ' ').trim();
  if (q.length < 2) return null;
  return q.slice(0, 100);
}

/**
 * Language codes a Hebrew feed is written with in the wild: `he`, `he-IL`, and the old `iw`.
 * Podcast Index takes a comma-separated list and ignores case.
 */
export const HEBREW = 'he,he-il,iw,iw-il';

/** The languages the app may ask the popular rail for; anything else is Hebrew. */
export function languageParam(raw: unknown): string {
  if (raw === 'any') return '';
  if (typeof raw === 'string' && /^[a-z]{2}(-[a-z]{2})?(,[a-z]{2}(-[a-z]{2})?)*$/i.test(raw) && raw.length <= 40) return raw.toLowerCase();
  return HEBREW;
}

function str(v: unknown): string | null {
  return typeof v === 'string' && v.trim() ? v.trim() : null;
}

function num(v: unknown): number | null {
  const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v) : NaN;
  return Number.isFinite(n) ? n : null;
}

/**
 * One feed from any of Podcast Index's lists (`search/byterm`, `podcasts/trending`, `recent/feeds`).
 * Null for one with no address or no title — the app could neither show nor add it. `artwork` is
 * preferred to `image` (it is the larger one when both are given); `categories` is an object of id
 * to name.
 */
export function podcastFrom(raw: unknown): PcPodcast | null {
  if (!raw || typeof raw !== 'object') return null;
  const f = raw as Record<string, unknown>;
  const feedUrl = str(f.url) ?? str(f.originalUrl);
  const title = str(f.title);
  const id = num(f.id);
  if (!feedUrl || !title || id === null) return null;
  if (!/^https?:\/\//i.test(feedUrl)) return null;
  const categories = f.categories && typeof f.categories === 'object'
    ? Object.values(f.categories as Record<string, unknown>).filter((c): c is string => typeof c === 'string' && c.length > 0)
    : [];
  const newest = num(f.newestItemPublishTime) ?? num(f.newestItemPubdate) ?? num(f.lastUpdateTime);
  return {
    id,
    title,
    author: str(f.author) ?? str(f.ownerName),
    image: str(f.artwork) ?? str(f.image),
    feedUrl,
    language: str(f.language)?.toLowerCase() ?? null,
    categories,
    description: str(f.description)?.slice(0, 600) ?? null,
    episodeCount: num(f.episodeCount),
    newestUtc: newest !== null && newest > 0 ? newest * 1000 : null,
  };
}

/** Every usable feed of an answer, once each by address, in Podcast Index's order. */
export function podcastsFrom(body: unknown, max: number): PcPodcast[] {
  const feeds = (body as { feeds?: unknown } | null)?.feeds;
  if (!Array.isArray(feeds)) return [];
  const seen = new Set<string>();
  const out: PcPodcast[] = [];
  for (const raw of feeds) {
    const p = podcastFrom(raw);
    if (!p || seen.has(p.feedUrl)) continue;
    seen.add(p.feedUrl);
    out.push(p);
    if (out.length >= max) break;
  }
  return out;
}

/** Hebrew first: a search for a Hebrew phrase usually means Hebrew shows, but is not limited to them. */
export function hebrewFirst(list: PcPodcast[]): PcPodcast[] {
  const he = (p: PcPodcast) => (p.language ?? '').startsWith('he') || (p.language ?? '').startsWith('iw');
  return [...list.filter(he), ...list.filter((p) => !he(p))];
}

/** A Podcast Index category name the app may filter the popular rail by, or null for none. */
export function categoryParam(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const c = raw.trim();
  return /^[A-Za-z][A-Za-z &-]{1,40}$/.test(c) ? c : null;
}

/** Whether both secrets hold a value; "unset" is the placeholder before the owner registers. */
export function configured(key: string, secret: string): boolean {
  return key.length > 0 && secret.length > 0 && key !== 'unset' && secret !== 'unset';
}
