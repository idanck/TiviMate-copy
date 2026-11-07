import { engine, playback, playerLog } from '../player/engine';
import { isFullscreen, liveFilter, visibleChannels } from '../state/actions';
import { guideProgress, refreshing, sources, storageOk, tvChannels } from '../state/store';
import { listDebug, remPx } from './common';
import { playerBox } from './PlayerHost';

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
  const data = {
    version: `${__APP_VERSION__} ${__APP_SHA__}`,
    window: `${window.innerWidth}x${window.innerHeight} dpr ${window.devicePixelRatio} rem ${remPx()}px`,
    userAgent: navigator.userAgent,
    focus: describe(document.activeElement),
    list: { ...listDebug, filter: liveFilter.value, shown: visibleChannels.value.length, all: tvChannels.value.length, focusedRowInBox: rowShown },
    player: {
      status: pb.status,
      channel: pb.channel?.name ?? null,
      message: pb.message ?? null,
      fullscreen: isFullscreen.value,
      readyState: v.readyState,
      networkState: v.networkState,
      error: v.error ? v.error.code : null,
      size: `${v.videoWidth}x${v.videoHeight}`,
      element: boxOf(v),
      slot: playerBox.slot,
      log: playerLog.slice(-8),
    },
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
  const report = [
    `Shidur ${data.version} · ${data.window}`,
    `focus: ${data.focus} · focused row inside the list box: ${rowShown ? 'yes' : 'no'}`,
    `list: offset ${listDebug.offset}px · focus ${listDebug.focus} · drawn ${listDebug.first}-${listDebug.last} of ${listDebug.count} · row ${Math.round(listDebug.rowPx)}px · view ${listDebug.viewPx}px · stray scrolls ${listDebug.strayScrolls}`,
    `player: ${p.status}${p.channel ? ` "${p.channel}"` : ''}${p.message ? ` (${p.message})` : ''} · full screen ${p.fullscreen ? 'on' : 'off'} · ready ${p.readyState} net ${p.networkState} error ${p.error ?? '-'} · picture ${p.size}`,
    `player box: element ${JSON.stringify(p.element)} · slot ${JSON.stringify(p.slot)}`,
    `player log: ${p.log.join(' | ') || '-'}`,
    `storage: ${data.storageOk ? 'ok' : 'FAILING'}`,
    ...data.sources.map((s) => `source "${s.name}"${s.enabled ? '' : ' (off)'}: ${s.refreshing ?? s.result ?? 'not loaded'}${s.guide ? ` · ${s.guide}` : ''}`),
  ].join('\n');
  return { ...data, report };
}

declare global {
  interface Window {
    shidur?: { state: typeof state };
  }
}

export function installDebug() {
  window.shidur = { state };
}
