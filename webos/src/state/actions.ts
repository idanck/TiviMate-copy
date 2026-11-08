import { computed, effect, signal } from '@preact/signals';
import { isIsraeliNews, isIsraeliSport } from '../data/classify';
import type { Channel } from '../data/model';
import { engine, playback } from '../player/engine';
import { onShelf } from './radio';
import { updateSettings, settings } from './settings';
import { channelByKey, favourites, markWatched, radioStations, recents, tvChannels } from './store';

/**
 * What the remote does, apart from moving focus (ui/App.tsx): which channel plays, the channel
 * list's filter, full screen. The computer version's (web/src/state/actions.ts), cut to the
 * channel list, the guide and the radio for now (ADR-057, step 2: Sport and News mode, search and
 * settings come after the owner has seen this much work on the television).
 */

/**
 * The element the video is laid over on the current screen (ui/PlayerHost.tsx). Null hides the
 * picture; the sound goes on.
 */
export const playerSlot = signal<HTMLElement | null>(null);

/** Which screen is up: the channel list (where the app opens), the guide (0.2.0) or the radio (0.3.0). */
export type Screen = 'live' | 'guide' | 'radio';
export const screen = signal<Screen>('live');

export function showScreen(next: Screen) {
  screen.value = next;
}

/** The picture over the whole screen. The app's own state: the page already fills the television. */
export const isFullscreen = signal(false);
/**
 * Full screen that the app took by itself after a long time without a key (ui/idle.ts), not the
 * viewer: any key gives the screen back as it was, and is not acted on.
 */
export const idleTakeover = signal(false);
// Full screen ends with the picture: with nothing playing there is nothing to fill it with.
effect(() => {
  const { channel, role } = playback.value;
  if (isFullscreen.value && (!channel || role !== 'tv')) isFullscreen.value = false;
  if (idleTakeover.value && !isFullscreen.value) idleTakeover.value = false;
});

/* ---------- the channel list's filter ---------- */

/**
 * `all`, `fav`, `recent`, `sport`, `news`, or `g:<sourceId>::<group>`. Remembered between visits.
 */
export const liveFilter = signal<string>(settings.value.liveFilter ?? 'all');

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

/** The list the channel screen shows, and the one the arrows walk through in full screen. */
export const visibleChannels = computed(() => applyFilter(tvChannels.value, liveFilter.value, favourites.value, recents.value));

/* ---------- the radio list's shelf ---------- */

/** `all`, `fav`, `recent`, or a shelf of the radio menu (ADR-050) by `shelfKey`. Remembered. */
export const radioFilter = signal<string>(settings.value.radioFilter ?? 'all');

export function setRadioFilter(id: string) {
  radioFilter.value = id;
  updateSettings({ radioFilter: id });
}

export function applyRadioFilter(list: Channel[], filter: string, favs: Set<string>, rec: string[]): Channel[] {
  if (filter === 'all' || filter === 'fav' || filter === 'recent') return applyFilter(list, filter, favs, rec);
  return list.filter((c) => onShelf(c, filter));
}

/** The stations the radio screen lists, and the ones the arrows walk through while one plays. */
export const visibleStations = computed(() => applyRadioFilter(radioStations.value, radioFilter.value, favourites.value, recents.value));

/* ---------- playing ---------- */

export function play(channel: Channel, role: 'tv' | 'radio' = 'tv') {
  engine.play(channel, role);
  markWatched(channel.key);
  updateSettings(role === 'tv' ? { lastChannelKey: channel.key } : { lastRadioKey: channel.key });
}

export function zap(delta: number) {
  const { channel, role } = playback.value;
  const list = role === 'radio'
    ? (visibleStations.value.length ? visibleStations.value : radioStations.value)
    : visibleChannels.value.length ? visibleChannels.value : tvChannels.value;
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
  const key = settings.value.lastChannelKey;
  const channel = key ? channelByKey.value.get(key) : undefined;
  if (channel) engine.play(channel, 'tv');
}

export function toggleFullscreen() {
  const entering = !isFullscreen.value && playback.value.role === 'tv' && !!playback.value.channel;
  // The screen under the picture is hidden from focus (ui/spatial.ts), so focus leaves it first.
  if (entering) (document.activeElement as HTMLElement | null)?.blur?.();
  isFullscreen.value = entering;
}
