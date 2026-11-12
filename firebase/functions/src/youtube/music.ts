import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { QuotaError, quotaLeft, YtApiError, ytGet, ytGetAsUser } from './api';
import { chunks } from './model';
import {
  artistFrom,
  LIKED_MAX,
  listFrom,
  musicCategoryId,
  onlyMusic,
  PLAYLIST_MAX,
  playlistVideoIds,
  songFrom,
  uniqueSongs,
  type LibraryPart,
  type MusicSearchKind,
  type YtArtist,
  type YtMusicList,
  type YtSong,
} from './musicModel';
import { sha256 } from '../codes';

/**
 * YouTube Music inside Shidur (ADR-069), the server's half: what the official YouTube Data API v3
 * gives for music, read through the same key, token and quota counters as the video tab (ADR-061).
 * What the API does not give — saved albums, a listening history, likes kept by Shidur — lives in
 * the account's own Firestore documents, written by the app (`firestore.rules`).
 *
 * Caches, all removed by Firestore's TTL once nobody asks (`expiresAt`):
 * - `ytMusic/category`: the music category's id, asked of YouTube once a month.
 * - `ytMusic/chart-IL`: the popular music chart, for everyone, six hours.
 * - `ytmPlaylists/{id}`: a public playlist or album and its songs, for everyone, six hours; a private
 *   one is kept under a key of the account's own (`u-…`), so nobody else is ever handed it.
 * - `ytmLib/{uid}`: the account's playlists (an hour) and liked songs (half an hour).
 * - `ytSearch/{hash}`: music searches share the video tab's search cache, under keys of their own.
 */

const db = () => getFirestore();

export const CATEGORY_TTL_MS = 30 * 24 * 60 * 60 * 1000;
export const CHART_TTL_MS = 6 * 60 * 60 * 1000;
export const PLAYLIST_TTL_MS = 6 * 60 * 60 * 1000;
export const LIB_PLAYLISTS_TTL_MS = 60 * 60 * 1000;
export const LIB_LIKED_TTL_MS = 30 * 60 * 1000;
export const MUSIC_SEARCH_TTL_MS = 6 * 60 * 60 * 1000;
const KEEP_MS = 7 * 24 * 60 * 60 * 1000;

const expires = (ms: number) => Timestamp.fromMillis(Date.now() + ms);

/** The music category's id, from YouTube's own list (1 unit, once a month). Null if YouTube names none. */
export async function musicCategory(key: string): Promise<string | null> {
  const ref = db().collection('ytMusic').doc('category');
  const hit = (await ref.get()).data() as { id: string | null; at: number } | undefined;
  if (hit && Date.now() - hit.at < CATEGORY_TTL_MS) return hit.id;
  const res = await ytGet('videoCategories', { part: 'snippet', regionCode: 'IL', hl: 'en' }, key);
  const id = musicCategoryId(res.items);
  await ref.set({ id, at: Date.now(), expiresAt: expires(CATEGORY_TTL_MS + KEEP_MS) });
  return id;
}

type Getter = (path: string, params: Record<string, string>) => Promise<any>; // eslint-disable-line @typescript-eslint/no-explicit-any

/** The details of [ids] as songs, in the order given (1 unit per 50). Gone or private videos drop out. */
async function songsFor(ids: string[], get: Getter): Promise<YtSong[]> {
  const found = new Map<string, YtSong>();
  for (const part of chunks(ids)) {
    const res = await get('videos', { part: 'snippet,contentDetails,status', id: part.join(','), maxResults: '50' });
    for (const item of res.items ?? []) {
      const s = songFrom(item);
      if (s) found.set(s.id, s);
    }
  }
  return ids.map((id) => found.get(id)).filter((s): s is YtSong => !!s);
}

/** The popular music chart for Israel, shared by everyone (1 unit every six hours). */
export async function chart(key: string): Promise<{ songs: YtSong[]; cached: boolean }> {
  const ref = db().collection('ytMusic').doc('chart-IL');
  const hit = (await ref.get()).data() as { songs: YtSong[]; at: number } | undefined;
  if (hit && Date.now() - hit.at < CHART_TTL_MS) return { songs: hit.songs, cached: true };
  const category = await musicCategory(key);
  const params: Record<string, string> = { part: 'snippet,contentDetails,status', chart: 'mostPopular', regionCode: 'IL', maxResults: '50' };
  if (category) params.videoCategoryId = category;
  const res = await ytGet('videos', params, key);
  const songs = uniqueSongs((res.items ?? []).map(songFrom).filter((s: YtSong | null): s is YtSong => !!s));
  await ref.set({ songs, at: Date.now(), expiresAt: expires(CHART_TTL_MS + KEEP_MS) });
  return { songs, cached: false };
}

/** Thrown when a playlist is neither public nor the caller's own. */
export class NotFound extends Error {}

/**
 * A playlist or an album and its songs (up to [PLAYLIST_MAX]). Read with the key first; YouTube
 * answers a private playlist with "not found", and then, when the caller has an account connected,
 * it is read again with the account's token and kept where only that account finds it.
 */
export async function playlist(id: string, key: string, uid: string | null, token: () => Promise<string | null>): Promise<{ list: YtMusicList; songs: YtSong[]; cached: boolean }> {
  const shared = db().collection('ytmPlaylists').doc(id);
  const own = uid ? db().collection('ytmPlaylists').doc(`u-${sha256(`${uid}:${id}`)}`) : null;
  for (const ref of own ? [shared, own] : [shared]) {
    const hit = (await ref.get()).data() as { list: YtMusicList; songs: YtSong[]; at: number } | undefined;
    if (hit && Date.now() - hit.at < PLAYLIST_TTL_MS) return { list: hit.list, songs: hit.songs, cached: true };
  }
  const read = async (get: Getter) => {
    const head = await get('playlists', { part: 'snippet,contentDetails,status', id, maxResults: '1' });
    const list = listFrom((head.items ?? [])[0]);
    if (!list) throw new NotFound();
    const ids: string[] = [];
    let pageToken = '';
    while (ids.length < PLAYLIST_MAX) {
      const params: Record<string, string> = { part: 'contentDetails', playlistId: id, maxResults: '50' };
      if (pageToken) params.pageToken = pageToken;
      const res = await get('playlistItems', params);
      for (const v of playlistVideoIds(res.items)) if (!ids.includes(v)) ids.push(v);
      pageToken = typeof res.nextPageToken === 'string' ? res.nextPageToken : '';
      if (!pageToken) break;
    }
    return { list, songs: await songsFor(ids.slice(0, PLAYLIST_MAX), get) };
  };

  let result: { list: YtMusicList; songs: YtSong[] };
  let ref = shared;
  try {
    result = await read((p, q) => ytGet(p, q, key));
  } catch (e) {
    const missing = e instanceof NotFound || (e instanceof YtApiError && (e.status === 404 || e.reason === 'playlistNotFound'));
    if (!missing || !own) throw missing ? new NotFound() : e;
    const t = await token();
    if (!t) throw new NotFound();
    try {
      result = await read((p, q) => ytGetAsUser(p, q, t));
    } catch (e2) {
      if (e2 instanceof YtApiError && (e2.status === 404 || e2.reason === 'playlistNotFound')) throw new NotFound();
      throw e2;
    }
    ref = own;
  }
  await ref.set({ ...result, at: Date.now(), expiresAt: expires(PLAYLIST_TTL_MS + KEEP_MS) });
  return { ...result, cached: false };
}

/**
 * A part of the account's library, read with its token:
 * - `playlists`: the playlists it owns (`playlists.list mine=true`, the first 50, 1 unit), an hour.
 * - `liked`: the videos it liked on YouTube (`videos.list myRating=like`, up to four pages), kept to
 *   the music category, half an hour. Whether a like given inside the YouTube Music app is among
 *   them is not documented; the tab adds the likes Shidur keeps itself either way.
 */
export async function library(uid: string, part: LibraryPart, key: string, token: string): Promise<{ playlists?: YtMusicList[]; songs?: YtSong[]; cached: boolean }> {
  const ref = db().collection('ytmLib').doc(uid);
  const doc = ((await ref.get()).data() ?? {}) as {
    playlists?: { items: YtMusicList[]; at: number };
    liked?: { items: YtSong[]; at: number };
  };
  if (part === 'playlists') {
    if (doc.playlists && Date.now() - doc.playlists.at < LIB_PLAYLISTS_TTL_MS) return { playlists: doc.playlists.items, cached: true };
    const res = await ytGetAsUser('playlists', { part: 'snippet,contentDetails,status', mine: 'true', maxResults: '50' }, token);
    const items = (res.items ?? []).map(listFrom).filter((l: YtMusicList | null): l is YtMusicList => !!l);
    await ref.set({ playlists: { items, at: Date.now() }, expiresAt: expires(KEEP_MS) }, { merge: true });
    return { playlists: items, cached: false };
  }
  if (doc.liked && Date.now() - doc.liked.at < LIB_LIKED_TTL_MS) return { songs: doc.liked.items, cached: true };
  const category = await musicCategory(key);
  const songs: YtSong[] = [];
  let pageToken = '';
  let read = 0;
  while (read < LIKED_MAX) {
    const params: Record<string, string> = { part: 'snippet,contentDetails,status', myRating: 'like', maxResults: '50' };
    if (pageToken) params.pageToken = pageToken;
    const res = await ytGetAsUser('videos', params, token);
    const items = res.items ?? [];
    read += items.length;
    for (const item of items) {
      const s = songFrom(item);
      if (s) songs.push(s);
    }
    pageToken = typeof res.nextPageToken === 'string' ? res.nextPageToken : '';
    if (!pageToken || items.length === 0) break;
  }
  const items = uniqueSongs(onlyMusic(songs, category));
  await ref.set({ liked: { items, at: Date.now() }, expiresAt: expires(KEEP_MS) }, { merge: true });
  return { songs: items, cached: false };
}

export interface MusicSearchResult {
  songs: YtSong[];
  artists: YtArtist[];
  lists: YtMusicList[];
}

/**
 * A music search: one `search.list` call from the day's hundred, kept six hours per phrase and kind
 * for everybody, then one `videos.list` for the songs' details (1 unit), which is also what keeps
 * "all" to the music category.
 */
export async function musicSearch(q: string, kind: MusicSearchKind, params: Record<string, string>, category: string | null, key: string): Promise<MusicSearchResult & { cached: boolean }> {
  const ref = db().collection('ytSearch').doc(sha256(`music:${kind}:${q}`));
  const hit = (await ref.get()).data() as { result: MusicSearchResult; at: number } | undefined;
  if (hit && Date.now() - hit.at < MUSIC_SEARCH_TTL_MS) return { ...hit.result, cached: true };
  if (!(await quotaLeft()).search) throw new QuotaError('search');

  const res = await ytGet('search', params, key, 'search');
  const videoIds: string[] = [];
  const artists: YtArtist[] = [];
  const lists: YtMusicList[] = [];
  for (const item of res.items ?? []) {
    const k = item?.id?.kind;
    if (k === 'youtube#video' && typeof item.id.videoId === 'string') videoIds.push(item.id.videoId);
    else if (k === 'youtube#channel') {
      const a = artistFrom(item);
      if (a) artists.push(a);
    } else if (k === 'youtube#playlist') {
      const l = listFrom(item);
      if (l) lists.push(l);
    }
  }
  const songs = onlyMusic(await songsFor(videoIds, (p, qs) => ytGet(p, qs, key)), category);
  const result = { songs, artists, lists };
  await ref.set({ result, at: Date.now(), expiresAt: expires(MUSIC_SEARCH_TTL_MS) });
  return { ...result, cached: false };
}
