import { basic } from './normalize';

/**
 * The name heuristics the Android app sorts channels and stations with — ports of
 * `data/sport/IsraeliSportChannels.kt`, `data/news/IsraeliNewsChannels.kt` and
 * `data/radio/RadioCategoryClassifier.kt`. Extend the lists as real names need it; what a playlist
 * contains is decided upstream (CLAUDE.md, "Bundled Israeli radio").
 */

const ISRAELI_GROUP_MARKERS = ['ישראל', 'isra', 'hebrew', 'hebreew', 'עברית'];
const ISRAELI_TAG = /^[\s|:\-[(]*il(?=[\s|:\-\])]|$)/i;
const SPORT_WORDS = ['sport', 'ספורט'];
const ONE = /(?:^|\s)(?:one|וואן)(?:\s|$)/;
const NOT_ONE = ['channel one', 'ערוץ וואן'];
const SPORT_FIVE = /(?<![\d.])5\s*(?:\+|plus\b|live\b|gold\b|stars?\b|4k\b)/i;

/** Lower case, accents off, punctuation to spaces — compatibility forms folded first. */
export function fold(text: string): string {
  return basic(text.normalize('NFKC'));
}

function israeliGroup(group: string | undefined): boolean {
  const g = group ? fold(group) : '';
  return ISRAELI_GROUP_MARKERS.some((m) => g.includes(m)) || ISRAELI_TAG.test(group ?? '');
}

export function isIsraeli(name: string, group?: string, tvgId?: string): boolean {
  // iptv-org's ids carry their feed after an `@` (`Kan11.il@SD`), which is not part of the country.
  const id = (tvgId?.trim().toLowerCase() ?? '').split('@')[0];
  return israeliGroup(group) || ISRAELI_TAG.test(name) || id.endsWith('.il') || id.startsWith('il.');
}

export function isIsraeliSport(name: string, group?: string, tvgId?: string): boolean {
  if (!isIsraeli(name, group, tvgId)) return false;
  const g = group ? fold(group) : '';
  const id = tvgId?.trim().toLowerCase() ?? '';
  const f = fold(name);
  return (
    SPORT_WORDS.some((w) => f.includes(w)) ||
    (ONE.test(f) && !NOT_ONE.some((w) => f.includes(w))) ||
    SPORT_FIVE.test(name) ||
    id.includes('sport') ||
    (israeliGroup(group) && SPORT_WORDS.some((w) => g.includes(w)))
  );
}

const NEWS_WORDS = ['news', 'חדשות', 'ניוז', 'i24', 'knesset', 'כנסת'];
const BY_NUMBER = /(?:^|\s)(?:channel|ערוץ|כאן|kan|קשת|keshet|רשת|reshet|now|עכשיו)\s+(?:11|12|13|14|20)(?:\s|$)/;
const NEWS_GUIDE_IDS = new Set([
  'il.channel-11', 'il.channel-12', 'il.channel-13', 'il.channel-14', 'il.channel-20',
  'il.i24', 'il.hot-keshet-12', 'il.hot-reshet-13',
]);

export function isIsraeliNews(name: string, group?: string, tvgId?: string): boolean {
  if (!isIsraeli(name, group, tvgId)) return false;
  const id = tvgId?.trim().toLowerCase() ?? '';
  const f = fold(name);
  return NEWS_WORDS.some((w) => f.includes(w)) || BY_NUMBER.test(f) || NEWS_GUIDE_IDS.has(id) || id.includes('news');
}

/* ---------- Radio ---------- */

export type RadioCategory =
  | { kind: 'NATIONAL' }
  | { kind: 'REGIONAL' }
  | { kind: 'GENRE'; label: string }
  | { kind: 'OTHER' };

export type RadioRegion = 'NORTH' | 'CENTER' | 'JERUSALEM' | 'SOUTH' | 'UNKNOWN';

export const REGION_ORDER: RadioRegion[] = ['NORTH', 'CENTER', 'JERUSALEM', 'SOUTH', 'UNKNOWN'];

const REGIONAL_GROUP_MARKERS = ['אזורי', 'regional'];
const NATIONAL_GROUP_MARKERS = ['ארצי', 'ישראלי', 'national'];
// "יוון" rather than "יווני": it is in "יווני" too, and the feed's Greek station is named after the country.
const GENRE_MARKERS: Array<[string, string[]]> = [
  ['יווני', ['יוון', 'greek']],
  ['מזרחי', ['מזרחי', 'mizrahi']],
];
const REGION_MARKERS: Array<[RadioRegion, string[]]> = [
  ['NORTH', [
    'צפון', 'חיפה', 'גליל', 'כרמל', 'כנרת', 'טבריה', 'נצרת', 'עכו', 'צפת', 'קריות',
    'קול רגע', 'יזרעאל', 'גולן', '104.5',
    'north', 'tzafon', 'haifa', 'galil', 'galilee', 'kinneret', 'kol rega', 'golan',
  ]],
  ['CENTER', [
    'מרכז', 'תל אביב', 'ת"א', 'שרון', 'לב המדינה', 'גוש דן', 'רמת גן', 'פתח תקווה',
    'נתניה', 'הרצליה', 'רחובות', 'מודיעין', 'עמק חפר', 'רדיו 90', 'רדיו תשעים', '90fm', '90 fm',
    'center', 'central', 'tel aviv', 'sharon', 'merkaz', 'hefer',
  ]],
  ['JERUSALEM', ['ירושלים', 'jerusalem', 'yerushalayim']],
  ['SOUTH', [
    'דרום', 'נגב', 'באר שבע', 'אילת', 'אשקלון', 'אשדוד', 'שדרות', 'ערד', 'דימונה',
    'south', 'darom', 'negev', 'eilat', 'beer sheva', 'ashdod', 'ashkelon',
  ]],
];
const RUSSIAN_MARKERS = ['רוסי', 'russian', 'russkoe', 'русск'];

export function isRussianStation(name: string, group?: string): boolean {
  const haystack = `${name.toLowerCase()} ${(group ?? '').toLowerCase()}`;
  return RUSSIAN_MARKERS.some((m) => haystack.includes(m)) || /[Ѐ-ӿ]/.test(name);
}

function genreOf(name: string): string | undefined {
  const n = name.toLowerCase();
  return GENRE_MARKERS.find(([, markers]) => markers.some((m) => n.includes(m)))?.[0];
}

function filedAsRegional(group: string | undefined): boolean {
  const g = (group ?? '').toLowerCase();
  return REGIONAL_GROUP_MARKERS.some((m) => g.includes(m));
}

export function radioCategory(group: string | undefined, name: string): RadioCategory {
  const genre = genreOf(name);
  if (genre !== undefined) return { kind: 'GENRE', label: genre };
  if (filedAsRegional(group)) return { kind: 'REGIONAL' };
  const g = (group ?? '').toLowerCase();
  if (NATIONAL_GROUP_MARKERS.some((m) => g.includes(m))) return { kind: 'NATIONAL' };
  return { kind: 'OTHER' };
}

export function radioRegion(name: string): RadioRegion {
  const n = name.toLowerCase();
  for (const [region, markers] of REGION_MARKERS) if (markers.some((m) => n.includes(m))) return region;
  return 'UNKNOWN';
}

/**
 * A shelf of the Android radio list's category menu (ADR-050), ported with its rules: every
 * station on one place shelf — the region its name names, "other regions" when the feed files it
 * as regional without saying where, national otherwise — and on its genre's shelf as well.
 */
export type RadioShelf =
  | { kind: 'NATIONAL' }
  | { kind: 'REGIONAL'; region: RadioRegion }
  | { kind: 'GENRE'; label: string };

export function radioRegionShelf(group: string | undefined, name: string): RadioShelf {
  const region = radioRegion(name);
  if (region !== 'UNKNOWN') return { kind: 'REGIONAL', region };
  if (filedAsRegional(group)) return { kind: 'REGIONAL', region: 'UNKNOWN' };
  return { kind: 'NATIONAL' };
}

export function radioShelves(group: string | undefined, name: string): RadioShelf[] {
  const genre = genreOf(name);
  return genre === undefined
    ? [radioRegionShelf(group, name)]
    : [radioRegionShelf(group, name), { kind: 'GENRE', label: genre }];
}

const shelfKey = (s: RadioShelf): string =>
  s.kind === 'NATIONAL' ? 'NATIONAL' : s.kind === 'REGIONAL' ? `REGIONAL:${s.region}` : `GENRE:${s.label}`;

/** National, the regions from the north, then the genres alphabetically; shelves nobody is on left out. */
export function radioMenu(stations: Array<{ group?: string; name: string }>): RadioShelf[] {
  const present = new Map<string, RadioShelf>();
  for (const s of stations) for (const shelf of radioShelves(s.group, s.name)) present.set(shelfKey(shelf), shelf);
  const places: RadioShelf[] = [{ kind: 'NATIONAL' }, ...REGION_ORDER.map((region): RadioShelf => ({ kind: 'REGIONAL', region }))];
  const genres = [...present.values()]
    .filter((s): s is Extract<RadioShelf, { kind: 'GENRE' }> => s.kind === 'GENRE')
    .sort((a, b) => (a.label < b.label ? -1 : a.label > b.label ? 1 : 0));
  return [...places.filter((p) => present.has(shelfKey(p))), ...genres];
}
