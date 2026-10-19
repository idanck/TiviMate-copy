import { describe, expect, it } from 'vitest';
import { readBackup, stripCredentials, writeBackup } from '../src/data/backup';
import { isIsraeliNews, isIsraeliSport, isRussianStation, radioCategory, radioRegion } from '../src/data/classify';
import { indexAt, nowNext } from '../src/data/guide';
import { identityOf, parseM3u, parseShiftMinutes } from '../src/data/m3u';
import { matchChannels } from '../src/data/matcher';
import type { EpgChannel, Programme, Source } from '../src/data/model';
import { canonical, redactUrl, similarity } from '../src/data/normalize';
import { buildChannels } from '../src/data/sources';
import { parseOnScreen, parseXmltvNs, parseXmltvTime, XmltvParser, type XmltvProgramme } from '../src/data/xmltv';
import { apiUrl, enc, liveUrl, parseAccount, parseCategories, parseLiveStreams, xmltvUrl } from '../src/data/xtream';

// Fixtures follow the Android app's own tests (app/src/test/.../M3uParserTest.kt and the rest),
// so both platforms are held to the same reading of the same input.

describe('parseM3u', () => {
  it('reads the documented attributes and the header guide address', () => {
    const p = parseM3u([
      '#EXTM3U url-tvg="http://epg.example/guide.xml"',
      '#EXTINF:-1 tvg-id="bbc1" tvg-name="BBC One" tvg-logo="http://l/bbc.png" group-title="UK" tvg-chno="101",BBC One HD',
      'http://s.example/1.ts',
    ].join('\n'));
    expect(p.epgUrl).toBe('http://epg.example/guide.xml');
    expect(p.entries).toHaveLength(1);
    expect(p.entries[0]).toMatchObject({ name: 'BBC One HD', tvgId: 'bbc1', tvgLogo: 'http://l/bbc.png', groupTitle: 'UK', tvgChno: 101 });
  });

  it('keeps a comma inside the display name', () => {
    const p = parseM3u('#EXTINF:-1 group-title="A, B",Channel 4, HD\nhttp://x/4');
    expect(p.entries[0].name).toBe('Channel 4, HD');
    expect(p.entries[0].groupTitle).toBe('A, B');
  });

  it('reads EXTVLCOPT and KODIPROP into headers', () => {
    const p = parseM3u([
      '#EXTINF:-1,Ch',
      '#EXTVLCOPT:http-user-agent=VLC/3',
      '#EXTVLCOPT:http-referrer=https://ref.example/',
      '#KODIPROP:inputstream.adaptive.stream_headers=Origin=https%3A%2F%2Fo.example&X-Token=abc',
      'http://x/ch',
    ].join('\n'));
    expect(p.entries[0].userAgent).toBe('VLC/3');
    expect(p.entries[0].headers).toEqual({ Referer: 'https://ref.example/', Origin: 'https://o.example', 'X-Token': 'abc' });
  });

  it('survives an EXTINF with no URL and counts it', () => {
    const p = parseM3u('#EXTINF:-1,Lost\n#EXTINF:-1,Found\nhttp://x/found');
    expect(p.entries.map((e) => e.name)).toEqual(['Found']);
    expect(p.malformedLines).toBe(1);
  });

  it('an HTML error page is not a playlist', () => {
    const p = parseM3u('<html><body>Account suspended</body></html>');
    expect(p.entries).toHaveLength(0);
    expect(p.malformedLines).toBeGreaterThan(0);
  });

  it('handles a byte order mark and CRLF endings', () => {
    const p = parseM3u('﻿#EXTM3U\r\n#EXTINF:-1,A\r\nhttp://x/a\r\n');
    expect(p.entries[0]).toMatchObject({ name: 'A', url: 'http://x/a' });
  });

  it('derives catch-up mode and window', () => {
    const p = parseM3u('#EXTINF:-1 catchup="shift" catchup-days="3",A\nhttp://x/a\n#EXTINF:-1 timeshift="2",B\nhttp://x/b');
    expect(p.entries[0]).toMatchObject({ catchupMode: 'SHIFT', catchupDays: 3 });
    expect(p.entries[1]).toMatchObject({ catchupMode: 'DEFAULT', catchupDays: 2 });
  });

  it('parses fractional and signed tvg-shift as minutes', () => {
    expect(parseShiftMinutes('1.5')).toBe(90);
    expect(parseShiftMinutes('-2')).toBe(-120);
    expect(parseShiftMinutes('+0.5')).toBe(30);
    expect(parseShiftMinutes('junk')).toBe(0);
  });

  it('identity prefers tvg-id, then the stream id, then the name', () => {
    expect(identityOf({ tvgId: 'x.il', url: 'http://h/live/u/p/42.ts', name: 'X' })).toBe('tvg:x.il');
    expect(identityOf({ url: 'http://h/live/u/p/42.ts', name: 'X' })).toBe('sid:42');
    expect(identityOf({ url: 'http://h/x.m3u8', name: 'Kan 11 HD' })).toBe('name:kan 11');
  });

  it('reads a built-in-playlist-shaped entry with a Hebrew name and a hosted logo', () => {
    const p = parseM3u([
      '#EXTINF:-1 tvg-id="Kan11.il@SD" tvg-logo="https://il-epg.idanck.com/logos/kan11.png" group-title="ישראל",כאן 11',
      'https://kancdn.medonecdn.net/livehls/oil/kancdn-live/live/kan11/live.livx/playlist.m3u8',
    ].join('\n'));
    expect(p.entries[0]).toMatchObject({ name: 'כאן 11', tvgId: 'Kan11.il@SD', groupTitle: 'ישראל' });
  });
});

describe('buildChannels', () => {
  it('turns a repeated identity into a failover list, not a second channel', () => {
    const p = parseM3u([
      '#EXTINF:-1 tvg-id="qa.failover",QA Failover', 'http://x/gone.m3u8',
      '#EXTINF:-1 tvg-id="qa.failover",QA Failover', 'http://x/a.m3u8',
      '#EXTINF:-1 tvg-id="qa.other",Other', 'http://x/b.m3u8',
    ].join('\n'));
    const channels = buildChannels(p.entries, 's1');
    expect(channels).toHaveLength(2);
    expect(channels[0]).toMatchObject({ key: 's1|tvg:qa.failover', urls: ['http://x/gone.m3u8', 'http://x/a.m3u8'] });
  });
});

describe('normalisation', () => {
  it('drops quality tokens, numbering and punctuation', () => {
    expect(canonical('101. BBC One [HD] (Backup)')).toBe('bbc one');
    expect(canonical('Café Télé FHD')).toBe('cafe tele');
  });

  it('never scores a digit mismatch as a match', () => {
    expect(similarity('Sky Sports F1', 'Sky Sports F2')).toBeLessThanOrEqual(0.5);
    expect(similarity('Sky Sports Main Event', 'Sky Sports Main Events')).toBeGreaterThan(0.78);
    expect(similarity('', 'x')).toBe(0);
  });

  it('redacts credentials from every shape a provider URL takes', () => {
    expect(redactUrl('http://h/get.php?username=u&password=p&type=m3u')).toBe('http://h/get.php?username=***&password=***&type=m3u');
    expect(redactUrl('http://u:p@h/x')).toBe('http://***:***@h/x');
  });
});

describe('matchChannels', () => {
  const guide: EpgChannel[] = [
    { id: 'bbc.one.uk', displayNames: ['BBC One'] },
    { id: 'bbc.two.uk', displayNames: ['BBC Two'] },
    { id: 'sky.sports.main', displayNames: ['Sky Sports Main Event'] },
    { id: 'Discovery.uk', displayNames: [] },
  ];
  const one = (name: string, tvgId?: string) => matchChannels([{ key: 'k', name, tvgId }], guide).get('k')!;

  it('exact tvg-id wins', () => expect(one('Totally Different', 'BBC.ONE.UK')).toEqual({ epgId: 'bbc.one.uk', kind: 'EXACT_ID' }));
  it('matches names through quality tokens', () => expect(one('BBC Two FHD')).toEqual({ epgId: 'bbc.two.uk', kind: 'NORMALIZED_NAME' }));
  it('reads a guide id as a name', () => expect(one('Discovery HD').epgId).toBe('Discovery.uk'));
  it('leaves an unrelated channel unmatched', () => expect(one('Al Jazeera Arabic')).toEqual({ epgId: null, kind: 'NONE' }));
  it('takes a near miss only above the threshold', () => expect(one('Sky Sports Main Events')).toEqual({ epgId: 'sky.sports.main', kind: 'FUZZY' }));
  it('leaves a sibling that differs by a digit unmatched', () => {
    const r = matchChannels([{ key: 'k', name: 'Sky Sports F2' }], [{ id: 'sky.f1', displayNames: ['Sky Sports F1'] }]);
    expect(r.get('k')!.epgId).toBeNull();
  });
});

describe('XMLTV', () => {
  const doc = `<?xml version="1.0" encoding="UTF-8"?>
<tv>
<channel id="kan11.il"><display-name lang="he">כאן 11</display-name><display-name>Kan 11</display-name><icon src="https://l/k.png"/></channel>
<channel id="empty"/>
<programme start="20261019180000 +0300" stop="20261019190000 +0300" channel="kan11.il">
  <title lang="he">מבט &amp; חדשות</title><title lang="en">Mabat</title>
  <desc><![CDATA[Line <b>one</b>]]></desc>
  <episode-num system="xmltv_ns">1.4.</episode-num>
</programme>
<programme start="20261019190000" channel="kan11.il"><title>No stop</title></programme>
<programme start="20261019200000" channel="kan11.il"></programme>
</tv>`;

  it('reads channels and programmes, however the text is cut into chunks', () => {
    for (const size of [7, 64, 100_000]) {
      const channels: EpgChannel[] = [];
      const programmes: XmltvProgramme[] = [];
      const parser = new XmltvParser((c) => channels.push(c), (p) => programmes.push(p));
      for (let i = 0; i < doc.length; i += size) parser.push(doc.slice(i, i + size));
      parser.end();
      expect(channels).toEqual([
        { id: 'kan11.il', displayNames: ['כאן 11', 'Kan 11'], iconUrl: 'https://l/k.png' },
        { id: 'empty', displayNames: [], iconUrl: undefined },
      ]);
      expect(programmes).toHaveLength(2);
      expect(programmes[0]).toMatchObject({
        channel: 'kan11.il', title: 'מבט & חדשות', description: 'Line <b>one</b>', season: 2, episode: 5,
        start: Date.UTC(2026, 9, 19, 15, 0, 0), end: Date.UTC(2026, 9, 19, 16, 0, 0),
      });
      // A missing stop becomes a 30-minute slot; a programme with no title is dropped.
      expect(programmes[1].end - programmes[1].start).toBe(30 * 60_000);
    }
  });

  it('parses times with and without an offset', () => {
    expect(parseXmltvTime('20260819143000 +0300')).toBe(Date.UTC(2026, 7, 19, 11, 30));
    expect(parseXmltvTime('20260819143000')).toBe(Date.UTC(2026, 7, 19, 14, 30));
    expect(parseXmltvTime('2026')).toBeNull();
  });

  it('reads episode numbers', () => {
    expect(parseXmltvNs('0.4.0/1')).toEqual([1, 5]);
    expect(parseOnScreen('S02E05')).toEqual([2, 5]);
    expect(parseOnScreen('2x05')).toEqual([2, 5]);
  });
});

describe('guide lookups', () => {
  const p = (start: number, end: number, title: string): Programme => ({ start, end, title });
  const list = [p(0, 10, 'a'), p(10, 20, 'b'), p(30, 40, 'c')];

  it('finds what is on, and what is next across a gap', () => {
    expect(indexAt(list, 5)).toBe(0);
    expect(indexAt(list, 25)).toBe(2);
    expect(indexAt(list, 45)).toBe(-1);
    expect(nowNext(list, 12, 0)).toMatchObject({ now: { title: 'b' }, next: { title: 'c' } });
    expect(nowNext(list, 25, 0)).toEqual({ next: list[2] });
  });

  it('shifts the question and the answer by the channel offset', () => {
    const r = nowNext(list, 15, 10);
    expect(r.now).toMatchObject({ title: 'a', start: 10, end: 20 });
  });
});

describe('Xtream', () => {
  it('encodes the account as the Android app does', () => {
    expect(enc('a b&c!')).toBe('a+b%26c%21');
    expect(apiUrl('http://p.example:8080/', 'u', 'p w', 'get_live_streams')).toBe('http://p.example:8080/player_api.php?username=u&password=p+w&action=get_live_streams');
    expect(xmltvUrl('http://p.example/player_api.php', 'u', 'p')).toBe('http://p.example/xmltv.php?username=u&password=p');
    expect(liveUrl('http://p.example', 'u', 'p', '7', 'TS')).toBe('http://p.example/live/u/p/7.ts');
  });

  it('reads an account, and a refusal', () => {
    expect(parseAccount('{"user_info":{"auth":1,"status":"Active","exp_date":"1794984242","max_connections":"1"}}'))
      .toMatchObject({ auth: true, status: 'Active', expiryUtc: 1794984242000, maxConnections: 1 });
    expect(parseAccount('{"user_info":{"auth":0}}')?.auth).toBe(false);
    expect(parseAccount('<html>')).toBeNull();
  });

  it('shapes live streams like playlist entries, defensively', () => {
    const cats = parseCategories('[{"category_id":"3","category_name":"ישראל"},{"category_id":null}]');
    const entries = parseLiveStreams(
      '[{"stream_id":12,"name":"|IL| SPORT 5","epg_channel_id":"sport5.il","stream_icon":"","num":"4","category_id":"3","tv_archive":1,"tv_archive_duration":"2"},{"name":"no id"}]',
      cats,
      (id) => `http://p/live/u/p/${id}.ts`,
    );
    expect(entries).toHaveLength(1);
    expect(entries[0]).toMatchObject({ name: '|IL| SPORT 5', tvgId: 'sport5.il', tvgLogo: undefined, tvgChno: 4, groupTitle: 'ישראל', catchupMode: 'XC', catchupDays: 2 });
  });
});

describe('classifiers', () => {
  it('Israeli sport', () => {
    expect(isIsraeliSport('|IL| SPORT 1', 'ישראל')).toBe(true);
    expect(isIsraeliSport('SPORT 2 HD', 'ISRAËL| HEBREEW')).toBe(true);
    expect(isIsraeliSport('|IL| ONE 2', 'ישראל')).toBe(true);
    expect(isIsraeliSport('|IL| ZONE HD', 'ישראל')).toBe(false);
    expect(isIsraeliSport('|IL| CHANNEL ONE', 'ישראל')).toBe(false);
    expect(isIsraeliSport('|IL| 5+ LIVE', 'ישראל')).toBe(true);
    expect(isIsraeliSport('|UK| SKY SPORTS F1', 'ספורט | אנגליה', 'SkySportsF1.uk')).toBe(false);
    expect(isIsraeliSport('Sport 5', undefined, 'sport5.il')).toBe(true);
    expect(isIsraeliSport('ILLUMINATE SPORTS', undefined)).toBe(false);
    expect(isIsraeliSport('|IL| ˢᴾᴼᴿᵀ 1 ᴴᴰ', 'ישראל')).toBe(true);
  });

  it('Israeli news', () => {
    for (const n of ['|IL| CHANNEL 12', '|IL| I24', '|IL| HOT Reshet 13']) expect(isIsraeliNews(n, 'ISRAËL| HEBREEW')).toBe(true);
    for (const n of ['כאן 11', 'קשת 12', 'ערוץ 14', 'איי 24 ניוז']) expect(isIsraeliNews(n, 'ישראל')).toBe(true);
    for (const n of ['|IL| CHANNEL 9', 'ערוץ 9', 'ספורט 1', 'קשת 120']) expect(isIsraeliNews(n, 'ישראל')).toBe(false);
    expect(isIsraeliNews('BBC News', 'UK')).toBe(false);
    expect(isIsraeliNews('Something', undefined, 'il.i24')).toBe(true);
  });

  it('radio shelves and regions', () => {
    expect(radioCategory('רדיו ישראלי', 'גלגלצ')).toEqual({ kind: 'NATIONAL' });
    expect(radioCategory('רדיו אזורי', 'רדיו חיפה')).toEqual({ kind: 'REGIONAL' });
    expect(radioCategory('רדיו ישראלי', 'רדיו מזרחי')).toEqual({ kind: 'GENRE', label: 'מזרחי' });
    expect(radioCategory('משהו', 'X')).toEqual({ kind: 'OTHER' });
    expect(radioRegion('104.5 רדיו צפון')).toBe('NORTH');
    expect(radioRegion('רדיו לב המדינה')).toBe('CENTER');
    expect(radioRegion('רדיו ירושלים')).toBe('JERUSALEM');
    expect(radioRegion('רדיו דרום')).toBe('SOUTH');
    expect(radioRegion('רדיו כלשהו')).toBe('UNKNOWN');
    expect(isRussianStation('Радио', undefined)).toBe(true);
    expect(isRussianStation('רדיו חיפה', 'רדיו אזורי')).toBe(false);
  });
});

describe('backup', () => {
  const android = JSON.stringify({
    format: 1,
    includesCredentials: true,
    playlists: [
      { id: 1, name: 'ערוצים פתוחים בישראל', type: 'M3U_URL', url: 'https://il-epg.idanck.com/channels.m3u', enabled: true, sortIndex: 0 },
      { id: 2, name: 'Provider', type: 'XTREAM', url: 'http://panel.example:8080', username: 'u', password: 'p', outputFormat: 'TS', headers: {}, enabled: true, sortIndex: 1 },
      { id: 3, name: 'File', type: 'M3U_FILE', url: 'content://x', enabled: true, sortIndex: 2 },
    ],
    channels: [
      { playlistId: 2, identity: 'sid:12', favourite: true },
      { playlistId: 1, identity: 'tvg:Kan11.il@SD', favourite: true },
      { playlistId: 2, identity: 'sid:13', favourite: false },
    ],
    settings: { epg_offset: 60, user_agent: 'VLC/3.0.20 LibVLC/3.0.20' },
  });

  it('reads an Android backup: sources, favourites and the two settings that apply here', () => {
    const r = readBackup(android, []);
    expect(r.sources).toHaveLength(1);
    expect(r.sources[0]).toMatchObject({ type: 'XTREAM', url: 'http://panel.example:8080', username: 'u', password: 'p', role: 'tv' });
    expect(r.favourites).toEqual([`${r.sources[0].id}|sid:12`, 'builtin-israel-tv|tvg:Kan11.il@SD']);
    expect(r).toMatchObject({ epgOffsetMinutes: 60, userAgent: 'VLC/3.0.20 LibVLC/3.0.20', skipped: 1, missingCredentials: [] });
  });

  it('does not add a source it already has', () => {
    const first = readBackup(android, []);
    expect(readBackup(android, first.sources).sources).toHaveLength(0);
  });

  it('writes a file the reader (and the Android app) takes back', () => {
    const source: Source = {
      id: 'src-a', name: 'Provider', type: 'XTREAM', role: 'tv', url: 'http://panel.example', username: 'u', password: 'p',
      outputFormat: 'TS', headers: {}, enabled: true, sortIndex: 1,
      epgUrl: 'http://panel.example/xmltv.php?username=u&password=p',
    };
    const without = JSON.parse(writeBackup([source], ['src-a|sid:1'], { epgOffsetMinutes: 0 }, false));
    expect(without.playlists[0].username).toBeUndefined();
    expect(without.playlists[0].epgUrl).toBeNull();
    const back = readBackup(writeBackup([source], ['src-a|sid:1'], { epgOffsetMinutes: 0 }, true), []);
    expect(back.sources[0]).toMatchObject({ username: 'u', password: 'p' });
    expect(back.favourites).toEqual([`${back.sources[0].id}|sid:1`]);
  });

  it('strips credentials only where there are some', () => {
    expect(stripCredentials('http://h/get.php?username=u&password=p&type=m3u')).toBe('http://h/get.php?type=m3u');
    expect(stripCredentials('http://h')).toBe('http://h');
  });
});

