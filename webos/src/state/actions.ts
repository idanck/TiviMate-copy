import { computed, effect, signal } from '@preact/signals';
import { isIsraeliNews, isIsraeliSport } from '../data/classify';
import type { Channel } from '../data/model';
import { engine, playback } from '../player/engine';
import { updateSettings, settings } from './settings';
import { channelByKey, favourites, markWatched, radioStations, recents, tvChannels } from './store';

/** The screens, addressed by the URL's hash. On the television Back is the app's (ui/App.tsx). */
export type Screen = 'live' | 'guide' | 'radio' | 'search' | 'settings';
const SCREENS: Screen[] = ['live', 'guide', 'radio', 'search', 'settings'];

function fromHash(): Screen {
  const h = location.hash.replace('#', '') as Screen;
  return SCREENS.includes(h) ? h : 'live';
}

export const screen = signal<Screen>(fromHash());
window.addEventListener('hashchange', () => (screen.value = fromHash()));

export function go(to: Screen) {
  if (screen.value === to) return;
  location.hash = to;
}

/** The element the video is laid over on the current screen; null means the corner mini player. */
export const playerSlot = signal<HTMLElement | null>(null);
/** The picture over the whole screen. The app's own state: the page already fills the television. */
export const isFullscreen = signal(false);
// Full screen ends with the picture: with nothing playing there is nothing to fill it with.
effect(() => {
  const { channel, role } = playback.value;
  if (isFullscreen.value && (!channel || role !== 'tv')) isFullscreen.value = false;
});

/* ---------- the channel list's filter ---------- */

/**
 * `all`, `fav`, `recent`, `sport`, `news`, or `g:<sourceId>::<group>`. Shared by the channel list
 * and the guide, and remembered between visits.
 */
export const liveFilter = signal<string>(settings.value.liveFilter ?? 'all');
export const textFilter = signal('');

export function setFilter(id: string) {
  liveFilter.value = id;
  updateSettings({ liveFilter: id });
}

export const groupKey = (c: Channel) => `g:${c.sourceId}::${c.group}`;

export function applyFilter(list: Channel[], filter: string, favs: Set<string>, rec: string[]): Channel[] {
  switch (filter) {
    case 'all':
      return list;
    case 'fav':
      return list.filter((c) => favs.has(c.key));
    case 'recent': {
      const byKey = new Map(list.map((c) => [c.key, c]));
      return rec.map((k) => byKey.get(k)).filter((c): c is Channel => !!c);
    }
    case 'sport':
      return list.filter((c) => isIsraeliSport(c.name, c.group, c.tvgId));
    case 'news':
      return list.filter((c) => isIsraeliNews(c.name, c.group, c.tvgId));
    default:
      return list.filter((c) => groupKey(c) === filter);
  }
}

/** The list the channel screen shows, and the one CH+/CH− and the arrow keys walk through. */
export const visibleChannels = computed(() => {
  const filtered = applyFilter(tvChannels.value, liveFilter.value, favourites.value, recents.value);
  const q = textFilter.value.trim().toLowerCase();
  return q ? filtered.filter((c) => c.name.toLowerCase().includes(q) || String(c.number ?? '') === q) : filtered;
});

/* ---------- playing ---------- */

export function play(channel: Channel, role: 'tv' | 'radio' = 'tv') {
  engine.play(channel, role);
  markWatched(channel.key);
  updateSettings(role === 'tv' ? { lastChannelKey: channel.key } : { lastRadioKey: channel.key });
}

export function zap(delta: number) {
  const { channel, role } = playback.value;
  const list = role === 'radio' ? radioStations.value : visibleChannels.value.length ? visibleChannels.value : tvChannels.value;
  if (!list.length) return;
  const i = channel ? list.findIndex((c) => c.key === channel.key) : -1;
  const next = list[(i + delta + list.length) % list.length];
  if (next) play(next, role);
}

/** The channel the number keys name: its playlist number, else its place in the list. */
export function channelByNumber(n: number): Channel | undefined {
  const all = tvChannels.value;
  return all.find((c) => c.number === n) ?? all[n - 1];
}

/** Starts the last channel when the app opens, if the user wants that (FR-UI startup channel). */
export function resumeLast() {
  if (!settings.value.autoplay || playback.value.channel) return;
  const key = screen.value === 'radio' ? settings.value.lastRadioKey : settings.value.lastChannelKey;
  const channel = key ? channelByKey.value.get(key) : undefined;
  if (channel) engine.play(channel, screen.value === 'radio' ? 'radio' : 'tv');
}

export function toggleFullscreen() {
  const entering = !isFullscreen.value && playback.value.role === 'tv' && !!playback.value.channel;
  // The screen under the picture is hidden from focus (ui/spatial.ts), so focus leaves it first.
  if (entering) (document.activeElement as HTMLElement | null)?.blur?.();
  isFullscreen.value = entering;
}
