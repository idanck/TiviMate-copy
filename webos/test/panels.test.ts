import { signal } from '@preact/signals';
import { describe, expect, it } from 'vitest';
import { newsStation } from '../src/data/classify';
import type { Channel } from '../src/data/model';
import { clean, FeedError, mergeFlashes, parseDate, parseFeed, shorten } from '../src/data/news';
import type { Panel, PanelState } from '../src/player/panel';
import { audibleSlot, choicesFor, FLASHES_ID, PanelSession, type Mode } from '../src/state/panels';
import { cells, keptPanels } from '../src/ui/panelLayout';

const channel = (key: string, name = key, sourceId = 'p', extra: Partial<Channel> = {}): Channel => ({
  key: `${sourceId}|${key}`,
  sourceId,
  identity: key,
  name,
  group: 'ישראל',
  urls: [`http://example.com/${key}.ts`],
  headers: {},
  tvgShiftMinutes: 0,
  catchupMode: 'NONE',
  catchupDays: 0,
  order: 0,
  ...extra,
});

class FakePanel implements Panel {
  readonly state = signal<PanelState>({ status: 'idle' });
  playing: Channel | null = null;
  audible = false;
  plays = 0;
  constructor(readonly id: number) {}
  play(c: Channel) {
    this.playing = c;
    this.plays++;
    this.state.value = { status: 'playing' };
  }
  release() {
    this.playing = null;
    this.state.value = { status: 'idle' };
  }
  setAudible(a: boolean) {
    this.audible = a;
  }
  setPaused() {}
}

function session() {
  const persisted: Array<[Mode, Array<string | null>]> = [];
  const s = new PanelSession({ createPanel: (id) => new FakePanel(id), persist: (mode, slots) => persisted.push([mode, slots]) });
  return { s, persisted, panel: (i: number) => s.slots.value[i].panel as FakePanel };
}

describe('the panels’ layout (ui/panelLayout.ts)', () => {
  it('gives the panel in focus 58% of the width, side by side, with the gap between', () => {
    const [a, b] = cells(1000, 500, 0, false, 4, 2);
    expect(a).toEqual({ x: 0, y: 0, width: 578, height: 500 });
    expect(b.x).toBe(582);
    expect(a.width + b.width + 4).toBe(1000);
  });

  it('moves the lines toward the panels not in focus in a grid of four', () => {
    const grid = cells(1000, 500, 3, false, 4, 4);
    expect(grid[3].width).toBeGreaterThan(grid[0].width);
    expect(grid[3].height).toBeGreaterThan(grid[0].height);
    expect(grid[1].x).toBe(grid[3].x);
  });

  it('fills the screen with the panel in focus and measures the others as nothing', () => {
    const full = cells(1000, 500, 1, true, 4, 2);
    expect(full[1]).toEqual({ x: 0, y: 0, width: 1000, height: 500 });
    expect(full[0].width).toBe(0);
  });

  it('keeps the panel in focus and the next filled ones when going down to two', () => {
    expect(keptPanels([true, false, true, true], 2, 2)).toEqual([2, 3]);
    expect(keptPanels([false, false, false, true], 0, 2)).toEqual([0, 3]);
    expect(keptPanels([false, false, false, false], 1, 2)).toEqual([1, 2]);
  });
});

describe('Sport and News mode’s session (state/panels.ts)', () => {
  const a = channel('a');
  const b = channel('b');
  const c = channel('c');

  it('opens the picker on the first panel when there is nothing to come back to', () => {
    const { s } = session();
    const opening = s.begin('sport', 2);
    s.offer(opening, [a, b], []);
    expect(s.picking.value).toBe(0);
    expect(s.slots.value[1].flashes).toBe(false);
  });

  it('brings a remembered grid back and gives the sound to the panel in focus', () => {
    const { s, panel } = session();
    s.offer(s.begin('sport', 2), [a, b], [a, b]);
    expect(panel(0).playing).toBe(a);
    expect(panel(1).playing).toBe(b);
    expect(panel(0).audible).toBe(true);
    s.focus(1);
    expect(panel(0).audible).toBe(false);
    expect(panel(1).audible).toBe(true);
  });

  it('swaps two panels, players and all, rather than opening the same stream twice', () => {
    const { s, panel } = session();
    s.offer(s.begin('sport', 2), [a, b], [a, b]);
    const first = panel(0);
    s.assign(1, a);
    expect(s.slots.value[1].channel).toBe(a);
    expect(s.slots.value[1].panel).toBe(first);
    expect(first.plays).toBe(1);
  });

  it('remembers the grid by channel key', () => {
    const { s, persisted } = session();
    s.offer(s.begin('sport', 2), [a, b], []);
    s.assign(0, b);
    expect(persisted.at(-1)).toEqual(['sport', [b.key, null, null, null]]);
  });

  it('puts the headlines in the second panel of a new News grid, and leaves the sound with the picture', () => {
    const { s, persisted } = session();
    s.offer(s.begin('news', 2), [a], []);
    expect(s.slots.value[1].flashes).toBe(true);
    s.assign(0, a);
    s.focus(1);
    expect(audibleSlot(s.slots.value, 1)).toBe(0);
    expect(persisted.at(-1)?.[1]).toEqual([a.key, FLASHES_ID, null, null]);
  });

  it('steps the panel with the remote over channels already on screen', () => {
    const { s } = session();
    s.offer(s.begin('sport', 2), [a, b, c], [a, b]);
    s.step(1);
    expect(s.slots.value[0].channel).toBe(c);
    s.step(1);
    expect(s.slots.value[0].channel).toBe(a);
  });

  it('goes from four panels to two keeping what is watched, and lets the rest go', () => {
    const { s, panel } = session();
    s.offer(s.begin('news', 4), [a, b, c], [a, null, b, c], -1);
    const watched = panel(2);
    s.focus(2);
    s.setPanels(2);
    expect(s.count.value).toBe(2);
    expect(s.slots.value.slice(0, 2).map((x) => x.channel)).toEqual([b, c]);
    expect(s.slots.value[0].panel).toBe(watched);
    expect(s.slots.value.slice(2).every((x) => x.channel === null)).toBe(true);
  });

  it('lets every panel go when the mode is left', () => {
    const { s, panel } = session();
    s.offer(s.begin('sport', 2), [a, b], [a, b]);
    s.close();
    expect(panel(0).playing).toBeNull();
    expect(panel(1).playing).toBeNull();
    expect(s.focusedChannel()).toBeNull();
  });

  it('ignores an offer meant for an earlier opening', () => {
    const { s } = session();
    const first = s.begin('sport', 2);
    s.close();
    s.begin('news', 2);
    s.offer(first, [a], [a]);
    expect(s.choices.value).toBeNull();
  });
});

describe('what each mode offers', () => {
  const free = new Set(['builtin-israel-tv']);
  const kan = channel('kan11', 'כאן 11', 'builtin-israel-tv', { tvgId: 'Kan11.il@SD' });
  const providerKan = channel('pkan', '|IL| CHANNEL 11', 'p', { tvgId: 'il.channel-11' });
  const i24en = channel('i24en', 'I24 NEWS ENGLISH', 'p', { tvgId: '' });
  const sport5 = channel('s5', 'ספורט 5', 'p');

  it('prefers the free-to-air copy of a station and replaces the provider’s', () => {
    const { choices, replacedBy } = choicesFor('news', [providerKan, kan, i24en, sport5], free);
    expect(choices).toEqual([kan, i24en]);
    expect(replacedBy.get(providerKan.key)).toBe(kan);
  });

  it('offers Israeli sports channels in Sport mode', () => {
    expect(choicesFor('sport', [providerKan, sport5], free).choices).toEqual([sport5]);
  });

  it('knows a station by its guide id, then its name', () => {
    expect(newsStation('anything', 'il.channel-20')).toBe('14');
    expect(newsStation('קשת 12')).toBe('12');
    expect(newsStation('HOT קשת 12')).toBeUndefined();
  });
});

describe('the headlines (data/news.ts)', () => {
  const nowUtc = Date.UTC(2026, 10, 5, 12, 0, 0);

  it('reads RSS, its CDATA and its dates', () => {
    const feed = parseFeed(`<?xml version="1.0"?><rss><channel><title>ynet - חדשות</title>
      <item><title><![CDATA[כותרת &amp; עוד]]></title><guid>1</guid><pubDate>Thu, 05 Nov 2026 13:30:00 +0200</pubDate></item>
      <item><title>שנייה</title><link>https://example.com/2</link></item>
    </channel></rss>`, nowUtc);
    expect(feed.title).toBe('ynet - חדשות');
    expect(feed.items[0]).toEqual({ id: '1', title: 'כותרת & עוד', publishedUtc: Date.UTC(2026, 10, 5, 11, 30) });
    expect(feed.items[1].id).toBe('https://example.com/2');
  });

  it('reads Atom without taking an entry’s source title for its own', () => {
    const feed = parseFeed(`<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title>
      <entry><source><title>Other</title></source><title type="html">&lt;b&gt;Bold&lt;/b&gt; news</title><id>x</id>
      <link href="https://example.com/x"/><updated>2026-11-05T10:00:00Z</updated></entry></feed>`, nowUtc);
    expect(feed.items[0]).toEqual({ id: 'x', title: 'Bold news', publishedUtc: Date.UTC(2026, 10, 5, 10) });
  });

  it('reads a JSON list, and a timestamp in seconds', () => {
    const feed = parseFeed(JSON.stringify([{ title: 'a', timestamp: 1793880000 }, { headline: 'b' }]), nowUtc);
    expect(feed.items.map((i) => i.title)).toEqual(['a', 'b']);
    expect(feed.items[0].publishedUtc).toBe(1793880000_000);
  });

  it('refuses an HTML page', () => {
    expect(() => parseFeed('<!DOCTYPE html><html><body>hi</body></html>')).toThrow(FeedError);
  });

  it('knows RFC 822 zone names', () => {
    expect(parseDate('Thu, 05 Nov 2026 10:00:00 GMT')).toBe(Date.UTC(2026, 10, 5, 10));
    expect(parseDate('5 Nov 2026 13:00 IDT')).toBe(Date.UTC(2026, 10, 5, 10));
    expect(parseDate('nonsense')).toBeUndefined();
  });

  it('cleans markup and double escaping', () => {
    expect(clean('a &lt;i&gt;b&lt;/i&gt;   c &#x5D0;')).toBe('a b c א');
    expect(clean('inflation < 3%')).toBe('inflation < 3%');
  });

  it('cuts a source’s name before its slogan', () => {
    expect(shorten('ynet - חדשות, כלכלה, ספורט')).toBe('ynet');
  });

  it('merges sources newest first, drops the stale and the repeated', () => {
    const sources = [{ url: 'https://a.example', enabled: true }, { url: 'https://b.example', enabled: true }];
    const merged = mergeFlashes(sources, {
      'https://a.example': { title: 'A', fetchedUtc: nowUtc, items: [
        { id: '1', title: 'old', publishedUtc: nowUtc - 2 * 86_400_000 },
        { id: '2', title: 'Same', publishedUtc: nowUtc - 60_000 },
      ] },
      'https://b.example': { title: 'B', fetchedUtc: nowUtc, items: [
        { id: '3', title: 'same', publishedUtc: nowUtc - 120_000 },
        { id: '4', title: 'newest', publishedUtc: nowUtc - 1_000 },
        { id: '5', title: 'undated' },
      ] },
    }, nowUtc);
    expect(merged.map((m) => m.flash.title)).toEqual(['newest', 'Same', 'undated']);
    expect(merged[0].colourIndex).toBe(1);
  });
});
