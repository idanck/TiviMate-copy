import { useEffect, useState } from 'preact/hooks';
import { engine, playback } from '../player/engine';
import { channelByNumber, go, isFullscreen, play, resumeLast, screen, toggleFullscreen, zap, type Screen } from '../state/actions';
import { channelByKey, init, ready, recents } from '../state/store';
import { isTyping, Logo, Toasts, useClock } from './common';
import { GuideScreen } from './GuideScreen';
import { Icon, type IconName } from './icons';
import { LiveScreen } from './LiveScreen';
import { flashInfo, PlayerHost, typedNumber } from './PlayerHost';
import { RadioScreen } from './RadioScreen';
import { SearchScreen } from './SearchScreen';
import { SettingsScreen } from './SettingsScreen';

const NAV: Array<{ id: Screen; label: string; icon: IconName }> = [
  { id: 'live', label: 'ערוצים', icon: 'tv' },
  { id: 'guide', label: 'מדריך', icon: 'guide' },
  { id: 'radio', label: 'רדיו', icon: 'radio' },
  { id: 'search', label: 'חיפוש', icon: 'search' },
];

type Gate = 'checking' | 'signin' | 'open' | 'offline';

export function App() {
  const [gate, setGate] = useState<Gate>('checking');

  useEffect(() => {
    fetch('/api/session', { credentials: 'same-origin' })
      .then((r) => r.json())
      .then((s: { authRequired: boolean; authenticated: boolean }) => setGate(s.authRequired && !s.authenticated ? 'signin' : 'open'))
      .catch(() => setGate('offline'));
  }, []);

  useEffect(() => {
    if (gate !== 'open') return;
    void init().then(resumeLast);
  }, [gate]);

  if (gate === 'checking') return <div class="boot"><div class="spinner" /></div>;
  if (gate === 'signin') return <SignIn onDone={() => setGate('open')} />;
  if (gate === 'offline') {
    return (
      <div class="boot">
        <div class="empty">
          <h2>אין חיבור לשרת של שידור</h2>
          <p>האפליקציה צריכה את השרת שלה כדי להגיע לספקים. בדוק שהוא פועל ונסה שוב.</p>
          <button class="btn primary" onClick={() => location.reload()}>נסה שוב</button>
        </div>
      </div>
    );
  }
  return <Shell />;
}

function Shell() {
  useShortcuts();
  const s = screen.value;
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
      <main class="screen">
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

function SignIn({ onDone }: { onDone: () => void }) {
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  return (
    <div class="signin">
      <form
        class="card"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError('');
          try {
            const r = await fetch('/api/login', {
              method: 'POST',
              headers: { 'content-type': 'application/json' },
              credentials: 'same-origin',
              body: JSON.stringify({ password }),
            });
            if (r.ok) return onDone();
            setError(r.status === 429 ? 'יותר מדי ניסיונות. נסה שוב בעוד כמה דקות.' : 'הסיסמה שגויה');
          } catch {
            setError('אין חיבור לשרת');
          } finally {
            setBusy(false);
          }
        }}
      >
        <div class="brand">שידור</div>
        <label class="field">
          <span>סיסמה</span>
          <input class="input" type="password" autoFocus autoComplete="current-password" value={password} onInput={(e) => setPassword((e.target as HTMLInputElement).value)} />
        </label>
        {error && <div class="error-text" role="alert">{error}</div>}
        <button class="btn primary" type="submit" disabled={busy || !password}>כניסה</button>
      </form>
    </div>
  );
}

/**
 * The remote control, on a keyboard: channel up and down, numbers, pause, full screen, mute.
 * Layout-independent (`code`, not `key`), so it works with the keyboard in Hebrew too.
 */
function useShortcuts() {
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
      if (e.defaultPrevented || document.querySelector('.scrim')) return;
      if (e.altKey && /^Digit[1-5]$/.test(e.code)) {
        e.preventDefault();
        go((['live', 'guide', 'radio', 'search', 'settings'] as Screen[])[Number(e.code.slice(5)) - 1]);
        return;
      }
      if (isTyping(e) || e.ctrlKey || e.metaKey || e.altKey) return;
      const s = screen.value;
      const watching = isFullscreen.value || s === 'live' || s === 'guide';
      const role = playback.value.role;
      const host = document.querySelector<HTMLElement>('.player-host');

      switch (e.code) {
        case 'ArrowUp':
        case 'PageUp':
        case 'ArrowDown':
        case 'PageDown': {
          if (!(watching && (s !== 'guide' || isFullscreen.value)) && !(s === 'radio' && role === 'radio')) return;
          e.preventDefault();
          zap(e.code === 'ArrowUp' || e.code === 'PageUp' ? -1 : 1);
          flashInfo.value++;
          return;
        }
        case 'Space':
          if ((e.target as HTMLElement)?.closest?.('button')) return;
          e.preventDefault();
          engine.togglePause();
          return;
        case 'KeyF':
          if (role === 'tv') toggleFullscreen(host);
          return;
        case 'KeyM':
          engine.toggleMute();
          return;
        case 'KeyP':
          if (document.pictureInPictureElement) void document.exitPictureInPicture();
          else if (role === 'tv' && playback.value.channel) void engine.media.requestPictureInPicture().catch(() => {});
          return;
        case 'Slash':
          e.preventDefault();
          go('search');
          return;
        case 'Backspace': {
          const previous = recents.value.slice(1).map((k) => channelByKey.value.get(k)).find(Boolean);
          if (previous && role === 'tv') {
            play(previous);
            flashInfo.value++;
          }
          return;
        }
        case 'Enter':
        case 'NumpadEnter':
          if (typedNumber.value) commitNumber();
          return;
      }
      const digit = /^(?:Digit|Numpad)(\d)$/.exec(e.code)?.[1];
      if (digit !== undefined && watching) {
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
