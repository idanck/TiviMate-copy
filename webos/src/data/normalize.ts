/**
 * Name normalisation for channel identity and guide matching: a port of the Android app's
 * `core/TextNormalizer.kt`, kept to the same rules so the two platforms bind the same channels to
 * the same guide entries.
 */

/** Quality/definition markers and separators that carry no identity information. */
const NOISE_TOKENS = new Set([
  'hd', 'fhd', 'uhd', 'sd', '4k', '8k', 'hevc', 'h265', 'h264', '1080', '1080p',
  '720', '720p', '576', '480', 'raw', 'backup', 'alt', 'vip', 'plus', 'hq', 'lq',
  'hdr', 'dolby', 'atmos', '50fps', '60fps', 'multi', 'mux',
]);

const BRACKETED = /[[(<{][^\])>}]*[\])>}]/g;
const NON_ALNUM = /[^\p{L}\p{Nd}]+/gu;
const LEADING_NUMBER = /^\d{1,4}[.:)-]\s*/;
const MARKS = /\p{Mn}+/gu;

/** Lowercase, de-accent, drop punctuation; keeps every meaningful character. */
export function basic(input: string): string {
  return input.trim().normalize('NFD').replace(MARKS, '').toLowerCase().replace(NON_ALNUM, ' ').trim();
}

/**
 * Aggressive form used for name matching: drops bracketed suffixes, a leading channel number, and
 * the quality tokens providers append inconsistently.
 */
export function canonical(input: string): string {
  let s = input.trim();
  s = s.replace(BRACKETED, ' ');
  s = s.replace(LEADING_NUMBER, '');
  s = basic(s);
  return s.split(' ').filter((t) => t && !NOISE_TOKENS.has(t)).join(' ');
}

export function tokens(input: string): Set<string> {
  return new Set(canonical(input).split(' ').filter(Boolean));
}

/** A digit mismatch can never produce a score a matcher should act on. */
export const DIGIT_MISMATCH_CEILING = 0.5;

/**
 * Similarity in 0..1: Jaccard over tokens and a normalised Levenshtein over the canonical strings.
 * Digits are decisive — `Sport 1` and `Sport 2` must never be taken for each other — so any
 * difference in the digit sequence caps the score at [DIGIT_MISMATCH_CEILING].
 */
export function similarity(a: string, b: string): number {
  return similarityPrepared(prepared(a), prepared(b));
}

export interface Prepared {
  canonical: string;
  tokens: Set<string>;
  digits: string;
}

/** A name worked out once for [similarityPrepared], which a matcher calls many times per name. */
export function prepared(name: string): Prepared {
  const c = canonical(name);
  return { canonical: c, tokens: new Set(c.split(' ').filter(Boolean)), digits: c.replace(/\D+/g, '') };
}

export function similarityPrepared(a: Prepared, b: Prepared): number {
  const ca = a.canonical;
  const cb = b.canonical;
  if (!ca || !cb) return 0;
  if (ca === cb) return 1;
  let intersection = 0;
  for (const t of a.tokens) if (b.tokens.has(t)) intersection++;
  const union = a.tokens.size + b.tokens.size - intersection;
  const jaccard = union === 0 ? 0 : intersection / union;
  const maxLen = Math.max(ca.length, cb.length);
  const edit = maxLen === 0 ? 0 : 1 - levenshtein(ca, cb) / maxLen;
  const score = Math.min(1, Math.max(0, 0.4 * jaccard + 0.6 * edit));
  return a.digits === b.digits ? score : Math.min(score, DIGIT_MISMATCH_CEILING);
}

export function levenshtein(a: string, b: string): number {
  if (a === b) return 0;
  if (!a.length) return b.length;
  if (!b.length) return a.length;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  let cur = new Array<number>(b.length + 1).fill(0);
  for (let i = 1; i <= a.length; i++) {
    cur[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      cur[j] = Math.min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost);
    }
    [prev, cur] = [cur, prev];
  }
  return prev[b.length];
}

/** Strips credentials from a URL so it can be shown on screen (NFR-SEC-02). */
export function redactUrl(url: string): string {
  return url
    .replace(/([?&](?:username|user|password|pass|token|auth|key)=)[^&\s]*/gi, '$1***')
    .replace(/\/\/([^/@\s:]+):([^/@\s]+)@/g, '//***:***@')
    .replace(/\/(live|movie|series|timeshift)\/([^/]+)\/([^/]+)\//gi, '/$1/***/***/');
}
