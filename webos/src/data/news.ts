/**
 * Headlines for News mode (ADR-053): the panel of headlines and the running strip. Ports of the
 * Android app's `data/news/NewsFeed.kt` (the reader), `NewsSources.kt` (sources, the merged list)
 * and `SuggestedNewsSources.kt` (the catalogue Settings offers), held to the same rules.
 *
 * The reader works on text, not on a DOM: the service hands it over as text (data/api.ts), and
 * the tests run without a browser. It knows the formats news sites publish, by their content:
 * RSS 2.0 and 1.0, Atom, JSON Feed and any JSON list whose items carry a title.
 */

export interface NewsFlash {
  /** Stable within one source; what the merged list de-duplicates on before the title. */
  id: string;
  title: string;
  /** When the source says it was published, or undefined when it gave no date this reader knows. */
  publishedUtc?: number;
}

export interface NewsFeed {
  title?: string;
  items: NewsFlash[];
}

/** A source the viewer switched on in Settings: an address, and whether its headlines show. */
export interface NewsSource {
  url: string;
  enabled: boolean;
}

/** What one source last said, kept so the screen has headlines before its first fetch returns. */
export interface NewsDigest {
  title?: string;
  items: NewsFlash[];
  fetchedUtc: number;
}

/** A headline on its way to the screen, with its source's name and its place in the list. */
export interface SourcedFlash {
  flash: NewsFlash;
  source: string;
  /** The source's place in the settings list, which is what picks its colour. */
  colourIndex: number;
}

export class FeedError extends Error {
  constructor(readonly code: 'not-a-feed' | 'not-xml' | 'not-json') {
    super(code);
  }
}

export const MAX_ITEMS = 20;
export const MAX_SOURCES = 8;
export const PER_SOURCE = 8;
export const MAX_FLASHES = 16;
export const MAX_AGE_MS = 24 * 3600_000;
/** How stale the headlines may get before they are read again (`NewsRepository`, ten minutes). */
export const CHECK_INTERVAL_MS = 10 * 60_000;
const FUTURE_SLACK_MS = 3600_000;
const DAY_MS = 24 * 3600_000;
const MAX_TITLE_FALLBACK = 160;
const MAX_LABEL = 22;
/** A number this large is milliseconds already; below it, seconds. */
const MILLIS_THRESHOLD = 100_000_000_000;

/**
 * The suggestions Settings offers, breaking news first, as on Android (ADR-038). None is on by
 * default: which newsroom scrolls past on somebody's television is theirs to choose.
 */
export const SUGGESTED_NEWS_SOURCES: Array<{ id: string; name: string; url: string; flashes: boolean; timed: boolean }> = [
  { id: 'ynet', name: 'ynet', url: 'https://www.ynet.co.il/Integration/StoryRss1854.xml', flashes: true, timed: true },
  { id: 'walla', name: 'וואלה', url: 'https://www.walla.co.il/rss/feed/news/breaking-news', flashes: true, timed: true },
  { id: 'maariv', name: 'מעריב', url: 'https://www.maariv.co.il/Rss/RssFeedsMivzakiChadashot', flashes: true, timed: true },
  { id: 'i24', name: 'i24NEWS', url: 'https://api.i24news.tv/v2/he/news', flashes: true, timed: false },
  { id: 'n12', name: 'N12', url: 'https://rcs.mako.co.il/rss/news-israel.xml', flashes: false, timed: true },
  { id: 'israel_hayom', name: 'ישראל היום', url: 'https://www.israelhayom.co.il/rss.xml', flashes: false, timed: true },
  { id: 'inn', name: 'ערוץ 7', url: 'https://www.inn.co.il/Rss.aspx', flashes: false, timed: true },
  { id: 'srugim', name: 'סרוגים', url: 'https://www.srugim.co.il/feed', flashes: false, timed: true },
];

/** HTTPS only, as on Android: a headline is not worth a cleartext request. */
export function acceptableSource(url: string): boolean {
  const t = url.trim();
  if (!/^https:\/\//i.test(t)) return false;
  try {
    return new URL(t).hostname.length > 0;
  } catch {
    return false;
  }
}

/* ---------- reading a feed ---------- */

/** Reads a feed document. Throws [FeedError] for a page that is not one (an HTML page, most often). */
export function parseFeed(raw: string, nowUtc = Date.now()): NewsFeed {
  const text = raw.replace(/^﻿/, '').trim();
  if (!text) return { items: [] };
  if (text[0] === '{' || text[0] === '[') return parseJson(text, nowUtc);
  return parseXml(text, nowUtc);
}

const FEED_ROOTS = new Set(['rss', 'feed', 'rdf']);
const DATE_TAGS = ['pubdate', 'published', 'date', 'issued', 'updated', 'modified'];

/** `dc:date` is `date`, `atom:link` is `link`: the prefix is never what decides. */
const local = (name: string) => name.replace(/^[^:]*:/, '').toLowerCase();

function parseXml(text: string, nowUtc: number): NewsFeed {
  // Declarations, comments and doctypes are not content.
  const body = text.replace(/<\?[\s\S]*?\?>/g, '').replace(/<!--[\s\S]*?-->/g, '').replace(/<!DOCTYPE[^>]*>/gi, '').trim();
  const root = /^<([A-Za-z][\w:.-]*)/.exec(body);
  if (!root) throw new FeedError('not-xml');
  if (!FEED_ROOTS.has(local(root[1]))) throw new FeedError('not-a-feed');

  // The feed's own title is the first <title> before the first item.
  const firstItem = body.search(/<(?:[\w-]+:)?(?:item|entry)[\s>]/i);
  const head = firstItem >= 0 ? body.slice(0, firstItem) : body;
  const feedTitle = clean(elementText(head, 'title') ?? '') || undefined;

  const items: NewsFlash[] = [];
  const seen = new Set<string>();
  const itemRe = /<((?:[\w-]+:)?(?:item|entry))(?:\s[^>]*)?>([\s\S]*?)<\/\1\s*>/gi;
  let m: RegExpExecArray | null;
  let index = 0;
  while ((m = itemRe.exec(body)) && items.length < MAX_ITEMS) {
    const flash = readItem(m[2], index++, nowUtc);
    if (flash && !seen.has(flash.id)) {
      seen.add(flash.id);
      items.push(flash);
    }
  }
  return { title: feedTitle, items };
}

/** The text of the first `<tag>` (any prefix) in [xml], markup inside it dropped; undefined when absent. */
function elementText(xml: string, tag: string): string | undefined {
  const re = new RegExp(`<((?:[\\w-]+:)?${tag})(?:\\s[^>]*)?(?:/>|>([\\s\\S]*?)</\\1\\s*>)`, 'i');
  const m = re.exec(xml);
  if (!m) return undefined;
  return unwrapCdata(m[2] ?? '');
}

function attribute(xml: string, tag: string, name: string): string | undefined {
  const re = new RegExp(`<(?:[\\w-]+:)?${tag}\\s[^>]*?${name}\\s*=\\s*("([^"]*)"|'([^']*)')`, 'i');
  const m = re.exec(xml);
  return m ? (m[2] ?? m[3]) : undefined;
}

function unwrapCdata(s: string): string {
  return s.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1');
}

/** One `<item>` or `<entry>`; undefined when it has nothing to print. */
function readItem(xml: string, index: number, nowUtc: number): NewsFlash | undefined {
  // An Atom entry's `<source>` names the feed it was copied from, and must not replace its own title.
  const own = xml.replace(/<(?:[\w-]+:)?source[\s>][\s\S]*?<\/(?:[\w-]+:)?source\s*>/gi, '');
  const title = elementText(own, 'title') ?? '';
  const description = elementText(own, 'description') ?? elementText(own, 'summary') ?? '';
  const id = (elementText(own, 'guid') ?? elementText(own, 'id') ?? '').trim();
  const link = (attribute(own, 'link', 'href') ?? elementText(own, 'link') ?? '').trim();
  let date: string | undefined;
  for (const tag of DATE_TAGS) {
    date = elementText(own, tag);
    if (date !== undefined) break;
  }
  const headline = clean(title) || clean(description).slice(0, MAX_TITLE_FALLBACK);
  if (!headline) return undefined;
  return { id: id || link || `${index}:${headline}`, title: headline, publishedUtc: date ? parseDate(date, nowUtc) : undefined };
}

const JSON_LISTS = ['items', 'flashes', 'news', 'articles', 'entries', 'data'];
const JSON_TITLES = ['title', 'headline', 'text', 'content_text', 'summary'];
const JSON_IDS = ['id', 'guid', 'url', 'link'];
const JSON_DATES = ['date_published', 'published', 'pubDate', 'date', 'time', 'timestamp', 'created_at', 'date_modified', 'updated'];

function parseJson(text: string, nowUtc: number): NewsFeed {
  let doc: unknown;
  try {
    doc = JSON.parse(text);
  } catch {
    throw new FeedError('not-json');
  }
  let title: string | undefined;
  let list: unknown[] | undefined;
  if (Array.isArray(doc)) {
    list = doc;
  } else if (doc && typeof doc === 'object') {
    const obj = doc as Record<string, unknown>;
    title = typeof obj.title === 'string' ? clean(obj.title) || undefined : undefined;
    for (const key of JSON_LISTS) {
      if (Array.isArray(obj[key])) {
        list = obj[key] as unknown[];
        break;
      }
    }
  }
  const items: NewsFlash[] = [];
  const seen = new Set<string>();
  (list ?? []).forEach((entry, index) => {
    if (items.length >= MAX_ITEMS || !entry || typeof entry !== 'object') return;
    const obj = entry as Record<string, unknown>;
    const field = (keys: string[]) => {
      for (const k of keys) {
        const v = obj[k];
        if (v !== undefined && v !== null && String(v).trim()) return String(v).trim();
      }
      return undefined;
    };
    let headline: string | undefined;
    for (const k of JSON_TITLES) {
      const v = typeof obj[k] === 'string' ? clean(obj[k] as string) : '';
      if (v) {
        headline = v.slice(0, MAX_TITLE_FALLBACK);
        break;
      }
    }
    if (!headline) return;
    const id = field(JSON_IDS) ?? `${index}:${headline}`;
    if (seen.has(id)) return;
    seen.add(id);
    const date = field(JSON_DATES);
    items.push({ id, title: headline, publishedUtc: date ? parseDate(date, nowUtc) : undefined });
  });
  return { title, items };
}

/* ---------- dates ---------- */

/** The zones RFC 822 names, and the two an Israeli newsroom is likely to print. */
const NAMED_ZONES: Record<string, string> = {
  GMT: '+0000', UT: '+0000', UTC: '+0000', Z: '+0000',
  EST: '-0500', EDT: '-0400', CST: '-0600', CDT: '-0500',
  MST: '-0700', MDT: '-0600', PST: '-0800', PDT: '-0700',
  IDT: '+0300', CET: '+0100', CEST: '+0200', BST: '+0100',
};
const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

/**
 * A feed's idea of a time, in UTC milliseconds; undefined when it is none this reader knows.
 * RFC 822 (RSS), ISO 8601 (Atom, JSON Feed), a Unix timestamp in seconds or milliseconds, and a
 * bare `20:51`, taken as the most recent time the clock read that. A time with no zone is local.
 */
export function parseDate(raw: string, nowUtc = Date.now()): number | undefined {
  const s = raw.trim();
  if (!s) return undefined;
  if (/^\d+$/.test(s)) {
    const n = Number(s);
    return n >= MILLIS_THRESHOLD ? n : n * 1000;
  }
  const bare = /^(\d{1,2}):(\d{2})$/.exec(s);
  if (bare) {
    const h = Number(bare[1]);
    const min = Number(bare[2]);
    if (h > 23 || min > 59) return undefined;
    const d = new Date(nowUtc);
    d.setHours(h, min, 0, 0);
    const at = d.getTime();
    // A headline at 23:50 read at 00:10 is from yesterday, not from the coming night.
    return at - nowUtc > FUTURE_SLACK_MS ? at - DAY_MS : at;
  }
  // ISO 8601, with or without a zone (none is local, which is how Date reads a date-time).
  const iso = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/i.exec(s);
  if (iso) {
    const [, y, mo, d, h, mi, sec, zone] = iso;
    if (!zone) return new Date(+y, +mo - 1, +d, +h, +mi, +(sec ?? 0)).getTime();
    const utc = Date.UTC(+y, +mo - 1, +d, +h, +mi, +(sec ?? 0));
    return utc - zoneMinutes(zone) * 60_000;
  }
  // RFC 822. The day name is dropped: it is optional, redundant, and what feeds get wrong most.
  const rfc = /^(?:[^\d\s,]+\.?,\s*)?(\d{1,2})\s+([A-Za-z]{3})[A-Za-z]*\.?\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([+-]\d{4}|[A-Za-z]{1,4})?$/.exec(s);
  if (rfc) {
    const [, d, monName, yRaw, h, mi, sec, zoneRaw] = rfc;
    const mo = MONTHS.indexOf(monName.toLowerCase());
    if (mo < 0) return undefined;
    const y = yRaw.length === 2 ? 2000 + Number(yRaw) : Number(yRaw);
    const zone = zoneRaw && !/^[+-]/.test(zoneRaw) ? NAMED_ZONES[zoneRaw.toUpperCase()] : zoneRaw;
    // A zone this table does not know (IST is Israel's to an Israeli newsroom and India's to
    // everybody else) is read as local time rather than as no time at all.
    if (!zone) return new Date(y, mo, +d, +h, +mi, +(sec ?? 0)).getTime();
    return Date.UTC(y, mo, +d, +h, +mi, +(sec ?? 0)) - zoneMinutes(zone) * 60_000;
  }
  return undefined;
}

function zoneMinutes(zone: string): number {
  if (/^z$/i.test(zone)) return 0;
  const m = /^([+-])(\d{2}):?(\d{2})$/.exec(zone);
  if (!m) return 0;
  const v = Number(m[2]) * 60 + Number(m[3]);
  return m[1] === '-' ? -v : v;
}

/* ---------- text ---------- */

const NAMED_ENTITIES: Record<string, string> = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ndash: '–', mdash: '—', hellip: '…',
  lsquo: '‘', rsquo: '’', ldquo: '“', rdquo: '”', laquo: '«', raquo: '»',
};
/** An HTML tag: `<` and a letter, so "inflation < 3%" keeps its sign. */
const TAG = /<\/?[A-Za-z][^<>]*>/g;

function decodeEntity(name: string): string | undefined {
  let code: number | undefined;
  if (/^#x/i.test(name)) code = parseInt(name.slice(2), 16);
  else if (name.startsWith('#')) code = parseInt(name.slice(1), 10);
  else return NAMED_ENTITIES[name];
  return Number.isFinite(code) && code! > 0 && code! <= 0x10ffff ? String.fromCodePoint(code!) : undefined;
}

/** A headline reduced to one printable line: markup dropped, entities decoded (twice-escaped too). */
export function clean(text: string): string {
  let out = unwrapCdata(text).replace(TAG, ' ');
  out = out.replace(/&(#?[A-Za-z0-9]+);/g, (whole, name: string) => decodeEntity(name) ?? whole);
  out = out.replace(TAG, ' ');
  return out.replace(/\s+/g, ' ').trim();
}

/* ---------- several sources as one list ---------- */

/** The separators sites put between their name and their slogan. */
const SLOGAN = /\s+[-–—|:]\s+|\s*\|\s*/;

export function shorten(title: string): string {
  const first = title.split(SLOGAN).map((p) => p.trim()).find((p) => p.length > 0) ?? title.trim();
  return first.length <= MAX_LABEL ? first : `${first.slice(0, MAX_LABEL - 1).trimEnd()}…`;
}

export function host(url: string): string {
  try {
    return new URL(url.trim()).hostname.replace(/^www\./, '') || url.trim();
  } catch {
    return url.trim();
  }
}

/** What a source is called on screen: its own name, cut before the slogan, or its host until read. */
export function sourceLabel(source: NewsSource, digest: NewsDigest | undefined): string {
  const t = digest?.title ? shorten(digest.title) : '';
  return t || host(source.url);
}

/**
 * Every enabled source's headlines as one list, newest first: at most [PER_SOURCE] from each,
 * none older than a day, the same story from two sources once, undated ones after the dated.
 */
export function mergeFlashes(sources: NewsSource[], digests: Record<string, NewsDigest>, nowUtc: number): SourcedFlash[] {
  const all: SourcedFlash[] = [];
  sources.forEach((source, index) => {
    if (!source.enabled) return;
    const digest = digests[source.url];
    if (!digest) return;
    const label = sourceLabel(source, digest);
    digest.items
      .filter((item) => item.publishedUtc === undefined || (nowUtc - item.publishedUtc >= -FUTURE_SLACK_MS && nowUtc - item.publishedUtc <= MAX_AGE_MS))
      .slice(0, PER_SOURCE)
      .forEach((flash) => all.push({ flash, source: label, colourIndex: index }));
  });
  // A stable sort, so undated headlines keep the order their source gave them.
  const sorted = all
    .map((f, i) => ({ f, i }))
    .sort((a, b) => (b.f.flash.publishedUtc ?? -Infinity) - (a.f.flash.publishedUtc ?? -Infinity) || a.i - b.i)
    .map(({ f }) => f);
  const titles = new Set<string>();
  return sorted.filter((f) => {
    const key = f.flash.title.toLowerCase();
    if (titles.has(key)) return false;
    titles.add(key);
    return true;
  }).slice(0, MAX_FLASHES);
}

/** The tones the sources are told apart by, in settings order (the screensaver's on Android). */
export const SOURCE_TONES = ['#FFB547', '#7FD8A6', '#A9C8FF', '#FF9C9C', '#E3A6FF', '#7FE0E6', '#F5E07A', '#FFB2D9'];

/** A headline's time as the strip prints it: the clock today, the day and clock before that. */
export function flashTime(at: number, nowUtc: number): string {
  const clock = new Intl.DateTimeFormat('he-IL', { hour: '2-digit', minute: '2-digit', hour12: false }).format(at);
  const sameDay = new Date(at).toDateString() === new Date(nowUtc).toDateString();
  return sameDay ? clock : `${new Intl.DateTimeFormat('he-IL', { weekday: 'short' }).format(at)} ${clock}`;
}
