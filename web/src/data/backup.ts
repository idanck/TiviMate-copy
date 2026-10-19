import { BUILT_IN_SOURCES, BUILT_IN_URLS } from './builtin';
import type { Source, SourceType } from './model';

/**
 * The Android app's backup file (`data/backup/BackupManager.kt`, format 1), read and written here
 * so the computer and the television can carry the same playlists and favourites. Credentials
 * travel only in a file exported with them — the Android default is without.
 */

export interface BackupImport {
  sources: Source[];
  /** Favourite channel keys, under the new sources' ids. */
  favourites: string[];
  epgOffsetMinutes?: number;
  userAgent?: string;
  /** Sources that will need their password typed in again, by name. */
  missingCredentials: string[];
  skipped: number;
}

interface BackupPlaylist {
  id: number | string;
  name?: string;
  type?: string;
  url?: string;
  outputFormat?: string;
  userAgent?: string | null;
  headers?: Record<string, string>;
  epgUrl?: string | null;
  enabled?: boolean;
  sortIndex?: number;
  username?: string;
  password?: string;
}

interface BackupFile {
  format?: number;
  playlists?: BackupPlaylist[];
  channels?: Array<{ playlistId: number | string; identity?: string; favourite?: boolean }>;
  settings?: Record<string, unknown>;
}

export class BackupError extends Error {}

const newId = () => `src-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

export function readBackup(text: string, existing: Source[]): BackupImport {
  let file: BackupFile;
  try {
    file = JSON.parse(text);
  } catch {
    throw new BackupError('הקובץ אינו קובץ גיבוי של שידור');
  }
  if (!Array.isArray(file.playlists)) throw new BackupError('הקובץ אינו קובץ גיבוי של שידור');
  if ((file.format ?? 1) > 1) throw new BackupError('הגיבוי נוצר בגרסה חדשה יותר של האפליקציה');

  const idFor = new Map<string, string>();
  const sources: Source[] = [];
  const missingCredentials: string[] = [];
  let skipped = 0;
  const known = new Set(existing.map((s) => `${s.type}|${s.url}|${s.username ?? ''}`));
  let sortIndex = existing.reduce((m, s) => Math.max(m, s.sortIndex), 0) + 1;

  for (const p of file.playlists) {
    const url = (p.url ?? '').trim();
    const builtIn = BUILT_IN_SOURCES.find((b) => b.url === url || (BUILT_IN_URLS.has(url) && b.role === (url.includes('radio') ? 'radio' : 'tv')));
    if (builtIn) {
      idFor.set(String(p.id), builtIn.id);
      continue;
    }
    const type = p.type as SourceType;
    // An uploaded file lives on the device that read it; the backup holds only its address there.
    if (type !== 'M3U_URL' && type !== 'XTREAM') {
      skipped++;
      continue;
    }
    const dupKey = `${type}|${url}|${p.username ?? ''}`;
    const twin = existing.find((s) => `${s.type}|${s.url}|${s.username ?? ''}` === dupKey);
    if (twin || known.has(dupKey)) {
      if (twin) idFor.set(String(p.id), twin.id);
      continue;
    }
    known.add(dupKey);
    const id = newId();
    idFor.set(String(p.id), id);
    const source: Source = {
      id,
      name: p.name?.trim() || (type === 'XTREAM' ? 'Xtream' : 'רשימה'),
      type,
      role: 'tv',
      url,
      username: p.username || undefined,
      password: p.password || undefined,
      outputFormat: p.outputFormat === 'HLS' ? 'HLS' : 'TS',
      userAgent: p.userAgent || undefined,
      headers: p.headers ?? {},
      epgUrl: p.epgUrl || undefined,
      enabled: p.enabled !== false,
      sortIndex: sortIndex++,
    };
    if (type === 'XTREAM' && (!source.username || !source.password)) missingCredentials.push(source.name);
    sources.push(source);
  }

  const favourites = (file.channels ?? [])
    .filter((c) => c.favourite && c.identity && idFor.has(String(c.playlistId)))
    .map((c) => `${idFor.get(String(c.playlistId))}|${c.identity}`);

  const offset = Number(file.settings?.['epg_offset']);
  const ua = file.settings?.['user_agent'];
  return {
    sources,
    favourites,
    epgOffsetMinutes: Number.isFinite(offset) ? offset : undefined,
    userAgent: typeof ua === 'string' && ua.trim() ? ua.trim() : undefined,
    missingCredentials,
    skipped,
  };
}

/** A backup the Android app can restore (Settings → Backup → Restore). */
export function writeBackup(
  sources: Source[],
  favourites: Iterable<string>,
  settings: { epgOffsetMinutes: number; userAgent?: string },
  includeCredentials: boolean,
): string {
  const exported = sources.filter((s) => s.type !== 'M3U_FILE');
  const number = new Map(exported.map((s, i) => [s.id, i + 1]));
  const playlists = exported.map((s) => ({
    id: number.get(s.id),
    name: s.name,
    type: s.type,
    url: includeCredentials ? s.url : stripCredentials(s.url),
    outputFormat: s.outputFormat,
    userAgent: s.userAgent ?? null,
    headers: s.headers,
    epgUrl: s.epgUrl && (includeCredentials || stripCredentials(s.epgUrl) === s.epgUrl) ? s.epgUrl : null,
    refreshIntervalMinutes: 720,
    enabled: s.enabled,
    colour: 0xff3d7bff | 0,
    sortIndex: s.sortIndex,
    ...(includeCredentials && s.type === 'XTREAM' ? { username: s.username ?? '', password: s.password ?? '' } : {}),
  }));
  const channels = [...favourites].flatMap((key) => {
    const bar = key.indexOf('|');
    const id = number.get(key.slice(0, bar));
    if (id === undefined) return [];
    return [{
      playlistId: id, identity: key.slice(bar + 1), customName: null, customLogoUrl: null, number: null,
      favourite: true, hidden: false, locked: false, sortIndex: 0, epgOffsetMinutes: null, manualEpgId: null,
      videoDecoder: 'AUTO', audioDecoder: 'AUTO',
    }];
  });
  const prefs: Record<string, unknown> = { epg_offset: settings.epgOffsetMinutes };
  if (settings.userAgent) prefs.user_agent = settings.userAgent;
  return JSON.stringify(
    { format: 1, createdUtc: Date.now(), includesCredentials: includeCredentials, playlists, groups: [], channels, settings: prefs },
    null,
    2,
  );
}

/** As the Android app's BackupCredentials.strip: account parameters and user:pass@ removed. */
export function stripCredentials(url: string): string {
  let u: URL;
  try {
    u = new URL(url);
  } catch {
    return url;
  }
  if (!u.username && !u.password && !u.searchParams.has('username') && !u.searchParams.has('password')) return url;
  u.username = '';
  u.password = '';
  u.searchParams.delete('username');
  u.searchParams.delete('password');
  return u.href;
}
