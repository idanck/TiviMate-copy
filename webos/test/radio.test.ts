import { describe, expect, it } from 'vitest';
import { radioMenu } from '../src/data/classify';
import { onShelf, shelfKey } from '../src/state/radio';
import { IDLE_MINUTES, shouldTakeOver } from '../src/ui/idleRule';

const stations = [
  { group: 'רדיו ישראלי', name: 'גלגלצ' },
  { group: 'רדיו אזורי', name: 'רדיו חיפה' },
  { group: 'רדיו אזורי', name: 'קול השפלה' },
  { group: 'מוזיקה', name: 'רדיו ירושלים 101' },
  { group: 'מוזיקה', name: 'נושמים מזרחית' },
];

describe('the radio menu (ADR-050, ui/RadioScreen.tsx)', () => {
  it('is one flat list: national, the regions from the north, other regions, then the genres', () => {
    expect(radioMenu(stations).map(shelfKey)).toEqual([
      'NATIONAL',
      'REGIONAL:NORTH',
      'REGIONAL:JERUSALEM',
      'REGIONAL:UNKNOWN',
      'GENRE:מזרחי',
    ]);
  });

  it('puts every station on its place, and a genre’s station on its genre too', () => {
    expect(onShelf(stations[0], 'NATIONAL')).toBe(true);
    expect(onShelf(stations[1], 'REGIONAL:NORTH')).toBe(true);
    expect(onShelf(stations[2], 'REGIONAL:UNKNOWN')).toBe(true);
    expect(onShelf(stations[4], 'NATIONAL')).toBe(true);
    expect(onShelf(stations[4], 'GENRE:מזרחי')).toBe(true);
    expect(onShelf(stations[0], 'GENRE:מזרחי')).toBe(false);
  });
});

describe('the app takes the screen after 25 minutes without a key (ui/idle.ts, ADR-057 option B)', () => {
  const limit = IDLE_MINUTES * 60_000;
  const t0 = 1_000_000;

  it('is 25 minutes, the owner’s figure', () => {
    expect(IDLE_MINUTES).toBe(25);
  });

  it('takes it at 25 minutes with a channel playing, not before', () => {
    expect(shouldTakeOver(t0 + limit - 1, t0, limit, true, false)).toBe(false);
    expect(shouldTakeOver(t0 + limit, t0, limit, true, false)).toBe(true);
  });

  it('leaves it alone with no television channel playing, or already full screen', () => {
    expect(shouldTakeOver(t0 + 2 * limit, t0, limit, false, false)).toBe(false);
    expect(shouldTakeOver(t0 + 2 * limit, t0, limit, true, true)).toBe(false);
  });
});
