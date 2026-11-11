import { engine, playback, playerLog } from '../player/engine';
import { idleTakeover, isFullscreen, liveFilter, radioFilter, screen, visibleChannels, visibleStations } from '../state/actions';
import { guideProgress, radioStations, refreshing, sources, storageOk, tvChannels } from '../state/store';
import { listDebug, remPx } from './common';
import { guideDebug } from './GuideScreen';
import { idle, IDLE_MINUTES, idleLine } from './idle';
import { playerBox } from './PlayerHost';
import { shellInfo, shellLine } from './shell';
import { memory, pageLine, watch } from './watch';

/**
 * What the app says about itself, for tools/cdp.mjs on the owner's phone: `cdp.mjs state` prints
 * `shidur.state().report`. The DOM can say "yes" while the screen shows otherwise (ADR-057): this
 * is evidence to read beside what the owner sees, never a substitute for it.
 */

function describe(el: Element | null): string {
  if (!el || el === document.body) return 'nothing';
  const h = el as HTMLElement;
  const row = h.closest('[data-index]');
  const name = h.querySelector?.('.name')?.textContent ?? h.textContent ?? '';
  const where = row ? `row ${row.getAttribute('data-index')}` : h.className || h.tagName.toLowerCase();
  return `${where}: ${name.trim().slice(0, 40)}`;
}

function boxOf(el: Element | null) {
  if (!el) return null;
  const r = el.getBoundingClientRect();
  return { top: Math.round(r.top), left: Math.round(r.left), width: Math.round(r.width), height: Math.round(r.height) };
}

export function state() {
  const v = engine.media;
  const pb = playback.value;
  const focusedRow = document.activeElement?.closest?.('[data-index]') as HTMLElement | null;
  const list = document.querySelector('.vlist');
  const rowBox = boxOf(focusedRow);
  const listBox = boxOf(list);
  const rowShown = !!(rowBox && listBox && rowBox.top >= listBox.top - 1 && rowBox.top + rowBox.height <= listBox.top + listBox.height + 1);
  const app = document.getElementById('app');
  const screenEl = document.querySelector('.screen');
  const data = {
    version: `${__APP_VERSION__} ${__APP_SHA__}`,
    window: `${window.innerWidth}x${window.innerHeight} dpr ${window.devicePixelRatio} rem ${remPx()}px`,
    userAgent: navigator.userAgent,
    screen: screen.value,
    focus: describe(document.activeElement),
    // The vanishing interface (ui/watch.ts): is the tree there, is the page shown, did anything throw.
    page: {
      uptimeMin: Math.round((Date.now() - watch.startedAt) / 60_000),
      visibility: document.visibilityState,
      hidden: document.hidden,
      hasFocus: document.hasFocus(),
      nodes: app ? app.getElementsByTagName('*').length : -1,
      screenNodes: screenEl ? screenEl.getElementsByTagName('*').length : -1,
      screenPresent: !!document.querySelector('.live, .guide, .radio, .boot, .crashed'),
      appStyle: app ? (() => { const cs = getComputedStyle(app.firstElementChild ?? app); return `display ${cs.display} visibility ${cs.visibility} opacity ${cs.opacity}`; })() : '-',
      renders: watch.renders,
      lastRenderAgoS: watch.lastRenderAt ? Math.round((Date.now() - watch.lastRenderAt) / 1000) : -1,
      lastFrameAgoS: watch.lastFrameAt ? Math.round((Date.now() - watch.lastFrameAt) / 1000) : -1,
      removedNodes: watch.removedNodes,
      memory: memory(),
    },
    errors: watch.errors,
    events: watch.events,
    snapshots: watch.snapshots,
    // Option B (ui/idle.ts): whether the app has put the picture over the screen by itself.
    idle: { takenOver: idleTakeover.value, limitMin: Math.round(idle.limitMs / 60_000), lastKeyAgoS: Math.round((Date.now() - idle.lastActivityAt) / 1000), line: idleLine() },
    guide: screen.value === 'guide' ? { ...guideDebug } : null,
    radio: { filter: radioFilter.value, shown: visibleStations.value.length, all: radioStations.value.length },
    list: { ...listDebug, filter: liveFilter.value, shown: visibleChannels.value.length, all: tvChannels.value.length, focusedRowInBox: rowShown },
    player: {
      status: pb.status,
      channel: pb.channel?.name ?? null,
      message: pb.message ?? null,
      fullscreen: isFullscreen.value,
      role: pb.role ?? null,
      readyState: v.readyState,
      networkState: v.networkState,
      error: v.error ? v.error.code : null,
      size: `${v.videoWidth}x${v.videoHeight}`,
      element: boxOf(v),
      slot: playerBox.slot,
      log: playerLog.slice(-8),
    },
    // The shell (shell/boot.js, ADR-057 0.4.0): which bundle runs, the last update check, storage.
    shell: shellInfo(),
    storageOk: storageOk.value,
    sources: sources.value.map((s) => ({
      name: s.name,
      enabled: s.enabled,
      refreshing: refreshing.value[s.id] ?? null,
      result: s.lastRefreshMessage ?? null,
      guide: guideProgress.value[s.id] ?? null,
    })),
  };
  const p = data.player;
  const pg = data.page;
  const g = data.guide;
  const report = [
    `Shidur ${data.version} · ${data.window} · up ${pg.uptimeMin} min · screen ${data.screen}`,
    `page: ${pg.visibility}${pg.hidden ? ' (hidden)' : ''} · window focus ${pg.hasFocus ? 'yes' : 'no'} · nodes ${pg.nodes} (screen ${pg.screenNodes}, present ${pg.screenPresent ? 'yes' : 'NO'}) · ${pg.appStyle} · renders ${pg.renders} (last ${pg.lastRenderAgoS}s ago) · frame ${pg.lastFrameAgoS}s ago · removed ${pg.removedNodes} nodes · heap ${pg.memory ? `${pg.memory.usedMB}/${pg.memory.limitMB} MB` : 'not reported'}`,
    `errors (${data.errors.length}): ${data.errors.map((e) => `${e.at} ${e.text}`).join(' | ') || 'none'}`,
    `events (${data.events.length}): ${data.events.map((e) => `${e.at} ${e.text}`).join(' | ') || 'none'}`,
    `idle: ${data.idle.line}`,
    `focus: ${data.focus} · focused row inside the list box: ${rowShown ? 'yes' : 'no'}`,
    ...(data.screen === 'radio' ? [`radio: shelf ${data.radio.filter} · ${data.radio.shown} of ${data.radio.all} stations`] : []),
    g
      ? `guide: ${g.day} · row ${g.row} cell ${g.col} · offset x ${g.offsetX}px y ${g.offsetY}px · rows drawn ${g.first}-${g.last} · cells drawn ${g.cells} · grid ${g.active ? 'has' : 'does not have'} focus · view ${Math.round(g.viewW)}x${Math.round(g.viewH)}px`
      : `list: offset ${listDebug.offset}px · focus ${listDebug.focus} · drawn ${listDebug.first}-${listDebug.last} of ${listDebug.count} · row ${Math.round(listDebug.rowPx)}px · view ${listDebug.viewPx}px · stray scrolls ${listDebug.strayScrolls}`,
    `player: ${p.status}${p.role ? ` ${p.role}` : ''}${p.channel ? ` "${p.channel}"` : ''}${p.message ? ` (${p.message})` : ''} · full screen ${p.fullscreen ? 'on' : 'off'} · ready ${p.readyState} net ${p.networkState} error ${p.error ?? '-'} · picture ${p.size}`,
    `player box: element ${JSON.stringify(p.element)} · slot ${JSON.stringify(p.slot)}`,
    `player log: ${p.log.join(' | ') || '-'}`,
    `storage: ${data.storageOk ? 'ok' : 'FAILING'}`,
    ...shellLine().split('\n'),
    ...data.sources.map((s) => `source "${s.name}"${s.enabled ? '' : ' (off)'}: ${s.refreshing ?? s.result ?? 'not loaded'}${s.guide ? ` · ${s.guide}` : ''}`),
    `now: ${pageLine()}`,
    `the last minutes (one line a minute):`,
    ...data.snapshots.slice(-12).map((line) => `  ${line}`),
  ].join('\n');
  return { ...data, report };
}

declare global {
  interface Window {
    shidur?: { state: typeof state; idle: (minutes: number) => string };
  }
}

export function installDebug() {
  window.shidur = {
    state,
    // For tools/cdp.mjs `idle <minutes>`: see the takeover without waiting 25 minutes. Until the
    // app is closed; 0 puts back the owner's figure.
    idle: (minutes: number) => {
      idle.limitMs = (minutes > 0 ? minutes : IDLE_MINUTES) * 60_000;
      return idleLine();
    },
  };
}
