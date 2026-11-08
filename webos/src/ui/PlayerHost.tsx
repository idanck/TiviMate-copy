import { signal } from '@preact/signals';
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { clockRange, nowNext, offsetMs, progress } from '../data/guide';
import { engine, playback, volume } from '../player/engine';
import { isFullscreen, playerSlot } from '../state/actions';
import { settings } from '../state/settings';
import { now, programmesOf } from '../state/store';
import { Logo, Progress } from './common';
import { Icon } from './icons';

/** Where the player is drawn, in window pixels. */
type Box = { top: number; left: number; width: number; height: number };

/** Briefly shows what is on: after a zap, or OK over the picture in full screen. */
export const flashInfo = signal(0);
/** The digits typed so far for a channel number. */
export const typedNumber = signal('');
/** Where the player was last placed, for tools/cdp.mjs (ui/debug.ts). */
export const playerBox = { slot: null as Box | null, fullscreen: false };

/**
 * The one video on screen, in a box as on Android (the owner's choice, ADR-057), and over the
 * whole screen on OK.
 *
 * The video is not inside the screen's layout but in a layer of its own, laid over the slot the
 * screen offers (the channel list's stage), so a change of screen never restarts the stream. The
 * page is not scaled at all (ADR-057), so the slot's place on the window is where the video goes:
 * no dividing by a scale, which is where the first version's picture went astray.
 */
export function PlayerHost() {
  const box = useRef<HTMLDivElement>(null);
  const [rect, setRect] = useState<Box | null>(null);
  const [active, setActive] = useState(false);
  const pb = playback.value;
  const slot = playerSlot.value;
  const full = isFullscreen.value;

  useLayoutEffect(() => {
    box.current?.appendChild(engine.media);
  }, []);

  // Follow the slot: it is measured again whenever it changes size. It never scrolls.
  useLayoutEffect(() => {
    if (!slot) {
      setRect(null);
      playerBox.slot = null;
      return;
    }
    const update = () => {
      const r = slot.getBoundingClientRect();
      const next = { top: r.top, left: r.left, width: r.width, height: r.height };
      playerBox.slot = next;
      setRect(next);
    };
    update();
    const ro = new ResizeObserver(update);
    ro.observe(slot);
    window.addEventListener('resize', update);
    return () => {
      ro.disconnect();
      window.removeEventListener('resize', update);
    };
  }, [slot]);

  useEffect(() => {
    playerBox.fullscreen = full;
  }, [full]);

  // What is on shows after a zap and on OK, then goes after a few seconds.
  useEffect(() => {
    if (!flashInfo.value) return;
    setActive(true);
    const t = setTimeout(() => setActive(false), 4000);
    return () => clearTimeout(t);
  }, [flashInfo.value]);

  const channel = pb.channel;
  const radio = pb.role === 'radio';
  // Radio is heard, not seen; with no slot and no full screen there is nowhere to draw.
  const hidden = !channel || radio || (!full && !rect);
  const style = !full && rect ? { top: `${rect.top}px`, left: `${rect.left}px`, width: `${rect.width}px`, height: `${rect.height}px` } : undefined;
  const air = channel && !radio ? nowNext(programmesOf(channel), now.value, offsetMs(channel, settings.value.epgOffsetMinutes)) : {};
  const showInfo = full && (active || pb.status !== 'playing' || typedNumber.value !== '');

  return (
    <div class={`player-host${hidden ? ' hidden' : ''}${full ? ' fullscreen' : ''}`} style={style} aria-label="נגן">
      <div class="player-video" ref={box} />
      {channel && <StateLayer compact={!full} />}
      {typedNumber.value && <div class="zap-number ltr">{typedNumber.value}</div>}
      {channel && showInfo && (
        <div class="player-info">
          <div class="info-top">
            <Logo url={channel.logoUrl} name={channel.name} size={40} />
            <span class="title" dir="auto">{channel.name}</span>
            {pb.status === 'playing' && <span class="badge-live">חי</span>}
          </div>
          {air.now && (
            <div class="info-programme">
              <span class="p-title" dir="auto">{air.now.title}</span>
              <span class="muted">{clockRange(air.now.start, air.now.end)}{air.next ? ` · הבא: ${air.next.title}` : ''}</span>
              <Progress value={progress(air.now, now.value)} live />
            </div>
          )}
          <div class="info-keys faint">▲▼ ערוץ · OK מידע · ירוק לוח שידורים · צהוב רדיו · BACK יציאה ממסך מלא</div>
        </div>
      )}
    </div>
  );
}

/**
 * Loading, an error, or sound held back. Nothing here takes focus: OK on the channel's row tries
 * again (ui/LiveScreen.tsx), so the remote never has to find its way into the picture.
 */
function StateLayer({ compact }: { compact: boolean }) {
  const pb = playback.value;
  if (pb.status === 'loading' || pb.status === 'reconnecting') {
    return (
      <div class={`player-state${compact ? ' compact' : ''}`}>
        <div class="spinner" />
        {pb.message && <div class="msg">{pb.message}</div>}
      </div>
    );
  }
  if (pb.status === 'error') {
    return (
      <div class={`player-state error${compact ? ' compact' : ''}`}>
        <Icon name="alert" size={32} />
        <div class="msg">{pb.message ?? 'לא ניתן לנגן את הערוץ'}</div>
        <div class="faint">OK על הערוץ — לנסות שוב</div>
      </div>
    );
  }
  if (pb.status === 'paused' || pb.status === 'blocked') {
    return (
      <div class={`player-state${compact ? ' compact' : ''}`}>
        <Icon name="play" size={40} />
        <div class="faint">▶ להמשך</div>
      </div>
    );
  }
  if (pb.autoMuted && volume.value.muted) {
    return (
      <div class={`player-state${compact ? ' compact' : ''}`}>
        <Icon name="mute" size={28} />
        <div class="faint">הקול מושתק</div>
      </div>
    );
  }
  return null;
}
