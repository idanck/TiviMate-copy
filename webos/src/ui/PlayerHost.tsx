import { signal } from '@preact/signals';
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { imageUrl } from '../data/api';
import { clockRange, nowNext, offsetMs, progress } from '../data/guide';
import { engine, playback, volume } from '../player/engine';
import { isFullscreen, playerSlot, toggleFullscreen, zap } from '../state/actions';
import { settings } from '../state/settings';
import { now, programmesOf } from '../state/store';
import { Logo, Progress } from './common';
import { Icon } from './icons';

/** Briefly shows the overlay — after a zap, so the new channel's name is seen. */
export const flashInfo = signal(0);
/** The digits typed so far for a channel number. */
export const typedNumber = signal('');

/**
 * The one video on screen. It is laid over whatever slot the current screen offers (the channel
 * list's stage, the guide's preview) so a change of screen never restarts the stream; with no
 * slot it shrinks to a corner, and for radio it is heard but not seen.
 */
export function PlayerHost() {
  const host = useRef<HTMLDivElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const [rect, setRect] = useState<DOMRect | null>(null);
  const [active, setActive] = useState(false);
  // The remote's focus inside the controls keeps them up, as the mouse over them does.
  const [focusInside, setFocusInside] = useState(false);
  const pb = playback.value;
  const slot = playerSlot.value;

  useLayoutEffect(() => {
    box.current?.appendChild(engine.media);
  }, []);

  // Follow the slot: its size changes with the window, its place with scrolling.
  useLayoutEffect(() => {
    if (!slot) {
      setRect(null);
      return;
    }
    const update = () => setRect(slot.getBoundingClientRect());
    update();
    const ro = new ResizeObserver(update);
    ro.observe(slot);
    window.addEventListener('resize', update);
    window.addEventListener('scroll', update, true);
    return () => {
      ro.disconnect();
      window.removeEventListener('resize', update);
      window.removeEventListener('scroll', update, true);
    };
  }, [slot]);

  // Show the overlay on movement and after a zap; hide it, and the cursor, a few seconds later.
  useEffect(() => {
    if (!flashInfo.value) return;
    setActive(true);
    const t = setTimeout(() => setActive(false), 4000);
    return () => clearTimeout(t);
  }, [flashInfo.value]);
  const moveTimer = useRef<ReturnType<typeof setTimeout>>();
  const onMove = () => {
    setActive(true);
    clearTimeout(moveTimer.current);
    moveTimer.current = setTimeout(() => setActive(false), 3000);
  };

  useMediaSession();

  const channel = pb.channel;
  const radio = pb.role === 'radio';
  // Radio is heard, not seen: its screen draws the station itself.
  const hidden = !channel || radio;
  const mini = !!channel && !radio && !slot && !isFullscreen.value;
  const narrow = mini || (!!rect && rect.width <= 420 && !isFullscreen.value);
  const style = rect && !mini ? { top: rect.top, left: rect.left, width: rect.width, height: rect.height } : undefined;
  const air = channel && !radio ? nowNext(programmesOf(channel), now.value, offsetMs(channel, settings.value.epgOffsetMinutes)) : {};
  const showOverlay = active || focusInside || pb.status !== 'playing' || typedNumber.value !== '';

  return (
    <div
      ref={host}
      class={`player-host${hidden ? ' hidden' : ''}${mini ? ' mini' : ''}${narrow ? ' narrow' : ''}${radio ? ' audio' : ''}${isFullscreen.value ? ' fullscreen' : ''}${isFullscreen.value && !active ? ' idle-cursor' : ''}`}
      style={style}
      onMouseMove={onMove}
      onFocusIn={() => setFocusInside(true)}
      onFocusOut={(e) => setFocusInside(!!host.current?.contains(e.relatedTarget as Node | null))}
      onDblClick={(e) => {
        if ((e.target as HTMLElement).closest('button, input')) return;
        toggleFullscreen();
      }}
      aria-label="נגן"
    >
      <div ref={box} style={{ width: '100%', height: '100%' }} />
      {channel && <StateLayer />}
      {typedNumber.value && <div class="zap-number ltr">{typedNumber.value}</div>}
      {channel && (
        <div class={`player-overlay${showOverlay ? ' show' : ''}`}>
          <div class="overlay-top">
            <Logo url={channel.logoUrl} name={channel.name} size={36} round={radio} />
            <span class="title" dir="auto">{channel.name}</span>
            {pb.status === 'playing' && <span class="badge-live">חי</span>}
            <span class="spacer" />
            {mini && (
              <button class="icon-btn" onClick={() => engine.stop()} aria-label="עצור" title="עצור">
                <Icon name="close" />
              </button>
            )}
          </div>
          <div class="overlay-bottom">
            {air.now && isFullscreen.value && (
              <div class="overlay-programme">
                <span class="p-title" dir="auto">{air.now.title}</span>
                <span class="muted">{clockRange(air.now.start, air.now.end)}{air.next ? ` · הבא: ${air.next.title}` : ''}</span>
                <Progress value={progress(air.now, now.value)} live />
              </div>
            )}
            <Controls />
          </div>
        </div>
      )}
    </div>
  );
}

function Controls() {
  const pb = playback.value;
  const v = volume.value;
  const paused = pb.status === 'paused' || pb.status === 'blocked';
  return (
    <div class="overlay-controls">
      <button class="icon-btn" onClick={() => engine.togglePause()} aria-label={paused ? 'נגן' : 'השהה'} title={paused ? 'נגן' : 'השהה'}>
        <Icon name={paused ? 'play' : 'pause'} />
      </button>
      {pb.role === 'tv' && (
        <>
          <button class="icon-btn zap-btn" onClick={() => zap(-1)} aria-label="הערוץ הקודם" title="הערוץ הקודם" style={{ transform: 'rotate(180deg)' }}>
            <Icon name="chevronDown" size={20} />
          </button>
          <button class="icon-btn zap-btn" onClick={() => zap(1)} aria-label="הערוץ הבא" title="הערוץ הבא">
            <Icon name="chevronDown" size={20} />
          </button>
        </>
      )}
      <button class="icon-btn" onClick={() => engine.toggleMute()} aria-label={v.muted ? 'הפעל קול' : 'השתק'} title={v.muted ? 'הפעל קול' : 'השתק'}>
        <Icon name={v.muted || v.level === 0 ? 'mute' : 'audio'} />
      </button>
      <input
        class="volume"
        type="range"
        min={0}
        max={1}
        step={0.05}
        value={v.muted ? 0 : v.level}
        onInput={(e) => engine.setVolume(Number((e.target as HTMLInputElement).value))}
        aria-label="עוצמת קול"
      />
      <span class="spacer" />
      <button class="icon-btn" onClick={() => toggleFullscreen()} aria-label={isFullscreen.value ? 'צא ממסך מלא' : 'מסך מלא'} title="מסך מלא">
        <Icon name={isFullscreen.value ? 'exitFullscreen' : 'fullscreen'} />
      </button>
    </div>
  );
}

function StateLayer() {
  const pb = playback.value;
  if (pb.status === 'loading' || pb.status === 'reconnecting') {
    return (
      <div class="player-state">
        <div class="spinner" />
        {pb.message && <div class="msg">{pb.message}</div>}
      </div>
    );
  }
  if (pb.status === 'error') {
    return (
      <div class="player-state error">
        <Icon name="alert" size={32} />
        <div class="msg">{pb.message ?? 'לא ניתן לנגן את הערוץ'}</div>
        <button class="btn" onClick={() => engine.retry()}>
          <Icon name="refresh" size={18} /> נסה שוב
        </button>
      </div>
    );
  }
  if (pb.status === 'blocked') {
    return (
      <div class="player-state">
        <button class="big-play" onClick={() => engine.togglePause()} aria-label="נגן">
          <Icon name="play" size={34} />
        </button>
      </div>
    );
  }
  if (pb.autoMuted && volume.value.muted) {
    return (
      <div class="player-state" style={{ alignContent: 'end', paddingBottom: 72 }}>
        <button class="btn primary" onClick={() => engine.toggleMute()}>
          <Icon name="audio" size={18} /> הפעל קול
        </button>
      </div>
    );
  }
  return null;
}

/** The keyboard's media keys and the system's media controls, for whatever is playing. */
function useMediaSession() {
  const channel = playback.value.channel;
  const role = playback.value.role;
  useEffect(() => {
    if (!('mediaSession' in navigator)) return;
    const ms = navigator.mediaSession;
    if (!channel) {
      ms.metadata = null;
      return;
    }
    const art = imageUrl(channel.logoUrl);
    ms.metadata = new MediaMetadata({
      title: channel.name,
      artist: role === 'radio' ? 'רדיו' : 'שידור',
      artwork: art ? [{ src: art, sizes: '512x512' }] : [],
    });
    ms.setActionHandler('play', () => engine.togglePause());
    ms.setActionHandler('pause', () => engine.togglePause());
    ms.setActionHandler('nexttrack', () => zap(1));
    ms.setActionHandler('previoustrack', () => zap(-1));
  }, [channel?.key, role]);
}
