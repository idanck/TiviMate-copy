import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions/v2';
import { QuotaError, quotaLeft, YtApiError, ytGet } from './api';
import { channelFrom, chunks, RECENT_PER_CHANNEL, sortChannel, videoFrom, type YtChannel, type YtChannelState, type YtVideo } from './model';

/**
 * Channels and what is on them, shared by every user (ADR-061): `ytChannels/{channelId}` holds a
 * channel's details, its live streams, the ones announced and its recent uploads, and is fetched
 * again only once it is older than the caller allows. A hundred televisions with the same channel
 * saved cost one fetch, not a hundred.
 *
 * Finding a channel's live stream: a new stream is among its recent uploads, so `playlistItems`
 * on the uploads list finds it for 1 unit. A stream that has run for weeks (a news channel's 24/7
 * stream) has long left that list, so once a day a channel with no known stream is also asked
 * through `search.list` (`eventType=live`), from the search bucket, while it lasts; a stream found
 * either way is remembered (`liveIds`) and checked by id until it ends.
 */

/** How old a channel may be before it is fetched again: one the viewer saved, or one only in their subscriptions. */
export const SAVED_TTL_MS = 10 * 60 * 1000;
export const SUBSCRIBED_TTL_MS = 60 * 60 * 1000;
/** How often a channel with no known live stream is looked for one by search. */
export const LIVE_SEARCH_EVERY_MS = 24 * 60 * 60 * 1000;
/** Cache documents nobody has asked for in this long are removed by Firestore's TTL. */
const KEEP_MS = 30 * 24 * 60 * 60 * 1000;

const cache = () => getFirestore().collection('ytChannels');

interface CacheDoc {
  data: YtChannelState;
  liveIds: string[];
  fetchedAt: number;
  liveSearchedAt: number;
}

/** Live stream ids of a channel, by search. Empty when the search bucket is gone. */
async function searchLive(channelId: string, key: string): Promise<string[]> {
  try {
    const res = await ytGet('search', { part: 'id', channelId, eventType: 'live', type: 'video', maxResults: '3' }, key, 'search');
    return (res.items ?? []).map((i: { id?: { videoId?: string } }) => i.id?.videoId).filter((v: unknown): v is string => typeof v === 'string');
  } catch (e) {
    if (e instanceof QuotaError) return [];
    throw e;
  }
}

/**
 * Fetches [ids] afresh and writes them to the cache. Calls, for n channels: 1 `channels.list`
 * per 50, 1 `playlistItems.list` each, 1 `videos.list` per 50 videos, and now and then a search.
 */
async function fetchChannels(ids: string[], key: string, previous: Map<string, CacheDoc>): Promise<Map<string, YtChannelState>> {
  const out = new Map<string, YtChannelState>();
  const channels: YtChannel[] = [];
  for (const part of chunks(ids)) {
    const res = await ytGet('channels', { part: 'snippet,contentDetails,statistics', id: part.join(','), maxResults: '50' }, key);
    for (const item of res.items ?? []) {
      const ch = channelFrom(item);
      if (ch) channels.push(ch);
    }
  }
  const now = Date.now();
  const left = await quotaLeft();
  const wanted = new Map<string, Set<string>>();
  const searched = new Map<string, number>();
  for (const ch of channels) {
    const prev = previous.get(ch.id);
    const set = new Set<string>(prev?.liveIds ?? []);
    if (ch.uploads) {
      // A channel that has never uploaded has an uploads playlist YouTube does not find (404
      // playlistNotFound), and one may hide it: that channel simply has no recent videos. One such
      // channel among fifty subscriptions must not fail the whole list.
      try {
        const res = await ytGet('playlistItems', { part: 'contentDetails', playlistId: ch.uploads, maxResults: String(RECENT_PER_CHANNEL) }, key);
        for (const item of res.items ?? []) {
          const v = item?.contentDetails?.videoId;
          if (typeof v === 'string') set.add(v);
        }
      } catch (e) {
        if (!(e instanceof YtApiError)) throw e;
        logger.info('uploads not readable; skipped', { channel: ch.id, status: e.status, reason: e.reason });
      }
    }
    let searchedAt = prev?.liveSearchedAt ?? 0;
    if ((prev?.liveIds ?? []).length === 0 && now - searchedAt >= LIVE_SEARCH_EVERY_MS && left.search) {
      for (const v of await searchLive(ch.id, key)) set.add(v);
      searchedAt = now;
    }
    searched.set(ch.id, searchedAt);
    wanted.set(ch.id, set);
  }
  const allIds = [...new Set([...wanted.values()].flatMap((s) => [...s]))];
  const videos: YtVideo[] = [];
  for (const part of chunks(allIds)) {
    const res = await ytGet('videos', { part: 'snippet,contentDetails,liveStreamingDetails,status', id: part.join(','), maxResults: '50' }, key);
    for (const item of res.items ?? []) {
      const v = videoFrom(item);
      if (v) videos.push(v);
    }
  }
  const batch = getFirestore().batch();
  for (const ch of channels) {
    const state = sortChannel(ch, videos.filter((v) => wanted.get(ch.id)?.has(v.id)));
    out.set(ch.id, state);
    const doc: CacheDoc & { expiresAt: Timestamp } = {
      data: state,
      liveIds: state.live.map((v) => v.id),
      fetchedAt: now,
      liveSearchedAt: searched.get(ch.id) ?? 0,
      expiresAt: Timestamp.fromMillis(now + KEEP_MS),
    };
    batch.set(cache().doc(ch.id), doc);
  }
  await batch.commit();
  return out;
}

/**
 * The state of [ids], from the cache where it is no older than [maxAgeMs] and fetched otherwise.
 * A channel YouTube does not know is left out. If the day's units are gone, what the cache has is
 * returned however old, and `stale` says so.
 */
export async function channelStates(ids: string[], key: string, maxAgeMs: number): Promise<{ states: YtChannelState[]; stale: boolean }> {
  if (ids.length === 0) return { states: [], stale: false };
  const snaps = await getFirestore().getAll(...ids.map((id) => cache().doc(id)));
  const cached = new Map<string, CacheDoc>();
  for (const s of snaps) if (s.exists) cached.set(s.id, s.data() as CacheDoc);
  const now = Date.now();
  const due = ids.filter((id) => !cached.has(id) || now - (cached.get(id)?.fetchedAt ?? 0) > maxAgeMs);
  let fresh = new Map<string, YtChannelState>();
  let stale = false;
  if (due.length > 0) {
    try {
      if (!(await quotaLeft()).units) throw new QuotaError('units');
      fresh = await fetchChannels(due, key, cached);
    } catch (e) {
      if (!(e instanceof QuotaError)) throw e;
      logger.warn('youtube units exhausted; serving the cache', { due: due.length });
      stale = true;
    }
  }
  const states: YtChannelState[] = [];
  for (const id of ids) {
    const s = fresh.get(id) ?? cached.get(id)?.data;
    if (s) states.push(s);
  }
  return { states, stale };
}

/** A channel by its handle ("@kannews"): 1 unit, then cached like any other. */
export async function channelIdForHandle(handle: string, key: string): Promise<string | null> {
  const res = await ytGet('channels', { part: 'id', forHandle: handle, maxResults: '1' }, key);
  const id = res.items?.[0]?.id;
  return typeof id === 'string' ? id : null;
}
