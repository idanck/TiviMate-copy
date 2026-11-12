// @ts-check
import { lookup } from 'node:dns/promises';
import { isIP } from 'node:net';

/**
 * Which upstream addresses the proxy may reach.
 *
 * The proxy fetches whatever URL a playlist names, so on a machine inside a home network it would
 * otherwise be a way in to the router, the NAS and everything else on the LAN. Private, loopback,
 * link-local and carrier-grade NAT ranges are refused unless SHIDUR_WEB_ALLOW_PRIVATE is set —
 * which a provider on the LAN (tvheadend, a local test server) needs.
 *
 * The check resolves the name and looks at every address it gets back. It is not proof against a
 * DNS answer that changes between this lookup and the fetch's own (the connection is not pinned to
 * the address that was checked).
 */

/** @param {string} ip */
export function isPrivateAddress(ip) {
  const v = isIP(ip);
  if (v === 4) return isPrivateV4(ip);
  if (v === 6) {
    const h = hextets(ip);
    if (!h) return true;
    /** @param {number} hi @param {number} lo */
    const v4 = (hi, lo) => isPrivateV4(`${hi >> 8}.${hi & 0xff}.${lo >> 8}.${lo & 0xff}`);
    // An IPv4 address inside an IPv6 one is judged as that IPv4 address: a socket opened to
    // ::ffff:127.0.0.1 reaches 127.0.0.1. A URL carries it as hex (`[::ffff:7f00:1]`), so the
    // address is read as numbers, never as text.
    const zeros = (/** @type {number} */ n) => h.slice(0, n).every((x) => x === 0);
    if (zeros(5) && h[5] === 0xffff) return v4(h[6], h[7]); // ::ffff:0:0/96, IPv4-mapped
    if (zeros(6)) return h[6] === 0 && h[7] <= 1 ? true : v4(h[6], h[7]); // ::, ::1, IPv4-compatible
    if (h[0] === 0x64 && h[1] === 0xff9b) return h.slice(2, 6).every((x) => x === 0) ? v4(h[6], h[7]) : true; // NAT64
    if (h[0] === 0x2002) return v4(h[1], h[2]); // 6to4
    if (h[0] === 0x2001 && h[1] === 0) return true; // Teredo: the IPv4 host inside is not ours to trust
    if ((h[0] & 0xfe00) === 0xfc00) return true; // fc00::/7 unique local
    if ((h[0] & 0xffc0) === 0xfe80) return true; // fe80::/10 link local
    if ((h[0] & 0xff00) === 0xff00) return true; // multicast
    return false;
  }
  return true;
}

/**
 * An IPv6 address as its eight 16-bit groups, or null when it is not one.
 *
 * @param {string} ip
 * @returns {number[] | null}
 */
function hextets(ip) {
  let text = ip.toLowerCase().replace(/%.*$/, '');
  // A dotted tail (`::ffff:1.2.3.4`) is the last two groups.
  const dotted = text.match(/(\d+)\.(\d+)\.(\d+)\.(\d+)$/);
  if (dotted) {
    const [a, b, c, d] = dotted.slice(1).map(Number);
    text = text.slice(0, dotted.index) + ((a << 8) | b).toString(16) + ':' + ((c << 8) | d).toString(16);
  }
  const halves = text.split('::');
  if (halves.length > 2) return null;
  const parse = (/** @type {string} */ part) => (part ? part.split(':').map((x) => parseInt(x, 16)) : []);
  const head = parse(halves[0]);
  const tail = halves.length === 2 ? parse(halves[1]) : [];
  const gap = 8 - head.length - tail.length;
  if (halves.length === 1 ? gap !== 0 : gap < 0) return null;
  const all = [...head, ...new Array(Math.max(gap, 0)).fill(0), ...tail];
  return all.length === 8 && all.every((x) => Number.isInteger(x) && x >= 0 && x <= 0xffff) ? all : null;
}

/** @param {string} ip */
function isPrivateV4(ip) {
  const [a, b] = ip.split('.').map(Number);
  return (
    a === 0 ||
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    a >= 224
  );
}

export class BlockedError extends Error {
  /** @param {string} message */
  constructor(message) {
    super(message);
    this.code = 'blocked';
  }
}

/**
 * Throws [BlockedError] when [url] is not an http(s) address on the public internet.
 *
 * @param {URL} url
 * @param {{ allowPrivate: boolean }} options
 */
export async function assertReachable(url, { allowPrivate }) {
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new BlockedError(`unsupported scheme ${url.protocol}`);
  }
  if (allowPrivate) return;
  const host = url.hostname.replace(/^\[|\]$/g, '');
  if (host === 'localhost' || host.endsWith('.localhost')) throw new BlockedError('private address');
  const addresses = isIP(host) ? [{ address: host }] : await lookup(host, { all: true });
  if (addresses.length === 0 || addresses.some((a) => isPrivateAddress(a.address))) {
    throw new BlockedError('private address');
  }
}
