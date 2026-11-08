import { engine, playback } from '../player/engine';

/**
 * A watch on the page, for the fault the owner saw on the television on 2026-11-07: after some
 * minutes on the channel list, only the picture in its box stayed on the screen and everything
 * else disappeared (ADR-057, *The vanishing interface*). Nothing in the app hides the interface
 * on a timer, so the cause is not known, and a desktop browser cannot show it. This records what
 * tells the candidates apart, and `shidur.state()` (ui/debug.ts) reports it, so that one
 * `cdp.mjs state` while it is happening says which it is:
 *
 * - **the DOM tree is gone or emptied** (the app unmounted it, or a render threw): the count of
 *   elements under `#app`, whether the screen's element is still there, and every removal of a
 *   whole screen, with the time;
 * - **an error**: every uncaught error and unhandled rejection, with the time, and a render that
 *   threw (ui/Guard.tsx);
 * - **the page hidden or suspended by webOS** (`visibilitychange`, `webOSRelaunch`, a pagehide):
 *   the events the platform sent, with the time, and whether animation frames still arrive;
 * - **memory**: Chromium's heap, where it says (`performance.memory` is Chromium's own);
 * - **a snapshot every minute** of all of the above, so the minutes before the fault are there
 *   to read after it.
 *
 * Installed before the first render (main.tsx), and costing the page nothing it would notice:
 * one animation frame every five seconds, one snapshot a minute, and a mutation observer that
 * only counts.
 */

export interface Noted {
  at: string;
  text: string;
}

const KEEP_ERRORS = 20;
const KEEP_EVENTS = 40;
const KEEP_SNAPSHOTS = 45;
const SNAPSHOT_MS = 60_000;
const FRAME_MS = 5_000;

const clock = () => new Date().toISOString().slice(11, 19);
const MB = (n: number) => Math.round(n / 1048576);

function push(list: Noted[], keep: number, text: string) {
  list.push({ at: clock(), text });
  if (list.length > keep) list.shift();
}

interface ChromeMemory {
  usedJSHeapSize: number;
  totalJSHeapSize: number;
  jsHeapSizeLimit: number;
}

export function memory(): { usedMB: number; totalMB: number; limitMB: number } | null {
  const m = (performance as Performance & { memory?: ChromeMemory }).memory;
  if (!m || !m.usedJSHeapSize) return null;
  return { usedMB: MB(m.usedJSHeapSize), totalMB: MB(m.totalJSHeapSize), limitMB: MB(m.jsHeapSizeLimit) };
}

export const watch = {
  startedAt: Date.now(),
  /** Bumped by every render of the root (ui/App.tsx): a frozen tree renders no more. */
  renders: 0,
  lastRenderAt: 0,
  lastFrameAt: 0,
  /** Nodes removed from under #app since the start, and the last time a whole screen went. */
  removedNodes: 0,
  errors: [] as Noted[],
  events: [] as Noted[],
  snapshots: [] as string[],
  noteError(kind: string, text: string) {
    push(this.errors, KEEP_ERRORS, `${kind}: ${text}`.slice(0, 300));
  },
  noteEvent(text: string) {
    push(this.events, KEEP_EVENTS, text);
  },
};

/** How the page stands now, in one line: what a snapshot keeps and the report prints. */
export function pageLine(): string {
  const app = document.getElementById('app');
  const nodes = app ? app.getElementsByTagName('*').length : -1;
  const screen = document.querySelector('.screen');
  const screenNodes = screen ? screen.getElementsByTagName('*').length : -1;
  const v = engine.media;
  const mem = memory();
  const frameAgo = watch.lastFrameAt ? Math.round((Date.now() - watch.lastFrameAt) / 1000) : -1;
  return [
    `vis ${document.visibilityState}`,
    `focus ${document.hasFocus() ? 'yes' : 'no'}`,
    `nodes ${nodes} (screen ${screenNodes})`,
    `renders ${watch.renders}`,
    `frame ${frameAgo}s ago`,
    `heap ${mem ? `${mem.usedMB}/${mem.limitMB}MB` : '-'}`,
    `player ${playback.value.status} ready ${v.readyState} t ${Math.round(v.currentTime)} ${v.videoWidth}x${v.videoHeight}`,
  ].join(' · ');
}

function describeTarget(e: Event): string {
  const t = e.target as (Node & { nodeName?: string }) | null;
  return t && t.nodeName ? t.nodeName.toLowerCase() : 'window';
}

export function installWatch() {
  if (typeof window === 'undefined') return;

  window.addEventListener('error', (e) => {
    const where = e.filename ? ` @ ${e.filename.split('/').pop()}:${e.lineno}:${e.colno}` : '';
    const message = e.error && e.error.stack ? String(e.error.stack).split('\n').slice(0, 2).join(' ') : e.message;
    watch.noteError('error', `${message}${where}`);
  });
  window.addEventListener('unhandledrejection', (e) => {
    const r = (e as PromiseRejectionEvent).reason;
    const text = r && r.stack ? String(r.stack).split('\n').slice(0, 2).join(' ') : r && r.message ? r.message : String(r);
    watch.noteError('rejection', text);
  });

  // What webOS and the browser tell the page. webOSLaunch/webOSRelaunch come on document
  // (webOSTV.js's names); the rest are the web platform's.
  const onDocument = ['visibilitychange', 'webOSLaunch', 'webOSRelaunch', 'cursorStateChange', 'keyboardStateChange', 'freeze', 'resume'];
  const onWindow = ['pagehide', 'pageshow', 'blur', 'focus', 'resize', 'online', 'offline', 'orientationchange', 'beforeunload', 'unload'];
  for (const name of onDocument) {
    document.addEventListener(name, (e) => {
      const detail = name === 'visibilitychange' ? ` → ${document.visibilityState}` : (e as CustomEvent).detail ? ` ${JSON.stringify((e as CustomEvent).detail).slice(0, 80)}` : '';
      watch.noteEvent(`${name}${detail} (${describeTarget(e)})`);
    });
  }
  for (const name of onWindow) {
    window.addEventListener(name, () => {
      const detail = name === 'resize' ? ` ${window.innerWidth}x${window.innerHeight}` : '';
      watch.noteEvent(`${name}${detail}`);
    });
  }

  // Animation frames stop when the page is hidden or its renderer is throttled; asked for one
  // every few seconds, their last arrival says whether the page is still being drawn.
  const frame = () => requestAnimationFrame(() => (watch.lastFrameAt = Date.now()));
  frame();
  setInterval(frame, FRAME_MS);

  // Removals under #app: a count, and the moment a whole screen or the app's tree goes.
  const app = document.getElementById('app');
  if (app && typeof MutationObserver === 'function') {
    new MutationObserver((records) => {
      let removed = 0;
      let gone = '';
      for (const r of records) {
        removed += r.removedNodes.length;
        for (let i = 0; i < r.removedNodes.length; i++) {
          const n = r.removedNodes[i] as Element;
          if (n.nodeType !== 1) continue;
          for (const name of ['app', 'screen', 'live', 'guide', 'player-host']) if (n.classList.contains(name)) gone += ` .${name}`;
        }
      }
      watch.removedNodes += removed;
      // A change of screen removes one on purpose (the list for the guide and back): the time
      // says whether it was the viewer's key or something else.
      if (gone || app.childElementCount === 0) watch.noteEvent(`dom: removed${gone || ' the tree'} (${removed} nodes; #app has ${app.childElementCount} children)`);
    }).observe(app, { childList: true, subtree: true });
  }

  const snapshot = () => {
    watch.snapshots.push(`${clock()} ${pageLine()}`);
    if (watch.snapshots.length > KEEP_SNAPSHOTS) watch.snapshots.shift();
  };
  setTimeout(snapshot, 5_000);
  setInterval(snapshot, SNAPSHOT_MS);
}
