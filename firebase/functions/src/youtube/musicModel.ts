import { parseDuration } from './model';

/**
 * YouTube Music inside Shidur (ADR-069): the shapes the music tab receives, and the pure functions
 * that make them out of YouTube Data API answers. There is no official YouTube Music API; all of
 * this is the YouTube Data API v3, read for music. Nothing here touches the network or Firestore,
 * so all of it is unit-tested (test/music.test.ts).
 */

type Json = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

/** A song as the music tab draws it: a YouTube video, read as a track. */
export interface YtSong {
  id: string;
  title: string;
  /** The channel, without the " - Topic" YouTube adds to an artist's automatic channel. */
  artist: string;
  channelId: string;
  /** The largest square-ish thumbnail YouTube gives up to 480 wide, or a smaller one. */
  thumb: string | null;
  /** Seconds; null when YouTube gives none (a live stream). */
  duration: number | null;
  /** False when the owner allows playing it only on YouTube itself. */
  embeddable: boolean;
  /** YouTube's category id ("10" is the one the API names Music, resolved at run time). */
  categoryId: string | null;
}

/** A playlist or an album as the music tab draws it. The API does not tell the two apart. */
export interface YtMusicList {
  id: string;
  title: string;
  /** Who made it: the channel's name, without " - Topic". */
  owner: string;
  channelId: string;
  thumb: string | null;
  /** How many items YouTube says it holds; null when it does not say. */
  count: number | null;
  /** "public", "unlisted" or "private", when YouTube says. */
  privacy: string | null;
}

/** An artist: a channel, as search finds it. */
export interface YtArtist {
  id: string;
  title: string;
  thumb: string | null;
}

/** " - Topic" is what YouTube calls an artist's automatic channel; the tab shows the artist's name. */
export function artistName(channelTitle: unknown): string {
  return String(channelTitle ?? '').replace(/\s+-\s+Topic$/, '').trim();
}

function bestThumb(snippet: Json | undefined): string | null {
  const t = snippet?.thumbnails;
  return t?.high?.url ?? t?.medium?.url ?? t?.default?.url ?? null;
}

/** A `videos.list` item (parts snippet, contentDetails, status). Null for anything unusable. */
export function songFrom(item: Json): YtSong | null {
  const id = item?.id;
  const sn = item?.snippet;
  if (typeof id !== 'string' || !sn) return null;
  // A live stream or an announced one is not a song: the video tab plays those.
  if (sn.liveBroadcastContent === 'live' || sn.liveBroadcastContent === 'upcoming') return null;
  return {
    id,
    title: String(sn.title ?? ''),
    artist: artistName(sn.channelTitle),
    channelId: String(sn.channelId ?? ''),
    thumb: bestThumb(sn),
    duration: parseDuration(item.contentDetails?.duration),
    embeddable: item.status?.embeddable !== false,
    categoryId: typeof sn.categoryId === 'string' ? sn.categoryId : null,
  };
}

/** A `playlists.list` item (parts snippet, contentDetails, status), or a search hit for a playlist. */
export function listFrom(item: Json): YtMusicList | null {
  const id = typeof item?.id === 'string' ? item.id : item?.id?.playlistId;
  const sn = item?.snippet;
  if (typeof id !== 'string' || !sn) return null;
  const count = item.contentDetails?.itemCount;
  return {
    id,
    title: String(sn.title ?? ''),
    owner: artistName(sn.channelTitle),
    channelId: String(sn.channelId ?? ''),
    thumb: bestThumb(sn),
    count: typeof count === 'number' && Number.isFinite(count) ? count : null,
    privacy: typeof item.status?.privacyStatus === 'string' ? item.status.privacyStatus : null,
  };
}

/** A search hit for a channel. */
export function artistFrom(item: Json): YtArtist | null {
  const id = item?.id?.channelId ?? item?.snippet?.channelId;
  const sn = item?.snippet;
  if (typeof id !== 'string' || !sn) return null;
  return { id, title: artistName(sn.title ?? sn.channelTitle), thumb: bestThumb(sn) };
}

/**
 * The music category's id among `videoCategories.list` items (asked with `hl=en`): the one titled
 * "Music". Resolved from YouTube rather than written here, so the tab never filters by a number
 * nobody checked. Null when the list has none.
 */
export function musicCategoryId(items: unknown): string | null {
  if (!Array.isArray(items)) return null;
  for (const it of items) {
    if (it?.snippet?.title === 'Music' && typeof it.id === 'string') return it.id;
  }
  return null;
}

/** Only the songs of [category]; with no category known, every song (better a mixed list than none). */
export function onlyMusic(songs: YtSong[], category: string | null): YtSong[] {
  if (!category) return songs;
  return songs.filter((s) => s.categoryId === category);
}

/** Songs without repeats, in the order first met. */
export function uniqueSongs(songs: YtSong[]): YtSong[] {
  const seen = new Set<string>();
  return songs.filter((s) => (seen.has(s.id) ? false : (seen.add(s.id), true)));
}

/**
 * The video ids of `playlistItems.list` items, in order, without repeats. A video that was deleted
 * or made private stays in a playlist with nothing to play; `videos.list` does not return it, so
 * it drops out when the details are read.
 */
export function playlistVideoIds(items: unknown): string[] {
  if (!Array.isArray(items)) return [];
  const out: string[] = [];
  for (const it of items) {
    const id = it?.contentDetails?.videoId ?? it?.snippet?.resourceId?.videoId;
    if (typeof id === 'string' && !out.includes(id)) out.push(id);
  }
  return out;
}

const PLAYLIST_ID = /^[A-Za-z0-9_-]{10,64}$/;

/**
 * A playlist id the app may ask for: YouTube's playlist and album ids. The account's two-letter
 * automatic lists (its likes, "LL") fall short of the length: the likes are a part of the library
 * of their own.
 */
export function playlistId(raw: unknown): string | null {
  return typeof raw === 'string' && PLAYLIST_ID.test(raw) ? raw : null;
}

export type MusicSearchKind = 'all' | 'songs' | 'artists' | 'playlists';

export function musicSearchKind(raw: unknown): MusicSearchKind {
  return raw === 'songs' || raw === 'artists' || raw === 'playlists' ? raw : 'all';
}

/**
 * The `search.list` parameters for a kind of music search. Only a search for videos alone can be
 * held to the music category (the API takes `videoCategoryId` only with `type=video`); "all" asks
 * for every type in one call, and its videos are filtered to music afterwards, from their details.
 * Channels and playlists cannot be filtered to music at all.
 */
export function musicSearchParams(q: string, kind: MusicSearchKind, category: string | null): Record<string, string> {
  const base: Record<string, string> = { part: 'snippet', q, maxResults: '25', regionCode: 'IL', safeSearch: 'moderate' };
  if (kind === 'songs') return category ? { ...base, type: 'video', videoCategoryId: category } : { ...base, type: 'video' };
  if (kind === 'artists') return { ...base, type: 'channel', maxResults: '15' };
  if (kind === 'playlists') return { ...base, type: 'playlist', maxResults: '20' };
  return { ...base, type: 'video,channel,playlist' };
}

/** What a library request may ask for: the account's own playlists, or its liked songs. */
export type LibraryPart = 'playlists' | 'liked';

export function libraryPart(raw: unknown): LibraryPart | null {
  return raw === 'playlists' || raw === 'liked' ? raw : null;
}

/** How many liked videos are read at most (four pages of 50); the API itself stops at 1,000. */
export const LIKED_MAX = 200;
/** How many items of a playlist are read at most (four pages of 50). */
export const PLAYLIST_MAX = 200;
