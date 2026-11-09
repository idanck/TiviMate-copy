// @ts-check
import { createHash, createHmac, timingSafeEqual } from 'node:crypto';

/**
 * The optional password in front of the proxy.
 *
 * With SHIDUR_WEB_PASSWORD set, every /api/ route but login, session and health needs a session
 * cookie. The cookie is `<expiry>.<hmac>`: nothing is stored on the server, so a restart keeps
 * everyone signed in, and changing the password (which the key derives from, unless
 * SHIDUR_WEB_SECRET is given) signs everyone out.
 */

export const COOKIE = 'shidur_session';
const SESSION_DAYS = 180;
const MAX_FAILURES = 10;
const FAILURE_WINDOW_MS = 10 * 60_000;

export class Auth {
  /**
   * @param {{ password?: string, secret?: string }} options
   */
  constructor({ password, secret }) {
    this.password = password || '';
    this.key = secret || createHash('sha256').update(`shidur-web:${this.password}`).digest('hex');
    /** @type {Map<string, { count: number, since: number }>} */
    this.failures = new Map();
  }

  get required() {
    return this.password.length > 0;
  }

  /** @param {number} expiry */
  sign(expiry) {
    return createHmac('sha256', this.key).update(String(expiry)).digest('base64url');
  }

  issue(now = Date.now()) {
    const expiry = now + SESSION_DAYS * 24 * 3600_000;
    return { value: `${expiry}.${this.sign(expiry)}`, maxAgeSeconds: SESSION_DAYS * 24 * 3600 };
  }

  /** @param {string | undefined} cookieHeader */
  valid(cookieHeader, now = Date.now()) {
    if (!this.required) return true;
    const value = readCookie(cookieHeader, COOKIE);
    if (!value) return false;
    const [expiryText, mac] = value.split('.');
    const expiry = Number(expiryText);
    if (!Number.isFinite(expiry) || expiry < now || !mac) return false;
    return safeEqual(mac, this.sign(expiry));
  }

  /**
   * @param {string} attempt
   * @param {string} client a key for rate limiting, the remote address
   * @returns {'ok' | 'wrong' | 'locked'}
   */
  check(attempt, client, now = Date.now()) {
    const entry = this.failures.get(client);
    if (entry && now - entry.since > FAILURE_WINDOW_MS) this.failures.delete(client);
    const current = this.failures.get(client);
    if (current && current.count >= MAX_FAILURES) return 'locked';
    if (safeEqual(attempt, this.password)) {
      this.failures.delete(client);
      return 'ok';
    }
    this.failures.set(client, { count: (current?.count ?? 0) + 1, since: current?.since ?? now });
    return 'wrong';
  }
}

/** @param {string} a @param {string} b */
export function safeEqual(a, b) {
  const x = createHash('sha256').update(a).digest();
  const y = createHash('sha256').update(b).digest();
  return timingSafeEqual(x, y) && a.length === b.length;
}

/** @param {string | undefined} header @param {string} name */
export function readCookie(header, name) {
  if (!header) return undefined;
  for (const part of header.split(';')) {
    const i = part.indexOf('=');
    if (i < 0) continue;
    if (part.slice(0, i).trim() === name) return decodeURIComponent(part.slice(i + 1).trim());
  }
  return undefined;
}
