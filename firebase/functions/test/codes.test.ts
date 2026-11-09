import { describe, expect, it } from 'vitest';
import { CODE_ALPHABET, CODE_LENGTH, formatCode, newCode, newSecret, normalizeCode, pairingUrl, secretMatches, sha256 } from '../src/codes';
import { clientIp, nextWindow } from '../src/limits';

describe('pairing codes', () => {
  it('are made of the readable alphabet only', () => {
    for (let i = 0; i < 200; i++) {
      const code = newCode();
      expect(code).toHaveLength(CODE_LENGTH);
      for (const ch of code) expect(CODE_ALPHABET).toContain(ch);
    }
  });

  it('leave out the letters that are read as digits', () => {
    for (const ch of '01OIL') expect(CODE_ALPHABET).not.toContain(ch);
  });

  it('are accepted the way people type them', () => {
    expect(normalizeCode('abcd-efgh')).toBe('ABCDEFGH');
    expect(normalizeCode(' ABCD EFGH ')).toBe('ABCDEFGH');
    expect(normalizeCode('ABCDEFGH')).toBe('ABCDEFGH');
  });

  it('refuse anything that cannot be a code', () => {
    expect(normalizeCode('ABCD-EFG')).toBeNull();
    expect(normalizeCode('ABCD-EFGO')).toBeNull();
    expect(normalizeCode('ABCD/EFGH')).toBeNull();
    expect(normalizeCode(42)).toBeNull();
    expect(normalizeCode(undefined)).toBeNull();
  });

  it('are shown in two halves', () => {
    expect(formatCode('ABCDEFGH')).toBe('ABCD-EFGH');
  });

  it('build the approval address', () => {
    expect(pairingUrl('https://example.web.app/', 'ABCDEFGH')).toBe('https://example.web.app/pair?code=ABCD-EFGH');
  });
});

describe('pairing secrets', () => {
  it('match only their own hash', () => {
    const secret = newSecret();
    expect(secretMatches(secret, sha256(secret))).toBe(true);
    expect(secretMatches(newSecret(), sha256(secret))).toBe(false);
    expect(secretMatches('', sha256(''))).toBe(false);
    expect(secretMatches(undefined, sha256(secret))).toBe(false);
    expect(secretMatches(secret, undefined)).toBe(false);
  });

  it('carry 256 bits', () => {
    expect(Buffer.from(newSecret(), 'base64url')).toHaveLength(32);
  });
});

describe('rate windows', () => {
  const max = 3;
  const windowMs = 1000;

  it('start a window on the first attempt', () => {
    expect(nextWindow(null, 5000, max, windowMs)).toEqual({ window: { start: 5000, count: 1 }, allowed: true });
  });

  it('count within the window and refuse past the limit', () => {
    let w = nextWindow(null, 0, max, windowMs).window;
    w = nextWindow(w, 100, max, windowMs).window;
    w = nextWindow(w, 200, max, windowMs).window;
    expect(w.count).toBe(3);
    expect(nextWindow(w, 300, max, windowMs).allowed).toBe(false);
  });

  it('start again once the window is over', () => {
    const full = { start: 0, count: max };
    expect(nextWindow(full, windowMs, max, windowMs)).toEqual({ window: { start: windowMs, count: 1 }, allowed: true });
  });
});

describe('client address', () => {
  it('takes the first forwarded address', () => {
    expect(clientIp({ ip: '198.51.100.9', headers: { 'x-forwarded-for': '203.0.113.5, 198.51.100.9' } })).toBe('203.0.113.5');
  });

  it('falls back to the socket address', () => {
    expect(clientIp({ ip: '198.51.100.9', headers: {} })).toBe('198.51.100.9');
    expect(clientIp({})).toBe('unknown');
  });
});
