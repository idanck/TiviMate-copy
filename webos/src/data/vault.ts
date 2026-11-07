import { kv } from './db';
import type { Channel, Source } from './model';
import { enc } from './xtream';

/**
 * Keeps Xtream passwords out of the television's storage in the clear (ADR-057; LG's app checklist
 * asks that saved credentials be encrypted).
 *
 * A source's password is sealed with AES-GCM before it is written to IndexedDB. The key is made
 * once, on this television, as a non-extractable CryptoKey and kept in IndexedDB beside the data:
 * the page can use it but not read it out, and it never leaves the device. An Xtream channel's
 * address carries the password too (/live/<user>/<password>/<id>.ts), so the cached channel list
 * and the guide address are stored with the password replaced by a marker and filled in again
 * when they are read back.
 *
 * Where the browser has no Web Crypto (never on a webOS television, which runs pages from
 * file://, a secure context) the password is kept as before rather than lost.
 *
 * A password that cannot be opened is never written back empty (ADR-057). In the first version a
 * key that failed to load once (a slow start, IndexedDB busy) turned the password into '', and the
 * next save of the sources stored that '' over the sealed one, so the account was lost for good.
 * Now the sealed text is held aside and saved again as it was, and a failed key is tried again on
 * the next call instead of being remembered.
 */

const KEY_NAME = 'vault-key';
const PREFIX = 'v1:';
/** Stands in for the password inside a stored address; braces never appear unencoded in a URL. */
const MARK = '{xc-secret}';

let keyPromise: Promise<CryptoKey | null> | null = null;

function subtle(): SubtleCrypto | null {
  return typeof crypto !== 'undefined' && crypto.subtle ? crypto.subtle : null;
}

/**
 * The sealed text last read or written for each source, and what it opens to (null: it could not
 * be opened). Saved back as it is whenever the password has not changed or cannot be sealed anew.
 */
const known = new Map<string, { plain: string | null; sealed: string }>();

function key(): Promise<CryptoKey | null> {
  if (!keyPromise) {
    const attempt: Promise<CryptoKey | null> = (async () => {
      const s = subtle();
      if (!s) return null;
      const stored = await kv.get<CryptoKey>(KEY_NAME);
      if (stored) return stored;
      const made = await s.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
      await kv.set(KEY_NAME, made);
      return made;
    })().catch(() => {
      // Not remembered: the next call tries again.
      if (keyPromise === attempt) keyPromise = null;
      return null;
    });
    keyPromise = attempt;
  }
  return keyPromise;
}

/** For the tests: forget the key and what was held aside, as a fresh start would. */
export function resetVaultForTests() {
  keyPromise = null;
  known.clear();
}

function toBase64(bytes: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

function fromBase64(text: string): Uint8Array {
  const bin = atob(text);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/**
 * Seals a password. Null when it cannot be sealed now (the key did not load): the caller must not
 * store the password in the clear in its place.
 */
export async function seal(plain: string): Promise<string | null> {
  const s = subtle();
  if (!s) return plain;
  const k = await key();
  if (!k) return null;
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const cipher = new Uint8Array(await s.encrypt({ name: 'AES-GCM', iv }, k, new TextEncoder().encode(plain)));
  const both = new Uint8Array(iv.length + cipher.length);
  both.set(iv);
  both.set(cipher, iv.length);
  return PREFIX + toBase64(both);
}

/**
 * Opens what [seal] made; anything else (an older, unsealed value) is returned as it is. Null when
 * it cannot be opened: the key did not load, or it is not the key that sealed it (storage was
 * cleared, and the user must type the password again).
 */
export async function unseal(stored: string): Promise<string | null> {
  if (!stored.startsWith(PREFIX)) return stored;
  const k = await key();
  const s = subtle();
  if (!k || !s) return null;
  try {
    const both = fromBase64(stored.slice(PREFIX.length));
    const plain = await s.decrypt({ name: 'AES-GCM', iv: both.slice(0, 12) }, k, both.slice(12));
    return new TextDecoder().decode(plain);
  } catch {
    return null;
  }
}

/* ---------- addresses that carry the password ---------- */

/** The password as it appears inside an address the app built (data/xtream.ts). */
function secretsIn(source: Source): string[] {
  if (source.type !== 'XTREAM' || !source.password) return [];
  const encoded = enc(source.password);
  return encoded === source.password ? [encoded] : [encoded, source.password];
}

export function maskAddress(url: string, source: Source): string {
  let out = url;
  for (const secret of secretsIn(source)) {
    out = out
      .split(`/${secret}/`).join(`/${MARK}/`)
      .split(`password=${secret}`).join(`password=${MARK}`);
  }
  return out;
}

export function unmaskAddress(url: string, source: Source): string {
  if (!url.includes(MARK) || !source.password) return url;
  return url.split(MARK).join(enc(source.password));
}

export function maskChannels(channels: Channel[], source: Source): Channel[] {
  if (source.type !== 'XTREAM' || !source.password) return channels;
  return channels.map((c) => ({ ...c, urls: c.urls.map((u) => maskAddress(u, source)) }));
}

export function unmaskChannels(channels: Channel[], source: Source): Channel[] {
  if (source.type !== 'XTREAM' || !source.password) return channels;
  return channels.map((c) => ({ ...c, urls: c.urls.map((u) => unmaskAddress(u, source)) }));
}

/* ---------- sources ---------- */

/**
 * A source as it is written to storage: password sealed, guide address masked. A password that was
 * never opened goes back as the sealed text it came as; one that cannot be sealed now does too, or
 * else is left out of this write (never written in the clear, never written as '').
 */
export async function sealSource(source: Source): Promise<Source> {
  if (source.type !== 'XTREAM') return source;
  const held = known.get(source.id);
  // Never opened, and not typed again since: keep what is stored.
  if (!source.password) return held && held.plain === null ? { ...source, password: held.sealed } : source;
  let sealed: string | null;
  if (held && held.plain === source.password) sealed = held.sealed;
  else {
    sealed = await seal(source.password);
    if (sealed === null) {
      // The key did not load. What was stored before stays; a new password is not saved this time.
      return { ...source, password: held?.sealed, resolvedEpgUrl: undefined };
    }
    if (sealed !== source.password) known.set(source.id, { plain: source.password, sealed });
  }
  return {
    ...source,
    password: sealed,
    resolvedEpgUrl: source.resolvedEpgUrl ? maskAddress(source.resolvedEpgUrl, source) : undefined,
  };
}

/** A source as the app uses it. One whose password cannot be opened has none until it is typed again. */
export async function unsealSource(stored: Source): Promise<Source> {
  if (stored.type !== 'XTREAM' || !stored.password) return stored;
  const plain = await unseal(stored.password);
  // Only a sealed value is kept to be saved back: one stored in the clear is sealed on the next save.
  if (stored.password.startsWith(PREFIX)) known.set(stored.id, { plain, sealed: stored.password });
  if (plain === null) return { ...stored, password: '' };
  const source = { ...stored, password: plain };
  if (source.resolvedEpgUrl) source.resolvedEpgUrl = unmaskAddress(source.resolvedEpgUrl, source);
  return source;
}

/** Whether a source's saved password could not be opened (so it needs typing again). */
export function passwordUnopened(id: string): boolean {
  return known.get(id)?.plain === null;
}
