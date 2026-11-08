import { batch } from '@preact/signals';
import { idleTakeover, isFullscreen } from '../state/actions';
import { playback } from '../player/engine';
import { IDLE_MINUTES, shouldTakeOver } from './idleRule';
import { watch } from './watch';

export { IDLE_MINUTES } from './idleRule';

/**
 * Option B of ADR-057's vanishing interface (the owner's decision, 2026-11-08): after
 * IDLE_MINUTES without a key, with a television channel playing, the app puts the picture over
 * the whole screen by itself. LG's OLED screen saver does not come while a video plays full
 * screen, and with `preferredType: 2` (appinfo.json, option A) it waits 30 minutes for the
 * interface, so 25 comes first. It applies on every screen, the guide and an open dialog too. Any
 * key, or the Magic Remote's pointer, gives back the screen exactly as it was (same channel, same
 * focus) and is not otherwise acted on. Radio, with no picture, is left to the television.
 */
export const idle = {
  /** Changed only from tools/cdp.mjs (`idle <minutes>`), to see the takeover without waiting. */
  limitMs: IDLE_MINUTES * 60_000,
  lastActivityAt: Date.now(),
  /** When the app last took over, and when a key last gave the screen back. */
  takenAt: 0,
  returnedAt: 0,
};

/** What had the remote when the app took over, to give it back. */
let focusBefore: Element | null = null;

function takeOver() {
  focusBefore = document.activeElement;
  idle.takenAt = Date.now();
  // Together: full screen without the flag would read as the viewer's own (state/actions.ts).
  batch(() => {
    isFullscreen.value = true;
    idleTakeover.value = true;
  });
  watch.noteEvent(`idle: full screen after ${Math.round(idle.limitMs / 60_000)} min without a key`);
}

/** Gives the screen back; true if it had been taken (the key that did it is then swallowed). */
export function giveBack(): boolean {
  if (!idleTakeover.value) return false;
  batch(() => {
    idleTakeover.value = false;
    isFullscreen.value = false;
  });
  idle.returnedAt = Date.now();
  const before = focusBefore;
  focusBefore = null;
  watch.noteEvent(`idle: a key gave the screen back after ${Math.round((idle.returnedAt - idle.takenAt) / 60_000)} min`);
  // After the screen is drawn again: the element that had the remote, where it was.
  setTimeout(() => {
    if (before instanceof HTMLElement && document.contains(before)) before.focus({ preventScroll: true });
  }, 0);
  return true;
}

/**
 * Listens before everything else (capture on the window): a key while the app has the screen
 * only gives it back. Installed once, before the app renders (main.tsx).
 */
export function installIdle() {
  const activity = (e: Event) => {
    idle.lastActivityAt = Date.now();
    if (giveBack()) {
      e.preventDefault();
      e.stopImmediatePropagation();
    }
  };
  window.addEventListener('keydown', activity, true);
  window.addEventListener('mousedown', activity, true);
  window.addEventListener('wheel', activity, true);
  // The pointer moving gives the screen back but is nothing to swallow.
  window.addEventListener('mousemove', () => {
    idle.lastActivityAt = Date.now();
    giveBack();
  }, true);
  setInterval(() => {
    const { channel, role } = playback.value;
    if (shouldTakeOver(Date.now(), idle.lastActivityAt, idle.limitMs, !!channel && role === 'tv', isFullscreen.value)) takeOver();
  }, 10_000);
}

/** One line for shidur.state() (ui/debug.ts). */
export function idleLine(): string {
  const ago = Math.round((Date.now() - idle.lastActivityAt) / 60_000);
  const parts = [`last key ${ago} min ago`, `takes over after ${Math.round(idle.limitMs / 60_000)} min with a channel playing`];
  if (idleTakeover.value) parts.push(`TAKEN OVER since ${new Date(idle.takenAt).toTimeString().slice(0, 8)}`);
  else if (idle.takenAt) parts.push(`last taken over ${new Date(idle.takenAt).toTimeString().slice(0, 8)}, given back ${new Date(idle.returnedAt).toTimeString().slice(0, 8)}`);
  return parts.join(' · ');
}
