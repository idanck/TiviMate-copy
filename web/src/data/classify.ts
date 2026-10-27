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
const GENRE_MARKERS: Array<[string, string[]]> = [
  ['יווני', ['יווני', 'greek']],
  ['מזרחי', ['מזרחי', 'mizrahi']],
];
const REGION_MARKERS: Array<[RadioRegion, string[]]> = [
  ['NORTH', [
    'צפון', 'חיפה', 'גליל', 'כרמל', 'כנרת', 'טבריה', 'נצרת', 'עכו', 'צפת', 'קריות',
    'קול רגע', 'עמק יזרעאל', '104.5',
    'north', 'tzafon', 'haifa', 'galil', 'galilee', 'kinneret', 'kol rega',
  ]],
  ['CENTER', [
    'מרכז', 'תל אביב', 'ת"א', 'שרון', 'לב המדינה', 'גוש דן', 'רמת גן', 'פתח תקווה',
    'נתניה', 'הרצליה', 'רחובות', 'מודיעין', 'עמק חפר', 'רדיו 90', '90fm', '90 fm',
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

export function radioCategory(group: string | undefined, name: string): RadioCategory {
  const n = name.toLowerCase();
  for (const [label, markers] of GENRE_MARKERS) if (markers.some((m) => n.includes(m))) return { kind: 'GENRE', label };
  const g = (group ?? '').toLowerCase();
  if (REGIONAL_GROUP_MARKERS.some((m) => g.includes(m))) return { kind: 'REGIONAL' };
  if (NATIONAL_GROUP_MARKERS.some((m) => g.includes(m))) return { kind: 'NATIONAL' };
  return { kind: 'OTHER' };
}

export function radioRegion(name: string): RadioRegion {
  const n = name.toLowerCase();
  for (const [region, markers] of REGION_MARKERS) if (markers.some((m) => n.includes(m))) return region;
  return 'UNKNOWN';
}
