import { effect, untracked } from '@preact/signals';
import type { Channel } from '../data/model';
import { engine, playback } from '../player/engine';
import { PanelPlayer } from '../player/panel';
import { PAIR } from '../ui/panelLayout';
import { isFullscreen, play, screen, type Screen } from './actions';
import { refreshNews } from './news';
import { choicesFor, FLASHES_ID, PanelSession, type Mode } from './panels';
import { settings, updateSettings } from './settings';
import { channelByKey, ready, sources, tvChannels } from './store';

/**
 * Sport mode and News mode on the television (ADR-053): the session (state/panels.ts) wired to the
 * app — its screens, the full-screen player it stands in for, the channels on offer, the settings
 * that remember each grid, and the platform's visibility.
 */

export const session = new PanelSession({
  createPanel: (id, othersPlaying) => new PanelPlayer(id, othersPlaying),
  persist: (mode, slots) => updateSettings(mode === 'sport' ? { sportSlots: slots } : { newsSlots: slots }),
});

export const modeOf = (s: Screen): Mode | null => (s === 'sport' ? 'sport' : s === 'news' ? 'news' : null);

/** What was playing as the mode opened, to start again if it is left with nothing chosen. */
let before: { channel: Channel; role: 'tv' | 'radio' } | null = null;
let offered = -1;

function enter(mode: Mode) {
  if (session.active) {
    if (session.mode.value === mode) return;
    session.close();
  } else {
    const { channel, role, status } = playback.value;
    before = channel && status !== 'error' && status !== 'paused' ? { channel, role } : null;
    isFullscreen.value = false;
    engine.stop();
  }
  offered = session.begin(mode, mode === 'sport' ? PAIR : settings.value.newsPanels);
}

/** Offers the channels once they are known; the app may open on this screen before they are. */
function offer() {
  if (offered !== session.opening || session.choices.peek() !== null) return;
  const mode = session.mode.value;
  const free = new Set(sources.value.filter((s) => s.builtIn === 'israel-tv').map((s) => s.id));
  const { choices, replacedBy } = choicesFor(mode, tvChannels.value, free);
  const stored = mode === 'sport' ? settings.value.sportSlots : settings.value.newsSlots;
  const restored = stored.map((key) => {
    if (!key || key === FLASHES_ID) return null;
    const c = channelByKey.value.get(key);
    return c ? replacedBy.get(c.key) ?? c : null;
  });
  session.offer(offered, choices, restored, mode === 'news' ? stored.indexOf(FLASHES_ID) : -1);
}

/**
 * Leaves the mode for the channel list. With [carryOn], the channel being heard goes on full
 * screen; with nothing chosen, whatever was playing before starts again.
 */
export function leavePanels(carryOn = true) {
  if (!session.active) return;
  const chosen = carryOn ? session.focusedChannel() : null;
  session.close();
  if (carryOn) location.hash = 'live';
  if (chosen) {
    play(chosen);
    isFullscreen.value = true;
  } else if (before) {
    if (before.role === 'tv') play(before.channel);
    else play(before.channel, 'radio');
  }
  before = null;
}

/** Back inside the mode, innermost first: a drawer, a full panel, then the mode itself. */
export function panelsBack() {
  if (session.picking.value !== null) return session.closePicker();
  if (session.menu.value !== null) return session.closeMenu();
  if (session.expanded.value) return session.setExpanded(false);
  leavePanels(true);
}

effect(() => {
  const mode = modeOf(screen.value);
  // Only the screen, and the channels arriving, open or feed the mode; what it reads and writes
  // on the way (the player, the settings) must not run this again.
  const loaded = ready.value && tvChannels.value.length >= 0;
  untracked(() => {
    if (mode) {
      enter(mode);
      if (loaded) offer();
    } else if (session.active) {
      // Left some other way than Back: nothing carries on, what was playing before comes back.
      leavePanels(false);
    }
  });
});

// The headlines are read while News mode is in front of the viewer, on their own ten-minute limit.
let newsTimer: ReturnType<typeof setInterval> | undefined;
effect(() => {
  const reading = session.live.value && session.mode.value === 'news';
  clearInterval(newsTimer);
  if (!reading) return;
  void refreshNews();
  newsTimer = setInterval(() => void refreshNews(), 60_000);
});

// Out of sight, the panels let go of their connections; back in front, they start again.
document.addEventListener('visibilitychange', () => {
  if (document.hidden) session.suspend();
  else session.resume();
});
