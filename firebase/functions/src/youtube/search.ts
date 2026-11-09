import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { QuotaError, quotaLeft, ytGet } from './api';
import { chunks, videoFrom, type SearchKind, type YtVideo } from './model';
import { sha256 } from '../codes';

/**
 * YouTube search (ADR-061). Search has a bucket of its own, 100 calls a day for the whole project
 * until Google raises it, so every answer is kept for six hours (`ytSearch/{hash}`) and the same
 * phrase from anybody costs nothing until then. The owner chose to build it now and ask Google for
 * more quota later; until then the app says plainly when the day's searches are gone.
 */

export const SEARCH_TTL_MS = 6 * 60 * 60 * 1000;

export interface SearchChannel {
  id: string;
  title: string;
  thumb: string | null;
}

export interface SearchResult {
  channels: SearchChannel[];
  videos: YtVideo[];
}

const cache = () => getFirestore().collection('ytSearch');

export async function search(q: string, kind: SearchKind, params: Record<string, string>, key: string): Promise<SearchResult & { cached: boolean }> {
  const ref = cache().doc(sha256(`${kind}:${q}`));
  const snap = await ref.get();
  const hit = snap.data() as { result: SearchResult; at: number } | undefined;
  if (hit && Date.now() - hit.at < SEARCH_TTL_MS) return { ...hit.result, cached: true };
  if (!(await quotaLeft()).search) throw new QuotaError('search');

  const res = await ytGet('search', params, key, 'search');
  const channels: SearchChannel[] = [];
  const videoIds: string[] = [];
  for (const item of res.items ?? []) {
    const id = item?.id;
    if (id?.kind === 'youtube#channel' && typeof id.channelId === 'string') {
      const t = item.snippet?.thumbnails;
      channels.push({ id: id.channelId, title: String(item.snippet?.title ?? ''), thumb: t?.medium?.url ?? t?.default?.url ?? null });
    } else if (id?.kind === 'youtube#video' && typeof id.videoId === 'string') {
      videoIds.push(id.videoId);
    }
  }
  // Search gives no length, no live state and no "embeddable": one videos.list call (1 unit) does.
  const videos: YtVideo[] = [];
  for (const part of chunks(videoIds)) {
    const v = await ytGet('videos', { part: 'snippet,contentDetails,liveStreamingDetails,status', id: part.join(','), maxResults: '50' }, key);
    for (const item of v.items ?? []) {
      const video = videoFrom(item);
      if (video) videos.push(video);
    }
  }
  const order = new Map(videoIds.map((id, i) => [id, i]));
  videos.sort((a, b) => (order.get(a.id) ?? 0) - (order.get(b.id) ?? 0));
  const result = { channels, videos };
  await ref.set({ result, at: Date.now(), expiresAt: Timestamp.fromMillis(Date.now() + SEARCH_TTL_MS) });
  return { ...result, cached: false };
}
