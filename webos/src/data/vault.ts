import { kv } from './db';
import type { Channel, Source } from './model';
import { enc } from './xtream';

/**
 * Keeps Xtream passwords out of the television's storage in the clear (ADR-052; LG's app checklist
 * asks that saved credentials be encrypted).
 *
 * A source's password is sealed with AES-GCM before it is written to IndexedDB. The key is made
 * once, on this television, as a non-extractable CryptoKey and kept in IndexedDB beside the data:
 * the page can use it but not read it out, and it never leaves the device. An Xtream channel's
 * address carries the password too (/live/<user>/<password>/<id>.ts), so the cached channel list
 * and the guide address are stored with the password replaced by a marker and filled in again
 * when they are read back.
 *
 * Where the browser has no Web Crypto (never on a webOS 6 television, which runs pages from
 * file://, a secure context) the password is kept as before rather than lost.
 */

const KEY_NAME = 'vault-key';
const PREFIX = 'v1:';
/** Stands in for the password inside a stored address; braces never appear unencoded in a URL. */
const MARK = '{xc-secret}';

let keyPromise: Promise<CryptoKey | null> | null = null;

function subtle(): SubtleCrypto | null {
  return typeof crypto !== 'undefined' && crypto.subtle ? crypto.subtle : null;
}

function key(): Promise<CryptoKey | null> {
  if (!keyPromise) {
    keyPromise = (async () => {
      const s = subtle();
      if (!s) return null;
      const stored = await kv.get<CryptoKey>(KEY_NAME);
      if (stored) return stored;
      const made = await s.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
      await kv.set(KEY_NAME, made);
      return made;
    })().catch(() => null);
  }
  return keyPromise;
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

export async function seal(plain: string): Promise<string> {
  const k = await key();
  const s = subtle();
  if (!k || !s) return plain;
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const cipher = new Uint8Array(await s.encrypt({ name: 'AES-GCM', iv }, k, new TextEncoder().encode(plain)));
  const both = new Uint8Array(iv.length + cipher.length);
  both.set(iv);
  both.set(cipher, iv.length);
  return PREFIX + toBase64(both);
}

/** Opens what [seal] made; anything else (an older, unsealed value) is returned as it is. */
export async function unseal(stored: string): Promise<string> {
  if (!stored.startsWith(PREFIX)) return stored;
  const k = await key();
  const s = subtle();
  if (!k || !s) return '';
  try {
    const both = fromBase64(stored.slice(PREFIX.length));
    const plain = await s.decrypt({ name: 'AES-GCM', iv: both.slice(0, 12) }, k, both.slice(12));
    return new TextDecoder().decode(plain);
  } catch {
    // A key lost with cleared storage: the user types the password again.
    return '';
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

/** A source as it is written to storage: password sealed, guide address masked. */
export async function sealSource(source: Source): Promise<Source> {
  if (source.type !== 'XTREAM' || !source.password) return source;
  return {
    ...source,
    password: await seal(source.password),
    resolvedEpgUrl: source.resolvedEpgUrl ? maskAddress(source.resolvedEpgUrl, source) : undefined,
  };
}

export async function unsealSource(stored: Source): Promise<Source> {
  if (stored.type !== 'XTREAM' || !stored.password) return stored;
  const source = { ...stored, password: await unseal(stored.password) };
  if (source.resolvedEpgUrl) source.resolvedEpgUrl = unmaskAddress(source.resolvedEpgUrl, source);
  return source;
}
