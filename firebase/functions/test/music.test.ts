import { describe, expect, it } from 'vitest';
import { canRate, canRead, RATE_SCOPE, SCOPE, SCOPES } from '../src/youtube/link';
import {
  artistFrom,
  isAlbumId,
  listsOfKind,
  videoId,
  artistName,
  libraryPart,
  listFrom,
  musicCategoryId,
  musicSearchKind,
  musicSearchParams,
  onlyMusic,
  playlistId,
  playlistVideoIds,
  songFrom,
  uniqueSongs,
  type YtSong,
} from '../src/youtube/musicModel';

const video = (id: string, over: Record<string, unknown> = {}) => ({
  id,
  snippet: {
    title: `song-${id}`,
    channelId: 'UCabcdefghijklmnopqrstuv',
    channelTitle: 'Some Artist - Topic',
    categoryId: '10',
    liveBroadcastContent: 'none',
    thumbnails: { medium: { url: `m-${id}` }, high: { url: `h-${id}` } },
  },
  contentDetails: { duration: 'PT3M42S' },
  status: { embeddable: true },
  ...over,
});

describe('artists', () => {
  it('lose the " - Topic" of their automatic channel', () => {
    expect(artistName('Some Artist - Topic')).toBe('Some Artist');
    expect(artistName('Kan')).toBe('Kan');
    expect(artistName(undefined)).toBe('');
  });
  it('are read from a channel search hit', () => {
    expect(artistFrom({ id: { kind: 'youtube#channel', channelId: 'UCx' }, snippet: { title: 'A - Topic', thumbnails: { default: { url: 'd' } } } })).toEqual({ id: 'UCx', title: 'A', thumb: 'd' });
    expect(artistFrom({ id: {} })).toBeNull();
  });
});

describe('songs', () => {
  it('carry what the tab draws', () => {
    expect(songFrom(video('a'))).toEqual({
      id: 'a', title: 'song-a', artist: 'Some Artist', channelId: 'UCabcdefghijklmnopqrstuv',
      thumb: 'h-a', duration: 222, embeddable: true, categoryId: '10',
    });
  });
  it('are not live streams or announced ones', () => {
    expect(songFrom(video('b', { snippet: { ...video('b').snippet, liveBroadcastContent: 'live' } }))).toBeNull();
    expect(songFrom(video('c', { snippet: { ...video('c').snippet, liveBroadcastContent: 'upcoming' } }))).toBeNull();
    expect(songFrom({ id: 'x' })).toBeNull();
  });
  it('say when they may not be embedded', () => {
    expect(songFrom(video('d', { status: { embeddable: false } }))?.embeddable).toBe(false);
  });
  it('are kept to music when the category is known, and all kept when it is not', () => {
    const songs = [songFrom(video('a'))!, songFrom(video('n', { snippet: { ...video('n').snippet, categoryId: '25' } }))!];
    expect(onlyMusic(songs, '10').map((s) => s.id)).toEqual(['a']);
    expect(onlyMusic(songs, null).map((s) => s.id)).toEqual(['a', 'n']);
  });
  it('appear once each', () => {
    const a = songFrom(video('a')) as YtSong;
    expect(uniqueSongs([a, a, songFrom(video('b'))!]).map((s) => s.id)).toEqual(['a', 'b']);
  });
});

describe('the music category', () => {
  it('is the one YouTube titles Music', () => {
    expect(musicCategoryId([{ id: '1', snippet: { title: 'Film & Animation' } }, { id: '10', snippet: { title: 'Music' } }])).toBe('10');
    expect(musicCategoryId([{ id: '1', snippet: { title: 'Film & Animation' } }])).toBeNull();
    expect(musicCategoryId(undefined)).toBeNull();
  });
});

describe('playlists and albums', () => {
  it('are read from playlists.list and from a search hit', () => {
    const item = { id: 'PLabcdefghij', snippet: { title: 'Mix', channelId: 'UCx', channelTitle: 'Me', thumbnails: { medium: { url: 'm' } } }, contentDetails: { itemCount: 12 }, status: { privacyStatus: 'private' } };
    expect(listFrom(item)).toEqual({ id: 'PLabcdefghij', title: 'Mix', owner: 'Me', channelId: 'UCx', thumb: 'm', count: 12, privacy: 'private', album: false });
    expect(listFrom({ id: { kind: 'youtube#playlist', playlistId: 'OLAK5uy_abcdefgh' }, snippet: { title: 'Album', channelTitle: 'Artist - Topic' } })).toMatchObject({ id: 'OLAK5uy_abcdefgh', owner: 'Artist', count: null, privacy: null, album: true });
  });
  it('give their videos in order, once each', () => {
    expect(playlistVideoIds([{ contentDetails: { videoId: 'a' } }, { snippet: { resourceId: { videoId: 'b' } } }, { contentDetails: { videoId: 'a' } }, {}])).toEqual(['a', 'b']);
    expect(playlistVideoIds(null)).toEqual([]);
  });
  it('are asked for by a playlist or album id, not the account\'s automatic lists', () => {
    expect(playlistId('PLabcdefghij_-12')).toBe('PLabcdefghij_-12');
    expect(playlistId('LL')).toBeNull();
    expect(playlistId('WL')).toBeNull();
    expect(playlistId('PL x')).toBeNull();
    expect(playlistId(42)).toBeNull();
  });
});

describe('music search', () => {
  it('holds songs alone to the music category', () => {
    expect(musicSearchParams('q', 'songs', '10')).toMatchObject({ type: 'video', videoCategoryId: '10' });
    expect(musicSearchParams('q', 'songs', null)).not.toHaveProperty('videoCategoryId');
  });
  it('asks every type in one call for "all", and one type for the rest', () => {
    expect(musicSearchParams('q', 'all', '10')).toMatchObject({ type: 'video,channel,playlist' });
    expect(musicSearchParams('q', 'all', '10')).not.toHaveProperty('videoCategoryId');
    expect(musicSearchParams('q', 'artists', '10').type).toBe('channel');
    expect(musicSearchParams('q', 'playlists', '10').type).toBe('playlist');
  });
  it('reads its kind and library part from what the app sends', () => {
    expect(musicSearchKind('songs')).toBe('songs');
    expect(libraryPart('liked')).toBe('liked');
    expect(libraryPart('albums')).toBeNull();
  });
});

describe('albums and playlists told apart', () => {
  it('by the id an album playlist has', () => {
    expect(isAlbumId('OLAK5uy_kXyz123')).toBe(true);
    expect(isAlbumId('PLabcdefghij')).toBe(false);
  });
  it('keep a search to the kind asked for', () => {
    const lists = [listFrom({ id: 'OLAK5uy_a1234567', snippet: { title: 'A' } })!, listFrom({ id: 'PLabcdefghij', snippet: { title: 'P' } })!];
    expect(listsOfKind(lists, 'albums').map((l) => l.title)).toEqual(['A']);
    expect(listsOfKind(lists, 'playlists').map((l) => l.title)).toEqual(['P']);
    expect(listsOfKind(lists, 'all')).toHaveLength(2);
    expect(musicSearchParams('q', 'albums', '10').type).toBe('playlist');
    expect(musicSearchKind('albums')).toBe('albums');
  });
});

describe('likes sent to YouTube', () => {
  it('ask for the write scope beside the read one', () => {
    expect(SCOPES.split(' ')).toEqual([SCOPE, RATE_SCOPE]);
  });
  it('need the write scope; either scope reads', () => {
    expect(canRate(`${SCOPE} ${RATE_SCOPE}`)).toBe(true);
    expect(canRate(SCOPE)).toBe(false);
    expect(canRead(SCOPE)).toBe(true);
    expect(canRead(RATE_SCOPE)).toBe(true);
    expect(canRead('email')).toBe(false);
  });
  it('are given to a video id only', () => {
    expect(videoId('dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(videoId('short')).toBeNull();
    expect(videoId(42)).toBeNull();
  });
});
