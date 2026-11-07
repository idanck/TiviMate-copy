import { describe, expect, it } from 'vitest';
import type { Channel, Source } from '../src/data/model';
import { maskAddress, maskChannels, unmaskAddress, unmaskChannels } from '../src/data/vault';
import { liveUrl, xmltvUrl } from '../src/data/xtream';
import { distance, pick, type Box } from '../src/ui/spatial';

const box = (left: number, top: number, w = 100, h = 40): Box => ({ left, top, right: left + w, bottom: top + h });

describe('the remote’s arrows (ui/spatial.ts)', () => {
  const from = box(100, 100);

  it('goes to the control below, not one off to the side that is nearer', () => {
    const below = box(100, 200);
    const aside = box(260, 150);
    expect(pick(from, [aside, below], 'down')).toBe(1);
  });

  it('never goes backwards', () => {
    expect(distance(from, box(100, 20), 'down')).toBe(Infinity);
    expect(distance(from, box(260, 100), 'left')).toBe(Infinity);
    expect(pick(from, [box(100, 20)], 'down')).toBe(-1);
  });

  it('goes sideways along a row', () => {
    const left = box(-60, 100);
    const right = box(260, 100);
    expect(pick(from, [left, right], 'right')).toBe(1);
    expect(pick(from, [left, right], 'left')).toBe(0);
  });

  it('prefers the nearer of two controls in line', () => {
    expect(pick(from, [box(100, 400), box(100, 160)], 'down')).toBe(1);
  });

  it('reaches a control in the next column even when it is not level', () => {
    expect(pick(from, [box(300, 130)], 'right')).toBe(0);
  });
});

describe('Xtream passwords stay out of stored addresses (data/vault.ts)', () => {
  const source = {
    id: 's', name: 'x', type: 'XTREAM', role: 'tv', url: 'http://panel.example:8080', username: 'me',
    password: 'p@ss word/1', outputFormat: 'TS', headers: {}, enabled: true, sortIndex: 0,
  } as Source;
  const live = liveUrl(source.url, 'me', source.password!, '42', 'TS');
  const guide = xmltvUrl(source.url, 'me', source.password!);

  it('masks the password in a live address and the guide address, and fills it back', () => {
    for (const url of [live, guide]) {
      const masked = maskAddress(url, source);
      expect(masked).not.toContain('p%40ss');
      expect(masked).toContain('{xc-secret}');
      expect(unmaskAddress(masked, source)).toBe(url);
    }
  });

  it('masks every address of every channel', () => {
    const channels = [{ key: 'k', urls: [live, live.replace('42', '43')] } as Channel];
    const masked = maskChannels(channels, source);
    expect(JSON.stringify(masked)).not.toContain('p%40ss');
    expect(unmaskChannels(masked, source)).toEqual(channels);
  });

  it('leaves other sources alone', () => {
    const m3u = { ...source, type: 'M3U_URL', password: undefined } as Source;
    const channels = [{ key: 'k', urls: ['http://example.com/a.ts'] } as Channel];
    expect(maskChannels(channels, m3u)).toBe(channels);
  });
});
