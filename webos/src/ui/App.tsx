import { useEffect } from 'preact/hooks';
import { platformBack } from '../data/luna';
import { engine, playback } from '../player/engine';
import { channelByNumber, isFullscreen, play, resumeLast, screen, showScreen, zap } from '../state/actions';
import { channelByKey, init, ready, recents } from '../state/store';
import { closeTopDialog, isTyping, Toasts } from './common';
import { Guard } from './Guard';
import { GuideScreen } from './GuideScreen';
import { LiveScreen } from './LiveScreen';
import { flashInfo, PlayerHost, typedNumber } from './PlayerHost';
import { remoteUsed } from './remote';
import { focus, move, type Direction } from './spatial';
import { watch } from './watch';

/**
 * The remote's keys (LG webOS key codes). Channel up and down are not given to apps on webOS, so
 * channels change with ▲▼ in full screen, as on most television apps. Green opens the guide, as
 * the colour keys are the only spare ones (the Guide key is the television's own).
 */
export const KEY = {
  OK: 13,
  LEFT: 37,
  UP: 38,
  RIGHT: 39,
  DOWN: 40,
  PAUSE: 19,
  BACK: 461,
  PLAY: 415,
  STOP: 413,
  RED: 403,
  GREEN: 404,
} as const;

const ARROWS: Record<number, Direction> = { [KEY.LEFT]: 'left', [KEY.UP]: 'up', [KEY.RIGHT]: 'right', [KEY.DOWN]: 'down' };

export function App() {
  watch.renders++;
  watch.lastRenderAt = Date.now();
  useEffect(() => {
    void init().then(resumeLast);
  }, []);
  useRemote();
  // Something has the remote from the start, and on every change of screen: the channel on
  // screen, else the first channel, else the first group; in the guide, the grid.
  const loaded = ready.value;
  const which = screen.value;
  useEffect(() => {
    if (!loaded) return;
    const t = setTimeout(focusChannel, 0);
    return () => clearTimeout(t);
  }, [loaded, which]);
  return (
    <div class="app">
      <main class="screen" aria-hidden={isFullscreen.value ? 'true' : undefined}>
        {!ready.value ? <div class="boot"><div class="spinner" /></div> : which === 'guide' ? <Guard><GuideScreen /></Guard> : <Guard><LiveScreen /></Guard>}
      </main>
      <PlayerHost />
      <Toasts />
    </div>
  );
}

/**
 * Puts focus on the channel on screen, or the first one, or wherever the arrows lead first; in
 * the guide, on the grid.
 */
function focusChannel() {
  const current = document.activeElement;
  if (current && current !== document.body && document.contains(current)) return;
  const row = screen.value === 'guide'
    ? document.querySelector<HTMLElement>('.epg')
    : (document.querySelector<HTMLElement>('.ch-row.selected') ?? document.querySelector<HTMLElement>('.ch-row'));
  if (row) focus(row);
  else move('down');
}

/** The guide, from wherever the viewer is; Green again, or Back, returns to the channel list. */
export function toggleGuide() {
  if (screen.value === 'guide') {
    showScreen('live');
    return;
  }
  isFullscreen.value = false;
  typedNumber.value = '';
  showScreen('guide');
}

/**
 * Back, from the innermost thing outward (LG's rule): a typed channel number, an open dialog,
 * full screen, the guide, focus away from the list; and from the channel list, which is where the
 * app opens, the platform's own way out (webOS 5 goes to the launcher).
 */
export function back() {
  if (typedNumber.value) {
    typedNumber.value = '';
    return;
  }
  if (closeTopDialog()) return;
  if (isFullscreen.value) {
    isFullscreen.value = false;
    // Back to the channel that was on screen, where the remote left off.
    setTimeout(() => {
      const row = document.querySelector<HTMLElement>('.ch-row.selected');
      if (row) focus(row);
      else focusChannel();
    }, 0);
    return;
  }
  if (screen.value === 'guide') {
    showScreen('live');
    return;
  }
  if (!document.activeElement?.closest?.('.channels')) {
    const row = document.querySelector<HTMLElement>('.ch-row.selected') ?? document.querySelector<HTMLElement>('.ch-row');
    if (row) {
      focus(row);
      return;
    }
  }
  platformBack();
}

/**
 * The remote: arrows move focus (ui/spatial.ts; ▲▼ in the channel list are the list's own, all
 * four in the guide's grid are the grid's), OK presses, Back goes back, numbers pick a channel,
 * Green opens the guide. A keyboard works too while developing: Escape is Back.
 */
function useRemote() {
  useEffect(() => {
    let numberTimer: ReturnType<typeof setTimeout> | undefined;
    const commitNumber = () => {
      clearTimeout(numberTimer);
      const n = parseInt(typedNumber.value, 10);
      typedNumber.value = '';
      const channel = Number.isFinite(n) ? channelByNumber(n) : undefined;
      if (channel) {
        play(channel);
        flashInfo.value++;
      }
    };

    // Before every other handler (capture): any key at all means the viewer has the remote now.
    const onAnyKey = () => {
      remoteUsed.value = true;
    };
    window.addEventListener('keydown', onAnyKey, true);
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented) return;
      const code = e.keyCode;
      const typing = isTyping(e);

      if (code === KEY.BACK || e.key === 'Escape' || (e.key === 'Backspace' && !typing)) {
        e.preventDefault();
        back();
        return;
      }

      const dir = ARROWS[code];
      if (dir) {
        e.preventDefault();
        if (isFullscreen.value) {
          if (dir === 'up' || dir === 'down') zap(dir === 'up' ? -1 : 1);
          flashInfo.value++;
          return;
        }
        move(dir);
        return;
      }
      if (typing) return;

      if (code === KEY.OK) {
        if (typedNumber.value) {
          e.preventDefault();
          commitNumber();
        } else if (isFullscreen.value) {
          // OK over the picture shows what is on, as on the Android TV shell.
          e.preventDefault();
          flashInfo.value++;
        }
        return;
      }
      switch (code) {
        case KEY.PLAY:
        case KEY.PAUSE:
          e.preventDefault();
          engine.togglePause();
          return;
        case KEY.STOP:
          e.preventDefault();
          engine.stop();
          return;
        case KEY.GREEN:
          e.preventDefault();
          toggleGuide();
          return;
        case KEY.RED: {
          // The channel before this one, as the Android TV shell's "last channel".
          e.preventDefault();
          const previous = recents.value.slice(1).map((k) => channelByKey.value.get(k)).find(Boolean);
          if (previous && playback.value.role === 'tv') {
            play(previous);
            flashInfo.value++;
          }
          return;
        }
      }
      const digit = code >= 48 && code <= 57 ? code - 48 : code >= 96 && code <= 105 ? code - 96 : -1;
      if (digit >= 0) {
        e.preventDefault();
        typedNumber.value = (typedNumber.value + digit).slice(-4);
        clearTimeout(numberTimer);
        numberTimer = setTimeout(commitNumber, 1500);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onAnyKey, true);
      window.removeEventListener('keydown', onKey);
      clearTimeout(numberTimer);
    };
  }, []);
}
