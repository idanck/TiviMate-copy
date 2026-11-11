import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import {
  authHeaders,
  categoryParam,
  configured,
  HEBREW,
  hebrewFirst,
  languageParam,
  normalizeQuery,
  podcastFrom,
  podcastsFrom,
} from '../src/podcasts/model';

describe('Podcast Index authentication', () => {
  it('signs with the SHA-1 of key, secret and Unix seconds', () => {
    const h = authHeaders('KEY', 'SECRET', 1_794_196_800_123, 'Shidur/1.0');
    expect(h['X-Auth-Date']).toBe('1794196800');
    expect(h['X-Auth-Key']).toBe('KEY');
    expect(h.Authorization).toBe(createHash('sha1').update('KEYSECRET1794196800').digest('hex'));
    expect(h['User-Agent']).toBe('Shidur/1.0');
  });

  it('is not configured while a secret is the placeholder', () => {
    expect(configured('unset', 'x')).toBe(false);
    expect(configured('k', 'unset')).toBe(false);
    expect(configured('', 's')).toBe(false);
    expect(configured('k', 's')).toBe(true);
  });
});

describe('what the app may ask for', () => {
  it('a phrase of two letters or more, folded', () => {
    expect(normalizeQuery('  היסטוריה   של  ')).toBe('היסטוריה של');
    expect(normalizeQuery('a')).toBeNull();
    expect(normalizeQuery(42)).toBeNull();
    expect(normalizeQuery('x'.repeat(300))?.length).toBe(100);
  });

  it('Hebrew unless another language is named', () => {
    expect(languageParam(undefined)).toBe(HEBREW);
    expect(languageParam('en')).toBe('en');
    expect(languageParam('EN,he')).toBe('en,he');
    expect(languageParam('any')).toBe('');
    expect(languageParam('en;drop table')).toBe(HEBREW);
  });

  it('a category is a plain name', () => {
    expect(categoryParam('History')).toBe('History');
    expect(categoryParam('Society & Culture')).toBe('Society & Culture');
    expect(categoryParam('<script>')).toBeNull();
    expect(categoryParam(7)).toBeNull();
  });
});

describe('what the app is given', () => {
  const feed = {
    id: 920666,
    title: 'הסכת לדוגמה',
    url: 'https://example.com/feed.xml',
    author: 'רשת',
    image: 'https://example.com/small.jpg',
    artwork: 'https://example.com/large.jpg',
    language: 'he-IL',
    categories: { 77: 'Society', 78: 'Culture' },
    description: 'd'.repeat(800),
    episodeCount: 142,
    newestItemPublishTime: 1_794_196_800,
  };

  it('keeps a feed with an address and a title', () => {
    const p = podcastFrom(feed);
    expect(p).toEqual({
      id: 920666,
      title: 'הסכת לדוגמה',
      author: 'רשת',
      image: 'https://example.com/large.jpg',
      feedUrl: 'https://example.com/feed.xml',
      language: 'he-il',
      categories: ['Society', 'Culture'],
      description: 'd'.repeat(600),
      episodeCount: 142,
      newestUtc: 1_794_196_800_000,
    });
  });

  it('drops one without an address, a title or an id, or with an address that is not the web', () => {
    expect(podcastFrom({ ...feed, url: '' , originalUrl: undefined })).toBeNull();
    expect(podcastFrom({ ...feed, title: ' ' })).toBeNull();
    expect(podcastFrom({ ...feed, id: undefined })).toBeNull();
    expect(podcastFrom({ ...feed, url: 'ftp://example.com/feed' })).toBeNull();
    expect(podcastFrom(null)).toBeNull();
  });

  it('lists each address once, up to the limit, and puts Hebrew first', () => {
    const en = { ...feed, id: 2, url: 'https://example.com/en.xml', language: 'en' };
    const list = podcastsFrom({ feeds: [en, feed, { ...feed, id: 3 }, 'junk'] }, 10);
    expect(list.map((p) => p.id)).toEqual([2, 920666]);
    expect(hebrewFirst(list).map((p) => p.id)).toEqual([920666, 2]);
    expect(podcastsFrom({ feeds: [en, feed] }, 1)).toHaveLength(1);
    expect(podcastsFrom({}, 10)).toEqual([]);
  });
});
