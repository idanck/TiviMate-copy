import { createHash, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';

/**
 * The letters a pairing code is made of: no 0/O, 1/I/L, so a code read off a television across a
 * room cannot be typed wrong. 31 symbols, eight of them: about 8.5 × 10^11 codes.
 */
export const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
export const CODE_LENGTH = 8;

export function newCode(): string {
  let code = '';
  for (let i = 0; i < CODE_LENGTH; i++) code += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return code;
}

/**
 * A code as somebody typed it: any case, with the dash the television shows or spaces.
 * Returns null for anything that cannot be a code, so the database is never asked about it.
 */
export function normalizeCode(input: unknown): string | null {
  if (typeof input !== 'string') return null;
  const code = input.toUpperCase().replace(/[\s-]/g, '');
  if (code.length !== CODE_LENGTH) return null;
  for (const ch of code) if (!CODE_ALPHABET.includes(ch)) return null;
  return code;
}

/** `ABCDEFGH` → `ABCD-EFGH`, the way the television shows it. */
export function formatCode(code: string): string {
  return `${code.slice(0, 4)}-${code.slice(4)}`;
}

/** The secret only the television that asked for a code holds; it is what claims the sign-in. */
export function newSecret(): string {
  return randomBytes(32).toString('base64url');
}

export function sha256(value: string): string {
  return createHash('sha256').update(value).digest('hex');
}

/** Compares a presented secret with the stored hash in constant time. */
export function secretMatches(presented: unknown, storedHash: unknown): boolean {
  if (typeof presented !== 'string' || typeof storedHash !== 'string' || presented.length === 0) return false;
  const a = Buffer.from(sha256(presented), 'hex');
  const b = Buffer.from(storedHash, 'hex');
  return a.length === b.length && timingSafeEqual(a, b);
}

/** The address a phone opens to approve [code]. */
export function pairingUrl(baseUrl: string, code: string): string {
  return `${baseUrl.replace(/\/+$/, '')}/pair?code=${encodeURIComponent(formatCode(code))}`;
}
