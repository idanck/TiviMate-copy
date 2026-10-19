import type { M3uEntry } from './m3u';

/**
 * Xtream Codes player API — the parts of the Android app's `data/xtream/XtreamClient.kt` a live
 * player needs. Panels vary a great deal: numbers arrive as strings, optional fields are null,
 * "" or missing, and some answer with an HTML page. Every read is defensive.
 */

export interface XtreamAccount {
  auth: boolean;
  status?: string;
  expiryUtc?: number;
  maxConnections?: number;
  activeConnections?: number;
  allowedOutputFormats: string[];
}

/** URLSearchParams-style encoding, as Java's URLEncoder does it (spaces as +). */
export function enc(s: string): string {
  return encodeURIComponent(s).replace(/%20/g, '+').replace(/[!'()~]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());
}

export function rootOf(baseUrl: string): string {
  return baseUrl.trim().replace(/\/+$/, '').replace(/\/(player_api|get)\.php.*$/i, '');
}

export function apiUrl(baseUrl: string, username: string, password: string, action?: string): string {
  return `${rootOf(baseUrl)}/player_api.php?username=${enc(username)}&password=${enc(password)}${action ? `&action=${action}` : ''}`;
}

export function xmltvUrl(baseUrl: string, username: string, password: string): string {
  return `${rootOf(baseUrl)}/xmltv.php?username=${enc(username)}&password=${enc(password)}`;
}

export function liveUrl(baseUrl: string, username: string, password: string, streamId: string, format: 'TS' | 'HLS'): string {
  return `${rootOf(baseUrl)}/live/${enc(username)}/${enc(password)}/${streamId}.${format === 'HLS' ? 'm3u8' : 'ts'}`;
}

function str(v: unknown): string | undefined {
  if (v === null || v === undefined) return undefined;
  const s = String(v).trim();
  return s && s !== 'null' ? s : undefined;
}

function int(v: unknown): number | undefined {
  const s = str(v);
  return s && /^-?\d+$/.test(s) ? parseInt(s, 10) : undefined;
}

function json(body: string): unknown {
  try {
    return JSON.parse(body);
  } catch {
    return null;
  }
}

export function parseAccount(body: string): XtreamAccount | null {
  const root = json(body) as { user_info?: Record<string, unknown> } | null;
  const info = root?.user_info;
  if (!info) return null;
  const exp = int(info.exp_date);
  return {
    auth: String(info.auth ?? '1') !== '0',
    status: str(info.status),
    expiryUtc: exp !== undefined ? exp * 1000 : undefined,
    maxConnections: int(info.max_connections),
    activeConnections: int(info.active_cons),
    allowedOutputFormats: Array.isArray(info.allowed_output_formats) ? info.allowed_output_formats.map(String) : [],
  };
}

export function parseCategories(body: string): Map<string, string> {
  const out = new Map<string, string>();
  const arr = json(body);
  if (!Array.isArray(arr)) return out;
  for (const o of arr) {
    const id = str(o?.category_id);
    const name = str(o?.category_name);
    if (id && name) out.set(id, name);
  }
  return out;
}

/** Live channels, shaped like playlist entries so one path ingests both kinds of source. */
export function parseLiveStreams(
  body: string,
  categories: Map<string, string>,
  build: (streamId: string) => string,
): M3uEntry[] {
  const arr = json(body);
  if (!Array.isArray(arr)) return [];
  const out: M3uEntry[] = [];
  for (const o of arr) {
    if (!o || typeof o !== 'object') continue;
    const streamId = str(o.stream_id);
    if (!streamId) continue;
    const name = str(o.name) ?? `Channel ${streamId}`;
    const days = int(o.tv_archive_duration) ?? 0;
    const archive = (int(o.tv_archive) ?? 0) > 0;
    out.push({
      name,
      url: build(streamId),
      tvgId: str(o.epg_channel_id),
      tvgName: name,
      tvgLogo: str(o.stream_icon),
      tvgChno: int(o.num),
      tvgShiftMinutes: 0,
      groupTitle: categories.get(str(o.category_id) ?? '') ?? 'Live',
      catchupMode: archive && days > 0 ? 'XC' : 'NONE',
      catchupDays: archive ? days : 0,
      headers: {},
      extraAttributes: {},
    });
  }
  return out;
}
