import type { EpgChannel, Programme } from './model';

/**
 * Streaming XMLTV reader — the browser's counterpart of the Android app's
 * `data/xmltv/XmltvParser.kt`, with the same rules: the first title/sub-title/desc/category wins,
 * a programme with no channel, start or title is dropped, and a missing or bad stop becomes
 * start + 30 minutes.
 *
 * Guides run to hundreds of megabytes, so the text is fed in chunks ([push]) and every
 * `<channel>` and `<programme>` is handed on as soon as its closing tag has arrived; only the
 * unfinished tail is kept between chunks.
 */

export interface XmltvProgramme extends Programme {
  channel: string;
}

export class XmltvParser {
  private buffer = '';
  channels = 0;
  programmes = 0;

  constructor(
    private readonly onChannel: (c: EpgChannel) => void,
    private readonly onProgramme: (p: XmltvProgramme) => void,
  ) {}

  push(chunk: string) {
    this.buffer += chunk;
    this.drain(false);
  }

  end() {
    this.drain(true);
    this.buffer = '';
  }

  private drain(final: boolean) {
    let pos = 0;
    const b = this.buffer;
    // The next of each start tag, searched again only once consumed: a guide holds its channels
    // first and then tens of thousands of programmes, and looking for `<channel` past every one
    // of them would rescan the whole chunk per programme.
    let c = b.indexOf('<channel');
    let p = b.indexOf('<programme');
    for (;;) {
      if (c >= 0 && c < pos) c = b.indexOf('<channel', pos);
      if (p >= 0 && p < pos) p = b.indexOf('<programme', pos);
      if (c < 0 && p < 0) {
        // Keep a short tail in case a start tag is split across chunks.
        pos = Math.max(pos, b.length - 16);
        break;
      }
      const isProgramme = p >= 0 && (c < 0 || p < c);
      const start = isProgramme ? p : c;
      const tag = isProgramme ? 'programme' : 'channel';
      const after = b.charCodeAt(start + tag.length + 1);
      // `<channels>` or `<programmeX>` are not ours: the name must end at whitespace, `>` or `/`.
      if (!Number.isNaN(after) && !(after === 32 || after === 9 || after === 10 || after === 13 || after === 62 || after === 47)) {
        pos = start + 1;
        continue;
      }
      const openEnd = b.indexOf('>', start);
      if (openEnd < 0) {
        pos = start;
        break;
      }
      let end: number;
      if (b.charCodeAt(openEnd - 1) === 47) {
        end = openEnd + 1; // self-closing
      } else {
        const close = b.indexOf(`</${tag}>`, openEnd);
        if (close < 0) {
          pos = start;
          break;
        }
        end = close + tag.length + 3;
      }
      const element = b.slice(start, end);
      if (isProgramme) {
        const prog = readProgramme(element);
        if (prog) {
          this.programmes++;
          this.onProgramme(prog);
        }
      } else {
        const ch = readChannel(element);
        if (ch) {
          this.channels++;
          this.onChannel(ch);
        }
      }
      pos = end;
    }
    this.buffer = final ? '' : b.slice(pos);
  }
}

function attr(openTag: string, name: string): string | undefined {
  const m = new RegExp(`\\s${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)')`).exec(openTag);
  if (!m) return undefined;
  return decodeEntities(m[1] ?? m[2] ?? '');
}

const CHILD = /<(display-name|title|sub-title|desc|category|episode-num)\b([^>]*)>([\s\S]*?)<\/\1\s*>/g;
const ICON = /<icon\b[^>]*\ssrc\s*=\s*(?:"([^"]*)"|'([^']*)')/;

function readChannel(element: string): EpgChannel | null {
  const open = element.slice(0, element.indexOf('>') + 1);
  const id = attr(open, 'id')?.trim();
  if (!id) return null;
  const displayNames: string[] = [];
  for (const m of element.matchAll(CHILD)) {
    if (m[1] === 'display-name') {
      const t = text(m[3]);
      if (t) displayNames.push(t);
    }
  }
  const icon = ICON.exec(element);
  const iconUrl = icon ? decodeEntities(icon[1] ?? icon[2] ?? '').trim() || undefined : undefined;
  return { id, displayNames, iconUrl };
}

function readProgramme(element: string): XmltvProgramme | null {
  const open = element.slice(0, element.indexOf('>') + 1);
  const channel = attr(open, 'channel')?.trim();
  const start = parseXmltvTime(attr(open, 'start') ?? '');
  const stop = parseXmltvTime(attr(open, 'stop') ?? '');
  let title: string | undefined;
  let subtitle: string | undefined;
  let description: string | undefined;
  let category: string | undefined;
  let season: number | undefined;
  let episode: number | undefined;
  for (const m of element.matchAll(CHILD)) {
    const value = text(m[3]);
    if (!value) continue;
    switch (m[1]) {
      case 'title':
        title ??= value;
        break;
      case 'sub-title':
        subtitle ??= value;
        break;
      case 'desc':
        description ??= value;
        break;
      case 'category':
        category ??= value;
        break;
      case 'episode-num': {
        const system = attr(`<x ${m[2]}>`, 'system')?.toLowerCase();
        const parsed = system === 'xmltv_ns' ? parseXmltvNs(value) : system === 'onscreen' && season === undefined ? parseOnScreen(value) : null;
        if (parsed) [season, episode] = parsed;
        break;
      }
    }
  }
  if (!channel || start === null || !title) return null;
  const end = stop !== null && stop > start ? stop : start + 30 * 60_000;
  return { channel, start, end, title, subtitle, description, category, season, episode };
}

function text(raw: string): string | undefined {
  const cdata = raw.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, (_m, c: string) => c.replace(/&/g, '&amp;').replace(/</g, '&lt;'));
  const stripped = cdata.replace(/<[^>]*>/g, '');
  const t = decodeEntities(stripped).trim();
  return t || undefined;
}

const NAMED: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };

export function decodeEntities(s: string): string {
  if (!s.includes('&')) return s;
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e: string) => {
    if (e[0] === '#') {
      const code = e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      return Number.isFinite(code) && code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : m;
    }
    return NAMED[e.toLowerCase()] ?? m;
  });
}

/**
 * `20260819143000 +0300`. With no offset the document is in the producer's zone, which cannot be
 * known: it is read as UTC, and the user corrects it with an offset rather than a guess
 * (core/TimeModel.kt).
 */
export function parseXmltvTime(raw: string): number | null {
  const s = raw.trim();
  if (s.length < 14 || !/^\d{14}/.test(s)) return null;
  const y = +s.slice(0, 4);
  const mo = +s.slice(4, 6);
  const d = +s.slice(6, 8);
  const h = +s.slice(8, 10);
  const mi = +s.slice(10, 12);
  const se = +s.slice(12, 14);
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || se > 60) return null;
  const local = Date.UTC(y, mo - 1, d, h, mi, se);
  return local - offsetSeconds(s.slice(14).trim()) * 1000;
}

function offsetSeconds(part: string): number {
  if (!part) return 0;
  const sign = part[0] === '+' ? 1 : part[0] === '-' ? -1 : 0;
  if (!sign) return 0;
  const digits = part.slice(1).replace(/\D/g, '');
  if (digits.length < 4) return 0;
  return sign * (+digits.slice(0, 2) * 3600 + +digits.slice(2, 4) * 60);
}

/** `xmltv_ns` is zero-based and shaped `season . episode . part`. */
export function parseXmltvNs(value: string): [number, number] | null {
  const parts = value.split('.');
  if (parts.length < 2) return null;
  const num = (p: string) => {
    const t = p.split('/')[0].trim();
    return /^\d+$/.test(t) ? parseInt(t, 10) + 1 : undefined;
  };
  const season = num(parts[0]);
  const episode = num(parts[1]);
  if (season === undefined && episode === undefined) return null;
  return [season ?? 0, episode ?? 0];
}

/** `onscreen` is free text; `S02E05` and `2x05` cover almost everything seen. */
export function parseOnScreen(value: string): [number, number] | null {
  const se = /s\s*(\d{1,3})\s*e\s*(\d{1,4})/i.exec(value);
  if (se) return [+se[1], +se[2]];
  const x = /^(\d{1,3})\s*x\s*(\d{1,4})$/.exec(value.trim());
  if (x) return [+x[1], +x[2]];
  return null;
}
