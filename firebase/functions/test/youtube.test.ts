import { describe, expect, it } from 'vitest';
import {
  channelFrom,
  channelIds,
  chunks,
  mergeFeed,
  normalizeQuery,
  parseChannelInput,
  parseDuration,
  quotaDay,
  searchKind,
  searchParams,
  sortChannel,
  videoFrom,
  type YtVideo,
} from '../src/youtube/model';
import { configured } from '../src/youtube/link';

const ID = 'UCabcdefghijklmnopqrstuv';

describe('a channel typed or pasted', () => {
  it('is read from an ID, a handle or an address', () => {
    expect(parseChannelInput(ID)).toEqual({ kind: 'id', value: ID });
    expect(parseChannelInput('@kannews')).toEqual({ kind: 'handle', value: '@kannews' });
    expect(parseChannelInput('kannews')).toEqual({ kind: 'handle', value: '@kannews' });
    expect(parseChannelInput(`https://www.youtube.com/channel/${ID}`)).toEqual({ kind: 'id', value: ID });
    expect(parseChannelInput('youtube.com/@kannews/videos')).toEqual({ kind: 'handle', value: '@kannews' });
    expect(parseChannelInput('https://m.youtube.com/@kannews?si=x')).toEqual({ kind: 'handle', value: '@kannews' });
  });

  it('is not a video link, a phrase or anything else', () => {
    expect(parseChannelInput('https://www.youtube.com/watch?v=abc')).toBeNull();
    expect(parseChannelInput('כאן חדשות שידור חי')).toBeNull();
    expect(parseChannelInput('')).toBeNull();
    expect(parseChannelInput(42)).toBeNull();
  });
});

describe('durations', () => {
  it('are read from ISO-8601', () => {
    expect(parseDuration('PT1H2M3S')).toBe(3723);
    expect(parseDuration('PT45S')).toBe(45);
    expect(parseDuration('P1DT1M')).toBe(86460);
  });
  it('are null for a live stream or nonsense', () => {
    expect(parseDuration('P0D')).toBeNull();
    expect(parseDuration('soon')).toBeNull();
    expect(parseDuration(undefined)).toBeNull();
  });
});

const item = (id: string, over: Record<string, unknown> = {}, live = 'none') => ({
  id,
  snippet: { title: `t-${id}`, channelId: ID, channelTitle: 'Kan', publishedAt: '2026-11-09T10:00:00Z', liveBroadcastContent: live, thumbnails: { medium: { url: `m-${id}` } } },
  contentDetails: { duration: 'PT10M' },
  ...over,
});

describe('videos', () => {
  it('carry what the app draws', () => {
    const v = videoFrom(item('a'))!;
    expect(v).toMatchObject({ id: 'a', title: 't-a', channelId: ID, thumb: 'm-a', duration: 600, live: false, upcoming: false, embeddable: true });
    expect(v.publishedAt).toBe(Date.parse('2026-11-09T10:00:00Z'));
  });

  it('that are live have viewers and no length', () => {
    const v = videoFrom(item('b', { liveStreamingDetails: { concurrentViewers: '1234', actualStartTime: '2026-11-09T09:00:00Z' } }, 'live'))!;
    expect(v.live).toBe(true);
    expect(v.duration).toBeNull();
    expect(v.viewers).toBe(1234);
    expect(v.startedAt).toBe(Date.parse('2026-11-09T09:00:00Z'));
  });

  it('say when their owner allows them only on YouTube', () => {
    expect(videoFrom(item('c', { status: { embeddable: false } }))!.embeddable).toBe(false);
  });

  it('without an id or snippet are dropped', () => {
    expect(videoFrom({})).toBeNull();
  });
});

describe('channels', () => {
  it('hide a hidden subscriber count, and keep only a real handle', () => {
    const c = channelFrom({ id: ID, snippet: { title: 'Kan', customUrl: '@kannews' }, statistics: { subscriberCount: '10', hiddenSubscriberCount: true, videoCount: '5' }, contentDetails: { relatedPlaylists: { uploads: 'UU1' } } })!;
    expect(c).toMatchObject({ id: ID, handle: '@kannews', subscribers: null, videos: 5, uploads: 'UU1' });
    expect(channelFrom({ id: ID, snippet: { title: 'x', customUrl: 'oldname' } })!.handle).toBeNull();
  });

  it('sort live, announced and recent', () => {
    const ch = channelFrom({ id: ID, snippet: { title: 'Kan' } })!;
    const live1 = videoFrom(item('l1', { liveStreamingDetails: { concurrentViewers: '5' } }, 'live'))!;
    const live2 = videoFrom(item('l2', { liveStreamingDetails: { concurrentViewers: '50' } }, 'live'))!;
    const soon = videoFrom(item('u', { liveStreamingDetails: { scheduledStartTime: '2026-11-10T10:00:00Z' } }, 'upcoming'))!;
    const old = videoFrom(item('o', { snippet: { ...item('o').snippet, publishedAt: '2026-11-01T00:00:00Z' } }))!;
    const fresh = videoFrom(item('f'))!;
    const s = sortChannel(ch, [old, live1, soon, fresh, live2]);
    expect(s.live.map((v) => v.id)).toEqual(['l2', 'l1']);
    expect(s.upcoming.map((v) => v.id)).toEqual(['u']);
    expect(s.recent.map((v) => v.id)).toEqual(['f', 'o']);
  });

  it('merge into a feed, live first, then newest', () => {
    const v = (id: string, at: number, live = false): YtVideo => ({ id, title: id, channelId: ID, channelTitle: '', thumb: null, publishedAt: at, duration: null, live, upcoming: false, startedAt: null, scheduledAt: null, viewers: null, embeddable: true });
    const ch = channelFrom({ id: ID, snippet: { title: 'Kan' } })!;
    const feed = mergeFeed([{ channel: ch, live: [v('L', 0, true)], upcoming: [], recent: [v('a', 1)] }, { channel: ch, live: [], upcoming: [], recent: [v('b', 3), v('c', 2)] }], 3);
    expect(feed.map((x) => x.id)).toEqual(['L', 'b', 'c']);
  });
});

describe('requests', () => {
  it('take clean channel ids, without repeats', () => {
    expect(channelIds([ID, ID, 'nope', 3])).toEqual([ID]);
    expect(channelIds('x')).toEqual([]);
  });
  it('are split into fifties', () => {
    expect(chunks(Array.from({ length: 120 }, (_, i) => i)).map((c) => c.length)).toEqual([50, 50, 20]);
  });
  it('normalize a search phrase', () => {
    expect(normalizeQuery('  כאן   חדשות ')).toBe('כאן חדשות');
    expect(normalizeQuery('a')).toBeNull();
  });
  it('build each kind of search', () => {
    expect(searchKind('live')).toBe('live');
    expect(searchKind('bogus')).toBe('all');
    expect(searchParams('x', 'live')).toMatchObject({ type: 'video', eventType: 'live' });
    expect(searchParams('x', 'all').type).toBe('channel,video');
  });
  it('count quota on the Pacific day', () => {
    // 06:00 UTC on the 10th is still the 9th in California.
    expect(quotaDay(Date.parse('2026-11-10T06:00:00Z'))).toBe('2026-11-09');
  });
  it('treat an unset OAuth client as not configured', () => {
    expect(configured({ id: 'unset', secret: 'unset' })).toBe(false);
    expect(configured({ id: 'a', secret: '' })).toBe(false);
    expect(configured({ id: 'a', secret: 'b' })).toBe(true);
  });
});
