import type { CatchupMode } from './model';
import { canonical } from './normalize';

/**
 * M3U/M3U8 reader: a port of the Android app's `data/m3u/M3uParser.kt`.
 *
 * Real provider playlists are routinely malformed — unquoted attributes, stray blank lines,
 * `#EXTINF` without a following URL, Windows line endings, BOMs. Nothing here throws on bad input:
 * an unusable entry is counted in `malformedLines` and skipped.
 */

export interface M3uEntry {
  name: string;
  url: string;
  tvgId?: string;
  tvgName?: string;
  tvgLogo?: string;
  tvgChno?: number;
  tvgShiftMinutes: number;
  groupTitle?: string;
  catchupMode: CatchupMode;
  catchupSource?: string;
  catchupDays: number;
  userAgent?: string;
  headers: Record<string, string>;
  extraAttributes: Record<string, string>;
}

export interface M3uPlaylist {
  entries: M3uEntry[];
  epgUrl?: string;
  globalUserAgent?: string;
  malformedLines: number;
}

const SCHEME = /^[A-Za-z][A-Za-z0-9+.-]*:\/\//;
const ATTRIBUTES = /([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))/g;
const XTREAM_PATH = /\/(?:live|movie|series)\/[^/]+\/[^/]+\/(\d+)(?:\.[a-z0-9]+)?$/i;

const KNOWN = new Set([
  'tvg-id', 'tvg-name', 'tvg-logo', 'tvg-chno', 'tvg-shift', 'group-title',
  'catchup', 'catchup-source', 'catchup-days', 'catchup-type', 'timeshift',
  'user-agent', 'channel-id', 'tvg-rec',
]);

/** FR-SRC-11 identity: tvg-id, then a stream id from the URL, then the normalised name. */
export function identityOf(entry: Pick<M3uEntry, 'tvgId' | 'url' | 'name'>): string {
  if (entry.tvgId?.trim()) return `tvg:${entry.tvgId}`;
  const sid = streamIdFromUrl(entry.url);
  if (sid) return `sid:${sid}`;
  return `name:${canonical(entry.name)}`;
}

export function streamIdFromUrl(url: string): string | undefined {
  return XTREAM_PATH.exec(url)?.[1];
}

export function catchupModeFrom(raw: string | undefined): CatchupMode {
  switch (raw?.trim().toLowerCase()) {
    case undefined: case '': case '0': case 'none': return 'NONE';
    case 'default': case '1': return 'DEFAULT';
    case 'append': return 'APPEND';
    case 'shift': case 'timeshift': return 'SHIFT';
    case 'flussonic': case 'flussonic-hls': case 'flussonic-ts': return 'FLUSSONIC';
    case 'xc': case 'xtream': case 'xtream-codes': return 'XC';
    default: return 'DEFAULT';
  }
}

function attributes(line: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of line.matchAll(ATTRIBUTES)) {
    out[m[1].toLowerCase()] = m[2] ?? m[3] ?? m[4] ?? '';
  }
  return out;
}

/**
 * The first comma outside a quoted attribute value ends the attribute list. The first, not the
 * last: names such as `Channel 4, HD` have commas of their own.
 */
function firstAttributeSafeComma(s: string): number {
  let quote: string | null = null;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (quote) {
      if (c === quote) quote = null;
    } else if (c === '"' || c === "'") {
      quote = c;
    } else if (c === ',') {
      return i;
    }
  }
  return -1;
}

/** `tvg-shift` is documented in hours and seen as `1`, `-2`, `1.5`, `+0.5`. */
export function parseShiftMinutes(raw: string | undefined): number {
  const v = raw?.trim().replace(/^\+/, '');
  if (!v) return 0;
  const hours = Number(v);
  return Number.isFinite(hours) ? Math.round(hours * 60) : 0;
}

const nonBlank = (s: string | undefined) => (s && s.trim() ? s : undefined);

interface Pending extends Omit<M3uEntry, 'url'> {}

function parseExtInf(line: string): Pending {
  const colon = line.indexOf(':');
  const afterColon = colon >= 0 ? line.slice(colon + 1) : '';
  const comma = firstAttributeSafeComma(afterColon);
  const attributePart = comma >= 0 ? afterColon.slice(0, comma) : afterColon;
  const displayName = comma >= 0 ? afterColon.slice(comma + 1).trim() : '';
  const attrs = attributes(attributePart);

  const extraAttributes: Record<string, string> = {};
  for (const [k, v] of Object.entries(attrs)) if (!KNOWN.has(k)) extraAttributes[k] = v;

  const catchupRaw = attrs['catchup'] ?? attrs['catchup-type'];
  const daysRaw = attrs['catchup-days'] ?? attrs['timeshift'] ?? attrs['tvg-rec'];
  const catchupDays = daysRaw !== undefined && /^-?\d+$/.test(daysRaw.trim()) ? parseInt(daysRaw.trim(), 10) : 0;
  let catchupMode: CatchupMode = 'NONE';
  if (catchupRaw !== undefined) catchupMode = catchupModeFrom(catchupRaw);
  else if (catchupDays > 0) catchupMode = 'DEFAULT';
  const chno = attrs['tvg-chno']?.trim();

  return {
    name: nonBlank(displayName) ?? nonBlank(attrs['tvg-name']) ?? 'Unnamed',
    tvgId: nonBlank(attrs['tvg-id']),
    tvgName: nonBlank(attrs['tvg-name']),
    tvgLogo: nonBlank(attrs['tvg-logo']),
    tvgChno: chno && /^-?\d+$/.test(chno) ? parseInt(chno, 10) : undefined,
    tvgShiftMinutes: parseShiftMinutes(attrs['tvg-shift']),
    groupTitle: nonBlank(attrs['group-title']),
    catchupMode,
    catchupSource: nonBlank(attrs['catchup-source']),
    catchupDays,
    userAgent: nonBlank(attrs['user-agent']),
    headers: {},
    extraAttributes,
  };
}

/** `http-user-agent=...`, `http-referrer=...`, `http-header=Name: value`. */
function applyVlcOpt(p: Pending, option: string) {
  const eq = option.indexOf('=');
  if (eq < 0) return;
  const key = option.slice(0, eq).trim().toLowerCase();
  const value = option.slice(eq + 1).trim();
  if (!value) return;
  switch (key) {
    case 'http-user-agent':
      p.userAgent = value;
      break;
    case 'http-referrer':
    case 'http-referer':
      p.headers['Referer'] = value;
      break;
    case 'http-origin':
      p.headers['Origin'] = value;
      break;
    case 'http-cookie':
      p.headers['Cookie'] = value;
      break;
    case 'http-header': {
      const i = value.indexOf(':');
      if (i > 0) {
        const name = value.slice(0, i).trim();
        const v = value.slice(i + 1).trim();
        if (name && v) p.headers[name] = v;
      }
      break;
    }
  }
}

function applyKodiProp(p: Pending, option: string) {
  const eq = option.indexOf('=');
  if (eq < 0) return;
  const key = option.slice(0, eq).trim().toLowerCase();
  const value = option.slice(eq + 1).trim();
  if (!value || !key.endsWith('stream_headers')) return;
  for (const pair of value.split('&')) {
    const i = pair.indexOf('=');
    if (i <= 0) continue;
    const k = pair.slice(0, i).trim();
    const v = pair.slice(i + 1).trim();
    if (!k || !v) continue;
    try {
      p.headers[k] = decodeURIComponent(v.replace(/\+/g, ' '));
    } catch {
      p.headers[k] = v;
    }
  }
}

export function parseM3u(text: string): M3uPlaylist {
  const entries: M3uEntry[] = [];
  let epgUrl: string | undefined;
  let globalUserAgent: string | undefined;
  let malformed = 0;
  let pending: Pending | null = null;

  for (const rawLine of text.split(/\r\n|\n|\r/)) {
    const line = rawLine.trim().replace(/^﻿/, '');
    if (!line) continue;
    const upper = line.slice(0, 10).toUpperCase();
    const rest = () => {
      const i = line.indexOf(':');
      return i >= 0 ? line.slice(i + 1).trim() : '';
    };

    if (upper.startsWith('#EXTM3U')) {
      const attrs = attributes(line);
      epgUrl = attrs['url-tvg'] ?? attrs['x-tvg-url'] ?? attrs['tvg-url'];
      globalUserAgent = attrs['user-agent'];
    } else if (upper.startsWith('#EXTINF')) {
      if (pending) malformed++; // the previous #EXTINF never got a URL
      pending = parseExtInf(line);
    } else if (upper.startsWith('#EXTVLCOPT')) {
      if (pending) applyVlcOpt(pending, rest());
    } else if (upper.startsWith('#KODIPROP')) {
      if (pending) applyKodiProp(pending, rest());
    } else if (upper.startsWith('#EXTGRP')) {
      const g = rest();
      if (g && pending && !pending.groupTitle) pending.groupTitle = g;
    } else if (line.startsWith('#')) {
      // a comment or a directive this player does not use
    } else if (!pending) {
      // A bare URL is still a channel, but only a URL: a suspended account answers 200 with an
      // HTML page, and taking its lines as channels would wipe the real list on refresh.
      if (SCHEME.test(line) || line.startsWith('/')) {
        const name = line.split('/').pop()?.split('?')[0] || line;
        entries.push({
          name, url: line, tvgShiftMinutes: 0, catchupMode: 'NONE', catchupDays: 0, headers: {}, extraAttributes: {},
        });
      } else {
        malformed++;
      }
    } else {
      entries.push({ ...pending, url: line, userAgent: pending.userAgent ?? globalUserAgent });
      pending = null;
    }
  }
  if (pending) malformed++;

  return { entries, epgUrl: nonBlank(epgUrl), globalUserAgent, malformedLines: malformed };
}
