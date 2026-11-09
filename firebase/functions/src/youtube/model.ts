/**
 * YouTube inside Shidur (ADR-061): the shapes the app receives, and the pure functions that make
 * them out of YouTube Data API answers. Nothing here touches the network or Firestore, so all of
 * it is unit-tested (test/youtube.test.ts).
 */

/** A video, a live stream, or a stream announced for later, as the app draws it. */
export interface YtVideo {
  id: string;
  title: string;
  channelId: string;
  channelTitle: string;
  /** Medium thumbnail (320×180), or the default one. */
  thumb: string | null;
  /** Epoch ms. */
  publishedAt: number | null;
  /** Seconds; null for a live stream or when YouTube gives none. */
  duration: number | null;
  live: boolean;
  upcoming: boolean;
  /** Epoch ms the live stream started, or the scheduled start of an upcoming one. */
  startedAt: number | null;
  scheduledAt: number | null;
  /** Concurrent viewers of a live stream, when the channel shows them. */
  viewers: number | null;
  /** False when the owner allows playing it only on YouTube itself (the app then offers the YouTube app). */
  embeddable: boolean;
}

export interface YtChannel {
  id: string;
  title: string;
  /** "@handle", when the channel has one. */
  handle: string | null;
  thumb: string | null;
  /** Null when the channel hides it. */
  subscribers: number | null;
  videos: number | null;
  uploads: string | null;
}

/** A channel with what is on it now: the cache document's `data`, and what `ytChannels` returns. */
export interface YtChannelState {
  channel: YtChannel;
  live: YtVideo[];
  upcoming: YtVideo[];
  recent: YtVideo[];
}

/** What a channel input in the app can be: a channel ID, or a handle. */
export type ChannelRef = { kind: 'id'; value: string } | { kind: 'handle'; value: string };

const CHANNEL_ID = /^UC[A-Za-z0-9_-]{22}$/;
const HANDLE = /^@?[A-Za-z0-9._·֐-׿-]{3,30}$/;

/**
 * Reads what a person types or pastes to add a channel: a channel ID, "@handle", a bare handle,
 * or a youtube.com address of either form (`/channel/UC…`, `/@handle`, with or without
 * `https://`, `www.` or `m.`). Anything else — a video link, a search phrase — is null: adding a
 * channel by name goes through search instead.
 */
export function parseChannelInput(raw: unknown): ChannelRef | null {
  if (typeof raw !== 'string') return null;
  let s = raw.trim();
  if (!s || s.length > 200) return null;
  const url = s.match(/^(?:https?:\/\/)?(?:www\.|m\.)?youtube\.com\/(.+)$/i);
  if (url) {
    const path = url[1].split(/[?#]/)[0].replace(/\/+$/, '');
    const parts = path.split('/');
    if (parts[0] === 'channel' && parts[1] && CHANNEL_ID.test(parts[1])) return { kind: 'id', value: parts[1] };
    if (parts[0].startsWith('@')) s = decodeURIComponent(parts[0]);
    else return null;
  }
  if (CHANNEL_ID.test(s)) return { kind: 'id', value: s };
  if (HANDLE.test(s)) return { kind: 'handle', value: s.startsWith('@') ? s : `@${s}` };
  return null;
}

/** ISO-8601 duration ("PT1H2M3S", "P1DT2H") to seconds; null for anything else, including "P0D". */
export function parseDuration(iso: unknown): number | null {
  if (typeof iso !== 'string') return null;
  const m = iso.match(/^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/);
  if (!m) return null;
  const [, d, h, min, sec] = m.map((v) => (v ? Number(v) : 0));
  const total = d * 86400 + h * 3600 + min * 60 + sec;
  return total > 0 ? total : null;
}

function ms(value: unknown): number | null {
  if (typeof value !== 'string') return null;
  const t = Date.parse(value);
  return Number.isFinite(t) ? t : null;
}

function num(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && /^\d+$/.test(value)) return Number(value);
  return null;
}

type Json = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

function thumbOf(snippet: Json | undefined): string | null {
  const t = snippet?.thumbnails;
  return t?.medium?.url ?? t?.high?.url ?? t?.default?.url ?? null;
}

/** A `videos.list` item (parts snippet, contentDetails, liveStreamingDetails, status). */
export function videoFrom(item: Json): YtVideo | null {
  const id = item?.id;
  const sn = item?.snippet;
  if (typeof id !== 'string' || !sn) return null;
  const live = sn.liveBroadcastContent === 'live';
  const upcoming = sn.liveBroadcastContent === 'upcoming';
  const ls = item.liveStreamingDetails ?? {};
  return {
    id,
    title: String(sn.title ?? ''),
    channelId: String(sn.channelId ?? ''),
    channelTitle: String(sn.channelTitle ?? ''),
    thumb: thumbOf(sn),
    publishedAt: ms(sn.publishedAt),
    duration: live || upcoming ? null : parseDuration(item.contentDetails?.duration),
    live,
    upcoming,
    startedAt: ms(ls.actualStartTime),
    scheduledAt: ms(ls.scheduledStartTime),
    viewers: live ? num(ls.concurrentViewers) : null,
    // Missing status is read as embeddable: the player says so itself if it is not.
    embeddable: item.status?.embeddable !== false,
  };
}

/** A `channels.list` item (parts snippet, contentDetails, statistics). */
export function channelFrom(item: Json): YtChannel | null {
  const id = item?.id;
  const sn = item?.snippet;
  if (typeof id !== 'string' || !sn) return null;
  const st = item.statistics ?? {};
  const handle = typeof sn.customUrl === 'string' && sn.customUrl.startsWith('@') ? sn.customUrl : null;
  return {
    id,
    title: String(sn.title ?? ''),
    handle,
    thumb: thumbOf(sn),
    subscribers: st.hiddenSubscriberCount ? null : num(st.subscriberCount),
    videos: num(st.videoCount),
    uploads: item.contentDetails?.relatedPlaylists?.uploads ?? null,
  };
}

/** How many recent videos a channel keeps, and how many a subscriptions feed shows. */
export const RECENT_PER_CHANNEL = 15;
export const FEED_SIZE = 40;

/**
 * Sorts a channel's videos into what is live now (most viewers first), what is announced (soonest
 * first) and the rest (newest first). A stream that was live and has ended is "rest", with its
 * length, like any upload.
 */
export function sortChannel(channel: YtChannel, videos: YtVideo[]): YtChannelState {
  const byId = new Map<string, YtVideo>();
  for (const v of videos) if (v.channelId === channel.id || !v.channelId) byId.set(v.id, v);
  const all = [...byId.values()];
  return {
    channel,
    live: all.filter((v) => v.live).sort((a, b) => (b.viewers ?? 0) - (a.viewers ?? 0)),
    upcoming: all.filter((v) => v.upcoming).sort((a, b) => (a.scheduledAt ?? 0) - (b.scheduledAt ?? 0)),
    recent: all
      .filter((v) => !v.live && !v.upcoming)
      .sort((a, b) => (b.publishedAt ?? 0) - (a.publishedAt ?? 0))
      .slice(0, RECENT_PER_CHANNEL),
  };
}

/** The subscriptions feed: every channel's recent videos merged, newest first, live ones first of all. */
export function mergeFeed(states: YtChannelState[], size = FEED_SIZE): YtVideo[] {
  const live = states.flatMap((s) => s.live);
  const recent = states.flatMap((s) => s.recent).sort((a, b) => (b.publishedAt ?? 0) - (a.publishedAt ?? 0));
  return [...live, ...recent].slice(0, size);
}

/**
 * The day YouTube counts quota in: its quota resets at midnight Pacific time. "2026-11-09".
 */
export function quotaDay(now: number): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Los_Angeles', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(now));
}

/** A search phrase as the cache keys it: trimmed, lower case, inner spaces collapsed. Null if unusable. */
export function normalizeQuery(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const q = raw.trim().replace(/\s+/g, ' ').toLowerCase();
  return q.length >= 2 && q.length <= 100 ? q : null;
}

export type SearchKind = 'all' | 'channel' | 'live' | 'video';

export function searchKind(raw: unknown): SearchKind {
  return raw === 'channel' || raw === 'live' || raw === 'video' ? raw : 'all';
}

/** The `search.list` parameters for a kind of search. */
export function searchParams(q: string, kind: SearchKind): Record<string, string> {
  const base: Record<string, string> = { part: 'snippet', q, maxResults: '20', relevanceLanguage: 'he', regionCode: 'IL', safeSearch: 'moderate' };
  if (kind === 'channel') return { ...base, type: 'channel' };
  if (kind === 'live') return { ...base, type: 'video', eventType: 'live' };
  if (kind === 'video') return { ...base, type: 'video' };
  return { ...base, type: 'channel,video' };
}

/** The ids of a list, cleaned: strings that look like channel IDs, without repeats, at most [max]. */
export function channelIds(raw: unknown, max = 50): string[] {
  if (!Array.isArray(raw)) return [];
  const out: string[] = [];
  for (const v of raw) {
    if (typeof v === 'string' && CHANNEL_ID.test(v) && !out.includes(v)) out.push(v);
    if (out.length >= max) break;
  }
  return out;
}

/** Splits a list into pieces of [size]: the API takes at most 50 ids a call. */
export function chunks<T>(list: T[], size = 50): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < list.length; i += size) out.push(list.slice(i, i + size));
  return out;
}
