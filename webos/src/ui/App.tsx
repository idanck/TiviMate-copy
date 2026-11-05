import { useEffect } from 'preact/hooks';
import { platformBack } from '../data/luna';
import { engine, playback } from '../player/engine';
import { channelByNumber, go, isFullscreen, play, resumeLast, screen, zap, type Screen } from '../state/actions';
import { channelByKey, init, ready, recents } from '../state/store';
import { isTyping, Logo, Toasts, useClock } from './common';
import { GuideScreen } from './GuideScreen';
import { Icon, type IconName } from './icons';
import { LiveScreen } from './LiveScreen';
import { flashInfo, PlayerHost, typedNumber } from './PlayerHost';
import { RadioScreen } from './RadioScreen';
import { SearchScreen } from './SearchScreen';
import { SettingsScreen } from './SettingsScreen';
import { PanelsScreen } from './PanelsScreen';
import { move, type Direction } from './spatial';
import { modeOf, panelsBack, session } from '../state/panelMode';

const NAV: Array<{ id: Screen; label: string; icon: IconName }> = [
  { id: 'live', label: 'ערוצים', icon: 'tv' },
  { id: 'guide', label: 'מדריך', icon: 'guide' },
  { id: 'radio', label: 'רדיו', icon: 'radio' },
  { id: 'sport', label: 'ספורט', icon: 'sport' },
  { id: 'news', label: 'חדשות', icon: 'news' },
  { id: 'search', label: 'חיפוש', icon: 'search' },
];

/**
 * The remote's keys (LG webOS key codes). Channel up and down are not given to apps on webOS, so
 * channels change with the arrows in full screen, as on most television apps.
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
  YELLOW: 405,
  BLUE: 406,
} as const;

const ARROWS: Record<number, Direction> = { [KEY.LEFT]: 'left', [KEY.UP]: 'up', [KEY.RIGHT]: 'right', [KEY.DOWN]: 'down' };

export function App() {
  useEffect(() => {
    void init().then(resumeLast);
  }, []);
  return <Shell />;
}

function Shell() {
  useRemote();
  const s = screen.value;
  // Sport and News mode are the whole screen: no rail, and no full-screen player under them.
  if (modeOf(s)) {
    return (
      <div class="app panels-app">
        <PanelsScreen />
        <Toasts />
      </div>
    );
  }
  return (
    <div class="app">
      <nav class="rail" aria-label="ניווט">
        <div class="rail-logo" aria-label="שידור">ש</div>
        {NAV.map((n) => (
          <button key={n.id} class="rail-item" aria-current={s === n.id ? 'page' : undefined} onClick={() => go(n.id)}>
            <Icon name={n.icon} size={22} />
            {n.label}
          </button>
        ))}
        <div class="rail-spacer" />
        <RailClock />
        <button class="rail-item" aria-current={s === 'settings' ? 'page' : undefined} onClick={() => go('settings')}>
          <Icon name="settings" size={22} />
          הגדרות
        </button>
      </nav>
      <main class="screen" aria-hidden={isFullscreen.value ? 'true' : undefined}>
        {!ready.value ? (
          <div class="boot"><div class="spinner" /></div>
        ) : s === 'live' ? (
          <LiveScreen />
        ) : s === 'guide' ? (
          <GuideScreen />
        ) : s === 'radio' ? (
          <RadioScreen />
        ) : s === 'search' ? (
          <SearchScreen />
        ) : (
          <SettingsScreen />
        )}
      </main>
      <PlayerHost />
      {playback.value.role === 'radio' && playback.value.channel && s !== 'radio' && <RadioPill />}
      <Toasts />
    </div>
  );
}

function RailClock() {
  const t = useClock(10_000);
  return (
    <div class="clock">
      {new Intl.DateTimeFormat('he-IL', { hour: '2-digit', minute: '2-digit', hour12: false }).format(t)}
    </div>
  );
}

/** Radio keeps playing on every screen; this is how it is seen, and stopped, away from its own. */
function RadioPill() {
  const pb = playback.value;
  const station = pb.channel!;
  const paused = pb.status === 'paused' || pb.status === 'blocked';
  return (
    <div class="radio-pill" role="region" aria-label="רדיו">
      <button class="radio-pill-main" onClick={() => go('radio')}>
        <Logo url={station.logoUrl} name={station.name} size={36} round />
        <span class="name">{station.name}</span>
      </button>
      <button class="icon-btn" onClick={() => engine.togglePause()} aria-label={paused ? 'נגן' : 'השהה'}>
        <Icon name={paused ? 'play' : 'pause'} size={18} />
      </button>
      <button class="icon-btn" onClick={() => engine.stop()} aria-label="עצור">
        <Icon name="close" size={18} />
      </button>
    </div>
  );
}

const focusInPlayer = () => !!document.activeElement?.closest?.('.player-host');

/**
 * Back, from the innermost thing outward (LG's rule for webOS 6 and later): a typed channel number,
 * full screen, the controls over the picture, another screen; and from the channel list, which is
 * where the app opens, the platform's own exit. Dialogs close themselves first (common.tsx).
 */
export function back() {
  if (modeOf(screen.value)) {
    panelsBack();
    return;
  }
  if (typedNumber.value) {
    typedNumber.value = '';
    return;
  }
  if (isFullscreen.value) {
    isFullscreen.value = false;
    (document.activeElement as HTMLElement | null)?.blur?.();
    // Back to the channel that was on screen, where the remote left off.
    setTimeout(() => document.querySelector<HTMLElement>('.ch-row.selected')?.focus(), 0);
    return;
  }
  if (focusInPlayer()) {
    (document.activeElement as HTMLElement).blur();
    return;
  }
  if (screen.value !== 'live') {
    go('live');
    return;
  }
  platformBack();
}

/**
 * The remote: arrows move focus (ui/spatial.ts), OK presses, Back goes back, numbers pick a
 * channel, the colour keys are shortcuts. A keyboard works too while developing: Escape is Back.
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

    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented) return;
      const code = e.keyCode;
      const typing = isTyping(e);

      if (code === KEY.BACK || e.key === 'Escape' || (e.key === 'Backspace' && !typing)) {
        e.preventDefault();
        back();
        return;
      }
      const dialog = !!document.querySelector('.scrim');

      // Sport and News mode: the arrows move between the panels, and up or down with no panel
      // that way steps the channel, as in full screen; play/pause is every panel's; OK is the
      // panel's own (ui/PanelsScreen.tsx); the number and colour keys are swallowed.
      if (modeOf(screen.value)) {
        const arrow = ARROWS[code];
        if (arrow) {
          e.preventDefault();
          if (!move(arrow) && !dialog && (arrow === 'up' || arrow === 'down')) session.step(arrow === 'up' ? -1 : 1);
          return;
        }
        if (code === KEY.OK) return;
        e.preventDefault();
        if (!dialog && (code === KEY.PLAY || code === KEY.PAUSE)) session.togglePause();
        return;
      }

      const dir = ARROWS[code];
      if (dir) {
        const target = e.target as HTMLElement | null;
        // Inside a text field the side arrows move the caret; a slider's change its value.
        if (typing && (dir === 'left' || dir === 'right') && target?.tagName !== 'SELECT') return;
        if (target instanceof HTMLInputElement && target.type === 'range' && (dir === 'left' || dir === 'right')) return;
        if (!dialog && isFullscreen.value && !focusInPlayer()) {
          e.preventDefault();
          if (dir === 'up' || dir === 'down') zap(dir === 'up' ? -1 : 1);
          flashInfo.value++;
          return;
        }
        e.preventDefault();
        move(dir);
        return;
      }
      if (dialog || typing) return;

      const s = screen.value;
      const watching = isFullscreen.value || s === 'live' || s === 'guide';
      const role = playback.value.role;

      if (code === KEY.OK) {
        if (typedNumber.value) {
          e.preventDefault();
          commitNumber();
        } else if (isFullscreen.value && !focusInPlayer()) {
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
        case KEY.RED: {
          e.preventDefault();
          const previous = recents.value.slice(1).map((k) => channelByKey.value.get(k)).find(Boolean);
          if (previous && role === 'tv') {
            play(previous);
            flashInfo.value++;
          }
          return;
        }
        case KEY.GREEN:
          e.preventDefault();
          isFullscreen.value = false;
          go('guide');
          return;
        case KEY.YELLOW:
          e.preventDefault();
          isFullscreen.value = false;
          go('search');
          return;
        case KEY.BLUE:
          e.preventDefault();
          isFullscreen.value = false;
          go('radio');
          return;
      }
      const digit = code >= 48 && code <= 57 ? code - 48 : code >= 96 && code <= 105 ? code - 96 : -1;
      if (digit >= 0 && watching) {
        e.preventDefault();
        typedNumber.value = (typedNumber.value + digit).slice(-4);
        clearTimeout(numberTimer);
        numberTimer = setTimeout(commitNumber, 1500);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      clearTimeout(numberTimer);
    };
  }, []);
}
