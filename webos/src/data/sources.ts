import { fetchText, HttpError, requestHeaders, type RequestHeaders } from './api';
import { identityOf, parseM3u, type M3uEntry } from './m3u';
import type { Channel, Source } from './model';
import { apiUrl, liveUrl, parseAccount, parseCategories, parseLiveStreams, xmltvUrl } from './xtream';

/** A source's channels, read fresh from the provider. */
export interface LoadedSource {
  channels: Channel[];
  epgUrl?: string;
  /** Shown under the source in Settings: "1,204 channels", or what went wrong. */
  message: string;
}

export class SourceError extends Error {}

/**
 * Turns parsed entries into channels. A playlist may repeat one identity — backup URLs for the
 * same channel — and those become the channel's failover list rather than a second row
 * (data/repo/SourceRepository.kt).
 */
export function buildChannels(entries: M3uEntry[], sourceId: string): Channel[] {
  const byIdentity = new Map<string, Channel>();
  entries.forEach((e, order) => {
    const identity = identityOf(e);
    const existing = byIdentity.get(identity);
    if (existing) {
      if (!existing.urls.includes(e.url)) existing.urls.push(e.url);
      return;
    }
    byIdentity.set(identity, {
      key: `${sourceId}|${identity}`,
      sourceId,
      identity,
      name: e.name,
      tvgId: e.tvgId,
      logoUrl: e.tvgLogo,
      group: e.groupTitle ?? '',
      number: e.tvgChno,
      urls: [e.url],
      userAgent: e.userAgent,
      headers: e.headers,
      tvgShiftMinutes: e.tvgShiftMinutes,
      catchupMode: e.catchupMode,
      catchupDays: e.catchupDays,
      order,
    });
  });
  return [...byIdentity.values()];
}

export function sourceHeaders(source: Source, globalUserAgent?: string): RequestHeaders {
  return requestHeaders(source.userAgent || globalUserAgent, source.headers);
}

/**
 * A failed request, in the viewer's words. The service tells a dead address from a refused
 * certificate (service/net.js `codeOf`); the first version said "no connection" for all of them.
 */
export function describe(err: unknown): string {
  if (err instanceof SourceError) return err.message;
  if (err instanceof HttpError) {
    switch (err.code) {
      case 'timeout': return 'הספק לא ענה בזמן';
      case 'dns': return 'שם השרת של הספק לא נמצא ברשת';
      case 'refused': return 'השרת של הספק דחה את החיבור';
      case 'reset': return 'החיבור לספק נקטע באמצע';
      case 'tls-cert': return 'הטלוויזיה לא מקבלת את תעודת האבטחה של הספק';
      case 'tls': return 'החיבור המאובטח לספק נכשל';
      case 'unreachable': return 'אין חיבור לספק';
      case 'bad-url': return 'הכתובת אינה תקינה';
      case 'redirects': return 'הספק מפנה מכתובת לכתובת בלי סוף';
      case 'too-large': return 'התשובה של הספק גדולה מדי';
      case 'gzip': return 'הקובץ הדחוס של הספק פגום';
      case 'stalled': return 'ההורדה נתקעה';
      case 'no-luna': return 'שירות הרשת של האפליקציה אינו זמין';
      default:
        if (err.status === 401 || err.status === 403) return `הספק סירב לבקשה (${err.status})`;
        if (err.status === 404) return 'הכתובת לא נמצאה (404)';
        if (err.status) return `שגיאה מהספק (${err.status})`;
        return `החיבור לספק נכשל (${err.code})`;
    }
  }
  if (err instanceof DOMException && err.name === 'AbortError') return 'בוטל';
  return 'אין חיבור לספק';
}

const countLabel = (n: number, source: Source) => `${n.toLocaleString('he-IL')} ${source.role === 'radio' ? 'תחנות' : 'ערוצים'}`;

export async function loadSource(source: Source, globalUserAgent?: string): Promise<LoadedSource> {
  try {
    const h = sourceHeaders(source, globalUserAgent);
    if (source.type === 'XTREAM') {
      const user = source.username ?? '';
      const pass = source.password ?? '';
      const account = parseAccount(await fetchText(apiUrl(source.url, user, pass), h));
      if (!account) throw new SourceError('הכתובת לא נראית כמו שרת Xtream');
      if (!account.auth) throw new SourceError('שם המשתמש או הסיסמה שגויים');
      if (account.status && account.status.toLowerCase() !== 'active') {
        throw new SourceError(`החשבון אינו פעיל (${account.status})`);
      }
      const categories = parseCategories(await fetchText(apiUrl(source.url, user, pass, 'get_live_categories'), h).catch(() => '[]'));
      const streams = await fetchText(apiUrl(source.url, user, pass, 'get_live_streams'), h);
      const entries = parseLiveStreams(streams, categories, (id) => liveUrl(source.url, user, pass, id, source.outputFormat));
      if (entries.length === 0) throw new SourceError('הספק לא החזיר ערוצים');
      const channels = buildChannels(entries, source.id);
      return {
        channels,
        epgUrl: source.epgUrl || xmltvUrl(source.url, user, pass),
        message: countLabel(channels.length, source),
      };
    }

    const text = source.type === 'M3U_FILE' ? source.fileText ?? '' : await fetchText(source.url, h);
    const playlist = parseM3u(text);
    if (playlist.entries.length === 0) {
      // A suspended account or a captive portal answers 200 with an HTML page; taking that as
      // an empty list would wipe the channels the user has.
      throw new SourceError(playlist.malformedLines > 0 ? 'התשובה אינה רשימת ערוצים' : 'הרשימה ריקה');
    }
    const channels = buildChannels(playlist.entries, source.id);
    return {
      channels,
      epgUrl: source.epgUrl || playlist.epgUrl,
      message: countLabel(channels.length, source),
    };
  } catch (err) {
    throw new SourceError(describe(err));
  }
}
